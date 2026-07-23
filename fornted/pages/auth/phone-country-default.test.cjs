const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadDefaultCountryModule({ api, osLanguage = '', locale = '' }) {
	const moduleNonce = `${Date.now()}-${Math.random()}`
	const apiGlobalKey = `__phoneCountryTestApi_${moduleNonce}`
	globalThis[apiGlobalKey] = api
	globalThis.uni = {
		getSystemInfoSync() { return { osLanguage } },
		getLocale() { return locale }
	}

	const authApiUrl = sourceUrl(`export const authApi = globalThis[${JSON.stringify(apiGlobalKey)}]`)
	const searchUrl = sourceUrl(`
		const countries = new Map([
			['cn', { id: 'cn-86', iso2: 'cn' }],
			['us', { id: 'us-1', iso2: 'us' }],
			['ca', { id: 'ca-1', iso2: 'ca' }],
			['gb', { id: 'gb-44', iso2: 'gb' }],
			['jp', { id: 'jp-81', iso2: 'jp' }]
		])
		export function getPhoneCountryByIso2(iso2) {
			return countries.get(String(iso2 || '').trim().toLowerCase()) || null
		}
		export function findPhoneCountryById(countryId) {
			return Array.from(countries.values()).find(country => country.id === countryId) || null
		}
	`)

	const authDirectory = path.resolve(__dirname, '../../common/auth')
	const source = fs.readFileSync(path.join(authDirectory, 'phone-country-default.js'), 'utf8')
		.replace("from './auth-api.js'", `from '${authApiUrl}'`)
		.replace("from './phone-country-search.js'", `from '${searchUrl}'`)
		.concat(`\n// fresh-module-${moduleNonce}`)
	const loadedModule = await import(sourceUrl(source))
	delete globalThis[apiGlobalKey]
	return loadedModule
}

test('extracts only explicit BCP 47 region subtags', async () => {
	const module = await loadDefaultCountryModule({ api: { phoneCountry: async () => ({}) } })

	assert.equal(module.parseDeviceRegionIso2('en-US'), 'US')
	assert.equal(module.parseDeviceRegionIso2('zh-Hans-CN'), 'CN')
	assert.equal(module.parseDeviceRegionIso2('en_US'), 'US')
	assert.equal(module.parseDeviceRegionIso2('en'), '')
	assert.equal(module.parseDeviceRegionIso2('zh-Hans'), '')
})

test('deduplicates the IP request and maps its ISO2 result', async () => {
	let requestCount = 0
	const module = await loadDefaultCountryModule({
		api: {
			async phoneCountry() {
				requestCount += 1
				return { resolved: true, countryIso2: 'US' }
			}
		},
		osLanguage: 'zh-CN'
	})

	const first = module.resolveInitialPhoneCountry()
	const second = module.resolveInitialPhoneCountry()
	const [firstResult, secondResult] = await Promise.all([first, second])

	assert.equal(requestCount, 1)
	assert.deepEqual(firstResult, { countryId: 'us-1', source: 'IP' })
	assert.deepEqual(secondResult, firstResult)
})

test('uses an explicit device region when the IP result is unavailable', async () => {
	const unresolvedApi = { phoneCountry: async () => ({ resolved: false, countryIso2: null }) }
	const deviceModule = await loadDefaultCountryModule({ api: unresolvedApi, osLanguage: 'en-GB' })
	const deviceResult = await deviceModule.resolveInitialPhoneCountry()

	assert.deepEqual(deviceResult, {
		countryId: 'gb-44',
		source: 'DEVICE'
	})
})

test('keeps the selection empty when neither IP nor device region resolves', async () => {
	const failedApi = { phoneCountry: async () => { throw new Error('offline') } }
	const languageOnlyModule = await loadDefaultCountryModule({ api: failedApi, osLanguage: 'en' })

	assert.deepEqual(await languageOnlyModule.resolveInitialPhoneCountry(), {
		countryId: '',
		source: 'FALLBACK'
	})
})

test('never overwrites a user selection with a late IP result', async () => {
	let resolveRequest
	const module = await loadDefaultCountryModule({
		api: {
			phoneCountry() {
				return new Promise(resolve => { resolveRequest = resolve })
			}
		},
		osLanguage: 'en-US'
	})

	const pending = module.resolveInitialPhoneCountry()
	assert.deepEqual(module.selectPhoneCountry('jp-81'), { countryId: 'jp-81', source: 'USER' })
	resolveRequest({ resolved: true, countryIso2: 'US' })

	assert.deepEqual(await pending, { countryId: 'jp-81', source: 'USER' })
	assert.deepEqual(module.getCurrentPhoneCountrySelection(), {
		countryId: 'jp-81',
		source: 'USER'
	})
})

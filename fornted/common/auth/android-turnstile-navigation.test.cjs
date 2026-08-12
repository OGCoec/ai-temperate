const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const modulePath = path.resolve(__dirname, 'android-turnstile-navigation.js')
const validChallenge = 'A'.repeat(38)
const validSiteKey = '1x00000000000000000000AA'
const validChannel = 'attempt_m4x8k2p9_0001_native'
const validPreAuthToken = 'B'.repeat(43)
const validDeviceInstallationId = 'eb00070b-d902-4793-b5a9-c5d14e878264'

async function loadNavigationModule() {
	const moduleSource = fs.readFileSync(modulePath, 'utf8')
	const moduleUrl = `data:text/javascript;base64,${Buffer.from(moduleSource).toString('base64')}`
	return import(moduleUrl)
}

function validOptions(overrides = {}) {
	return {
		baseUrl: 'https://niko000o.site',
		challenge: validChallenge,
		action: 'login',
		siteKey: validSiteKey,
		channel: validChannel,
		preAuthToken: validPreAuthToken,
		deviceInstallationId: validDeviceInstallationId,
		...overrides
	}
}

function recordingWebview() {
	const calls = []
	return {
		calls,
		loadURL(url, headers) {
			calls.push({ url, headers })
		}
	}
}

test('Android Turnstile navigation loads once with only the required URL fields and security headers', async () => {
	const { loadAndroidTurnstilePage } = await loadNavigationModule()
	const webview = recordingWebview()

	loadAndroidTurnstilePage(webview, validOptions())

	assert.equal(webview.calls.length, 1)
	assert.equal(
		webview.calls[0].url,
		`https://niko000o.site/api/auth/turnstile/page?challenge=${validChallenge}&action=login#siteKey=${validSiteKey}&channel=${validChannel}`
	)
	assert.deepEqual(webview.calls[0].headers, {
		'X-AIT-PreAuth': validPreAuthToken,
		'X-Device-Installation-Id': validDeviceInstallationId,
		'X-Client-Platform': 'ANDROID'
	})
	assert.equal(Object.hasOwn(webview.calls[0].headers, 'Cookie'), false)
	assert.equal(webview.calls[0].url.includes(validPreAuthToken), false)
	assert.equal(webview.calls[0].url.includes(validDeviceInstallationId), false)
	assert.equal(webview.calls[0].url.includes('X-Device-Installation-Id'), false)
})

test('Android Turnstile navigation accepts every supported action and encodes only challenge and action', async () => {
	const { loadAndroidTurnstilePage } = await loadNavigationModule()

	for (const action of ['register', 'login', 'password_reset']) {
		const webview = recordingWebview()
		loadAndroidTurnstilePage(webview, validOptions({ action }))

		assert.equal(webview.calls.length, 1)
		assert.equal(
			webview.calls[0].url,
			`https://niko000o.site/api/auth/turnstile/page?challenge=${validChallenge}&action=${encodeURIComponent(action)}#siteKey=${validSiteKey}&channel=${validChannel}`
		)
	}
})

test('Android Turnstile navigation rejects an unavailable or malformed public site key', async () => {
	const { loadAndroidTurnstilePage } = await loadNavigationModule()

	for (const siteKey of [
		'',
		'A'.repeat(19),
		'A'.repeat(201),
		'bad site key',
		`${validSiteKey}\n`
	]) {
		const webview = recordingWebview()
		assert.throws(
			() => loadAndroidTurnstilePage(webview, validOptions({ siteKey })),
			(error) => error && error.code === 'TURNSTILE_NAVIGATION_INVALID'
		)
		assert.equal(webview.calls.length, 0)
	}
})

test('Android Turnstile navigation rejects a missing or malformed result channel', async () => {
	const { loadAndroidTurnstilePage } = await loadNavigationModule()

	for (const channel of ['', 'short', 'bad channel', `${validChannel}\n`, 'A'.repeat(81)]) {
		const webview = recordingWebview()
		assert.throws(
			() => loadAndroidTurnstilePage(webview, validOptions({ channel })),
			(error) => error && error.code === 'TURNSTILE_NAVIGATION_INVALID'
		)
		assert.equal(webview.calls.length, 0)
	}
})

test('Android Turnstile navigation fails closed when the security context is unavailable', async () => {
	const { loadAndroidTurnstilePage } = await loadNavigationModule()

	for (const overrides of [
		{ preAuthToken: '' },
		{ preAuthToken: 'A'.repeat(42) },
		{ preAuthToken: `${'A'.repeat(43)}\n` },
		{ deviceInstallationId: '' },
		{ deviceInstallationId: 'not-a-uuid' },
		{ deviceInstallationId: 'eb00070b-d902-3793-b5a9-c5d14e878264' },
		{ deviceInstallationId: 'eb00070b-d902-4793-75a9-c5d14e878264' },
		{ deviceInstallationId: `${validDeviceInstallationId}\n` }
	]) {
		const webview = recordingWebview()
		assert.throws(
			() => loadAndroidTurnstilePage(webview, validOptions(overrides)),
			(error) => error && error.code === 'TURNSTILE_SECURITY_CONTEXT_UNAVAILABLE'
		)
		assert.equal(webview.calls.length, 0)
	}
})

test('Android Turnstile navigation rejects invalid WebViews, origins, challenges, and actions', async () => {
	const { loadAndroidTurnstilePage } = await loadNavigationModule()

	for (const [webview, overrides] of [
		[{}, {}],
		[recordingWebview(), { baseUrl: 'http://niko000o.site' }],
		[recordingWebview(), { baseUrl: 'https://niko000o.site/path' }],
		[recordingWebview(), { baseUrl: 'https://niko000o.site?query=1' }],
		[recordingWebview(), { baseUrl: 'https://niko000o.site#fragment' }],
		[recordingWebview(), { baseUrl: 'https://user@niko000o.site' }],
		[recordingWebview(), { challenge: 'A'.repeat(37) }],
		[recordingWebview(), { challenge: `${'A'.repeat(38)}\n` }],
		[recordingWebview(), { action: 'unknown' }]
	]) {
		assert.throws(
			() => loadAndroidTurnstilePage(webview, validOptions(overrides)),
			(error) => error && error.code === 'TURNSTILE_NAVIGATION_INVALID'
		)
		assert.equal(Array.isArray(webview.calls) ? webview.calls.length : 0, 0)
	}
})

test('Android Turnstile navigation errors never disclose security values or the protected URL', async () => {
	const { loadAndroidTurnstilePage } = await loadNavigationModule()
	const sensitiveOptions = validOptions({ preAuthToken: `${'C'.repeat(43)}\n` })

	assert.throws(
		() => loadAndroidTurnstilePage(recordingWebview(), sensitiveOptions),
		(error) => {
			const serialized = `${error.name}:${error.message}:${error.code}`
			assert.equal(serialized.includes(sensitiveOptions.preAuthToken.trim()), false)
			assert.equal(serialized.includes(sensitiveOptions.deviceInstallationId), false)
			assert.equal(serialized.includes('/api/auth/turnstile/page'), false)
			return error.code === 'TURNSTILE_SECURITY_CONTEXT_UNAVAILABLE'
		}
	)
})

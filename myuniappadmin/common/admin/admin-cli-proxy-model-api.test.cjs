const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	let source = fs.readFileSync(
		path.join(__dirname, 'admin-cli-proxy-model-api.js'),
		'utf8')
	source = source.replace(
		"import { adminRequest } from './admin-http.js'",
		'const adminRequest = async () => { throw new Error("not configured") }')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('discover uses the fixed protected backend GET route without request data', async () => {
	const { createAdminCliProxyModelApi } = await loadModule()
	const calls = []
	const api = createAdminCliProxyModelApi(async (requestPath, options) => {
		calls.push({ requestPath, options })
		return {
			source: 'CLI_PROXY',
			fetchedAt: '2026-07-29T15:30:00Z',
			total: 0,
			models: []
		}
	})

	const result = await api.discover()

	assert.equal(calls[0].requestPath, '/api/admin/ai-model-sources/cli-proxy/models')
	assert.deepEqual(calls[0].options, { method: 'GET' })
	assert.equal(result.total, 0)
	assert.equal('data' in calls[0].options, false)
})

test('discover validates matched and unregistered model contracts', async () => {
	const { createAdminCliProxyModelApi } = await loadModule()
	const api = createAdminCliProxyModelApi(async () => ({
		source: 'CLI_PROXY',
		fetchedAt: '2026-07-29T15:30:00Z',
		total: 2,
		models: [
			{
				modelId: 'gpt-5.4',
				owner: 'openai',
				createdEpochSeconds: '1773709206',
				matchStatus: 'MATCHED',
				localModelPublicId: 'AAABi0VWeJ8',
				localVendor: 'openai',
				inputRatio: 1,
				cachedInputRatio: 0.1,
				outputRatio: 4,
				localEnabled: true
			},
			{
				modelId: 'gpt-5.4-codex',
				owner: 'openai',
				createdEpochSeconds: null,
				matchStatus: 'UNREGISTERED',
				localModelPublicId: null,
				localVendor: null,
				inputRatio: null,
				cachedInputRatio: null,
				outputRatio: null,
				localEnabled: null
			}
		]
	}))

	const result = await api.discover()

	assert.deepEqual(result.models.map(model => model.matchStatus), [
		'MATCHED',
		'UNREGISTERED'
	])
	assert.equal(result.models[0].localModelPublicId, 'AAABi0VWeJ8')
	assert.equal(result.models[0].createdEpochSeconds, 1773709206)
	assert.equal(result.models[0].cachedInputRatio, 0.1)
	assert.equal(result.models[1].inputRatio, null)
})

test('discover rejects non-canonical or unsafe epoch-second strings', async () => {
	const { createAdminCliProxyModelApi } = await loadModule()
	for (const createdEpochSeconds of ['1773709200.5', '-1', ' 1773709200', '9007199254740992']) {
		const api = createAdminCliProxyModelApi(async () => ({
			source: 'CLI_PROXY',
			fetchedAt: '2026-07-29T15:30:00Z',
			total: 1,
			models: [{
				modelId: 'gpt-5.4',
				owner: 'openai',
				createdEpochSeconds,
				matchStatus: 'UNREGISTERED',
				localModelPublicId: null,
				localVendor: null,
				inputRatio: null,
				cachedInputRatio: null,
				outputRatio: null,
				localEnabled: null
			}]
		}))

		await assert.rejects(
			() => api.discover(),
			error => error.code === 'CLI_PROXY_MODEL_DISCOVERY_RESPONSE_INVALID')
	}
})

test('invalid discovery response fails locally without retaining unsafe values', async () => {
	const { createAdminCliProxyModelApi } = await loadModule()
	const api = createAdminCliProxyModelApi(async () => ({
		source: 'CLI_PROXY',
		fetchedAt: 'not-a-time',
		total: 1,
		models: [{
			modelId: 'gpt-5.4',
			matchStatus: 'MATCHED',
			localModelPublicId: 'internal-bigint',
			inputRatio: 1,
			cachedInputRatio: 0.1,
			outputRatio: 4,
			localEnabled: true
		}]
	}))

	await assert.rejects(
		() => api.discover(),
		error => error.code === 'CLI_PROXY_MODEL_DISCOVERY_RESPONSE_INVALID')
})

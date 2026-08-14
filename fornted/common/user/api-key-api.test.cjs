const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadApi(request) {
	const nonce = `${Date.now()}-${Math.random()}`
	globalThis.__apiKeyAuthorizedRequest = request
	const httpClientUrl = `${sourceUrl(`export const authorizedRequest = (...args) => globalThis.__apiKeyAuthorizedRequest(...args)`)}#http-${nonce}`
	const source = fs.readFileSync(path.resolve(__dirname, 'api-key-api.js'), 'utf8')
		.replace("from '../auth/http-client.js'", `from '${httpClientUrl}'`)
	return import(`${sourceUrl(source)}#api-${nonce}`)
}

function summary(overrides = {}) {
	return {
		id: 'AAAAAAAAAAE',
		maskedKey: 'sk-…Ab3D',
		status: 'ENABLED',
		expiresAt: null,
		expired: false,
		lastUsedAt: null,
		createdAt: '2026-08-14T00:00:00Z',
		updatedAt: '2026-08-14T00:00:00Z',
		rowVersion: 3,
		...overrides
	}
}

function detail(overrides = {}) {
	return {
		...summary(),
		models: [{
			modelPublicId: 'AAAAAAAAAAI',
			modelName: 'gpt-test',
			vendor: 'openai',
			enabled: true
		}],
		...overrides
	}
}

test('lists masked summaries with stable cursor pagination', async () => {
	const calls = []
	const { apiKeyApi } = await loadApi(async (...args) => {
		calls.push(args)
		return { items: [summary()], nextCursor: 'next/cursor+' }
	})

	const page = await apiKeyApi.list({ cursor: 'before/cursor+', pageSize: 20 })

	assert.deepEqual(calls, [[
		'/api/users/me/api-keys?cursor=before%2Fcursor%2B&pageSize=20',
		{ method: 'GET' }
	]])
	assert.equal(page.items[0].maskedKey, 'sk-…Ab3D')
	assert.equal(page.nextCursor, 'next/cursor+')
	assert.equal(Object.isFrozen(page.items[0]), true)
})

test('creates one full key and requires the server ETag to match rowVersion', async () => {
	const fullKey = `sk-${'A'.repeat(86)}`
	const calls = []
	const { apiKeyApi } = await loadApi(async (...args) => {
		calls.push(args)
		return { data: { ...detail(), apiKey: fullKey }, etag: '"v3"' }
	})

	const created = await apiKeyApi.create({
		expiresAt: null,
		modelPublicIds: ['AAAAAAAAAAI']
	})

	assert.equal(created.value.apiKey, fullKey)
	assert.equal(created.etag, '"v3"')
	assert.deepEqual(calls, [[
		'/api/users/me/api-keys',
		{
			method: 'POST',
			data: { expiresAt: null, modelPublicIds: ['AAAAAAAAAAI'] },
			captureEtag: true
		}
	]])
})

test('rejects full secrets in list and detail responses', async () => {
	const fullKey = `sk-${'B'.repeat(86)}`
	const listModule = await loadApi(async () => ({
		items: [summary({ apiKey: fullKey })],
		nextCursor: null
	}))
	await assert.rejects(
		() => listModule.apiKeyApi.list({}),
		error => error.code === 'API_KEY_RESPONSE_INVALID')

	const detailModule = await loadApi(async () => ({
		data: { ...detail(), apiKey: fullKey },
		etag: '"v3"'
	}))
	await assert.rejects(
		() => detailModule.apiKeyApi.detail('AAAAAAAAAAE'),
		error => error.code === 'API_KEY_RESPONSE_INVALID')
})

test('rejects missing weak or row-version-mismatched ETags', async () => {
	for (const etag of ['', 'W/"v3"', '"v4"']) {
		const { apiKeyApi } = await loadApi(async () => ({ data: detail(), etag }))
		await assert.rejects(
			() => apiKeyApi.detail('AAAAAAAAAAE'),
			error => error.code === 'API_KEY_RESPONSE_INVALID')
	}
})

test('sends the latest strong ETag for lifecycle model and soft-delete requests', async () => {
	const calls = []
	const { apiKeyApi } = await loadApi(async (...args) => {
		calls.push(args)
		if (args[1].method === 'DELETE') return undefined
		return { data: detail({ rowVersion: 4 }), etag: '"v4"' }
	})

	await apiKeyApi.update('AAAAAAAAAAE', '"v3"', {
		status: 'DISABLED',
		expiresAt: null
	})
	await apiKeyApi.replaceModels('AAAAAAAAAAE', '"v4"', [])
	await apiKeyApi.remove('AAAAAAAAAAE', '"v5"')

	assert.deepEqual(calls.map(call => call[1].headers['If-Match']), ['"v3"', '"v4"', '"v5"'])
	assert.equal(calls[1][0], '/api/users/me/api-keys/AAAAAAAAAAE/models')
	assert.equal(calls[2][1].method, 'DELETE')
})

test('rejects malformed public IDs duplicate grants and invalid lifecycle input before transport', async () => {
	let calls = 0
	const { apiKeyApi } = await loadApi(async () => {
		calls += 1
		return undefined
	})

	await assert.rejects(() => apiKeyApi.detail('1'), error => error.code === 'API_KEY_INPUT_INVALID')
	await assert.rejects(() => apiKeyApi.create({
		expiresAt: null,
		modelPublicIds: ['AAAAAAAAAAI', 'AAAAAAAAAAI']
	}), error => error.code === 'API_KEY_INPUT_INVALID')
	await assert.rejects(() => apiKeyApi.update('AAAAAAAAAAE', 'W/"v3"', {
		status: 'DELETED',
		expiresAt: null
	}), error => error.code === 'API_KEY_INPUT_INVALID')
	assert.equal(calls, 0)
})

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	let source = fs.readFileSync(path.join(__dirname, 'admin-ai-model-api.js'), 'utf8')
	source = source.replace(
		"import { adminRequest } from './admin-http.js'",
		'const adminRequest = async () => { throw new Error("not configured") }')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('list sends only fixed server-supported pagination and filter parameters', async () => {
	const { createAdminAiModelApi } = await loadModule()
	const calls = []
	const api = createAdminAiModelApi(async (requestPath, options) => {
		calls.push({ requestPath, options })
		return { models: [], pageNum: 2, pageSize: 25 }
	})

	await api.list({
		pageNum: 2,
		pageSize: 25,
		keyword: 'gpt %',
		enabled: false,
		sortPriority: 'OUTPUT_FIRST',
		direction: 'DESC'
	})

	assert.equal(
		calls[0].requestPath,
		'/api/admin/ai-models?pageNum=2&pageSize=25&keyword=gpt%20%25&enabled=false&sortPriority=OUTPUT_FIRST&direction=DESC')
	assert.equal(calls[0].options.method, 'GET')
	assert.doesNotMatch(calls[0].requestPath, /orderBy|cursor|nextCursor/)
})

test('detail and patch preserve strong ETag response metadata', async () => {
	const { createAdminAiModelApi } = await loadModule()
	const calls = []
	const api = createAdminAiModelApi(async (requestPath, options) => {
		calls.push({ requestPath, options })
		return {
			data: { publicId: 'AAABi0VWeJ8', rowVersion: 4 },
			headers: { ETag: '"v4"' },
			statusCode: 200
		}
	})

	const detail = await api.detail('AAABi0VWeJ8')
	const patched = await api.patch(
		'AAABi0VWeJ8',
		'"v4"',
		{ description: null, capabilities: ['RESPONSES'] })

	assert.equal(detail.etag, '"v4"')
	assert.equal(patched.etag, '"v4"')
	assert.equal(calls[0].options.returnResponse, true)
	assert.equal(calls[1].options.method, 'PATCH')
	assert.equal(calls[1].options.returnResponse, true)
	assert.equal(calls[1].options.headers['If-Match'], '"v4"')
	assert.equal(calls[1].options.headers['Content-Type'], 'application/merge-patch+json')
	assert.deepEqual(calls[1].options.data, {
		description: null,
		capabilities: ['RESPONSES']
	})
})

test('create and status operations never expose a delete method or route', async () => {
	const { createAdminAiModelApi } = await loadModule()
	const calls = []
	const api = createAdminAiModelApi(async (requestPath, options) => {
		calls.push({ requestPath, options })
		return {}
	})

	await api.create({ modelName: 'gpt-5.6', enabled: false })
	await api.setEnabled('AAABi0VWeJ8', true)
	await api.setEnabledBatch(['AAABi0VWeJ8'], false)

	assert.deepEqual(calls.map(call => call.options.method), ['POST', 'PATCH', 'POST'])
	assert.deepEqual(calls.map(call => call.requestPath), [
		'/api/admin/ai-models',
		'/api/admin/ai-models/AAABi0VWeJ8/status',
		'/api/admin/ai-models/status/batch'
	])
	assert.equal(calls.some(call => /delete/i.test(call.requestPath)), false)
})

test('invalid identifiers and list parameters fail before issuing a request', async () => {
	const { createAdminAiModelApi } = await loadModule()
	let calls = 0
	const api = createAdminAiModelApi(async () => {
		calls += 1
		return {}
	})

	await assert.rejects(() => api.detail('123'), error => error.code === 'AI_MODEL_PUBLIC_ID_INVALID')
	assert.throws(
		() => api.list({ pageNum: 0, pageSize: 101 }),
		error => error.code === 'AI_MODEL_LIST_QUERY_INVALID')
	assert.equal(calls, 0)
})

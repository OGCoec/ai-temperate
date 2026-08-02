const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	let source = fs.readFileSync(path.join(__dirname, 'admin-ai-model-icon-api.js'), 'utf8')
	source = source.replace(
		"import { adminRequest, adminUploadFile } from './admin-http.js'",
		'const adminRequest = async () => {}; const adminUploadFile = async () => {}')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('icon API keeps JSON CRUD and multipart uploads behind shared request helpers', async () => {
	const { createAdminAiModelIconApi } = await loadModule()
	const requests = []
	const uploads = []
	const api = createAdminAiModelIconApi(
		async (requestPath, options) => {
			requests.push({ requestPath, options })
			return {}
		},
		async (requestPath, options) => {
			uploads.push({ requestPath, options })
			return {}
		})

	await api.createRemote({
		iconName: 'OpenAI',
		iconUrl: 'https://example.test/openai.png',
		description: 'OpenAI'
	})
	await api.patch('AAAAAAAAAAE', { description: null })
	await api.delete('AAAAAAAAAAE')
	await api.createUpload({ iconName: 'Claude', filePath: 'temp/claude.png' })
	await api.replaceFile('AAAAAAAAAAE', 'temp/openai.webp')

	assert.deepEqual(requests.map(call => call.options.method), ['POST', 'PATCH', 'DELETE'])
	assert.equal(requests[1].options.headers['Content-Type'], 'application/merge-patch+json')
	assert.deepEqual(uploads.map(call => call.requestPath), [
		'/api/admin/ai-model-icons/upload',
		'/api/admin/ai-model-icons/AAAAAAAAAAE/file'
	])
	assert.deepEqual(uploads.map(call => call.options.method), ['POST', 'POST'])
	assert.equal(uploads[0].options.name, 'file')
})

test('listAll reads bounded pages and preserves server name order', async () => {
	const { createAdminAiModelIconApi } = await loadModule()
	const calls = []
	const api = createAdminAiModelIconApi(async (requestPath, options) => {
		calls.push({ requestPath, options })
		return calls.length === 1
			? { icons: [{ iconName: 'Anthropic' }], hasNext: true }
			: { icons: [{ iconName: 'OpenAI' }], hasNext: false }
	})

	const scope = { isActive: () => true }
	const icons = await api.listAll({ scope })

	assert.deepEqual(icons.map(icon => icon.iconName), ['Anthropic', 'OpenAI'])
	assert.deepEqual(calls.map(call => call.requestPath), [
		'/api/admin/ai-model-icons?pageNum=1&pageSize=100',
		'/api/admin/ai-model-icons?pageNum=2&pageSize=100'
	])
	assert.equal(calls.every(call => call.options.scope === scope), true)
})

test('invalid icon IDs and empty upload paths fail before network access', async () => {
	const { createAdminAiModelIconApi } = await loadModule()
	let calls = 0
	const api = createAdminAiModelIconApi(
		async () => { calls += 1 },
		async () => { calls += 1 })

	assert.throws(
		() => api.delete('1'),
		error => error.code === 'AI_MODEL_ICON_PUBLIC_ID_INVALID')
	assert.throws(
		() => api.replaceFile('AAAAAAAAAAE', ''),
		error => error.code === 'AI_MODEL_ICON_FILE_REQUIRED')
	assert.equal(calls, 0)
})

test('URL source presentation exposes only the remote hostname', async () => {
	const { aiModelIconUrlSource } = await loadModule()

	assert.equal(
		aiModelIconUrlSource('https://cdn.example.test/icons/openai.png?secret=hidden'),
		'cdn.example.test')
	assert.equal(aiModelIconUrlSource('not-a-url'), '无效地址')
})

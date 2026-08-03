const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

async function loadParser() {
	return loadEsmModule(path.join(__dirname, 'ai-response-parser.js'))
}

test('keeps ordinary and mixed responses on the existing Markdown path', async () => {
	const { detectAiResponseFormatCandidate, parseAiResponse } = await loadParser()

	assert.equal(detectAiResponseFormatCandidate('# Title'), 'markdown')
	assert.equal(detectAiResponseFormatCandidate('Explanation\n{"name":"Ada"}'), 'markdown')
	assert.equal(detectAiResponseFormatCandidate('true'), 'markdown')
	assert.equal(parseAiResponse('# Title').children[0].type, 'heading')
})

test('keeps fenced JSON and XML as Markdown code blocks', async () => {
	const { parseAiResponse } = await loadParser()
	const fence = String.fromCharCode(96).repeat(3)
	const json = parseAiResponse(fence + 'json\n{"name":"Ada"}\n' + fence)
	const xml = parseAiResponse(fence + 'xml\n<name>Ada</name>\n' + fence)

	assert.equal(json.children[0].type, 'codeBlock')
	assert.equal(json.children[0].language.id, 'json')
	assert.equal(xml.children[0].type, 'codeBlock')
	assert.equal(xml.children[0].language.id, 'xml')
})

test('shows raw JSON and XML candidates as streaming highlighted code', async () => {
	const { parseAiResponse } = await loadParser()
	const json = parseAiResponse('{"name":"Ada', { streaming: true })
	const xml = parseAiResponse('<user><name>Ada', { streaming: true })

	assert.equal(json.children[0].type, 'codeBlock')
	assert.equal(json.children[0].language.id, 'json')
	assert.equal(json.children[0].code, '{"name":"Ada')
	assert.equal(json.children[0].streaming, true)
	assert.equal(xml.children[0].language.id, 'xml')
	assert.equal(xml.children[0].streaming, true)
})

test('upgrades complete raw JSON and XML into structured AST documents', async () => {
	const { parseAiResponse } = await loadParser()
	const json = parseAiResponse('{"name":"Ada"}', { streaming: false })
	const xml = parseAiResponse('<user><name>Ada</name></user>', { streaming: false })

	assert.equal(json.children[0].type, 'unorderedList')
	assert.equal(xml.children[0].type, 'unorderedList')
})

test('falls back without losing invalid raw JSON or XML text', async () => {
	const { parseAiResponse } = await loadParser()
	const invalidJson = parseAiResponse('{not json', { streaming: false })
	const invalidXml = parseAiResponse('<root><child></root>', { streaming: false })

	assert.equal(JSON.stringify(invalidJson).includes('{not json'), true)
	assert.equal(JSON.stringify(invalidXml).includes('<root>'), true)
})

test('keeps oversized structured candidates readable as code blocks', async () => {
	const { parseAiResponse } = await loadParser()
	const oversized = '{"value":"' + 'x'.repeat(1024 * 1024) + '"}'
	const ast = parseAiResponse(oversized, { streaming: false })

	assert.equal(ast.children[0].type, 'codeBlock')
	assert.equal(ast.children[0].language.id, 'json')
	assert.equal(ast.children[0].code, oversized)
})

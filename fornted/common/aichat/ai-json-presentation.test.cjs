const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

function textContent(node) {
	if (!node) return ''
	if (node.type === 'text' || node.type === 'inlineCode') {
		return String(node.value || '')
	}
	const children = Array.isArray(node.children) ? node.children : []
	const headers = Array.isArray(node.headers) ? node.headers : []
	const rows = Array.isArray(node.rows) ? node.rows.flat() : []
	return [...children, ...headers, ...rows].map(textContent).join(' ')
}

async function loadPresentation() {
	return loadEsmModule(path.join(__dirname, 'ai-json-presentation.js'))
}

test('imports the ESM-only lossless-json package through named exports', () => {
	const source = fs.readFileSync(path.join(__dirname, 'ai-json-presentation.js'), 'utf8')

	assert.match(source, /import \{ isLosslessNumber, parse as parseLosslessJson \} from 'lossless-json'/)
	assert.doesNotMatch(source, /losslessJsonModule\.default/)
})

test('maps JSON objects into the existing safe Markdown AST without losing large numbers', async () => {
	const { parseAiJsonDocument } = await loadPresentation()
	const result = parseAiJsonDocument(
		'{"id":9007199254740993,"name":"Alice","enabled":true,"empty":null}'
	)

	assert.equal(result.ok, true)
	assert.equal(result.ast.type, 'document')
	assert.equal(result.ast.children[0].type, 'unorderedList')
	const visibleText = textContent(result.ast)
	assert.equal(visibleText.includes('9007199254740993'), true)
	assert.equal(visibleText.includes('Alice'), true)
	assert.equal(visibleText.includes('true'), true)
	assert.equal(visibleText.includes('null'), true)
})

test('maps small flat object arrays into the existing table AST', async () => {
	const { parseAiJsonDocument } = await loadPresentation()
	const result = parseAiJsonDocument(
		'[{"name":"Ada","score":10},{"name":"Lin","score":20}]'
	)

	assert.equal(result.ok, true)
	assert.equal(result.ast.children[0].type, 'table')
	assert.deepEqual(
		result.ast.children[0].headers.map(cell => textContent(cell)),
		['name', 'score']
	)
	assert.deepEqual(
		result.ast.children[0].rows.map(row => row.map(cell => textContent(cell))),
		[['Ada', '10'], ['Lin', '20']]
	)
})

test('keeps nested and inconsistent arrays as ordered lists', async () => {
	const { parseAiJsonDocument } = await loadPresentation()
	const result = parseAiJsonDocument('[{"name":"Ada"},{"name":"Lin","score":20}]')

	assert.equal(result.ok, true)
	assert.equal(result.ast.children[0].type, 'orderedList')
})

test('represents empty containers and treats prototype-shaped keys as plain text', async () => {
	const { parseAiJsonDocument } = await loadPresentation()
	const result = parseAiJsonDocument('{"items":[],"metadata":{},"__proto__":"visible"}')

	assert.equal(result.ok, true)
	const visibleText = textContent(result.ast)
	assert.equal(visibleText.includes('[]'), true)
	assert.equal(visibleText.includes('{}'), true)
	assert.equal(visibleText.includes('__proto__'), true)
	assert.equal(visibleText.includes('visible'), true)
})

test('rejects invalid and excessively deep JSON documents with controlled reasons', async () => {
	const { parseAiJsonDocument } = await loadPresentation()
	const invalid = parseAiJsonDocument('{not json')
	const deep = parseAiJsonDocument('['.repeat(65) + '0' + ']'.repeat(65))

	assert.deepEqual(invalid, { ok: false, reason: 'INVALID' })
	assert.deepEqual(deep, { ok: false, reason: 'LIMIT' })
})

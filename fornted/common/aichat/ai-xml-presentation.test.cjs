const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

function textContent(node) {
	if (!node) return ''
	if (node.type === 'text' || node.type === 'inlineCode') {
		return String(node.value || '')
	}
	return (node.children || []).map(textContent).join(' ')
}

async function loadPresentation() {
	return loadEsmModule(path.join(__dirname, 'ai-xml-presentation.js'))
}

test('maps XML elements, attributes, namespaces, text, and CDATA into the safe AST', async () => {
	const { parseAiXmlDocument } = await loadPresentation()
	const result = parseAiXmlDocument(
		'<?xml version="1.0"?><ns:user xmlns:ns="urn:test" enabled="true">' +
		'<name>Alice</name><![CDATA[raw <value>]]></ns:user>'
	)

	assert.equal(result.ok, true)
	assert.equal(result.ast.type, 'document')
	assert.equal(result.ast.children.some(node => node.type === 'unorderedList'), true)
	const visibleText = textContent(result.ast)
	assert.equal(visibleText.includes('ns:user'), true)
	assert.equal(visibleText.includes('@enabled'), true)
	assert.equal(visibleText.includes('Alice'), true)
	assert.equal(visibleText.includes('raw <value>'), true)
})

test('preserves XML comments and processing instructions as inert readable text', async () => {
	const { parseAiXmlDocument } = await loadPresentation()
	const result = parseAiXmlDocument('<root><!--note--><?step go?><value>ok</value></root>')

	assert.equal(result.ok, true)
	const visibleText = textContent(result.ast)
	assert.equal(visibleText.includes('<!--note-->'), true)
	assert.equal(visibleText.includes('<?step go?>'), true)
})

test('rejects doctypes, entity declarations, and multiple roots', async () => {
	const { parseAiXmlDocument } = await loadPresentation()
	const doctype = parseAiXmlDocument(
		'<!DOCTYPE root [<!ENTITY x "value">]><root>&x;</root>'
	)
	const multipleRoots = parseAiXmlDocument('<one></one><two></two>')

	assert.deepEqual(doctype, { ok: false, reason: 'UNSAFE' })
	assert.deepEqual(multipleRoots, { ok: false, reason: 'INVALID' })
})

test('rejects malformed and excessively deep XML with controlled reasons', async () => {
	const { parseAiXmlDocument } = await loadPresentation()
	const malformed = parseAiXmlDocument('<root><child></root>')
	const deep = parseAiXmlDocument(
		Array.from({ length: 65 }, (_, index) => `<n${index}>`).join('') +
		Array.from({ length: 65 }, (_, index) => `</n${64 - index}>`).join('')
	)

	assert.deepEqual(malformed, { ok: false, reason: 'INVALID' })
	assert.deepEqual(deep, { ok: false, reason: 'LIMIT' })
})

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { pathToFileURL } = require('node:url')
const test = require('node:test')

function sourceUrl(source) {
	return 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
}

async function loadParser() {
	const parserPath = path.join(__dirname, 'ai-markdown-parser.js')
	const source = fs.readFileSync(parserPath, 'utf8')
	const markdownItPath = require.resolve('markdown-it')
	const patchedSource = source.replace(
		"import MarkdownIt from 'markdown-it'",
		'import MarkdownIt from ' + JSON.stringify(pathToFileURL(markdownItPath).href)
	)
	return import(sourceUrl(patchedSource) + '#' + Date.now() + '-' + Math.random())
}

function childTypes(node) {
	return (node.children || []).map(child => child.type)
}

test('parses block and inline Markdown into the project AST', async () => {
	const { parseAiMarkdown } = await loadParser()
	const fence = String.fromCharCode(96).repeat(3)
	const ast = parseAiMarkdown(
		'# Title\n\n**bold** and *emphasis* with ' +
			String.fromCharCode(96) +
			'code' +
			String.fromCharCode(96) +
			' and [safe](https://example.com).\n\n- one\n- two\n\n> quoted\n\n---'
	)

	assert.equal(ast.type, 'document')
	assert.deepEqual(childTypes(ast), [
		'heading',
		'paragraph',
		'unorderedList',
		'blockquote',
		'thematicBreak'
	])
	assert.equal(ast.children[0].level, 1)
	assert.deepEqual(childTypes(ast.children[1]), [
		'strong',
		'text',
		'emphasis',
		'text',
		'inlineCode',
		'text',
		'link',
		'text'
	])
	assert.equal(ast.children[2].children.length, 2)
	assert.equal(ast.children[3].children[0].type, 'paragraph')
	assert.equal(fence.length, 3)
})

test('parses fenced code, language metadata, GFM tables, and task items', async () => {
	const { parseAiMarkdown } = await loadParser()
	const fence = String.fromCharCode(96).repeat(3)
	const ast = parseAiMarkdown(
		fence +
			'Java\npublic class Main {}\n' +
			fence +
			'\n\n| Name | Score |\n| :--- | ---: |\n| Ada | 10 |\n\n- [x] done\n- [ ] next'
	)

	assert.equal(ast.children[0].type, 'codeBlock')
	assert.equal(ast.children[0].language.id, 'java')
	assert.equal(ast.children[0].code, 'public class Main {}\n')
	assert.equal(ast.children[1].type, 'table')
	assert.deepEqual(ast.children[1].alignments, ['left', 'right'])
	assert.deepEqual(ast.children[1].headers[0].children[0], {
		type: 'text',
		value: 'Name'
	})
	assert.equal(ast.children[2].children[0].type, 'taskItem')
	assert.equal(ast.children[2].children[0].checked, true)
	assert.equal(ast.children[2].children[1].checked, false)
})

test('does not create executable HTML and rejects unsafe links', async () => {
	const { parseAiMarkdown } = await loadParser()
	const ast = parseAiMarkdown(
		'<script>alert(1)</script> [run](javascript:alert(1)) [ok](mailto:test@example.com)'
	)
	const serialized = JSON.stringify(ast)

	assert.equal(serialized.includes('<script>'), true)
	assert.equal(serialized.includes('alert(1)'), true)
	assert.equal(ast.children[0].type, 'paragraph')
	const links = ast.children[0].children.filter(child => child.type === 'link')
	assert.equal(links.every(link => link.safe), true)
	assert.equal(links.some(link => link.href === 'mailto:test@example.com'), true)
})

test('returns an empty document for empty input', async () => {
	const { parseAiMarkdown } = await loadParser()
	assert.deepEqual(parseAiMarkdown(''), { type: 'document', children: [] })
	assert.deepEqual(parseAiMarkdown(null), { type: 'document', children: [] })
})

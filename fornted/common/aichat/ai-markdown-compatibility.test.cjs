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

test('keeps heading levels, hard breaks, and Unicode content structurally readable', async () => {
	const { parseAiMarkdown } = await loadParser()
	const ast = parseAiMarkdown(
		'Setext title\n============\n\n#### H4\n##### H5\n###### H6\n\n中文 😀  \nnext'
	)

	assert.deepEqual(ast.children.slice(0, 4).map(node => node.type), [
		'heading',
		'heading',
		'heading',
		'heading'
	])
	assert.equal(ast.children[0].level, 1)
	assert.equal(ast.children[3].level, 6)
	assert.equal(ast.children[4].type, 'paragraph')
	assert.equal(
		ast.children[4].children.map(node => node.value || '').join('').includes('\n'),
		true
	)
})

test('handles images, autolinks, reference links, and malformed tables without unsafe nodes', async () => {
	const { parseAiMarkdown } = await loadParser()
	const ast = parseAiMarkdown(
		'![avatar](https://example.com/a.png "title") <https://example.com> [docs][ref]\n\n[ref]: https://example.com/docs\n\n| broken | table\n| ---'
	)
	const serialized = JSON.stringify(ast)

	assert.equal(serialized.includes('onerror'), false)
	assert.equal(serialized.includes('javascript:'), false)
	assert.equal(ast.type, 'document')
	assert.ok(ast.children.length >= 1)
})

test('keeps an unclosed code fence in a safe code node without exposing the fence', async () => {
	const { parseAiMarkdown } = await loadParser()
	const fence = String.fromCharCode(96).repeat(3)
	const ast = parseAiMarkdown(fence + 'java\npublic class Main {}')

	assert.equal(ast.children[0].type, 'codeBlock')
	assert.equal(ast.children[0].language.id, 'java')
	assert.equal(ast.children[0].code.includes(fence), false)
})

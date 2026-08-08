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

function tableCellTokens(tag, value, alignment = null) {
	const style = alignment ? [['style', 'text-align:' + alignment]] : []
	const tokens = [{ type: tag + '_open', nesting: 1, attrs: style }]
	if (value !== null) {
		tokens.push({
			type: 'inline',
			nesting: 0,
			children: [{ type: 'text', nesting: 0, content: value }]
		})
	}
	tokens.push({ type: tag + '_close', nesting: -1 })
	return tokens
}

function tableRowTokens(tag, cells) {
	return [
		{ type: 'tr_open', nesting: 1 },
		...cells.flatMap(cell => tableCellTokens(tag, cell.value, cell.alignment)),
		{ type: 'tr_close', nesting: -1 }
	]
}

function tableTokens(headers, rows) {
	return [
		{ type: 'table_open', nesting: 1 },
		{ type: 'thead_open', nesting: 1 },
		...tableRowTokens('th', headers),
		{ type: 'thead_close', nesting: -1 },
		{ type: 'tbody_open', nesting: 1 },
		...rows.flatMap(row => tableRowTokens('td', row)),
		{ type: 'tbody_close', nesting: -1 },
		{ type: 'table_close', nesting: -1 }
	]
}

async function parseTableTokens(tokens) {
	const { createAiMarkdownParser } = await loadParser()
	return createAiMarkdownParser({
		markdown: { parse: () => tokens }
	}).parse('table').children[0]
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
	assert.equal(ast.children[0].streaming, false)
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

test('normalizes missing and explicit empty table cells into rectangular rows', async () => {
	const table = await parseTableTokens(tableTokens(
		[
			{ value: 'Name', alignment: 'left' },
			{ value: 'Score', alignment: 'right' },
			{ value: 'Notes', alignment: null }
		],
		[
			[{ value: 'Ada', alignment: null }],
			[
				{ value: 'Lin', alignment: null },
				{ value: null, alignment: null }
			]
		]
	))

	assert.equal(table.headers.length, 3)
	assert.deepEqual(table.rows.map(row => row.length), [3, 3])
	assert.deepEqual(table.rows[0][1], {
		type: 'tableCell',
		header: false,
		alignment: null,
		children: []
	})
	assert.deepEqual(table.rows[1][1], table.rows[1][2])
	assert.deepEqual(table.alignments, ['left', 'right', null])
})

test('uses the widest data row when normalizing table alignment length', async () => {
	const table = await parseTableTokens(tableTokens(
		[{ value: 'Name', alignment: 'center' }],
		[[
			{ value: 'Ada', alignment: null },
			{ value: '10', alignment: null },
			{ value: 'Ready', alignment: null }
		]]
	))

	assert.equal(table.headers.length, 3)
	assert.equal(table.rows[0].length, 3)
	assert.equal(table.headers[1].header, true)
	assert.equal(table.headers[2].header, true)
	assert.deepEqual(table.alignments, ['center', null, null])
})

test('propagates assistant streaming state to fenced code blocks', async () => {
	const { parseAiMarkdown } = await loadParser()
	const fence = String.fromCharCode(96).repeat(3)
	const ast = parseAiMarkdown(fence + 'java\npublic class Main {', { streaming: true })

	assert.equal(ast.children[0].type, 'codeBlock')
	assert.equal(ast.children[0].streaming, true)
})

test('bounds fenced language metadata before it reaches the code toolbar', async () => {
	const { parseAiMarkdown } = await loadParser()
	const fence = String.fromCharCode(96).repeat(3)
	const ast = parseAiMarkdown(fence + 'x'.repeat(200) + '\nvalue\n' + fence)

	assert.equal(ast.children[0].language.id.length, 64)
	assert.equal(ast.children[0].language.label.length, 64)
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

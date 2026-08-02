const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const componentRoot = path.resolve(__dirname, '../../components/user/workspace')

function readComponent(name) {
	return fs.readFileSync(path.join(componentRoot, name), 'utf8')
}

test('markdown renderer uses an explicit component whitelist and never injects HTML', () => {
	const source = [
		'user-markdown-message.vue',
		'user-markdown-node.vue',
		'user-markdown-inline.vue',
		'user-markdown-code-block.vue',
		'user-markdown-table.vue',
		'user-dialog-block.vue'
	].map(readComponent).join('\n')

	assert.equal(source.includes('v-html'), false)
	assert.equal(source.includes('innerHTML'), false)
	assert.equal(source.includes('eval('), false)
	assert.equal(source.includes('new Function'), false)
	assert.equal(source.includes('component :is'), false)
	assert.equal(source.includes('onclick'), false)
})

test('code and table components keep Markdown control syntax out of visible UI', () => {
	const code = readComponent('user-markdown-code-block.vue')
	const table = readComponent('user-markdown-table.vue')

	assert.equal(code.includes('String.fromCharCode(96)'), false)
	assert.equal(code.includes('languageLabel'), true)
	assert.equal(code.includes('setClipboardData'), true)
	assert.equal(table.includes('alignments'), true)
	assert.equal(table.includes('setClipboardData'), true)
	assert.equal(table.includes('TSV'), true)
})

test('renderer exposes accessible labels for copy actions and preserves safe links', () => {
	const source = readComponent('user-markdown-node.vue')
	const inline = readComponent('user-markdown-inline.vue')
	const code = readComponent('user-markdown-code-block.vue')
	const table = readComponent('user-markdown-table.vue')

	assert.equal(inline.includes('navigator'), true)
	assert.equal(code.includes('aria-label'), true)
	assert.equal(table.includes('aria-label'), true)
})

test('dialog UI is driven by typed block data and emits commands instead of executing model text', () => {
	const dialog = readComponent('user-dialog-block.vue')

	assert.equal(dialog.includes('role="dialog"'), true)
	assert.equal(dialog.includes('aria-modal="true"'), true)
	assert.equal(dialog.includes('@keydown.esc'), true)
	assert.equal(dialog.includes('$emit(\'action\''), true)
	assert.equal(dialog.includes('onclick'), false)
	assert.equal(dialog.includes('v-html'), false)
})

test('assistant messages use the Markdown view boundary instead of raw response interpolation', () => {
	const panel = fs.readFileSync(
		path.join(componentRoot, 'user-chat-panel.vue'),
		'utf8'
	)

	assert.equal(panel.includes('<user-markdown-message'), true)
	assert.equal(panel.includes('{{ message.responseText }}'), false)
	assert.equal(panel.includes('createAiMarkdownRenderState'), true)
})

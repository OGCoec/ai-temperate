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
		'user-markdown-code-lines.vue',
		'user-markdown-code-line.vue',
		'user-markdown-html-preview.vue',
		'user-markdown-table.vue',
		'user-source-chip.vue',
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
	assert.equal(code.includes('{{ code }}'), false)
	assert.equal(code.includes('highlightedStableLines'), true)
	assert.equal(code.includes('highlightedUnstableLines'), true)
	assert.equal(code.includes("revision + ':'"), false)
	assert.equal(table.includes('alignments'), true)
	assert.equal(table.includes('setClipboardData'), true)
	assert.equal(table.includes('TSV'), true)
})

test('code blocks receive a stable AST path key and never render arbitrary token styles', () => {
	const node = readComponent('user-markdown-node.vue')
	const code = [
		readComponent('user-markdown-code-block.vue'),
		readComponent('user-markdown-code-lines.vue'),
		readComponent('user-markdown-code-line.vue')
	].join('\n')

	assert.equal(node.includes(':block-key="messageKey +'), true)
	assert.equal(code.includes(':style='), false)
	assert.equal(code.includes('ai-code-color-'), true)
	assert.equal(code.includes('createAiCodeHighlightSession'), true)
	assert.equal(code.includes("blockKey + ':line:' + line.index"), true)
})

test('code token styles live beside the child component that renders token DOM', () => {
	const block = readComponent('user-markdown-code-block.vue')
	const line = readComponent('user-markdown-code-line.vue')

	for (const selector of [
		'.ai-code-line',
		'.ai-code-token',
		'.ai-code-color-keyword',
		'.ai-code-color-comment',
		'.ai-code-color-type',
		'.ai-code-color-bracket-level-1'
	]) {
		assert.equal(line.includes(selector), true, selector + ' must be scoped to the token-rendering component')
	}

	assert.equal(block.includes('.ai-code-color-keyword'), false)
	assert.equal(block.includes('.ai-code-token'), false)
})

test('HTML preview uses a script-only sandbox and cleans temporary URLs', () => {
	const preview = readComponent('user-markdown-html-preview.vue')

	assert.equal(preview.includes('sandbox="allow-scripts"'), true)
	assert.equal(preview.includes('allow-same-origin'), false)
	assert.equal(preview.includes('referrerpolicy="no-referrer"'), true)
	assert.equal(preview.includes('URL.createObjectURL'), true)
	assert.equal(preview.includes('URL.revokeObjectURL'), true)
	assert.equal(preview.includes('v-html'), false)
	assert.equal(preview.includes('innerHTML'), false)
	assert.equal(preview.includes('eval('), false)
	assert.equal(preview.includes('new Function'), false)
})

test('HTML code blocks expose an accessible code and preview toggle', () => {
	const code = readComponent('user-markdown-code-block.vue')

	assert.equal(code.includes('isAiHtmlPreviewLanguage'), true)
	assert.equal(code.includes('role="group"'), true)
	assert.equal(code.includes('aria-label="代码块视图切换"'), true)
	assert.equal(code.includes(':aria-pressed='), true)
	assert.equal(code.includes(':aria-disabled='), true)
	assert.equal(code.includes(':disabled="previewDisabled"'), true)
	assert.equal(code.includes('<user-markdown-html-preview'), true)
	assert.equal(code.includes("viewMode = 'code'"), true)
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

test('matched SSE sources use one accessible chip without weakening ordinary link safety', () => {
	const message = readComponent('user-markdown-message.vue')
	const inline = readComponent('user-markdown-inline.vue')
	const chip = readComponent('user-source-chip.vue')
	const panel = readComponent('user-chat-panel.vue')

	assert.equal(message.includes('decorateAiMarkdownSources'), true)
	assert.equal(message.includes("sources: { type: Array, default: () => [] }"), true)
	assert.equal(inline.includes('node.safe && node.source'), true)
	assert.equal(inline.includes('<user-source-chip'), true)
	assert.equal(inline.includes('v-else-if="node?.type === \'link\' && node.safe"'), true)
	assert.equal(chip.includes(':aria-disabled="String(inactive)"'), true)
	assert.equal(chip.includes('window.open(url, \'_blank\', \'noopener,noreferrer\')'), true)
	assert.equal(chip.includes('plus.runtime.openURL(url)'), true)
	assert.equal(chip.includes('@error="handleFaviconError"'), true)
	assert.equal(chip.includes('/static/icons/source-globe.svg'), true)
	assert.equal(panel.includes('mergeAiConversationSources'), true)
	assert.equal(panel.includes(':sources="researchSources(message)"'), true)
	assert.equal(panel.includes('variant="activity"'), true)
	assert.equal(panel.includes('variant="card"'), true)
	assert.equal(chip.includes('v-html'), false)
})

test('research summaries render through compact safe Markdown and preserve exact activity states', () => {
	const panel = readComponent('user-chat-panel.vue')

	assert.equal(panel.includes('formatAiReasoningSummaryMarkdown'), true)
	assert.equal(panel.includes('compact'), true)
	assert.equal(panel.includes('presentAiSearchActivity'), true)
	assert.equal(panel.includes("STARTED: '已开始'"), true)
	assert.equal(panel.includes("IN_PROGRESS: '进行中'"), true)
	assert.equal(panel.includes("COMPLETED: '已完成'"), true)
	assert.equal(panel.includes('openResearchSource'), false)
})

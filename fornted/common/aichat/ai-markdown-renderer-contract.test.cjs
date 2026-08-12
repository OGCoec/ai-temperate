const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const componentRoot = path.resolve(__dirname, '../../components/user/workspace')

function readComponent(name) {
	return fs.readFileSync(path.join(componentRoot, name), 'utf8')
}

function sourceUrl(source) {
	return 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
}

async function loadTableLayout() {
	const layoutPath = path.join(__dirname, 'ai-markdown-table-layout.js')
	return import(sourceUrl(fs.readFileSync(layoutPath, 'utf8')) + '#' + Date.now() + '-' + Math.random())
}

function tableCell(value, type = 'text') {
	return {
		type: 'tableCell',
		header: false,
		alignment: null,
		children: value === '' ? [] : [{ type, value }]
	}
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

test('wide Markdown tables keep every column inside an accessible horizontal scroller', () => {
	const table = readComponent('user-markdown-table.vue')
	const panel = readComponent('user-chat-panel.vue')
	const layout = fs.readFileSync(path.join(__dirname, 'ai-markdown-table-layout.js'), 'utf8')

	assert.equal(table.includes('scroll-x'), true)
	assert.equal(table.includes(':show-scrollbar="true"'), true)
	assert.equal(table.includes(':show-scrollbar="false"'), false)
	assert.equal(table.includes(':style="tableStyle"'), true)
	assert.equal(table.includes('columnProfiles'), true)
	assert.equal(table.includes('tableMinWidth'), true)
	assert.equal(layout.includes('[112, 160, 224, 320]'), true)
	assert.equal(table.includes('overflow-wrap: anywhere'), true)
	assert.equal(table.includes('word-break: break-word'), true)
	assert.equal(table.includes('表格可左右滑动查看更多内容'), true)
	assert.equal(table.includes(':class="cellClass(cell, index, true)"'), true)
	assert.equal(table.includes("'is-numeric': Boolean(profile.numeric && !header)"), true)
	assert.match(panel, /\.assistant-message\s*\{[^}]*width:\s*100%/)
	assert.match(panel, /\.assistant-message\s*\{[^}]*max-width:\s*100%/)
	assert.match(panel, /\.assistant-message\s*\{[^}]*min-width:\s*0/)
})

test('table layout measures Unicode content and preserves every responsive column', async () => {
	const {
		aiMarkdownDisplayUnits,
		createAiMarkdownTableLayout
	} = await loadTableLayout()

	assert.equal(aiMarkdownDisplayUnits('ABCDEFGH'), 8)
	assert.equal(aiMarkdownDisplayUnits('中文ＡＢ'), 8)
	assert.equal(aiMarkdownDisplayUnits('❤️❤️❤️❤️❤️'), 10)
	assert.equal(aiMarkdownDisplayUnits('👨‍👩‍👧‍👦'), 2)
	assert.equal(aiMarkdownDisplayUnits('1️⃣#️⃣*️⃣'), 6)
	assert.equal(aiMarkdownDisplayUnits(String.fromCodePoint(
		0x1f3f4, 0xe0067, 0xe0062, 0xe0065, 0xe006e, 0xe0067, 0xe007f
	)), 2)

	for (const columnCount of [2, 6, 12]) {
		const headers = Array.from({ length: columnCount }, (_, index) => tableCell('C' + index))
		const rows = [Array.from({ length: columnCount }, (_, index) => tableCell(String(index)))]
		const layout = createAiMarkdownTableLayout(headers, rows, [])
		assert.equal(layout.columnProfiles.length, columnCount)
		assert.equal(layout.tableMinWidth, columnCount * 112)
	}
})

test('table layout gives identifiers and code room while keeping numeric alignment stable', async () => {
	const { createAiMarkdownTableLayout } = await loadTableLayout()
	const headers = [
		tableCell('Request'),
		tableCell('Code'),
		tableCell('Tokens'),
		tableCell('Forced'),
		tableCell('Relative URL'),
		tableCell('Embedded ID'),
		tableCell('Ratio')
	]
	const rows = [[
		tableCell('req_abc123'),
		tableCell('x'),
		tableCell('128K'),
		tableCell('1M'),
		tableCell('See /api/v1/users'),
		tableCell('ID: req_abc123 (primary)'),
		tableCell('ratio 5/10')
	]]
	rows[0][1] = tableCell('model-identifier', 'inlineCode')

	const layout = createAiMarkdownTableLayout(headers, rows, [null, null, null, 'left'])
	assert.equal(layout.columnProfiles[0].width, 224)
	assert.equal(layout.columnProfiles[1].width, 224)
	assert.equal(layout.columnProfiles[2].width, 112)
	assert.equal(layout.columnProfiles[2].alignment, 'right')
	assert.equal(layout.columnProfiles[3].alignment, 'left')
	assert.equal(layout.columnProfiles[4].width, 224)
	assert.equal(layout.columnProfiles[5].width, 224)
	assert.equal(layout.columnProfiles[6].width, 160)
})

test('numeric body cells stay compact without forcing long headers onto one line', async () => {
	const { createAiMarkdownTableLayout } = await loadTableLayout()
	const layout = createAiMarkdownTableLayout(
		[tableCell('Average response duration')],
		[[tableCell('12ms')]],
		[]
	)

	assert.equal(layout.columnProfiles[0].width, 112)
	assert.equal(layout.columnProfiles[0].numeric, true)
	assert.equal(layout.columnProfiles[0].alignment, 'right')
})

test('table layout exercises every content-width bucket', async () => {
	const { createAiMarkdownTableLayout } = await loadTableLayout()
	const headers = [tableCell('A'), tableCell('B'), tableCell('C'), tableCell('D')]
	const rows = [[
		tableCell('short'),
		tableCell('abcdefghij'),
		tableCell('abcdefghijklmnopqrst'),
		tableCell('x'.repeat(29))
	]]
	const layout = createAiMarkdownTableLayout(headers, rows, [])

	assert.deepEqual(layout.columnProfiles.map(profile => profile.width), [112, 160, 224, 320])
})

test('table TSV serialization includes off-screen and trailing empty cells', async () => {
	const { aiMarkdownTableAsTsv } = await loadTableLayout()
	const value = aiMarkdownTableAsTsv(
		[tableCell('Name'), tableCell('Score'), tableCell('Notes')],
		[
			[tableCell('Ada'), tableCell('10'), tableCell('ready')],
			[tableCell('Lin'), tableCell('9'), tableCell('')],
			[tableCell('Eve'), tableCell('8'), tableCell('line 1\r\nline "2"\tvalue')]
		]
	)

	assert.equal(
		value,
		'Name\tScore\tNotes\nAda\t10\tready\nLin\t9\t\nEve\t8\t"line 1\nline ""2""\tvalue"'
	)
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

test('HTML preview uses a separate-origin sandbox and exact message boundary', () => {
	const preview = readComponent('user-markdown-html-preview.vue')

	assert.equal(preview.includes('sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox"'), true)
	assert.equal(preview.includes('allow-top-navigation'), false)
	assert.equal(preview.includes('allow-downloads'), false)
	assert.equal(preview.includes('referrerpolicy="no-referrer"'), true)
	assert.equal(preview.includes('event.source !== frame.contentWindow'), true)
	assert.equal(preview.includes('event.origin !== this.previewOrigin'), true)
	assert.equal(preview.includes("postMessage(message, this.previewOrigin)"), true)
	assert.equal(preview.includes('URL.createObjectURL'), false)
	assert.equal(preview.includes('URL.revokeObjectURL'), false)
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
	const message = readComponent('user-markdown-message.vue')
	const panel = fs.readFileSync(
		path.join(componentRoot, 'user-chat-panel.vue'),
		'utf8'
	)

	assert.equal(panel.includes('<user-markdown-message'), true)
	assert.equal(panel.includes('{{ message.responseText }}'), false)
	assert.equal(panel.includes('createAiMarkdownRenderState'), true)
	assert.equal(message.includes('parseAiResponse'), true)
	assert.equal(message.includes('parseAiMarkdown(this.text'), false)
})

test('matched SSE sources use one accessible chip without weakening ordinary link safety', () => {
	const message = readComponent('user-markdown-message.vue')
	const inline = readComponent('user-markdown-inline.vue')
	const chip = readComponent('user-source-chip.vue')
	const panel = readComponent('user-chat-panel.vue')
	const opener = fs.readFileSync(path.resolve(__dirname,
		'../platform/external-url-opener.js'), 'utf8')

	assert.equal(message.includes('decorateAiMarkdownSources'), true)
	assert.equal(message.includes("sources: { type: Array, default: () => [] }"), true)
	assert.equal(inline.includes('node.safe && node.source'), true)
	assert.equal(inline.includes('<user-source-chip'), true)
	assert.equal(inline.includes('v-else-if="node?.type === \'link\' && node.safe"'), true)
	assert.equal(chip.includes(':aria-disabled="String(inactive)"'), true)
	assert.equal(chip.includes('openExternalHttpUrl(this.linkUrl)'), true)
	assert.equal(inline.includes('openExternalHttpUrl(value)'), true)
	assert.equal(inline.includes('tabindex="0"'), true)
	assert.equal(inline.includes('@keyup.enter.stop'), true)
	assert.equal(opener.includes("window.open(url, '_blank', 'noopener,noreferrer')"), true)
	assert.equal(opener.includes('plus.runtime.openURL(url)'), true)
	assert.equal(chip.includes('@error="handleFaviconError"'), true)
	assert.equal(chip.includes('/static/icons/source-globe.svg'), true)
	assert.equal(panel.includes('mergeAiConversationSources'), true)
	assert.equal(panel.includes('presentAiResearchTimeline'), true)
	assert.equal(panel.includes(':sources="researchSources(message)"'), true)
	assert.equal(panel.includes("item.type === 'source'"), true)
	assert.equal(panel.includes('已检索'), true)
	assert.equal(panel.includes('variant="activity"'), true)
	assert.equal(panel.includes('variant="card"'), true)
	assert.equal(chip.includes('v-html'), false)
})

test('research summaries render through compact safe Markdown and preserve exact activity states', () => {
	const panel = readComponent('user-chat-panel.vue')

	assert.equal(panel.includes('formatAiReasoningSummaryMarkdown'), true)
	assert.equal(panel.includes('compact'), true)
	assert.equal(panel.includes('presentAiResearchTimeline'), true)
	assert.equal(panel.includes("STARTED: '已开始'"), true)
	assert.equal(panel.includes("IN_PROGRESS: '进行中'"), true)
	assert.equal(panel.includes("COMPLETED: '已完成'"), true)
	assert.equal(panel.includes('openResearchSource'), false)
})

test('H5 and Android code blocks share one Shiki token and presentation contract', () => {
	const block = readComponent('user-markdown-code-block.vue')
	const lines = readComponent('user-markdown-code-lines.vue')
	const line = readComponent('user-markdown-code-line.vue')

	assert.equal(block.includes('createAiCodeHighlightSession'), true)
	assert.equal(block.includes('clientPlatform'), false)
	assert.equal(block.includes('androidPlainText'), false)
	assert.equal(block.includes('androidSourceCode'), false)
	assert.equal(lines.includes('showLineNumbers'), false)
	assert.equal(lines.includes('wrapPlainText'), false)
	assert.equal(line.includes('ai-code-line-number'), false)
	assert.equal(line.includes('wrapPlainText'), false)
	assert.equal(line.includes('.ai-code-color-keyword { color: #569cd6; }'), true)
	assert.equal(line.includes('.ai-code-color-variable { color: #9cdcfe; }'), true)
	assert.equal(line.includes('.ai-code-color-function { color: #dcdcaa; }'), true)
	assert.equal(line.includes('.ai-code-color-string { color: #ce9178; }'), true)
	assert.equal(line.includes('.ai-code-color-number { color: #b5cea8; }'), true)
	assert.equal(line.includes('.ai-code-color-comment { color: #6a9955; }'), true)
	assert.equal(block.includes('max-height:'), false)
	assert.equal(block.includes('折叠'), false)
})

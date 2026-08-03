const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const componentRoot = path.resolve(__dirname, '../../components/user/workspace')

function readComponent(name) {
	return fs.readFileSync(path.join(componentRoot, name), 'utf8')
}

test('preview component exposes explicit connection and runtime states', () => {
	const source = readComponent('user-markdown-html-preview.vue')

	for (const state of ['connecting', 'rendering', 'ready', 'warning', 'error']) {
		assert.equal(source.includes(`'${state}'`), true, state)
	}
	assert.equal(source.includes('AI_HTML_PREVIEW_READY_TIMEOUT_MS'), true)
	assert.equal(source.includes('AI_HTML_PREVIEW_RENDER_TIMEOUT_MS'), true)
	assert.equal(source.includes('预览服务连接超时'), true)
	assert.equal(source.includes('页面仍在加载'), true)
})

test('preview component validates the iframe window, origin, protocol, and channel', () => {
	const source = readComponent('user-markdown-html-preview.vue')

	assert.equal(source.includes('event.source !== frame.contentWindow'), true)
	assert.equal(source.includes('event.origin !== this.previewOrigin'), true)
	assert.equal(source.includes('isAiHtmlPreviewShellMessage'), true)
	assert.equal(source.includes('this.channelId'), true)
	assert.equal(source.includes('postMessage(message, this.previewOrigin)'), true)
})

test('preview component disposes timers, listeners, and the active render', () => {
	const source = readComponent('user-markdown-html-preview.vue')

	assert.equal(source.includes("window.addEventListener('message'"), true)
	assert.equal(source.includes("window.removeEventListener('message'"), true)
	assert.equal(source.includes('createAiHtmlPreviewDisposeMessage'), true)
	assert.equal(source.includes('clearTimeout'), true)
	assert.equal(source.includes('beforeUnmount'), true)
})

test('code block provides accessible full-screen, download, and motion behavior', () => {
	const source = readComponent('user-markdown-code-block.vue')

	assert.equal(source.includes('aria-label="代码块视图切换"'), true)
	assert.equal(source.includes('aria-label="全屏预览"'), true)
	assert.equal(source.includes('aria-label="关闭全屏预览"'), true)
	assert.equal(source.includes('aria-label="下载 HTML"'), true)
	assert.equal(source.includes("document.addEventListener('keydown'"), true)
	assert.equal(source.includes("event.key === 'Tab'"), true)
	assert.equal(source.includes('ai-code-focus-sentinel'), true)
	assert.equal(source.includes('restoreDocumentScroll'), true)
	assert.equal(source.includes('this.$refs.fullscreenButton'), true)
	assert.equal(source.includes('prefers-reduced-motion: reduce'), true)
	assert.equal(source.includes('translateX(38px)'), true)
	assert.equal(source.includes('cubic-bezier(0.4, 0, 0.2, 1)'), true)
	assert.equal(source.includes('scale(.97)'), true)
})

test('code block uses inline SVG icons instead of text glyphs', () => {
	const source = readComponent('user-markdown-code-block.vue')

	assert.equal(source.includes('<svg'), true)
	assert.equal(source.includes('&lt;/&gt;'), false)
	assert.equal(source.includes('>▶</text>'), false)
})

const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

async function loadConfig() {
	return loadEsmModule(path.join(__dirname, 'ai-html-preview-config.js'))
}

test('normalizes exact HTTPS preview origins', async () => {
	const { normalizeAiHtmlPreviewOrigin } = await loadConfig()

	assert.equal(
		normalizeAiHtmlPreviewOrigin('https://ai-temperate-html-preview.pages.dev/'),
		'https://ai-temperate-html-preview.pages.dev'
	)
})

test('rejects origins that include unsafe or non-origin fields', async () => {
	const { normalizeAiHtmlPreviewOrigin } = await loadConfig()

	for (const value of [
		'http://localhost:4174',
		'https://user:secret@localhost:4174',
		'https://localhost:4174/path',
		'https://localhost:4174/?mode=preview',
		'https://localhost:4174/#channel',
		'https://localhost:4174',
		'https://127.0.0.1:4174',
		'https://127.0.0.2:4174',
		'https://[::1]:4174',
		'not-a-url'
	]) {
		assert.equal(normalizeAiHtmlPreviewOrigin(value), '', value)
	}
})

test('returns a user-facing disabled configuration instead of guessing an origin', async () => {
	const { createAiHtmlPreviewConfig } = await loadConfig()

	assert.deepEqual(createAiHtmlPreviewConfig(''), {
		enabled: false,
		origin: '',
		error: 'HTML 安全预览地址尚未配置'
	})
	assert.equal(createAiHtmlPreviewConfig('http://localhost:4174').enabled, false)
	assert.deepEqual(createAiHtmlPreviewConfig('https://localhost:4174'), {
		enabled: false,
		origin: '',
		error: '公网页面禁止连接本机 HTML 预览服务'
	})
})

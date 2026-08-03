const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

test('only HTML language identifiers enable preview', async () => {
	const { isAiHtmlPreviewLanguage } = await loadEsmModule(
		path.join(__dirname, 'ai-html-preview-document.js')
	)

	assert.equal(isAiHtmlPreviewLanguage({ id: 'html' }), true)
	assert.equal(isAiHtmlPreviewLanguage({ canonicalId: 'html' }), true)
	assert.equal(isAiHtmlPreviewLanguage({ id: 'htm' }), true)
	assert.equal(isAiHtmlPreviewLanguage({ id: 'java' }), false)
	assert.equal(isAiHtmlPreviewLanguage({ id: '../../html' }), false)
})

test('main application no longer constructs or secures executable HTML documents', async () => {
	const module = await loadEsmModule(path.join(__dirname, 'ai-html-preview-document.js'))

	assert.equal('createAiHtmlPreviewDocument' in module, false)
	assert.equal('AI_HTML_PREVIEW_CSP' in module, false)
})

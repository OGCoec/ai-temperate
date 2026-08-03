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

test('builds a script-capable document with a restrictive CSP', async () => {
	const { createAiHtmlPreviewDocument } = await loadEsmModule(
		path.join(__dirname, 'ai-html-preview-document.js')
	)
	const source = '<html><head><title>Demo</title></head><body><script>document.body.dataset.ready="yes"</script></body></html>'
	const documentText = createAiHtmlPreviewDocument(source)

	assert.match(documentText, /Content-Security-Policy/)
	assert.match(documentText, /script-src 'unsafe-inline'/)
	assert.match(documentText, /connect-src 'none'/)
	assert.match(documentText, /form-action 'none'/)
	assert.match(documentText, /frame-src 'none'/)
	assert.match(documentText, /document\.body\.dataset\.ready="yes"/)
	assert.ok(documentText.indexOf('Content-Security-Policy') < documentText.indexOf('<script>'))
})

test('wraps an HTML fragment without changing its executable source', async () => {
	const { createAiHtmlPreviewDocument } = await loadEsmModule(
		path.join(__dirname, 'ai-html-preview-document.js')
	)
	const source = '<button id="run">Run</button><script>run.onclick=()=>run.textContent="Done"</script>'
	const documentText = createAiHtmlPreviewDocument(source)

	assert.match(documentText, /^<!doctype html>/i)
	assert.match(documentText, /<body>/i)
	assert.match(documentText, /run\.onclick/)
})

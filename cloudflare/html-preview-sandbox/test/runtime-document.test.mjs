import assert from 'node:assert/strict'
import test from 'node:test'
import {
	AI_HTML_PREVIEW_RUNTIME_CSP,
	createPreviewRuntimeDocument
} from '../public/runtime-document.js'

const runtime = {
	channelId: '000102030405060708090a0b0c0d0e0f',
	renderId: '101112131415161718191a1b1c1d1e1f',
	theme: 'dark',
	shellOrigin: 'https://ai-temperate-html-preview.pages.dev'
}

test('injects the bridge and runtime CSP before user module scripts', () => {
	const source = '<!doctype html><html><head><title>Water</title></head><body><script type="module">import("https://cdn.jsdelivr.net/npm/three/build/three.module.js")</script></body></html>'
	const documentText = createPreviewRuntimeDocument(source, runtime)

	assert.match(documentText, /Content-Security-Policy/)
	assert.match(documentText, /script-src https: data: blob: 'unsafe-inline' 'unsafe-eval'/)
	assert.match(documentText, /type="module"/)
	assert.match(documentText, /cdn\.jsdelivr\.net/)
	assert.ok(documentText.indexOf('ait-html-preview-runtime') < documentText.indexOf('type="module"'))
})

test('wraps fragments without rewriting their executable source', () => {
	const source = '<button id="run">Run</button><script>run.onclick=()=>run.textContent="Done"</script>'
	const documentText = createPreviewRuntimeDocument(source, runtime)

	assert.match(documentText, /^<!doctype html>/i)
	assert.match(documentText, /<body[^>]*>/i)
	assert.match(documentText, /run\.onclick/)
})

test('preserves literal closing-script text outside the injected bridge', () => {
	const source = '<pre id="source">&lt;/script&gt;</pre>'
	const documentText = createPreviewRuntimeDocument(source, runtime)

	assert.match(documentText, /&lt;\/script&gt;/)
	assert.equal((documentText.match(/id="source"/g) || []).length, 1)
})

test('keeps the permissive runtime policy isolated from the main application', () => {
	assert.match(AI_HTML_PREVIEW_RUNTIME_CSP, /connect-src https: wss:/)
	assert.match(AI_HTML_PREVIEW_RUNTIME_CSP, /worker-src https: blob:/)
	assert.match(AI_HTML_PREVIEW_RUNTIME_CSP, /object-src 'none'/)
	assert.equal(AI_HTML_PREVIEW_RUNTIME_CSP.includes('niko000o.site'), false)
})

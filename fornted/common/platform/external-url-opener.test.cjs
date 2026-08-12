const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('../aichat/ai-code-test-loader.cjs')

const modulePath = path.resolve(__dirname, 'external-url-opener.js')

async function loadModule() {
	return loadEsmModule(modulePath)
}

test('opens only canonical HTTP URLs through the injected platform adapter', async () => {
	const { createExternalHttpUrlOpener } = await loadModule()
	const opened = []
	const open = createExternalHttpUrlOpener(url => opened.push(url))

	assert.equal(open('HTTPS://Docs.Oracle.Com:443/java'), true)
	assert.deepEqual(opened, ['https://docs.oracle.com/java'])
	assert.equal(open('javascript:alert(1)'), false)
	assert.equal(open('https://user:secret@example.com'), false)
	assert.deepEqual(opened, ['https://docs.oracle.com/java'])
})

test('reports unavailable platform integration without throwing', async () => {
	const { createExternalHttpUrlOpener } = await loadModule()
	assert.equal(createExternalHttpUrlOpener(null)('https://example.com'), false)
	assert.equal(createExternalHttpUrlOpener(() => {
		throw new Error('platform unavailable')
	})('https://example.com'), false)
})

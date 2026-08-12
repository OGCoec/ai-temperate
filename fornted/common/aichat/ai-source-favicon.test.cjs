const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

function loadModule() {
	return loadEsmModule(path.join(__dirname, 'ai-source-favicon.js'))
}

test('builds a Google S2 favicon URL for a safe DNS hostname', async () => {
	const { buildAiSourceFaviconUrl } = await loadModule()

	assert.equal(buildAiSourceFaviconUrl('Docs.Oracle.COM.'),
		'https://www.google.com/s2/favicons?domain=https%3A%2F%2Fdocs.oracle.com&sz=128')
	assert.equal(buildAiSourceFaviconUrl('raw.githubusercontent.com'),
		'https://www.google.com/s2/favicons?domain=https%3A%2F%2Fraw.githubusercontent.com&sz=128')
	assert.equal(buildAiSourceFaviconUrl('help.openai.com'),
		'https://www.google.com/s2/favicons?domain=https%3A%2F%2Fhelp.openai.com&sz=128')
})

test('rejects local, IP, credential, port, path, and malformed favicon targets', async () => {
	const { buildAiSourceFaviconUrl } = await loadModule()
	for (const value of [
		'localhost',
		'127.0.0.1',
		'[::1]',
		'docs.oracle.com:8443',
		'user@docs.oracle.com',
		'docs.oracle.com/path',
		'-docs.oracle.com',
		'docs..oracle.com',
		'docs.oracle.com\nexample.com',
		''
	]) {
		assert.equal(buildAiSourceFaviconUrl(value), '', value)
	}
})

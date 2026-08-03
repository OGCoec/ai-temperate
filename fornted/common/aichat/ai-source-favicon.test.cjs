const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function loadModule() {
	const source = fs.readFileSync(path.join(__dirname,
		'ai-source-favicon.js'), 'utf8')
		.replaceAll('export function ', 'function ')
	const factory = new Function(`${source}; return { buildAiSourceFaviconUrl }`)
	return factory()
}

test('builds only an HTTPS root favicon URL for a safe DNS hostname', () => {
	const { buildAiSourceFaviconUrl } = loadModule()

	assert.equal(buildAiSourceFaviconUrl('Docs.Oracle.COM.'),
		'https://docs.oracle.com/favicon.ico')
	assert.equal(buildAiSourceFaviconUrl('raw.githubusercontent.com'),
		'https://raw.githubusercontent.com/favicon.ico')
})

test('rejects local, IP, credential, port, path, and malformed favicon targets', () => {
	const { buildAiSourceFaviconUrl } = loadModule()
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

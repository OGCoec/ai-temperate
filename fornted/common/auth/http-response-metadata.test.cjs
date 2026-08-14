const assert = require('node:assert/strict')
const path = require('node:path')
const { pathToFileURL } = require('node:url')
const test = require('node:test')

async function loadModule() {
	const url = pathToFileURL(path.resolve(__dirname, 'http-response-metadata.js'))
	url.searchParams.set('test', `${Date.now()}-${Math.random()}`)
	return import(url.href)
}

test('captures only data and a case-insensitive ETag from an HTTP response', async () => {
	const { captureEtagPayload } = await loadModule()
	const data = { id: 'AAAAAAAAAAE' }
	const result = captureEtagPayload(data, {
		'X-New-Access-Token': 'must-not-escape',
		eTaG: '"v3"'
	})

	assert.deepEqual(result, { data, etag: '"v3"' })
	assert.equal(Object.isFrozen(result), true)
	assert.equal(Object.hasOwn(result, 'headers'), false)
})

test('returns an empty ETag when the response omits the header', async () => {
	const { captureEtagPayload } = await loadModule()
	assert.deepEqual(captureEtagPayload({ ok: true }, {}), {
		data: { ok: true },
		etag: ''
	})
})

test('HTTP client keeps the legacy data return path unless captureEtag is explicitly true', () => {
	const source = require('node:fs').readFileSync(
		path.resolve(__dirname, 'http-client.js'), 'utf8')

	assert.match(source, /options\.captureEtag === true[\s\S]*captureEtagPayload/)
	assert.match(source, /:\s*response\.data/)
	assert.match(source, /captureEtag:\s*options\.captureEtag === true/)
})

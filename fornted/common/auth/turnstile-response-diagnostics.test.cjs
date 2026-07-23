const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'turnstile-response-diagnostics.js'),
		'utf8'
	)
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(sourceUrl)
}

test('classifies a Cloudflare HTML challenge even when its HTTP status is successful', async () => {
	const { inspectAuthResponse } = await loadModule()
	const result = inspectAuthResponse({
		statusCode: 200,
		header: {
			'Content-Type': 'text/html; charset=UTF-8',
			'cf-mitigated': 'challenge',
			'X-Trace-Id': 'trace-id',
			'CF-Ray': 'ray-id'
		},
		data: '<!doctype html><html></html>'
	})

	assert.equal(result.classification, 'EDGE_CHALLENGE')
	assert.equal(result.traceId, 'trace-id')
	assert.equal(result.cfRay, 'ray-id')
	assert.equal(Object.hasOwn(result, 'data'), false)
	assert.equal(Object.hasOwn(result, 'body'), false)
})

test('classifies backend JSON and reads headers case-insensitively', async () => {
	const { inspectAuthResponse } = await loadModule()
	const result = inspectAuthResponse({
		statusCode: 403,
		header: {
			'content-type': 'application/json',
			'x-trace-id': 'lower-trace',
			'cf-ray': 'lower-ray'
		},
		data: { code: 'TURNSTILE_REJECTED' }
	})

	assert.equal(result.classification, 'BACKEND_JSON')
	assert.equal(result.traceId, 'lower-trace')
	assert.equal(result.cfRay, 'lower-ray')
})

test('creates bounded correlation identifiers without treating them as credentials', async () => {
	const { createTurnstileAttemptId } = await loadModule()
	const identifiers = new Set(Array.from({ length: 20 }, createTurnstileAttemptId))

	assert.equal(identifiers.size, 20)
	for (const identifier of identifiers) {
		assert.match(identifier, /^[A-Za-z0-9_-]{8,80}$/)
	}
})

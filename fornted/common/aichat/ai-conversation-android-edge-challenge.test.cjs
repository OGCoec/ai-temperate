const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function source(relativePath) {
	return fs.readFileSync(path.resolve(__dirname, relativePath), 'utf8')
}

test('Android SSE reports Cloudflare challenge metadata without reading challenge HTML as events', () => {
	const contract = source('../../uni_modules/ait-sse/utssdk/interface.uts')
	const transport = source('../../uni_modules/ait-sse/utssdk/app-android/index.uts')
	const adapter = source('ai-conversation-sse-app.js')

	for (const field of ['contentType', 'cfMitigated', 'cfRay']) {
		assert.match(contract, new RegExp(`${field}: string`))
		assert.match(transport, new RegExp(field))
	}
	assert.match(transport, /android\.webkit\.CookieManager/)
	assert.match(transport, /cf_clearance/)
	assert.match(transport, /EDGE_CHALLENGE/)
	assert.match(adapter, /failure\?\.cfMitigated/)
	assert.match(adapter, /failure\?\.contentType/)
	assert.match(adapter, /failure\?\.cfRay/)
	assert.doesNotMatch(adapter, /failure\?\.body|failure\?\.data/)
})

test('Android POST SSE retries a managed challenge only before accepted', () => {
	const stream = source('ai-conversation-stream.js')
	const http = source('../auth/http-client.js')

	assert.match(http,
		/recoverAuthorizedStreamingSession[\s\S]*EDGE_CHALLENGE[\s\S]*ensureAndroidEdgeClearance/)
	assert.match(stream,
		/!closed\s*&&\s*!accepted\s*&&\s*!retried[\s\S]*recoverAuthorizedStreamingSession/)
	assert.match(stream, /return connect\(true\)/)
	assert.match(stream, /Idempotency-Key': command\.idempotencyKey/)
})

test('Android generation observer may recover one challenge while WebSocket code remains unchanged', () => {
	const stream = source('ai-conversation-stream.js')
	const voice = source('../voice/voice-websocket-session.js')

	assert.match(stream, /recoverGenerationEdgeChallenge/)
	assert.match(stream, /edgeChallengeRetried/)
	assert.doesNotMatch(voice,
		/android-edge-challenge|EDGE_CHALLENGE|cf_clearance|\/__edge\/android-clearance/)
})

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')

test('H5 OAuth WebRTC ownership is centralized in one gate module', () => {
	const gate = read('common/auth/h5-oauth-webrtc-gate.js')
	const flow = read('common/auth/oauth-flow.js')
	const verification = read('common/auth/webrtc-verification.js')

	for (const phase of [
		'PREPARED',
		'OAUTH_SUSPENDED',
		'RESUMED',
		'PENDING_VERDICT',
		'VERIFIED',
		'FAILED'
	]) {
		assert.match(gate, new RegExp(`['"]${phase}['"]`))
	}
	for (const api of [
		'readH5OAuthWebRtcGate',
		'writeH5OAuthWebRtcGate',
		'clearH5OAuthWebRtcGate',
		'ownsH5WebRtcScheduling',
		'hasPendingH5OAuthWebRtcVerdict'
	]) {
		assert.match(gate, new RegExp(`export function ${api}`))
	}
	assert.doesNotMatch(flow, /sessionStorage/)
	assert.doesNotMatch(verification, /sessionStorage/)
})

test('every generic H5 WebRTC entry point yields to an owned OAuth attempt', () => {
	const verification = read('common/auth/webrtc-verification.js')
	const http = read('common/auth/http-client.js')
	const app = read('App.vue')

	assert.match(verification, /ownsH5WebRtcScheduling/)
	assert.match(verification, /WEBRTC_BACKGROUND_SKIPPED/)
	assert.match(verification, /oauth_attempt_owned/)
	assert.match(http, /ownsH5WebRtcScheduling/)
	assert.match(http, /effectiveWebRtcSchedulingPolicy/)
	assert.match(http, /effectivePreAuthBootstrapPolicy/)
	assert.match(app, /ownsH5WebRtcScheduling/)
	assert.doesNotMatch(app, /!hasPendingH5OAuthWebRtcVerdict\(\)/)
})

test('OAuth report is single-flight by attempt and generation and never invokes start', () => {
	const verification = read('common/auth/webrtc-verification.js')
	const collectStart = verification.indexOf('export function collectAndReportAttempt')
	const collectEnd = verification.indexOf('async function queryOAuthVerdictUntilFinal', collectStart)
	const collectBody = verification.slice(collectStart, collectEnd)

	assert.match(verification, /oauthAttemptTasks\s*=\s*new Map\(\)/)
	assert.match(collectBody, /oauthAttemptTasks\.get/)
	assert.match(collectBody, /oauthAttemptTasks\.set/)
	assert.doesNotMatch(collectBody, /START_PATH|startPlatformWebRtcVerification/)
	assert.match(collectBody, /attemptId,\s*probeGeneration:\s*generation,\s*webRtcIps/)
})

test('OAuth pending session adopts the existing H5 PreAuth without bootstrapping it', () => {
	const preAuth = read('common/auth/pre-auth.js')
	const flow = read('common/auth/oauth-flow.js')
	const http = read('common/auth/http-client.js')

	assert.match(preAuth, /export function adoptExistingH5PreAuth\(\)/)
	assert.match(flow, /adoptExistingH5PreAuth\(\)/)
	assert.match(flow, /webRtcVerdict\s*===\s*['"]PENDING['"]/)
	assert.match(http, /PreAuthBootstrapPolicy\.REQUIRE_EXISTING/)
	assert.match(http, /WebRtcSchedulingPolicy\.SUPPRESS/)
})

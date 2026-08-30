const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const source = file => fs.readFileSync(path.join(__dirname, file), 'utf8')
const verification = source('webrtc-verification.js')

function section(start, end) {
	const startIndex = verification.indexOf(start)
	const endIndex = verification.indexOf(end, startIndex + start.length)
	assert.notEqual(startIndex, -1, `missing section start: ${start}`)
	assert.notEqual(endIndex, -1, `missing section end: ${end}`)
	return verification.slice(startIndex, endIndex)
}

test('H5 scheduler is detached and records only bounded background outcomes', () => {
	const scheduler = section(
		'export function scheduleH5WebRtcVerification',
		'export function currentWebRtcFailure')

	assert.match(scheduler, /void startPlatformWebRtcVerification\(expectedGeneration,/)
	assert.doesNotMatch(scheduler, /return\s+startPlatformWebRtcVerification/)
	assert.match(scheduler, /WEBRTC_BACKGROUND_SCHEDULED/)
	assert.match(scheduler, /WEBRTC_BACKGROUND_COMPLETED/)
	assert.match(scheduler, /WEBRTC_BACKGROUND_FAILED/)
	assert.doesNotMatch(scheduler, /Cookie|preAuthToken|deviceId|httpIp|webRtcIps|candidate/)
})

test('response headers preserve monotonic generation and join the existing single-flight', () => {
	const observer = section(
		'export function observeWebRtcVerificationHeaders',
		'export function scheduleH5WebRtcVerification')
	const starter = section(
		'function startPlatformWebRtcVerification',
		'export async function refreshWebRtcFailure')

	assert.match(observer, /compareGeneration\(generation, latestGeneration\) < 0/)
	assert.match(observer, /trigger\.state === 'VERIFIED'/)
	assert.match(observer, /trigger\.state === 'REQUIRED' \|\| trigger\.state === 'PENDING'/)
	assert.match(observer, /context\?\.responseAccepted !== true/)
	assert.match(observer, /requestEpoch !== preAuthEpoch/)
	assert.match(observer, /WEBRTC_RESPONSE_HEADER_IGNORED/)
	assert.match(observer, /WEBRTC_RESPONSE_HEADER_ACCEPTED/)
	assert.match(observer, /scheduleH5WebRtcVerification\(/)
	assert.match(starter, /activeEntry/)
	assert.match(starter, /taskKey\.startsWith\(`\$\{epoch\}:`\)/)
	assert.match(starter, /probeRunId:\s*activeDiagnosticAttempt\?\.probeRunId/)
	assert.match(starter, /return activeEntry\[1\]/)
})

test('H5 background scheduling requires a current epoch and a ready PreAuth', () => {
	const scheduler = section(
		'export function scheduleH5WebRtcVerification',
		'export function currentWebRtcFailure')

	assert.match(verification, /isPreAuthReady/)
	assert.match(verification, /export function currentWebRtcVerificationEpoch/)
	assert.match(scheduler, /requestEpoch !== preAuthEpoch/)
	assert.match(scheduler, /!isPreAuthReady\(\)/)
	assert.match(scheduler, /WEBRTC_BACKGROUND_SKIPPED/)
	assert.match(scheduler, /startPlatformWebRtcVerification\(expectedGeneration,[\s\S]*requestEpoch/)
})

test('WebRTC start diagnostics preserve the parent trigger without recording credentials', () => {
	const requestEdge = section('function requestEdge', 'function isEpochActive')
	const authDiagnostics = source('auth-diagnostics.js')

	assert.match(requestEdge, /WEBRTC_START_REQUEST_DISPATCHED/)
	assert.match(requestEdge, /WEBRTC_START_DISPATCHED/)
	assert.match(requestEdge, /triggerClientRequestId/)
	assert.match(requestEdge, /probeRunId/)
	assert.match(authDiagnostics, /X-AIT-WebRTC-Probe-Run-Id/)
	assert.doesNotMatch(requestEdge, /Cookie|preAuthToken:\s|deviceInstallationId:\s|httpIp|webRtcIps/)
})

test('cross-document diagnostics record the one-millisecond budget and report decision', () => {
	const verify = section('async function verify', 'function traceAndroidVerification')

	assert.match(verification, /installH5WebRtcDiagnosticLifecycle/)
	assert.match(verification, /WEBRTC_ATTEMPT_CREATED/)
	assert.match(verification, /WEBRTC_PAGEHIDE_WITH_ACTIVE_ATTEMPT/)
	assert.match(verification, /WEBRTC_ATTEMPT_ABANDONED/)
	assert.match(verify, /WEBRTC_START_RESOLVED/)
	assert.match(verification, /generation,\s*\n\s*webRtcGeneration:\s*generation/)
	assert.match(verify, /pendingRemainingMs:\s*remainingMillis/)
	assert.match(verify, /reportGraceMs:\s*reportGraceMillis/)
	assert.match(verify, /probeBudgetMs:\s*probeMillis/)
	assert.match(verify, /WEBRTC_PROBE_STARTED/)
	assert.match(verify, /WEBRTC_PROBE_FINISHED/)
	assert.match(verify, /stage === 'ice_finished' \|\| stage === 'ice_timeout'/)
	assert.match(verification, /if \(stage === 'ice_timeout' \|\| reason === 'timeout'\) return 'TIMEOUT'/)
	assert.match(verify, /WEBRTC_REPORT_PREPARED/)
	assert.match(verify, /reportDispatched:\s*true/)
	assert.match(verify, /WEBRTC_REPORT_COMPLETED/)
	assert.match(verify, /WEBRTC_REPORT_FAILED/)
	assert.match(verify, /WEBRTC_ATTEMPT_COMPLETED/)
})

test('PreAuth invalidation detaches stale bootstrap promises from the current lifecycle', () => {
	const preAuth = source('pre-auth.js')

	assert.match(preAuth, /preAuthLifecycleEpoch/)
	assert.match(preAuth, /export function isPreAuthReady/)
	assert.match(preAuth, /bootstrapInFlight\s*=\s*null/)
	assert.match(preAuth, /PREAUTH_ATTEMPT_STALE/)
	assert.match(preAuth, /bootstrapInFlight === entry/)
})

test('invalidating WebRTC makes old epoch completions unable to publish current state', () => {
	const invalidation = section(
		'export function invalidateWebRtcVerification',
		'export function observeWebRtcVerificationHeaders')
	const scheduler = section(
		'export function scheduleH5WebRtcVerification',
		'export function currentWebRtcFailure')

	assert.match(invalidation, /preAuthEpoch \+= 1/)
	assert.match(invalidation, /verificationTasks\.clear\(\)/)
	assert.equal((scheduler.match(/epoch !== preAuthEpoch/g) || []).length, 2)
	assert.match(verification, /if \(!isInvocationCurrent\(attempt\)\) return ignoredResult\(\)/)
})

test('H5 and Android keep their platform-specific probe implementations', () => {
	const h5 = source('webrtc-verification-h5.js')
	const android = source('webrtc-verification-android.js')

	assert.match(h5, /collectH5WebRtcIps|collectH5VerificationIps/)
	assert.doesNotMatch(h5, /plus\.webview|collectAndroidWebRtcIpsInBackground/)
	assert.match(android, /collectAndroidWebRtcIpsInBackground/)
	assert.doesNotMatch(android, /RTCPeerConnection|collectH5WebRtcIps/)
	assert.doesNotMatch(verification, /await startAndroidWebRtcVerificationInBackground\(/)
})

test('WebRTC attempts own cancellable probes and in-flight edge requests', () => {
	const invalidation = section(
		'export function invalidateWebRtcVerification',
		'export function currentWebRtcVerificationEpoch')
	const starter = section(
		'function startPlatformWebRtcVerification',
		'export async function refreshWebRtcFailure')
	const requestEdge = section('function requestEdge', 'function isEpochActive')

	assert.match(starter, /abortController/)
	assert.match(starter, /requestTasks:\s*new Set\(\)/)
	assert.match(starter, /cancelled:\s*false/)
	assert.match(starter, /cancelReason:\s*''/)
	assert.match(starter, /settled:\s*false/)
	assert.ok(invalidation.indexOf('preAuthEpoch += 1') < invalidation.indexOf('cancelWebRtcAttempt'))
	assert.match(invalidation, /cancelWebRtcAttempt\(attempt,\s*cancelReason\)/)
	assert.match(requestEdge, /requestTask = uni\.request\(/)
	assert.match(requestEdge, /attempt\.requestTasks\.add/)
	assert.match(requestEdge, /requestTask\.abort\(\)/)
	assert.match(requestEdge, /WEBRTC_REQUEST_ABORTED/)
})

test('cancelled or stale attempts skip reports without entering failure recovery', () => {
	const verify = section('async function verify', 'function traceAndroidVerification')

	assert.match(verify, /assertAttemptActive/)
	assert.match(verify, /isWebRtcAttemptCancellation/)
	assert.match(verify, /WEBRTC_REPORT_SKIPPED/)
	assert.match(verify, /return ignoredResult\(\)/)
	assert.ok(
		verify.indexOf('assertAttemptActive(attempt)')
			< verify.indexOf('WEBRTC_REPORT_DISPATCHED')
	)
})

test('H5 document lifecycle cancels pagehide work and starts a fresh BFCache task', () => {
	const lifecycle = section(
		'export function installH5WebRtcDiagnosticLifecycle',
		'export function observeWebRtcVerificationHeaders')

	assert.match(lifecycle, /pagehide/)
	assert.match(lifecycle, /invalidateWebRtcVerification\('DOCUMENT_UNLOADED'\)/)
	assert.match(lifecycle, /pageshow/)
	assert.match(lifecycle, /event\?\.persisted === true/)
	assert.match(lifecycle, /scheduleH5WebRtcVerification\(/)
	assert.doesNotMatch(lifecycle, /sendBeacon/)
})

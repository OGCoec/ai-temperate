const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const source = fs.readFileSync(path.join(__dirname, 'http-client.js'), 'utf8')

function section(start, end) {
	const startIndex = source.indexOf(start)
	const endIndex = source.indexOf(end, startIndex + start.length)
	assert.notEqual(startIndex, -1, `missing section start: ${start}`)
	assert.notEqual(endIndex, -1, `missing section end: ${end}`)
	return source.slice(startIndex, endIndex)
}

test('ordinary H5 request entry points schedule WebRTC without awaiting the probe', () => {
	const csrf = section(
		'export async function initializeBrowserCsrf',
		'export async function publicRequest')
	const publicRequest = section(
		'export async function publicRequest',
		'async function bootstrapBrowserSession')
	const authorized = section(
		'export async function authorizedRequest',
		'function handleAuthorizedSecurityFailure')
	const streaming = section(
		'export async function prepareAuthorizedStreamingRequest',
		'export async function recoverAuthorizedStreamingSession')

	for (const requestSource of [csrf, publicRequest, authorized, streaming]) {
		assert.match(requestSource, /scheduleH5WebRtcForRequest\(/)
		assert.doesNotMatch(requestSource, /await ensureH5WebRtcVerified\(\)/)
	}
	assert.doesNotMatch(source, /H5_WEBRTC_BACKGROUND_PATHS|shouldAwaitH5WebRtc/)
})

test('only explicit WebRTC rejection recovery waits and retries once', () => {
	const authorized = section(
		'export async function authorizedRequest',
		'function handleAuthorizedSecurityFailure')
	const recovery = section(
		'async function recoverH5WebRtc',
		'function recoverAndroidWebRtc')

	assert.equal((source.match(/await ensureH5WebRtcVerified\(\)/g) || []).length, 1)
	assert.match(recovery, /invalidateWebRtcVerification\(\)[\s\S]*await ensureH5WebRtcVerified\(\)/)
	assert.match(authorized, /!retryState\.webRtc\s*&&\s*isWebRtcRetryCode/)
	assert.match(authorized, /nextRetryState\(\{ webRtc: true \}\)/)
})

test('PreAuth recovery stays recoverable while terminal 401 clears every old security task', () => {
	const policy = fs.readFileSync(path.join(__dirname, 'session-retry-policy.js'), 'utf8')
	const terminalCodes = policy.slice(policy.indexOf('export const SESSION_TERMINAL_ERROR_CODES'), policy.indexOf('const TERMINAL_ERROR_CODE_SET'))
	const publicRequest = section(
		'export async function publicRequest',
		'async function bootstrapBrowserSession')
	const authorized = section(
		'export async function authorizedRequest',
		'function handleAuthorizedSecurityFailure')
	const bootstrap = section(
		'async function bootstrapBrowserSession',
		'export function restoreBrowserSession')
	const terminalCleanup = section(
		'function clearTerminalSessionState',
		'function handleTerminalSessionError')

	assert.match(
		publicRequest,
		/PREAUTH_REQUIRED[\s\S]*invalidatePreAuth\(\)[\s\S]*invalidateWebRtcVerification\(\)[\s\S]*await ensurePreAuth\(\)[\s\S]*scheduleH5WebRtcForRequest\(/
	)
	assert.match(
		authorized,
		/PREAUTH_REQUIRED[\s\S]*invalidatePreAuth\(\)[\s\S]*invalidateWebRtcVerification\(\)[\s\S]*await ensurePreAuth\(\)[\s\S]*scheduleH5WebRtcForRequest\(/
	)
	assert.match(terminalCodes, /REFRESH_TOKEN_REQUIRED/)
	assert.doesNotMatch(terminalCodes, /PREAUTH_REQUIRED/)
	assert.match(bootstrap, /catch \(error\)[\s\S]*clearTerminalSessionState\(\s*error,\s*authDiagnostic,\s*sessionGeneration,/)
	assert.match(
		terminalCleanup,
		/clearSession\(\)[\s\S]*invalidatePreAuth\(\)[\s\S]*invalidateWebRtcVerification\(\)/
	)
	assert.doesNotMatch(terminalCleanup, /uni\.reLaunch/)
})

test('response header observation receives response eligibility and the dispatch epoch without awaiting a probe', () => {
	const rawRequest = section('function rawRequestTask', 'async function requestTask')

	assert.match(rawRequest, /currentWebRtcVerificationEpoch\(\)/)
	assert.match(rawRequest, /requestEpoch/)
	assert.match(rawRequest, /observeWebRtcVerificationHeaders\(/)
	assert.match(rawRequest, /responseAccepted:/)
	assert.match(rawRequest, /diagnostics\.classification\s*!==\s*'EDGE_CHALLENGE'/)
	assert.doesNotMatch(rawRequest, /await\s+observeWebRtcVerificationHeaders/)
	assert.ok(
		rawRequest.indexOf('inspectAuthResponse')
			< rawRequest.indexOf('observeWebRtcVerificationHeaders')
	)
})

test('terminal session failures share one cleanup and redirect transition', () => {
	const imports = section(
		"import { AUTH_API_BASE_URL",
		'const CSRF_PATH')
	const terminalCleanup = section(
		'function clearTerminalSessionState',
		'async function recoverH5WebRtc')

	assert.match(imports, /beginRuntimeTerminalSessionTransition/)
	assert.match(imports, /claimRuntimeTerminalSessionRedirect/)
	assert.match(terminalCleanup, /SESSION_CLEAR_COALESCED/)
	assert.match(terminalCleanup, /if \(!beginRuntimeTerminalSessionTransition\(\)\)/)
	assert.match(terminalCleanup, /claimRuntimeTerminalSessionRedirect\(\)/)
})

test('authentication boundaries suppress both WebRTC scheduling hooks', () => {
	const rawRequest = section('function rawRequestTask', 'async function requestTask')
	const publicRequest = section(
		'export async function publicRequest',
		'async function bootstrapBrowserSession')

	assert.match(source, /WebRtcSchedulingPolicy/)
	assert.match(source, /NORMAL/)
	assert.match(source, /SUPPRESS/)
	assert.match(rawRequest, /effectiveWebRtcSchedulingPolicy\(options\)/)
	assert.match(rawRequest, /observeWebRtcVerificationHeaders/)
	assert.match(publicRequest, /webRtcSchedulingPolicy/)
	assert.match(publicRequest, /scheduleH5WebRtcForRequest/)
	assert.match(source, /WEBRTC_SCHEDULING_SUPPRESSED/)
})

test('authentication completion requests can disable generic request replay', () => {
	const publicRequest = section(
		'export async function publicRequest',
		'async function bootstrapBrowserSession')

	assert.match(publicRequest, /disableAutomaticReplay/)
	assert.match(publicRequest, /automaticReplayAllowed/)
	assert.match(publicRequest, /PREAUTH_REQUIRED[\s\S]*!automaticReplayAllowed[\s\S]*throw error/)
	assert.match(publicRequest, /RISK_CHALLENGE_REQUIRED[\s\S]*!automaticReplayAllowed[\s\S]*throw error/)
})

test('cookie-scope recovery links the rejected request, migration, and one replay without changing retry limits', () => {
	const publicRequest = section(
		'export async function publicRequest',
		'async function bootstrapBrowserSession')

	assert.match(publicRequest, /COOKIE_SCOPE_428_RECOVERY_STARTED/)
	assert.match(publicRequest, /ensureCookieScopeMigration\([\s\S]*triggerClientRequestId/)
	assert.match(publicRequest, /COOKIE_SCOPE_428_RECOVERY_COMPLETED/)
	assert.match(publicRequest, /COOKIE_SCOPE_428_RECOVERY_FAILED/)
	assert.match(publicRequest, /migrationRetried/)
	assert.match(publicRequest, /triggerClientRequestId/)
	assert.doesNotMatch(publicRequest, /migrationRetried\s*<\s*2/)
})

test('Android PreAuth mismatch clears an authenticated session without replaying anonymously', () => {
	const publicRequest = section(
		'export async function publicRequest',
		'async function bootstrapBrowserSession')
	const authorized = section(
		'export async function authorizedRequest',
		'function handleAuthorizedSecurityFailure')
	const androidTermination = section(
		'function terminateAuthenticatedAndroidSession',
		'async function recoverH5WebRtc')
	const diagnostics = section('function rawRequestTask', 'async function requestTask')

	assert.match(publicRequest, /terminateAuthenticatedAndroidSession\(error, authDiagnostic\)/)
	assert.match(authorized, /terminateAuthenticatedAndroidSession\(error, authDiagnostic\)/)
	assert.match(publicRequest, /disableAutomaticReplay/)
	assert.match(androidTermination, /clearSession\(\)/)
	assert.match(androidTermination, /clearAndroidOAuthFlow\(\)/)
	assert.match(androidTermination, /invalidatePreAuth\(\)/)
	assert.match(androidTermination, /redirectTerminalSessionToLogin\(error, authDiagnostic\)/)
	assert.match(diagnostics, /networkFailureDiagnostics\(cause,/)
	assert.match(diagnostics, /currentAndroidOAuthPhase\(\)/)
	assert.match(diagnostics, /preAuthReady/)
})

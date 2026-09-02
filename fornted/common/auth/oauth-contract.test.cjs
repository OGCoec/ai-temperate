const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')

test('Android OAuth Flow uses the existing encrypted flow vault while Google ID Token never enters storage', () => {
	const vault = read('common/auth/android-flow-keystore.js')
	const api = read('common/auth/auth-api.js')
	const flow = read('common/auth/oauth-flow.js')

	assert.match(vault, /saveAndroidOAuthFlow/)
	assert.match(vault, /phoneFlowToken/)
	assert.match(vault, /turnstileChallenge/)
	assert.match(vault, /updateAndroidOAuthFlowExpiry/)
	assert.doesNotMatch(vault, /idToken|googleIdToken/i)
	assert.match(api, /X-OAuth-Flow-Token/)
	assert.match(api, /X-OAuth-Phone-Flow-Token/)
	assert.doesNotMatch(api, /setStorageSync\([^\n]*idToken/i)
	assert.doesNotMatch(flow, /setStorage|saveAndroidOAuthFlow\([^\n]*idToken/i)
	assert.match(flow, /if \(!idToken\)[\s\S]*oauthCancel/)
})

test('Google OAuth diagnostics use the approved stages and never log credentials', () => {
	const flow = read('common/auth/oauth-flow.js')
	const api = read('common/auth/auth-api.js')
	const plugin = read('uni_modules/ait-google-signin/utssdk/app-android/index.uts')
	const pluginInterface = read('uni_modules/ait-google-signin/utssdk/interface.uts')

	assert.match(flow, /GOOGLE_OAUTH_LOG_PREFIX\s*=\s*['"]\[AIT_GOOGLE_OAUTH\]['"]/)
	assert.match(flow, /GOOGLE_NATIVE_TIMEOUT_MS\s*=\s*30000/)
	assert.match(flow, /GOOGLE_NATIVE_COMPLETE_TIMEOUT_MS\s*=\s*30000/)
	assert.match(flow, /let settled\s*=\s*false/)
	assert.match(flow, /const settle\s*=\s*callback\s*=>\s*value\s*=>/)
	assert.match(flow, /clearTimeout\(timeoutHandle\)/)
	assert.match(flow, /const allowed\s*=\s*\[['"]provider['"],\s*['"]mode['"],\s*['"]code['"],\s*['"]status['"],\s*['"]httpStatus['"],\s*['"]elapsedMs['"],\s*['"]tokenPresent['"]\]/)
	for (const stage of [
		'oauth_start_begin',
		'oauth_start_success',
		'oauth_start_fail',
		'native_begin',
		'native_success',
		'native_cancel',
		'native_fail',
		'native_timeout',
		'native_complete_begin',
		'native_complete_success',
		'native_complete_fail',
		'native_complete_timeout'
	]) {
		assert.match(flow, new RegExp(`['"]${stage}['"]`))
	}
	for (const stage of [
		'native_android_request_begin',
		'native_android_result',
		'native_android_success',
		'native_android_error',
		'native_android_cancel'
	]) {
		assert.match(plugin, new RegExp(stage))
	}
	assert.match(api, /oauthNativeGoogleComplete[\s\S]*timeout:\s*30000/)
	assert.match(api, /\/api\/auth\/oauth2\/google\/native\/complete/)
	assert.match(pluginInterface, /success\s*:\s*\(result : GoogleSignInResult\)\s*=>\s*void/)
	assert.match(pluginInterface, /cancel\s*:\s*\(\)\s*=>\s*void/)
	assert.match(pluginInterface, /fail\s*:\s*\(code : string, message : string\)\s*=>\s*void/)
	assert.doesNotMatch(flow, /console\.(?:log|info|warn|error)\([^\n]*(?:idToken|nonce|oauthFlowToken)/i)
	assert.doesNotMatch(plugin, /Log\.[a-zA-Z]+\([^\n]*(?:idToken|nonce|oauthFlowToken)/i)
})

test('Google native plugin uses the explicit Sign in with Google option', () => {
	const plugin = read('uni_modules/ait-google-signin/utssdk/app-android/index.uts')
	const config = JSON.parse(read('uni_modules/ait-google-signin/utssdk/app-android/config.json'))

	assert.match(plugin, /GetSignInWithGoogleOption/)
	assert.match(plugin, /new GetSignInWithGoogleOption\.Builder\(serverClientId\)/)
	assert.match(plugin, /setNonce\(nonce\)/)
	assert.doesNotMatch(plugin, /GetGoogleIdOption/)
	assert.doesNotMatch(plugin, /setFilterByAuthorizedAccounts/)
	assert.doesNotMatch(plugin, /native_android_retry_no_credential/)
	assert.deepEqual(config.dependencies, [
		'androidx.credentials:credentials:1.6.0',
		'androidx.credentials:credentials-play-services-auth:1.6.0',
		'com.google.android.libraries.identity.googleid:googleid:1.2.0',
		'com.google.android.gms:play-services-base:18.3.0'
	])
})

test('Android Google login is strictly native and never exposes browser fallback', () => {
	const login = read('pages/auth/login.vue')
	const flow = read('common/auth/oauth-flow.js')
	const config = read('common/auth/config.js')

	assert.doesNotMatch(login, /googleBrowserFallback/)
	assert.doesNotMatch(login, /改用浏览器登录 Google/)
	assert.doesNotMatch(login, /forceBrowser/)
	assert.match(flow, /provider === 'GOOGLE'[\s\S]*GOOGLE_NATIVE/)
	assert.doesNotMatch(flow, /nativeUnavailable[\s\S]*interactionMode:\s*['"]BROWSER['"]/
	)
	assert.match(config, /#ifdef APP-PLUS[\s\S]*uni\.getSystemInfoSync\(\)/)
	assert.doesNotMatch(config, /#ifdef APP-(?:ANDROID|IOS)/)
})

test('native Google plugin is compiled for the plus runtime and guarded by Android detection', () => {
	const flow = read('common/auth/oauth-flow.js')

	assert.match(flow, /#ifdef APP-PLUS[\s\S]*ait-google-signin[\s\S]*#endif/)
	assert.match(flow, /clientPlatform\(\) !== ['"]ANDROID['"]|clientPlatform\(\) === ['"]ANDROID['"]/)
	assert.doesNotMatch(flow, /#ifdef APP-ANDROID/)
	assert.doesNotMatch(flow, /#ifdef APP-IOS/)
})

test('login, App onShow and OAuth phone page expose the required recovery paths', () => {
	const login = read('pages/auth/login.vue')
	const app = read('App.vue')
	const phone = read('pages/auth/oauth-phone.vue')

	assert.match(login, /使用 Google 登录/)
	assert.match(login, /使用 GitHub 登录/)
	assert.doesNotMatch(login, /改用浏览器登录 Google/)
	assert.match(app, /resumePendingOAuth/)
	assert.match(phone, /action="oauth_phone"/)
	assert.match(phone, /phone-delivery-method/)
	assert.match(phone, /60/)
})

test('H5 OAuth flow pages never enter authenticated session bootstrap', () => {
	const protectedRoutes = read('common/auth/protected-routes.js')
	const returnPage = read('pages/auth/oauth-return.vue')
	const phonePage = read('pages/auth/oauth-phone.vue')

	assert.match(protectedRoutes, /'\/pages\/auth\/oauth-return'/)
	assert.match(protectedRoutes, /'\/pages\/auth\/oauth-phone'/)
	assert.doesNotMatch(returnPage, /restorePersistedSession|session\/bootstrap/)
	assert.doesNotMatch(phonePage, /restorePersistedSession|session\/bootstrap/)
})

test('H5 OAuth return completion is page-idempotent and delegates state transitions once', () => {
	const returnPage = read('pages/auth/oauth-return.vue')
	const flow = read('common/auth/oauth-flow.js')

	assert.match(returnPage, /completionPromise/)
	assert.match(returnPage, /if \(this\.completionPromise\) return this\.completionPromise/)
	assert.match(returnPage, /this\.completionPromise = this\.runCompletion\(\)/)
	assert.match(flow, /\['PHONE_REQUIRED', 'HUMAN_VERIFICATION_REQUIRED', 'CODE_READY'\]/)
	assert.match(flow, /status\.state === 'READY_TO_COMPLETE'/)
	assert.match(flow, /status\.state === 'TOTP_REQUIRED'/)
	assert.match(flow, /status\.state === 'AUTHENTICATED'/)
	assert.match(flow, /result\?\.status === 'TOTP_REQUIRED'/)
	assert.match(flow, /result\?\.status === 'AUTHENTICATED'/)
	assert.match(flow, /\['FAILED', 'EXPIRED'\]/)
})

test('H5 OAuth reuses one start and completes before the asynchronous WebRTC verdict', () => {
	const api = read('common/auth/auth-api.js')
	const flow = read('common/auth/oauth-flow.js')
	const app = read('App.vue')

	assert.match(api, /authenticationCompletionRequest/)
	assert.match(api, /disableAutomaticReplay:\s*true/)
	assert.match(api, /WebRtcSchedulingPolicy\.SUPPRESS/)
	assert.match(api, /PreAuthBootstrapPolicy\.REQUIRE_EXISTING/)
	assert.match(api, /oauthStart\([\s\S]*?oauthRequestOptions\(/)
	assert.match(api, /oauthStatus\([\s\S]*?existingH5OAuthRequestOptions/)
	assert.match(api, /oauthWebRtcResume/)
	assert.match(api, /oauthWebRtcVerdictStatus/)
	assert.match(api, /oauthComplete\([\s\S]*?X-AIT-OAuth-WebRTC-Attempt-Id/)
	assert.match(flow, /prepareWebRtcAttempt/)
	assert.match(flow, /suspendH5WebRtcForOAuth/)
	assert.match(flow, /settlePendingH5OAuthWebRtcVerdict/)
	assert.match(flow, /OAUTH_WEBRTC_ATTEMPT_PREPARED/)
	assert.match(flow, /OAUTH_WEBRTC_ATTEMPT_SUSPENDED/)
	assert.match(flow, /OAUTH_WEBRTC_ATTEMPT_RESUMED/)
	assert.match(flow, /OAUTH_WEBRTC_ASYNC_SESSION_ISSUED/)
	assert.match(flow, /OAUTH_WEBRTC_VERDICT_VERIFIED/)
	assert.match(flow, /OAUTH_WEBRTC_VERDICT_REJECTED/)
	assert.match(flow, /OAUTH_WEBRTC_VERDICT_TIMEOUT/)
	assert.match(flow, /OAUTH_WEBRTC_FALLBACK_STARTED/)
	assert.match(flow, /h5OAuthCompletionPromise/)
	assert.match(app, /isH5OAuthReturnPath/)
	assert.match(app, /hasPendingH5OAuthWebRtcVerdict/)
	assert.match(app, /settlePendingH5OAuthWebRtcVerdict/)
	assert.match(app, /!ownsH5WebRtcScheduling\(\)/)
	assert.match(api, /AUTHENTICATED_EPOCH_ROTATED/)
	assert.match(api, /WEBRTC_AUTH_EPOCH_STARTED/)
	const readyIndex = flow.indexOf("status.state === 'READY_TO_COMPLETE'")
	const resumeIndex = flow.indexOf('authApi.oauthWebRtcResume(', readyIndex)
	const completeIndex = flow.indexOf('authApi.oauthComplete(', resumeIndex + 1)
	const reportIndex = flow.indexOf('collectAndReportAttempt(', completeIndex + 1)
	assert.ok(readyIndex >= 0, 'READY_TO_COMPLETE branch must exist')
	assert.ok(resumeIndex > readyIndex, 'resume must run after READY_TO_COMPLETE')
	assert.ok(completeIndex > resumeIndex, 'OAuth complete must follow resume')
	assert.ok(reportIndex > completeIndex, 'background report must be defined after OAuth complete')
	assert.doesNotMatch(flow, /invalidatePreAuth\(/)
	assert.doesNotMatch(flow, /ensureH5WebRtcVerified\(/)
	for (const path of [
		'/api/auth/oauth2/complete',
		'/api/auth/oauth2/google/native/complete',
		'/api/auth/login/password',
		'/api/auth/login/code/verify',
		'/api/auth/login/totp/verify'
	]) {
		assert.match(api, new RegExp(path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
	}
	assert.match(api, /startAndroidWebRtcVerificationInBackground/)
})

test('H5 OAuth asynchronous report failure clears the session and cannot start a second generation', () => {
	const flow = read('common/auth/oauth-flow.js')
	const verification = read('common/auth/webrtc-verification.js')

	assert.match(flow, /OAUTH_WEBRTC_VERDICT_REJECTED/)
	assert.match(flow, /clearSession\(\)/)
	assert.match(flow, /presentWebRtcFailure\(/)
	assert.match(flow, /WEBRTC_VERIFICATION_FAILED/)
	assert.match(flow, /fallbackUsed/)
	assert.match(flow, /OAUTH_WEBRTC_FALLBACK_STARTED/)
	assert.match(flow, /authApi\.oauthComplete\(/)
	assert.match(verification, /collectAndReportAttempt/)
	assert.match(verification, /VERDICT_STATUS_PATH/)
	const collectStart = verification.indexOf('export async function collectAndReportAttempt')
	const statusStart = verification.indexOf('async function queryOAuthVerdictUntilFinal', collectStart)
	const collectBody = verification.slice(collectStart, statusStart)
	assert.ok(collectStart >= 0 && statusStart > collectStart)
	assert.doesNotMatch(collectBody, /submitReport\(/)
	assert.match(collectBody, /requestEdge\(/)
	assert.match(verification, /isWebRtcRetryCode\(error\?\.code\)/)
	assert.doesNotMatch(flow, /recoverH5WebRtc\(/)
})

test('Android app lifecycle cancels only WebRTC and resumes OAuth before a fresh probe', () => {
	const app = read('App.vue')
	const onShowIndex = app.indexOf('onShow()')
	const onHideIndex = app.indexOf('onHide()')
	const onShow = app.slice(onShowIndex, onHideIndex)
	const onHide = app.slice(onHideIndex, app.indexOf('globalData', onHideIndex))

	assert.match(onHide, /cancelActiveWebRtcVerification\('APP_HIDDEN'\)/)
	assert.doesNotMatch(onHide, /oauthCancel|cancelGoogle|CredentialManager/)
	assert.ok(onShow.indexOf('resumePendingOAuth()') < onShow.indexOf('ensurePreAuth()'))
	assert.ok(
		onShow.indexOf('ensurePreAuth()')
			< onShow.indexOf('startAndroidWebRtcVerificationInBackground()')
	)
})

test('Android OAuth coordinator serializes native callback and onShow recovery', () => {
	const coordinator = read('common/auth/android-oauth-coordinator.js')
	const api = read('common/auth/auth-api.js')
	const flow = read('common/auth/oauth-flow.js')
	const app = read('App.vue')

	for (const phase of [
		'IDLE', 'PREAUTH_READY', 'FLOW_STARTED', 'NATIVE_PICKER',
		'NATIVE_COMPLETE', 'SESSION_COMPLETE', 'CREDENTIALS_COMMITTED', 'DONE'
	]) assert.match(coordinator, new RegExp(`['"]${phase}['"]`))
	for (const phase of ['NETWORK_UNKNOWN', 'FLOW_EXPIRED', 'PREAUTH_MISMATCH', 'FAILED']) {
		assert.match(coordinator, new RegExp(`['"]${phase}['"]`))
	}
	assert.match(coordinator, /same operation joins|operationKey/)
	assert.match(flow, /androidOAuthCoordinator\.run\(/)
	assert.match(flow, /androidOAuthCoordinator\.join\(/)
	assert.match(flow, /if \(resumePromise\) return resumePromise/)
	assert.match(app, /androidOAuthCoordinator\.join\(['"]google-native['"]\)/)
	assert.match(api, /androidOAuthNativeCompletePromise/)
	assert.match(api, /androidOAuthCompletePromise/)
	assert.match(flow, /confirmAndroidOAuthResult/)
	assert.match(flow, /NETWORK_UNKNOWN/)
	assert.match(flow, /ALREADY_COMPLETED/)
	assert.match(flow, /SESSION_RESPONSE_INVALID/)
})

test('login silently ignores OAuth clicks until the shared PreAuth state is ready', () => {
	const login = read('pages/auth/login.vue')
	const methodStart = login.indexOf('async oauthLogin(provider)')
	const methodEnd = login.indexOf('\n\t\t\tsyncTurnstileBounds(', methodStart)
	const method = login.slice(methodStart, methodEnd)

	assert.match(
		login,
		/import\s*\{\s*isPreAuthReady\s*\}\s*from\s*['"]@\/common\/auth\/pre-auth\.js['"]/
	)
	assert.ok(methodStart >= 0 && methodEnd > methodStart)
	const readinessGuard = method.indexOf('if (!isPreAuthReady()) return')
	const providerAssignment = method.indexOf('this.oauthProvider = provider')
	const runStart = method.indexOf('this.run(')
	const oauthStart = method.indexOf('startOAuth(provider)')
	assert.ok(readinessGuard >= 0, 'OAuth click must check the shared PreAuth readiness')
	assert.ok(readinessGuard < providerAssignment, 'PreAuth guard must run before provider state changes')
	assert.ok(readinessGuard < runStart, 'PreAuth guard must run before OAuth enters busy state')
	assert.ok(readinessGuard < oauthStart, 'PreAuth guard must run before the OAuth flow starts')
	assert.doesNotMatch(method.slice(0, providerAssignment), /this\.error|uni\.showToast/)
})

test('login renders stable OAuth buttons without visual loading spinners', () => {
	const login = read('pages/auth/login.vue')
	const googleIconPath = path.join(root, 'static/icons/auth/google.svg')
	const githubIconPath = path.join(root, 'static/icons/auth/github.svg')

	assert.match(login, /\.oauth-actions\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/)
	assert.match(login, /\.oauth-actions\s*\{[^}]*column-gap:\s*14px/)
	assert.match(login, /\.oauth-actions\s*\{[^}]*width:\s*100%/)
	assert.match(login, /\.oauth-button\s*\{[^}]*width:\s*100%[^}]*min-width:\s*0[^}]*margin:\s*0[^}]*box-sizing:\s*border-box[^}]*overflow:\s*hidden/)
	assert.match(login, /\.oauth-button:disabled\s*\{[^}]*opacity:\s*\.46[^}]*filter:\s*grayscale\(\.35\)\s+saturate\(\.65\)[^}]*cursor:\s*not-allowed/)
	assert.match(login, /class="oauth-icon"\s+src="\/static\/icons\/auth\/google\.svg"\s+mode="aspectFit"\s+aria-hidden="true"/)
	assert.match(login, /class="oauth-icon"\s+src="\/static\/icons\/auth\/github\.svg"\s+mode="aspectFit"\s+aria-hidden="true"/)
	assert.doesNotMatch(login, /\.oauth-fallback\s*\{[^}]*grid-column:\s*1\s*\/\s*-1/)
	assert.match(
		login,
		/@media\s+screen\s+and\s+\(max-width:\s*359px\)[\s\S]*?\.oauth-actions\s*\{[^}]*grid-template-columns:\s*1fr[^}]*row-gap:\s*10px/
	)
	assert.doesNotMatch(login, /:loading="busy\s*&&\s*oauthProvider\s*===\s*'(?:GOOGLE|GITHUB)'"/)
	assert.equal((login.match(/:disabled="busy"/g) || []).length >= 2, true)
	assert.equal(fs.existsSync(googleIconPath), true)
	assert.equal(fs.existsSync(githubIconPath), true)
	assert.match(fs.readFileSync(googleIconPath, 'utf8'), /#4285F4[\s\S]*#34A853[\s\S]*#FBBC05[\s\S]*#EA4335/)
	assert.match(fs.readFileSync(githubIconPath, 'utf8'), /fill="white"/)
})

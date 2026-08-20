const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const source = file => fs.readFileSync(path.join(root, file), 'utf8')

test('ordinary H5 bootstraps PreAuth before protected API requests without exposing its cookie', () => {
	const preAuth = source('common/auth/pre-auth.js')
	const http = source('common/auth/http-client.js')

	assert.match(preAuth, /\/api\/_edge\/pre-auth/)
	assert.match(preAuth, /ensureCookieScopeMigration\(\)/)
	assert.match(preAuth, /withCredentials:\s*true/)
	assert.match(preAuth, /clientPlatform\(\)\s*!==\s*'ANDROID'/)
	assert.match(preAuth, /X-AIT-PreAuth-Reset/)
	assert.match(preAuth, /response\?\.status\s*===\s*'DISABLED'/)
	assert.match(preAuth, /RISK_CHALLENGE_REQUIRED/)
	assert.match(preAuth, /beginRiskChallenge\(error\)/)
	assert.match(preAuth, /reauthenticationRequired/)
	assert.match(preAuth, /clearSession\(\)/)
	assert.match(preAuth, /error\.preAuthToken/)
	assert.doesNotMatch(preAuth, /document\.cookie|XSRF-TOKEN/)
	assert.match(http, /await ensurePreAuth\(\)/)
	assert.match(http, /headers\['X-AIT-PreAuth'\]/)
	assert.match(http, /PREAUTH_REQUIRED/)
	assert.match(http, /preAuthRetried/)
	assert.match(http, /initializeBrowserCsrf\(migrationRetried,\s*true,\s*webRtcRetried\)/)
	assert.match(http, /beginRiskChallenge\(error\)/)
	assert.match(http, /clearSession\(\)[\s\S]*invalidatePreAuth\(\)/)
})

test('only exact H5 bootstrap paths continue while the WebRTC report is pending', () => {
	const http = source('common/auth/http-client.js')
	const authApi = source('common/auth/auth-api.js')
	const publicRequest = http.slice(http.indexOf('export async function publicRequest'))

	assert.match(http, /PHONE_COUNTRY_PATH\s*=\s*'\/api\/auth\/phone-country'/)
	assert.match(http, /OAUTH_START_PATH\s*=\s*'\/api\/auth\/oauth2\/start'/)
	assert.match(
		http,
		/H5_WEBRTC_BACKGROUND_PATHS\s*=\s*new Set\(\[\s*PHONE_COUNTRY_PATH,\s*OAUTH_START_PATH\s*\]\)/
	)
	assert.match(
		http,
		/function shouldAwaitH5WebRtc\(path\)\s*\{\s*return !H5_WEBRTC_BACKGROUND_PATHS\.has\(path\)\s*\}/
	)
	assert.match(
		publicRequest,
		/await ensureCookieScopeMigration\(\)[\s\S]*await ensurePreAuth\(\)[\s\S]*if \(shouldAwaitH5WebRtc\(path\)\) \{\s*await ensureH5WebRtcVerified\(\)\s*\}/
	)
	assert.match(
		publicRequest,
		/error\.code === 'PREAUTH_REQUIRED'[\s\S]*await ensurePreAuth\(\)[\s\S]*if \(shouldAwaitH5WebRtc\(path\)\) \{\s*await ensureH5WebRtcVerified\(\)\s*\}/
	)
	assert.equal(
		(publicRequest.match(/if \(shouldAwaitH5WebRtc\(path\)\) \{/g) || []).length,
		2
	)
	assert.doesNotMatch(http, /skipWebRtc/i)
	assert.match(
		authApi,
		/phoneCountry\(\)\s*\{[\s\S]*publicRequest\('\/api\/auth\/phone-country'/
	)
	assert.match(
		authApi,
		/oauthStart\(provider, interactionMode\)[\s\S]*publicRequest\('\/api\/auth\/oauth2\/start'/
	)
})

test('ordinary risk challenge uses the shared three-round state machine and a recoverable failure gate', () => {
	const navigation = source('common/auth/risk-challenge-navigation.js')
	const preAuth = source('common/auth/pre-auth.js')
	const completion = source('pages/risk/challenge-complete.vue')
	const failure = source('pages/risk/challenge-failed.vue')
	const gate = source('../shared-frontend/auth/risk-challenge-failed-gate.vue')
	const core = source('../shared-frontend/auth/risk-challenge-state-machine.js')
	const pages = source('pages.json')
	const app = source('App.vue')
	const flowPages = `${completion}\n${failure}`

	assert.match(navigation, /ALLOWED_PATH\s*=\s*'\/api\/_edge\/risk-challenge'/)
	assert.match(navigation, /createRiskChallengeFlow/)
	assert.match(preAuth, /claimRiskChallengeRecheck\(\)[\s\S]*requestBootstrap\(headers\)/)
	assert.match(core, /MAX_ATTEMPTS\s*=\s*3/)
	assert.match(core, /phase:\s*RISK_CHALLENGE_PHASE\.FAILED/)
	assert.match(core, /current\.attempt\s*>=\s*maxAttempts/)
	assert.match(core, /RISK_CHALLENGE_FAILURE_REASON\.EXHAUSTED/)
	assert.match(navigation, /window\.location\.pathname/)
	assert.match(navigation, /window\.location\.search/)
	assert.match(navigation, /window\.location\.hash/)
	assert.match(navigation, /window\.location\.assign/)
	assert.match(navigation, /window\.location\.replace/)
	assert.doesNotMatch(navigation, /uni\.request|fetch\(/)
	assert.match(completion, /restoreRiskChallengeReturn/)
	assert.match(failure, /ChallengeFailedGate/)
	assert.match(failure, /resetRiskChallengeForManualRetry/)
	assert.match(app, /if \(!isRiskChallengeFlowPage\(options\?\.path\)\)/)
	assert.doesNotMatch(flowPages, /ensurePreAuth|ensureWebRtcVerified|uni\.request|fetch\(/)
	assert.match(gate, /重新检查/)
	assert.match(gate, /正在重新检查/)
	assert.match(gate, /aria-busy/)
	assert.match(gate, /prefers-reduced-motion:\s*reduce/)
	assert.match(gate, /min-height:\s*100dvh/)
	assert.match(gate, /@media \(min-width:\s*760px\)/)
	assert.equal((gate.match(/<button/g) || []).length, 1)
	assert.doesNotMatch(gate, /428|Cloudflare|WAF|信用分|ASN|供应商/)
	assert.match(pages, /pages\/risk\/challenge-failed/)
})
test('ordinary Android risk challenge bridges PreAuth through one bounded WebView retry', () => {
	const coordinator = source('common/auth/android-risk-challenge.js')
	const preAuth = source('common/auth/pre-auth.js')
	const http = source('common/auth/http-client.js')
	const shared = source('../shared-frontend/auth/android-risk-challenge.js')

	assert.match(coordinator, /__Host-ait-preauth/)
	assert.match(coordinator, /\/api\/_edge\/risk-challenge/)
	assert.match(coordinator, /\/pages\/risk\/challenge-complete/)
	assert.match(preAuth, /ensureAndroidRiskChallenge/)
	assert.match(preAuth, /recheckPreAuthAfterRiskChallenge/)
	assert.match(http, /repeatedAndroidRiskChallengeError/)
	assert.match(http, /riskChallenge/)
	assert.match(shared, /RISK_CHALLENGE_REPEATED/)
	assert.match(shared, /BRIDGE_COOKIE_MAX_AGE_SECONDS\s*=\s*180/)
	assert.match(shared, /RISK_CHALLENGE_COOKIE_FAILED/)
	assert.match(shared, /RISK_CHALLENGE_TIMEOUT/)
	assert.match(shared, /RISK_CHALLENGE_CANCELLED/)
	assert.doesNotMatch(shared, /removeAllCookies|console\./)
})

test('ordinary risk block enters a non-retryable top-level security gate', () => {
	const app = source('App.vue')
	const login = source('pages/auth/login.vue')
	const preAuth = source('common/auth/pre-auth.js')
	const http = source('common/auth/http-client.js')
	const webRtcFailure = source('pages/risk/webrtc-failed.vue')
	const navigation = source('common/auth/risk-block-navigation.js')
	const blockPage = source('pages/risk/blocked.vue')
	const pages = source('pages.json')
	const csrfInitializationStart = login.indexOf('async initializePageCsrf()')
	const csrfInitialization = login.slice(
		csrfInitializationStart,
		login.indexOf('completeLogin() {', csrfInitializationStart)
	)

	assert.match(navigation, /RISK_BLOCKED/)
	assert.match(navigation, /BLOCK_PAGE\s*=\s*'\/pages\/risk\/blocked'/)
	assert.match(navigation, /window\.location\.replace\(BLOCK_PAGE\)/)
	assert.match(navigation, /uni\.reLaunch\(\{[\s\S]*url:\s*BLOCK_PAGE/)
	assert.doesNotMatch(navigation, /uni\.navigateTo|uni\.navigateBack/)
	assert.match(navigation, /success\(\)/)
	assert.match(navigation, /fail\(\)/)
	assert.match(navigation, /complete\(\)/)
	assert.match(app, /presentRiskBlock\(error\)/)
	assert.match(login, /import\s*\{\s*presentRiskBlock\s*\}/)
	assert.match(csrfInitialization, /if\s*\(presentRiskBlock\(error\)\)\s*return/)
	assert.ok(
		csrfInitialization.indexOf('presentRiskBlock(error)') <
			csrfInitialization.indexOf('this.error = authErrorMessage(error)')
	)
	assert.match(preAuth, /presentRiskBlock\(error\)/)
	assert.match(preAuth, /RISK_CHALLENGE_REQUIRED/)
	assert.match(preAuth, /NETWORK_ERROR/)
	assert.match(http, /presentRiskBlock\(error\)/)
	assert.match(webRtcFailure, /presentRiskBlock\(error\)/)
	assert.match(blockPage, /当前访问已暂停/)
	assert.match(blockPage, /当前网络环境风险较高，请更换可信网络后重试。/)
	assert.match(blockPage, /onBackPress\(\)[\s\S]*return\s+true/)
	assert.match(blockPage, /window\.history\.pushState/)
	assert.match(blockPage, /addEventListener\(\s*['"]popstate['"]/)
	assert.match(blockPage, /removeEventListener\(\s*['"]popstate['"]/)
	assert.doesNotMatch(
		blockPage,
		/<button|@click|uni\.request|fetch\(|\/api\/|重新检测|正在检测|riskScore|baseScore|finalScore/
	)
	assert.match(pages, /pages\/risk\/blocked/)
	assert.match(
		pages,
		/"path":\s*"pages\/risk\/blocked"[\s\S]*?"navigationStyle":\s*"custom"/
	)
})

test('ordinary session vault persists Android PreAuth only in the encrypted credential payload', () => {
	const credentials = source('common/auth/session-credentials.js')
	const keystore = source('common/auth/android-keystore.js')

	assert.match(credentials, /preAuthToken/)
	assert.match(keystore, /preAuthToken/)
	assert.match(keystore, /AndroidKeyStore/)
	assert.doesNotMatch(keystore, /uni\.setStorageSync\([^,]+,\s*state/)
})

test('H5 and Android WebRTC verification use isolated platform probes and never persist IPs', () => {
	const app = source('App.vue')
	const verification = source('common/auth/webrtc-verification.js')
	const h5Verification = source('common/auth/webrtc-verification-h5.js')
	const androidVerification = source('common/auth/webrtc-verification-android.js')
	const core = source('../shared-frontend/auth/webrtc-verification-core.js')
	const h5Probe = source('../shared-frontend/auth/h5-webrtc-probe.js')
	const http = source('common/auth/http-client.js')
	const authApi = source('common/auth/auth-api.js')
	const androidProbe = source('../shared-frontend/auth/android-webrtc-background-probe.js')
	const diagnostics = source('../shared-frontend/auth/webrtc-diagnostics.js')
	const cryptoInterface = source('uni_modules/ait-webrtc-crypto/utssdk/interface.uts')
	const cryptoAndroid = source('uni_modules/ait-webrtc-crypto/utssdk/app-android/index.uts')
	const adminCryptoInterface = source('../myuniappadmin/uni_modules/ait-webrtc-crypto/utssdk/interface.uts')
	const adminCryptoAndroid = source('../myuniappadmin/uni_modules/ait-webrtc-crypto/utssdk/app-android/index.uts')
	const localProbe = source('hybrid/html/webrtc-probe.js')
	const localProbeHtml = source('hybrid/html/webrtc-probe.html')
	const failurePage = source('pages/risk/webrtc-failed.vue')
	const pages = source('pages.json')
	const backendConfig = source('../ai-temperate-web/src/main/resources/application.yml')
	const presenter = verification.slice(
		verification.indexOf('export function presentWebRtcFailure'),
		verification.indexOf('async function verify')
	)

	assert.match(verification, /\/api\/_edge\/webrtc\/start/)
	assert.match(core, /12000/)
	assert.match(verification, /preAuthEpoch/)
	assert.match(verification, /verificationTasks\s*=\s*new Map/)
	assert.match(verification, /activeEntry/)
	assert.match(verification, /compareGeneration/)
	assert.match(verification, /observeAndroidWebRtcVerificationHeaders/)
	assert.match(verification, /createWebRtcDiagnosticLogger/)
	assert.match(verification, /WEBRTC_DIAGNOSTICS_ENABLED\s*=\s*process\.env\.NODE_ENV\s*===\s*'development'/)
	assert.match(verification, /createWebRtcDiagnosticLogger\(\s*'user-flow',\s*WEBRTC_DIAGNOSTICS_ENABLED\s*\)/)
	assert.match(verification, /start_response_received/)
	assert.match(verification, /platform_probe_completed/)
	assert.match(verification, /report_payload_prepared/)
	assert.match(verification, /report_completed/)
	assert.match(verification, /nextProbeRunId/)
	assert.match(verification, /probeRunId:\s*attempt\.probeRunId/)
	assert.match(core, /X-AIT-WebRTC-State/i)
	assert.match(core, /X-AIT-WebRTC-Generation/i)
	assert.doesNotMatch(core, /RTCPeerConnection|plus\.webview/)
	assert.match(h5Probe, /RTCPeerConnection/)
	assert.match(h5Probe, /candidateType\s*!==\s*'host'\s*&&\s*candidateType\s*!==\s*'srflx'/)
	assert.match(core, /WEBRTC_IP_FAMILY_INCOMPLETE/)
	assert.match(h5Probe, /iceGatheringState\s*===\s*'complete'/)
	assert.doesNotMatch(h5Probe, /plus\.webview|android-webrtc-background-probe/)
	assert.match(h5Verification, /collectH5WebRtcIps/)
	assert.doesNotMatch(h5Verification, /plus\.webview|collectAndroidWebRtcIpsInBackground/)
	assert.match(androidVerification, /collectAndroidWebRtcIpsInBackground/)
	assert.match(androidVerification, /createWebRtcProbeChannel/)
	assert.match(androidVerification, /decryptWebRtcProbePayload/)
	assert.match(androidVerification, /cryptoBridge/)
	assert.match(androidVerification, /createWebRtcDiagnosticLogger/)
	assert.match(androidVerification, /WEBRTC_DIAGNOSTICS_ENABLED\s*=\s*process\.env\.NODE_ENV\s*===\s*'development'/)
	assert.match(androidVerification, /createWebRtcDiagnosticLogger\(\s*'user-android-parent',\s*WEBRTC_DIAGNOSTICS_ENABLED\s*\)/)
	assert.match(androidVerification, /diagnosticsEnabled/)
	assert.match(androidVerification, /onDiagnostic/)
	assert.match(androidVerification, /probeRunId:\s*options\.probeRunId/)
	assert.match(androidVerification, /WEBRTC_VERIFICATION_FAILED/)
	assert.doesNotMatch(androidVerification, /RTCPeerConnection|collectH5WebRtcIps|globalThis\.crypto|console\./)
	assert.doesNotMatch(
		verification,
		/clientPlatform\(\)\s*===\s*'ANDROID'[\s\S]*collectAndroidWebRtcIpsInBackground[\s\S]*collectBrowserWebRtcIps/
	)
	assert.match(core, /WEBRTC_VERIFICATION_PENDING/)
	assert.match(http, /await ensureH5WebRtcVerified\(\)/)
	assert.match(http, /observeAndroidWebRtcVerificationHeaders/)
	assert.match(http, /#ifdef APP-PLUS[\s\S]*observeAndroidWebRtcVerificationHeaders/)
	assert.match(http, /void startAndroidWebRtcVerificationInBackground/)
	assert.match(authApi, /#ifdef APP-PLUS[\s\S]*startAndroidWebRtcVerificationInBackground/)
	assert.doesNotMatch(authApi, /ensureH5WebRtcVerified/)
	assert.match(http, /retryState\.preAuth/)
	assert.match(verification, /probeGeneration/)
	assert.match(androidProbe, /plus\.webview\.create/)
	assert.match(androidProbe, /plusrequire:\s*'none'/)
	assert.match(androidProbe, /overrideUrlLoading/)
	assert.match(androidProbe, /const RESULT_URL_MATCH\s*=\s*'\^aitwebrtc:\/\/result\.\*\$'/)
	assert.match(androidProbe, /match:\s*RESULT_URL_MATCH/)
	assert.match(androidProbe, /effect:\s*'instant'/)
	assert.doesNotMatch(androidProbe, /match:\s*'aitwebrtc:\/\/\*'/)
	assert.match(androidProbe, /left:\s*'-10000px'/)
	assert.match(androidProbe, /cryptoBridge/)
	assert.match(androidProbe, /decryptPayload/)
	assert.match(androidProbe, /function parseAitWebRtcResultUrl\(rawUrl\)/)
	assert.match(androidProbe, /const MAX_RESULT_URL_LENGTH\s*=\s*4608/)
	assert.doesNotMatch(androidProbe, /\bnew\s+URL\s*\(/)
	assert.doesNotMatch(androidProbe, /\bURLSearchParams\b|searchParams\.get/)
	assert.doesNotMatch(androidProbe, /globalThis\.crypto|crypto\.subtle|randomBytes|randomHex/)
	assert.doesNotMatch(diagnostics, /typeof process|process\.env/)
	assert.match(diagnostics, /createWebRtcDiagnosticLogger\(scope, enabled = false\)/)
	assert.match(diagnostics, /\[ait-webrtc\]/)
	assert.doesNotMatch(
		diagnostics,
		/Cookie|PreAuth|deviceId|channelId|nonce|candidateAddress|['"]payload['"]/
	)
	assert.equal(cryptoInterface, adminCryptoInterface)
	assert.equal(cryptoAndroid, adminCryptoAndroid)
	assert.match(cryptoAndroid, /SecureRandom/)
	assert.match(cryptoAndroid, /AES\/GCM\/NoPadding/)
	assert.match(cryptoAndroid, /GCMParameterSpec/)
	assert.doesNotMatch(cryptoAndroid, /console\.|AndroidKeyStore|Storage|HttpURLConnection|java\.net/)
	assert.match(localProbe, /aitwebrtc:\/\/result/)
	assert.match(localProbe, /AES-GCM/)
	assert.match(localProbe, /nonce/)
	assert.match(localProbe, /diagnosticsEnabled/)
	assert.match(localProbe, /probeRunId/)
	assert.match(localProbe, /acceptedCount/)
	assert.match(localProbe, /sourceIndexes/)
	assert.doesNotMatch(localProbe, /uni\.postMessage|localStorage|sessionStorage/)
	assert.match(verification, /\{ probeGeneration: generation, webRtcIps \}/)
	assert.doesNotMatch(verification, /probeGeneration:\s*generation[\s\S]{0,80}diagnostic/)
	assert.doesNotMatch(h5Verification, /createWebRtcDiagnosticLogger|android-parent/)
	assert.match(localProbeHtml, /script-src\s+'self'/)
	assert.doesNotMatch(localProbeHtml, /unsafe-eval/)
	assert.ok(localProbe.includes("split(/\\s+/)"))
	assert.ok(localProbe.includes("/^stun:[a-z0-9.-]+:\\d{1,5}$/i"))
	assert.doesNotMatch(verification, /timeoutMillis\s*\+\s*3000/)
	assert.match(app, /#ifdef H5[\s\S]*ensureH5WebRtcVerified/)
	assert.match(app, /#ifdef APP-PLUS[\s\S]*startAndroidWebRtcVerificationInBackground/)
	assert.doesNotMatch(app, /then\(\(\)\s*=>\s*startWebRtcVerificationInBackground\(\)\)/)
	assert.match(app, /presentWebRtcFailure\(error\)/)
	assert.match(presenter, /uni\.reLaunch\(\{[\s\S]*url:\s*FAILURE_PAGE/)
	assert.doesNotMatch(presenter, /uni\.navigateTo/)
	assert.doesNotMatch(failurePage, /重新检测|force:\s*true/)
	assert.match(failurePage, /AUTH_ROUTES\.sessionGate/)
	assert.match(failurePage, /onBackPress\(\)[\s\S]*return\s+true/)
	assert.match(failurePage, /window\.history\.pushState/)
	assert.match(failurePage, /addEventListener\(\s*['"]popstate['"]/)
	assert.match(failurePage, /removeEventListener\(\s*['"]popstate['"]/)
	assert.match(failurePage, /uni\.reLaunch\(\{[\s\S]*AUTH_ROUTES\.sessionGate/)
	assert.doesNotMatch(failurePage, /uni\.navigateBack/)
	assert.match(failurePage, /当前 HTTP IP/)
	assert.match(failurePage, /WebRTC 公网 IP/)
	assert.match(failurePage, /WEBRTC_IP_FAMILY_INCOMPLETE/)
	assert.match(failurePage, /同协议族/)
	assert.match(failurePage, /未获取到/)
	assert.match(failurePage, /#37d39a/i)
	assert.doesNotMatch(failurePage, /@\/components\/.*webrtc/i)
	assert.doesNotMatch(pages, /pages\/risk\/webrtc-probe/)
	assert.match(pages, /pages\/risk\/webrtc-failed/)
	assert.match(
		pages,
		/"path":\s*"pages\/risk\/webrtc-failed"[\s\S]*?"navigationStyle":\s*"custom"/
	)
	const stunUrls = [
		'stun:stun.l.google.com:19302',
		'stun:stun.cloudflare.com:3478',
		'stun:global.stun.twilio.com:3478',
		'stun:stun.nextcloud.com:3478'
	]
	const positions = stunUrls.map(url => backendConfig.indexOf(url))
	assert.ok(positions.every(position => position >= 0))
	assert.deepEqual(positions, [...positions].sort((a, b) => a - b))
	assert.doesNotMatch(verification, /localStorage|sessionStorage|console\./)
	assert.doesNotMatch(core, /localStorage|sessionStorage|console\./)
	assert.doesNotMatch(h5Probe, /localStorage|sessionStorage|console\./)
	assert.doesNotMatch(localProbe, /localStorage|sessionStorage|console\./)
})

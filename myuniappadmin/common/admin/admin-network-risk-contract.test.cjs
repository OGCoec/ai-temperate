const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const source = file => fs.readFileSync(path.join(root, file), 'utf8')

test('administrator H5 uses history routing for direct security callback documents', () => {
	const manifest = source('manifest.json')

	assert.match(
		manifest,
		/"h5"\s*:\s*\{[\s\S]*?"router"\s*:\s*\{[\s\S]*?"mode"\s*:\s*"history"[\s\S]*?"base"\s*:\s*""/
	)
})

test('administrator H5 uses the isolated PreAuth endpoint and Android header', () => {
	const preAuth = source('common/admin/admin-pre-auth.js')
	const http = source('common/admin/admin-http.js')
	const api = source('common/admin/admin-api.js')

	assert.match(preAuth, /\/api\/admin\/_edge\/pre-auth/)
	assert.match(preAuth, /RISK_CHALLENGE_REQUIRED/)
	assert.match(preAuth, /beginAdminRiskChallenge\(error\)/)
	assert.match(preAuth, /reauthenticationRequired/)
	assert.match(preAuth, /clearLocalAdminAuthentication\(\)/)
	assert.match(preAuth, /error\.preAuthToken/)
	assert.match(preAuth, /ensureAdminCookieScopeMigration\(\)/)
	assert.match(preAuth, /withCredentials:\s*true/)
	assert.match(preAuth, /X-AIT-PreAuth-Reset/)
	assert.match(preAuth, /response\?\.status\s*===\s*'DISABLED'/)
	assert.doesNotMatch(preAuth, /document\.cookie|ADMIN-XSRF-TOKEN/)
	assert.match(http, /await ensureAdminPreAuth\(\)/)
	assert.match(http, /headers\['X-AIT-PreAuth'\]/)
	assert.match(http, /PREAUTH_REQUIRED/)
	assert.match(http, /retryState\.preAuth/)
	assert.match(api, /clearAdminSession\(\)[\s\S]*invalidateAdminPreAuth\(\)/)
})

test('administrator challenge accepts only its path and shares the bounded failure flow', () => {
	const navigation = source('common/admin/admin-risk-challenge-navigation.js')
	const preAuth = source('common/admin/admin-pre-auth.js')
	const completion = source('pages/risk/challenge-complete.vue')
	const failure = source('pages/risk/challenge-failed.vue')
	const gate = source('../shared-frontend/auth/risk-challenge-failed-gate.vue')
	const core = source('../shared-frontend/auth/risk-challenge-state-machine.js')
	const pages = source('pages.json')
	const app = source('App.vue')
	const flowPages = `${completion}\n${failure}`

	assert.match(navigation, /ALLOWED_PATH\s*=\s*'\/api\/admin\/_edge\/risk-challenge'/)
	assert.match(navigation, /createRiskChallengeFlow/)
	assert.match(preAuth, /claimAdminRiskChallengeRecheck\(\)[\s\S]*requestBootstrap\(headers\)/)
	assert.match(core, /MAX_ATTEMPTS\s*=\s*3/)
	assert.match(navigation, /window\.location\.assign/)
	assert.match(navigation, /window\.location\.replace/)
	assert.match(navigation, /window\.location\.pathname/)
	assert.match(navigation, /window\.location\.search/)
	assert.match(navigation, /window\.location\.hash/)
	assert.doesNotMatch(navigation, /uni\.request|fetch\(/)
	assert.match(completion, /restoreAdminRiskChallengeReturn/)
	assert.match(failure, /ChallengeFailedGate/)
	assert.match(failure, /audience="ADMIN"/)
	assert.match(app, /if \(!isAdminRiskChallengeFlowPage\(options\?\.path\)\)/)
	assert.doesNotMatch(flowPages, /ensureAdminPreAuth|ensureAdminWebRtcVerified|uni\.request|fetch\(/)
	assert.match(gate, /管理员安全验证未完成/)
	assert.match(gate, /aria-live="assertive"/)
	assert.equal((gate.match(/<button/g) || []).length, 1)
	assert.doesNotMatch(gate, /428|Cloudflare|WAF|信用分|ASN|供应商/)
	assert.match(pages, /pages\/risk\/challenge-failed/)
})
test('administrator Android challenge is isolated to the admin host and cookie', () => {
	const coordinator = source('common/admin/admin-android-risk-challenge.js')
	const preAuth = source('common/admin/admin-pre-auth.js')
	const http = source('common/admin/admin-http.js')
	const config = source('common/admin/admin-config.js')

	assert.match(config, /https:\/\/admin\.niko000o\.site/)
	assert.match(coordinator, /__Host-ait-admin-preauth/)
	assert.match(coordinator, /\/api\/admin\/_edge\/risk-challenge/)
	assert.match(coordinator, /\/pages\/risk\/challenge-complete/)
	assert.match(preAuth, /ensureAdminAndroidRiskChallenge/)
	assert.match(preAuth, /recheckAdminPreAuthAfterRiskChallenge/)
	assert.match(http, /repeatedAndroidRiskChallengeError/)
	assert.match(http, /riskChallenge/)
	assert.match(coordinator, /repeatedAndroidRiskChallengeError/)
})

test('administrator risk block enters a non-retryable top-level security gate', () => {
	const app = source('App.vue')
	const preAuth = source('common/admin/admin-pre-auth.js')
	const http = source('common/admin/admin-http.js')
	const webRtcFailure = source('pages/risk/webrtc-failed.vue')
	const navigation = source('common/admin/admin-risk-block-navigation.js')
	const blockPage = source('pages/risk/blocked.vue')
	const pages = source('pages.json')

	assert.match(navigation, /RISK_BLOCKED/)
	assert.match(navigation, /BLOCK_PAGE\s*=\s*'\/pages\/risk\/blocked'/)
	assert.match(navigation, /uni\.reLaunch\(\{[\s\S]*url:\s*BLOCK_PAGE/)
	assert.doesNotMatch(navigation, /uni\.navigateTo|uni\.navigateBack/)
	assert.match(app, /presentAdminRiskBlock\(error\)/)
	assert.match(preAuth, /presentAdminRiskBlock\(error\)/)
	assert.match(http, /presentAdminRiskBlock\(error\)/)
	assert.match(webRtcFailure, /presentAdminRiskBlock\(error\)/)
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

test('administrator secure vault carries the PreAuth token inside its AES-GCM payload', () => {
	const vault = source('common/admin/admin-secure-vault.js')

	assert.match(vault, /preAuthToken/)
	assert.match(vault, /AndroidKeyStore/)
	assert.match(vault, /AES\/GCM\/NoPadding/)
	assert.doesNotMatch(vault, /uni\.setStorageSync\([^,]+,\s*state/)
})

test('administrator WebRTC verification uses generation-scoped background work and an ephemeral Android bridge', () => {
	const app = source('App.vue')
	const verification = source('common/admin/admin-webrtc-verification.js')
	const core = source('../shared-frontend/auth/webrtc-verification-core.js')
	const http = source('common/admin/admin-http.js')
	const androidProbe = source('../shared-frontend/auth/android-webrtc-background-probe.js')
	const localProbe = source('hybrid/html/webrtc-probe.js')
	const failurePage = source('pages/risk/webrtc-failed.vue')
	const pages = source('pages.json')
	const backendConfig = source('../ai-temperate-web/src/main/resources/application.yml')
	const presenter = verification.slice(
		verification.indexOf('export function presentAdminWebRtcFailure'),
		verification.indexOf('async function verify')
	)

	assert.match(verification, /\/api\/admin\/_edge\/webrtc\/start/)
	assert.match(core, /12000/)
	assert.match(verification, /preAuthEpoch/)
	assert.match(verification, /verificationTasks\s*=\s*new Map/)
	assert.match(verification, /activeEntry/)
	assert.match(verification, /compareGeneration/)
	assert.match(verification, /observeAdminWebRtcVerificationHeaders/)
	assert.match(core, /X-AIT-WebRTC-State/i)
	assert.match(core, /X-AIT-WebRTC-Generation/i)
	assert.match(core, /RTCPeerConnection/)
	assert.match(core, /candidateType\s*!==\s*'srflx'/)
	assert.match(core, /WEBRTC_VERIFICATION_PENDING/)
	assert.doesNotMatch(http, /await ensureAdminWebRtcVerified\(\)/)
	assert.doesNotMatch(http, /await recoverAdminWebRtc\(error\)/)
	assert.match(http, /observeAdminWebRtcVerificationHeaders/)
	assert.match(http, /void startAdminWebRtcVerificationInBackground/)
	assert.match(verification, /probeGeneration/)
	assert.match(androidProbe, /plus\.webview\.create/)
	assert.match(androidProbe, /overrideUrlLoading/)
	assert.match(androidProbe, /left:\s*'-10000px'/)
	assert.match(androidProbe, /AES-GCM/)
	assert.match(localProbe, /aitwebrtc:\/\/result/)
	assert.match(localProbe, /nonce/)
	assert.doesNotMatch(localProbe, /uni\.postMessage|localStorage|sessionStorage/)
	assert.ok(localProbe.includes("split(/\\s+/)"))
	assert.ok(localProbe.includes("/^stun:[a-z0-9.-]+:\\d{1,5}$/i"))
	assert.doesNotMatch(verification, /timeoutMillis\s*\+\s*3000/)
	assert.match(app, /presentAdminWebRtcFailure\(error\)/)
	assert.match(presenter, /uni\.reLaunch\(\{[\s\S]*url:\s*FAILURE_PAGE/)
	assert.doesNotMatch(presenter, /uni\.navigateTo/)
	assert.doesNotMatch(failurePage, /重新检测|force:\s*true/)
	assert.match(failurePage, /ADMIN_SAFE_ENTRY/)
	assert.match(failurePage, /onBackPress\(\)[\s\S]*return\s+true/)
	assert.match(failurePage, /window\.history\.pushState/)
	assert.match(failurePage, /addEventListener\(\s*['"]popstate['"]/)
	assert.match(failurePage, /removeEventListener\(\s*['"]popstate['"]/)
	assert.match(failurePage, /uni\.reLaunch\(\{[\s\S]*ADMIN_SAFE_ENTRY/)
	assert.doesNotMatch(failurePage, /uni\.navigateBack/)
	assert.match(failurePage, /当前 HTTP IP/)
	assert.match(failurePage, /WebRTC 公网 IP/)
	assert.match(failurePage, /未获取到/)
	assert.match(failurePage, /#39d6d2/i)
	assert.match(failurePage, /#69d4e2/i)
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
	assert.doesNotMatch(localProbe, /localStorage|sessionStorage|console\./)
})

test('administrator H5 uses one-shot WebRTC verification and on-demand recovery', () => {
	const app = source('App.vue')
	const http = source('common/admin/admin-http.js')
	const verification = source('common/admin/admin-webrtc-verification.js')
	const failurePage = source('pages/risk/webrtc-failed.vue')
	const monitorPath = path.join(root, 'common/admin/admin-network-revalidation.js')
	const oneShotRuntime = [app, http, verification, failurePage].join('\n')

	assert.match(
		app,
		/ensureAdminPreAuth\(\)[\s\S]*\.then\(\(\)\s*=>\s*startAdminWebRtcVerificationInBackground\(\)\)/
	)
	assert.doesNotMatch(
		app,
		/startAdminNetworkRevalidationMonitor|admin-network-revalidation/
	)
	assert.match(http, /await ensureAdminPreAuth\(\)/)
	assert.doesNotMatch(http, /await ensureAdminWebRtcVerified\(\)/)
	assert.doesNotMatch(http, /await recoverAdminWebRtc\(error\)/)
	assert.match(http, /retryState\.preAuth/)
	assert.match(http, /retryState\.migration/)
	assert.doesNotMatch(http, /force:\s*error\.code\s*===\s*'WEBRTC_NETWORK_CHANGED'/)
	assert.doesNotMatch(
		http,
		/ensureAdminNetworkTrusted|requestAdminNetworkRevalidation|admin-network-revalidation/
	)
	assert.doesNotMatch(failurePage, /ensureAdminWebRtcVerified|force:\s*true/)
	assert.doesNotMatch(
		failurePage,
		/requestAdminNetworkRevalidation|admin-network-revalidation/
	)
	assert.match(verification, /verificationTasks/)
	assert.match(verification, /error\.challengeRef/)
	assert.match(verification, /error\.challengePath/)
	assert.doesNotMatch(
		oneShotRuntime,
		/addEventListener\(\s*['"](?:online|offline|focus|visibilitychange|change)['"]/
	)
	assert.doesNotMatch(oneShotRuntime, /navigator\.connection|setInterval\(/)
	assert.equal(fs.existsSync(monitorPath), false)
})

test('missing administrator session stays logged out without rebuilding PreAuth or WebRTC', () => {
	const http = source('common/admin/admin-http.js')
	const page = source('pages/index/index.vue')
	const recoveryStart = http.indexOf('if (!retryState.preAuth')
	const recoveryEnd = http.indexOf(
		"if (platform === 'H5'",
		recoveryStart
	)
	const preAuthRecovery = http.slice(recoveryStart, recoveryEnd)

	assert.ok(recoveryStart >= 0)
	assert.ok(recoveryEnd > recoveryStart)
	assert.match(
		preAuthRecovery,
		/\['PREAUTH_REQUIRED',\s*'ADMIN_PREAUTH_REQUIRED'\]\.includes\(error\.code\)/
	)
	assert.doesNotMatch(preAuthRecovery, /ADMIN_SESSION_INVALID/)
	assert.match(
		page,
		/adminApi\.bootstrap\(\)[\s\S]*error\.code\s*!==\s*'ADMIN_SESSION_INVALID'[\s\S]*this\.screenState\s*=\s*'ACTIVE'/
	)
	assert.doesNotMatch(
		page,
		/error\.code\s*===\s*'ADMIN_SESSION_INVALID'[\s\S]{0,180}(?:ensureAdminPreAuth|ensureAdminWebRtcVerified)/
	)
})

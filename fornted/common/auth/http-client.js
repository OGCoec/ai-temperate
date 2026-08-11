import {
	applyBrowserCsrfHeader,
	browserCsrfToken,
	requiresCsrf
} from './browser-cookies.js'
import { AUTH_API_BASE_URL, AUTH_ROUTES, clientPlatform } from './config.js'
import {
	androidEdgeRequestHeaders,
	ensureAndroidEdgeClearance,
	runAndroidRequestWithEdgeRecovery
} from './android-edge-challenge.js'
import {
	ensureCookieScopeMigration,
	invalidateCookieScopeMigration
} from './cookie-scope-migration.js'
import { getDeviceInstallationId } from './device-installation.js'
import {
	acceptAndroidRiskChallenge,
	currentPreAuthToken,
	ensurePreAuth,
	invalidatePreAuth,
	recheckPreAuthAfterRiskChallenge
} from './pre-auth.js'
import { repeatedAndroidRiskChallengeError } from './android-risk-challenge.js'
import { presentRiskBlock } from './risk-block-navigation.js'
import { beginRiskChallenge } from './risk-challenge-navigation.js'
import { hasCompleteSessionCredentials } from './session-credentials.js'
import { SessionRenewalMode, sessionRenewalMode } from './session-retry-policy.js'
import { clearSession, currentSession, saveSession } from './session-vault.js'
import {
	applyDiagnosticsToError,
	inspectAuthResponse,
	networkFailureDiagnostics
} from './turnstile-response-diagnostics.js'
import {
	isWebRtcFailureCode,
	isWebRtcRetryCode
} from '@shared-auth/webrtc-verification-core.js'
import {
	invalidateWebRtcVerification,
	presentWebRtcFailure
} from './webrtc-verification.js'
// #ifdef H5
import { ensureH5WebRtcVerified } from './webrtc-verification.js'
// #endif
// #ifdef APP-PLUS
import {
	observeAndroidWebRtcVerificationHeaders,
	startAndroidWebRtcVerificationInBackground
} from './webrtc-verification.js'
// #endif

const CSRF_PATH = '/api/auth/csrf'
const BOOTSTRAP_PATH = '/api/auth/session/bootstrap'
const TERMINAL_SESSION_ERRORS = new Set([
	'AT_REQUIRED',
	'AT_INVALID',
	'REFRESH_TOKEN_REQUIRED',
	'REFRESH_TOKEN_INVALID',
	'SESSION_MISMATCH',
	'DEVICE_MISMATCH',
	'CSRF_INVALID',
	'ACCOUNT_UNAVAILABLE',
	'SESSION_RESPONSE_INVALID'
])

let bootstrapInFlight = null
let csrfInFlight = null

function rawRequestTask(options) {
	return new Promise((resolve, reject) => {
		uni.request({
			url: `${AUTH_API_BASE_URL}${options.path}`,
			method: options.method || 'POST',
			data: options.data,
			header: androidEdgeRequestHeaders(options.headers || {}),
			timeout: options.timeout,
			withCredentials: true,
			success(response) {
				// #ifdef APP-PLUS
				// Android 根据响应头推进后台 WebView 探测；H5 不消费这条异步触发链路。
				observeAndroidWebRtcVerificationHeaders(response.header || response.headers || {})
				// #endif
				try {
					// Android 必须在解释业务状态前保存同请求续签的 AT；H5 由浏览器接收 HttpOnly Cookie。
					applySessionRenewalHeaders(response.header || {})
				} catch (renewalError) {
					reject(renewalError)
					return
				}
				const diagnostics = inspectAuthResponse(response)
				notifyResponseObserver(options.onResponse, diagnostics)
				if (diagnostics.classification === 'EDGE_CHALLENGE') {
					const edgeError = new Error('Cloudflare 安全检查尚未完成，请重新完成人机验证。')
					edgeError.code = 'EDGE_CHALLENGE'
					edgeError.statusCode = response.statusCode
					reject(applyDiagnosticsToError(edgeError, diagnostics))
					return
				}
				if (response.statusCode >= 200 && response.statusCode < 300) {
					resolve(response.data)
					return
				}
				const hasStableClientMessage = typeof response.data?.code === 'string' &&
					typeof response.data?.message === 'string' &&
					response.data.message.trim().length > 0
				const error = new Error(hasStableClientMessage
					? response.data.message
					: '请求未完成，请稍后重试。')
				error.code = response.data?.code || `HTTP_${response.statusCode}`
				error.statusCode = response.statusCode
				error.challengeRef = response.data?.challengeRef || ''
				error.challengePath = response.data?.challengePath || ''
				error.expiresAt = response.data?.expiresAt || ''
				error.preAuthToken = response.data?.preAuthToken || ''
				error.webRtcStatus = response.data?.webRtcStatus
				error.httpIp = response.data?.httpIp || ''
				error.webRtcIps = Array.isArray(response.data?.webRtcIps)
					? [...response.data.webRtcIps]
					: []
				error.retryable = response.data?.retryable === true
				reject(applyDiagnosticsToError(error, diagnostics))
			},
			fail() {
				const diagnostics = networkFailureDiagnostics()
				notifyResponseObserver(options.onResponse, diagnostics)
				const networkError = new Error('网络连接失败，请检查后重试。')
				networkError.code = 'NETWORK_ERROR'
				reject(applyDiagnosticsToError(networkError, diagnostics))
			}
		})
	})
}

async function requestTask(options) {
	await ensureCookieScopeMigration()
	return runAndroidRequestWithEdgeRecovery(() => rawRequestTask(options))
}

function notifyResponseObserver(observer, diagnostics) {
	if (typeof observer !== 'function') return
	try {
		observer(diagnostics)
	} catch (_) {
		// 诊断观察器异常不能覆盖原始网络响应或认证结果。
	}
}

function clientContextHeaders() {
	const headers = { 'Content-Type': 'application/json' }
	headers['X-Device-Installation-Id'] = getDeviceInstallationId()
	headers['X-Client-Platform'] = clientPlatform()
	const preAuthToken = currentPreAuthToken()
	if (clientPlatform() === 'ANDROID' && preAuthToken) {
		headers['X-AIT-PreAuth'] = preAuthToken
	}
	return headers
}

export async function initializeBrowserCsrf(
	migrationRetried = false,
	preAuthRetried = false,
	webRtcRetried = false
) {
	if (clientPlatform() !== 'H5') return ''
	// 必须先清理旧父域 Cookie，再建立 Host-only PreAuth 和读取 CSRF。
	await ensureCookieScopeMigration()
	await ensurePreAuth()
	try {
		await ensureH5WebRtcVerified()
		const existing = browserCsrfToken()
		if (existing) return existing
		if (!csrfInFlight) {
			csrfInFlight = requestTask({
				path: CSRF_PATH,
				method: 'GET',
				headers: clientContextHeaders()
			}).finally(() => { csrfInFlight = null })
		}
		await csrfInFlight
	} catch (error) {
		if (presentRiskBlock(error)) throw error
		if (isWebRtcFailureCode(error.code)) presentWebRtcFailure(error)
		if (!webRtcRetried && isWebRtcRetryCode(error.code)) {
			await recoverH5WebRtc()
			return initializeBrowserCsrf(migrationRetried, preAuthRetried, true)
		}
		if (error.code === 'RISK_CHALLENGE_REQUIRED') {
			beginRiskChallenge(error)
		}
		if (!preAuthRetried && error.code === 'PREAUTH_REQUIRED') {
			invalidatePreAuth()
			invalidateWebRtcVerification()
			await ensurePreAuth()
			await ensureH5WebRtcVerified()
			return initializeBrowserCsrf(migrationRetried, true, webRtcRetried)
		}
		if (migrationRetried
			|| error.code !== 'EDGE_COOKIE_SCOPE_RESET_REQUIRED') {
			throw error
		}
		invalidateCookieScopeMigration()
		invalidatePreAuth()
		invalidateWebRtcVerification()
		await ensureCookieScopeMigration()
		return initializeBrowserCsrf(true, preAuthRetried, webRtcRetried)
	}
	return browserCsrfToken()
}

export async function publicRequest(
	path,
	options = {},
	migrationRetried = false,
	preAuthRetried = false,
	webRtcRetried = false,
	riskChallengeRetried = false
) {
	await ensureCookieScopeMigration()
	await ensurePreAuth()
	try {
		// #ifdef H5
		await ensureH5WebRtcVerified()
		// #endif
		const method = String(options.method || 'POST').toUpperCase()
		const headers = clientContextHeaders()
		Object.assign(headers, options.headers || {})
		if (clientPlatform() === 'H5' && requiresCsrf(method) && path !== BOOTSTRAP_PATH) {
			const csrfToken = browserCsrfToken() || await initializeBrowserCsrf()
			if (!csrfToken) {
				const error = new Error('CSRF token is unavailable.')
				error.code = 'CSRF_INVALID'
				throw error
			}
			applyBrowserCsrfHeader(headers, method, csrfToken)
		}
		return await requestTask({
			path,
			method,
			data: options.data,
			headers,
			timeout: options.timeout,
			onResponse: options.onResponse
		})
	} catch (error) {
		if (presentRiskBlock(error)) throw error
		if (isWebRtcFailureCode(error.code)) presentWebRtcFailure(error)
		if (!webRtcRetried && isWebRtcRetryCode(error.code)) {
			// #ifdef H5
			await recoverH5WebRtc()
			// #endif
			// #ifdef APP-PLUS
			recoverAndroidWebRtc()
			// #endif
			return publicRequest(
				path,
				options,
				migrationRetried,
				preAuthRetried,
				true,
				riskChallengeRetried)
		}
		if (error.code === 'RISK_CHALLENGE_REQUIRED') {
			if (clientPlatform() === 'H5') beginRiskChallenge(error)
			if (riskChallengeRetried) {
				throw repeatedAndroidRiskChallengeError(error)
			}
			await acceptAndroidRiskChallenge(error)
			await recheckPreAuthAfterRiskChallenge()
			return publicRequest(
				path,
				options,
				migrationRetried,
				preAuthRetried,
				webRtcRetried,
				true)
		}
		if (!preAuthRetried && error.code === 'PREAUTH_REQUIRED') {
			invalidatePreAuth()
			invalidateWebRtcVerification()
			await ensurePreAuth()
			// #ifdef H5
			await ensureH5WebRtcVerified()
			// #endif
			// #ifdef APP-PLUS
			void startAndroidWebRtcVerificationInBackground().catch(() => {})
			// #endif
			return publicRequest(
				path,
				options,
				migrationRetried,
				true,
				webRtcRetried,
				riskChallengeRetried)
		}
		if (clientPlatform() !== 'H5'
			|| migrationRetried
			|| error.code !== 'EDGE_COOKIE_SCOPE_RESET_REQUIRED') {
			throw error
		}
		invalidateCookieScopeMigration()
		invalidatePreAuth()
		invalidateWebRtcVerification()
		await ensureCookieScopeMigration()
		return publicRequest(
			path,
			options,
			true,
			preAuthRetried,
			webRtcRetried,
			riskChallengeRetried)
	}
}

async function bootstrapBrowserSession() {
	const response = await publicRequest(BOOTSTRAP_PATH)
	saveSession(response)
	return response
}

export function restoreBrowserSession() {
	if (clientPlatform() !== 'H5') return Promise.resolve(null)
	if (!bootstrapInFlight) {
		bootstrapInFlight = bootstrapBrowserSession()
			.finally(() => { bootstrapInFlight = null })
	}
	return bootstrapInFlight
}

export function restorePersistedSession() {
	if (clientPlatform() === 'H5') return restoreBrowserSession()
	const credentials = currentSession()
	return Promise.resolve(hasCompleteSessionCredentials(credentials)
		? { restored: true }
		: null)
}

export async function authorizedRequest(path, options = {}, retryState = {}) {
	const preserveSessionOnFailure = options.preserveSessionOnFailure === true
	try {
		// H5 保持 WebRTC 前置校验；Android 的 Report 继续由独立后台 WebView 完成。
		await ensureCookieScopeMigration()
		await ensurePreAuth()
		// #ifdef H5
		await ensureH5WebRtcVerified()
		// #endif
		const headers = await protectedCredentialHeaders(options.headers)
		return await requestTask({
			path,
			method: options.method || 'POST',
			data: options.data,
			headers,
			timeout: options.timeout,
			onResponse: options.onResponse
		})
	} catch (error) {
		handleAuthorizedSecurityFailure(error)
		if (error?.code === 'RISK_CHALLENGE_REQUIRED') {
			if (clientPlatform() === 'H5') beginRiskChallenge(error)
			if (retryState.riskChallenge) {
				throw repeatedAndroidRiskChallengeError(error)
			}
			await acceptAndroidRiskChallenge(error)
			await recheckPreAuthAfterRiskChallenge()
			return authorizedRequest(
				path,
				options,
				{ ...retryState, riskChallenge: true })
		}
		if (!retryState.preAuth && error?.code === 'PREAUTH_REQUIRED') {
			await ensurePreAuth()
			// #ifdef H5
			await ensureH5WebRtcVerified()
			// #endif
			// #ifdef APP-PLUS
			void startAndroidWebRtcVerificationInBackground().catch(() => {})
			// #endif
			return authorizedRequest(path, options, { ...retryState, preAuth: true })
		}
		if (!preserveSessionOnFailure || TERMINAL_SESSION_ERRORS.has(error?.code)) {
			handleTerminalSessionError(error)
		}
		throw error
	}
}

function handleAuthorizedSecurityFailure(error) {
	if (presentRiskBlock(error)) return
	if (isWebRtcFailureCode(error?.code)) {
		presentWebRtcFailure(error)
		invalidateWebRtcVerification()
	}
	if (error?.code === 'PREAUTH_REQUIRED') {
		invalidatePreAuth()
		invalidateWebRtcVerification()
	}
	if (error?.code === 'EDGE_COOKIE_SCOPE_RESET_REQUIRED') {
		invalidateCookieScopeMigration()
		invalidatePreAuth()
		invalidateWebRtcVerification()
	}
}

/**
 * 为无法通过 uni.request 消费的流式请求准备与普通认证请求完全相同的地址和安全请求头。
 * 调用方只能把结果用于一次受保护的 SSE 请求，不得写入本地存储或日志。
 */
export async function prepareAuthorizedStreamingRequest(path, options = {}) {
	await ensureCookieScopeMigration()
	await ensurePreAuth()
	// #ifdef H5
	await ensureH5WebRtcVerified()
	// #endif
	const method = String(options.method || 'POST').toUpperCase()
	const headers = await protectedCredentialHeaders(options.headers)
	return Object.freeze({
		url: `${AUTH_API_BASE_URL}${path}`,
		method,
		headers: Object.freeze({ ...headers })
	})
}

/**
 * 仅在流式请求尚未收到 accepted 时执行一次既有会话恢复；accepted 之后禁止自动重放，避免重复计费。
 */
export async function recoverAuthorizedStreamingSession(error) {
	if (clientPlatform() === 'ANDROID' && error?.code === 'EDGE_CHALLENGE') {
		await ensureAndroidEdgeClearance()
		return true
	}
	const mode = sessionRenewalMode(clientPlatform(), error?.code, false)
	if (mode === SessionRenewalMode.NONE) {
		handleTerminalSessionError(error)
		return false
	}
	if (!bootstrapInFlight) {
		bootstrapInFlight = bootstrapBrowserSession()
			.finally(() => { bootstrapInFlight = null })
	}
	await bootstrapInFlight
	return true
}

async function protectedCredentialHeaders(additionalHeaders = {}) {
	const headers = clientContextHeaders()
	Object.assign(headers, additionalHeaders || {})
	const session = currentSession()
	if (clientPlatform() === 'ANDROID') {
		if (session.accessToken) headers.Authorization = `Bearer ${session.accessToken}`
		if (session.refreshToken) headers['X-Refresh-Token'] = session.refreshToken
		if (session.csrfToken) headers['X-CSRF-Token'] = session.csrfToken
		return headers
	}
	if (!browserCsrfToken()) {
		// 受保护会话丢失 CSRF 时必须通过 bootstrap 同步轮换 Redis 绑定，不能只领取未绑定的新 Cookie。
		await restoreBrowserSession()
	}
	const csrfToken = browserCsrfToken()
	if (!csrfToken) {
		const error = new Error('CSRF token is unavailable.')
		error.code = 'CSRF_INVALID'
		throw error
	}
	// RT-first 要求安全读取请求也提交 CSRF，因此这里不再按 HTTP 方法过滤。
	headers['X-CSRF-Token'] = csrfToken
	return headers
}

export function applySessionRenewalHeaders(headers = {}) {
	const renewed = responseHeader(headers, 'X-Session-Renewed')
	if (String(renewed).toLowerCase() !== 'true') return false
	if (clientPlatform() === 'H5') return true
	const newAccessToken = responseHeader(headers, 'X-New-Access-Token')
	if (!newAccessToken) {
		const error = new Error('Session renewal response is incomplete.')
		error.code = 'SESSION_RESPONSE_INVALID'
		throw error
	}
	saveSession({ accessToken: String(newAccessToken) })
	return true
}

function responseHeader(headers, expectedName) {
	const entry = Object.entries(headers || {})
		.find(([name]) => name.toLowerCase() === expectedName.toLowerCase())
	return entry ? entry[1] : ''
}

function handleTerminalSessionError(error) {
	if (!TERMINAL_SESSION_ERRORS.has(error?.code)) return
	clearSession()
	uni.reLaunch({ url: AUTH_ROUTES.login })
}

async function recoverH5WebRtc() {
	invalidateWebRtcVerification()
	try {
		return await ensureH5WebRtcVerified()
	} catch (verificationError) {
		if (presentRiskBlock(verificationError)) return
		if (isWebRtcFailureCode(verificationError.code)) {
			presentWebRtcFailure(verificationError)
		}
		throw verificationError
	}
}

function recoverAndroidWebRtc() {
	invalidateWebRtcVerification()
	void startAndroidWebRtcVerificationInBackground().catch(verificationError => {
		if (presentRiskBlock(verificationError)) return
		if (isWebRtcFailureCode(verificationError.code)) {
			presentWebRtcFailure(verificationError)
		}
	})
}

export async function logoutSession() {
	const platform = clientPlatform()
	const session = currentSession()
	const headers = {}
	let data
	if (platform === 'ANDROID') {
		if (session.accessToken) headers.Authorization = `Bearer ${session.accessToken}`
		if (session.csrfToken) headers['X-CSRF-Token'] = session.csrfToken
		data = { refreshToken: session.refreshToken || undefined }
	}
	try {
		if (platform === 'H5' && !browserCsrfToken()) {
			await bootstrapBrowserSession()
		}
		try {
			await publicRequest('/api/auth/session/logout', { headers, data })
		} catch (error) {
			if (platform !== 'H5' || error.code !== 'CSRF_INVALID') throw error
			await bootstrapBrowserSession()
			await publicRequest('/api/auth/session/logout', { headers, data })
		}
	} finally {
		clearSession()
		invalidatePreAuth()
		invalidateWebRtcVerification()
	}
}

export async function logoutAllSessions() {
	// 全设备撤销失败时保留本地凭据，让用户留在当前页面重试。
	await authorizedRequest('/api/auth/session/logout-all', {
		preserveSessionOnFailure: true
	})
	clearSession()
	invalidatePreAuth()
	invalidateWebRtcVerification()
}

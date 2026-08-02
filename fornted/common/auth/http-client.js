import {
	applyBrowserCsrfHeader,
	browserCsrfToken,
	requiresCsrf
} from './browser-cookies.js'
import { AUTH_API_BASE_URL, AUTH_ROUTES, clientPlatform } from './config.js'
import {
	ensureCookieScopeMigration,
	invalidateCookieScopeMigration
} from './cookie-scope-migration.js'
import { getDeviceInstallationId } from './device-installation.js'
import {
	currentPreAuthToken,
	ensurePreAuth,
	invalidatePreAuth
} from './pre-auth.js'
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
	ensureWebRtcVerified,
	invalidateWebRtcVerification,
	presentWebRtcFailure
} from './webrtc-verification.js'

const CSRF_PATH = '/api/auth/csrf'
const BOOTSTRAP_PATH = '/api/auth/session/bootstrap'
const TERMINAL_SESSION_ERRORS = new Set([
	'AT_REQUIRED',
	'AT_EXPIRED',
	'AT_INVALID',
	'REFRESH_TOKEN_REQUIRED',
	'REFRESH_TOKEN_INVALID',
	'SESSION_MISMATCH',
	'DEVICE_MISMATCH',
	'CSRF_INVALID',
	'ACCOUNT_UNAVAILABLE'
])

let refreshInFlight = null
let csrfInFlight = null

function rawRequestTask(options) {
	return new Promise((resolve, reject) => {
		uni.request({
			url: `${AUTH_API_BASE_URL}${options.path}`,
			method: options.method || 'POST',
			data: options.data,
			header: options.headers || {},
			timeout: options.timeout,
			withCredentials: true,
			success(response) {
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
	return rawRequestTask(options)
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
		await ensureWebRtcVerified()
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
			await recoverWebRtc(error)
			return initializeBrowserCsrf(migrationRetried, preAuthRetried, true)
		}
		if (error.code === 'RISK_CHALLENGE_REQUIRED') {
			beginRiskChallenge(error)
		}
		if (!preAuthRetried && error.code === 'PREAUTH_REQUIRED') {
			invalidatePreAuth()
			invalidateWebRtcVerification()
			await ensurePreAuth()
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
	webRtcRetried = false
) {
	await ensureCookieScopeMigration()
	await ensurePreAuth()
	try {
		await ensureWebRtcVerified()
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
			await recoverWebRtc(error)
			return publicRequest(path, options, migrationRetried, preAuthRetried, true)
		}
		if (error.code === 'RISK_CHALLENGE_REQUIRED') {
			beginRiskChallenge(error)
		}
		if (!preAuthRetried && error.code === 'PREAUTH_REQUIRED') {
			invalidatePreAuth()
			invalidateWebRtcVerification()
			await ensurePreAuth()
			return publicRequest(path, options, migrationRetried, true, webRtcRetried)
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
		return publicRequest(path, options, true, preAuthRetried, webRtcRetried)
	}
}

async function refreshSession(bootstrap = false) {
	const session = currentSession()
	const android = clientPlatform() === 'ANDROID'
	if (!android && !bootstrap && !browserCsrfToken()) {
		return refreshSession(true)
	}
	const headers = {}
	let data
	if (android) {
		if (session.accessToken) headers.Authorization = `Bearer ${session.accessToken}`
		if (session.csrfToken) headers['X-CSRF-Token'] = session.csrfToken
		data = { refreshToken: session.refreshToken || undefined }
	}
	const response = await publicRequest(
		bootstrap ? BOOTSTRAP_PATH : '/api/auth/session/refresh',
		{ headers, data }
	)
	saveSession(response)
	return response
}

export function restoreBrowserSession() {
	if (clientPlatform() !== 'H5') return Promise.resolve(null)
	if (!refreshInFlight) {
		refreshInFlight = refreshSession(true).finally(() => { refreshInFlight = null })
	}
	return refreshInFlight
}

export function restorePersistedSession() {
	if (clientPlatform() === 'H5') return restoreBrowserSession()
	const credentials = currentSession()
	return Promise.resolve(hasCompleteSessionCredentials(credentials)
		? { restored: true }
		: null)
}

export async function authorizedRequest(path, options = {}, retried = false) {
	const platform = clientPlatform()
	const session = currentSession()
	const preserveSessionOnFailure = options.preserveSessionOnFailure === true
	const headers = { ...(options.headers || {}) }
	if (platform === 'ANDROID' && session.accessToken) {
		headers.Authorization = `Bearer ${session.accessToken}`
	}
	try {
		return await publicRequest(path, { ...options, headers })
	} catch (error) {
		const renewalMode = sessionRenewalMode(platform, error.code, retried)
		if (renewalMode !== SessionRenewalMode.NONE) {
			try {
				if (!refreshInFlight) {
					refreshInFlight = renewSession(renewalMode)
						.finally(() => { refreshInFlight = null })
				}
				await refreshInFlight
				return authorizedRequest(path, options, true)
			} catch (renewalError) {
				if (!preserveSessionOnFailure) handleTerminalSessionError(renewalError)
				throw renewalError
			}
		}
		if (!preserveSessionOnFailure) handleTerminalSessionError(error)
		throw error
	}
}

/**
 * 为无法通过 uni.request 消费的流式请求准备与普通认证请求完全相同的地址和安全请求头。
 * 调用方只能把结果用于一次受保护的 SSE 请求，不得写入本地存储或日志。
 */
export async function prepareAuthorizedStreamingRequest(path, options = {}) {
	await ensureCookieScopeMigration()
	await ensurePreAuth()
	await ensureWebRtcVerified()
	const method = String(options.method || 'POST').toUpperCase()
	const headers = clientContextHeaders()
	Object.assign(headers, options.headers || {})
	if (clientPlatform() === 'H5' && requiresCsrf(method)) {
		const csrfToken = browserCsrfToken() || await initializeBrowserCsrf()
		if (!csrfToken) {
			const error = new Error('CSRF token is unavailable.')
			error.code = 'CSRF_INVALID'
			throw error
		}
		applyBrowserCsrfHeader(headers, method, csrfToken)
	}
	const session = currentSession()
	if (clientPlatform() === 'ANDROID' && session.accessToken) {
		headers.Authorization = `Bearer ${session.accessToken}`
	}
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
	const mode = sessionRenewalMode(clientPlatform(), error?.code, false)
	if (mode === SessionRenewalMode.NONE) {
		handleTerminalSessionError(error)
		return false
	}
	if (!refreshInFlight) {
		refreshInFlight = renewSession(mode).finally(() => { refreshInFlight = null })
	}
	await refreshInFlight
	return true
}

async function renewSession(renewalMode) {
	const h5 = clientPlatform() === 'H5'
	if (h5 && renewalMode === SessionRenewalMode.BOOTSTRAP) return refreshSession(true)
	try {
		return await refreshSession(false)
	} catch (error) {
		if (h5 && error.code === 'CSRF_INVALID') return refreshSession(true)
		throw error
	}
}

function handleTerminalSessionError(error) {
	if (!TERMINAL_SESSION_ERRORS.has(error?.code)) return
	clearSession()
	uni.reLaunch({ url: AUTH_ROUTES.login })
}

async function recoverWebRtc(error) {
	invalidateWebRtcVerification()
	try {
		return await ensureWebRtcVerified({
			force: error.code === 'WEBRTC_NETWORK_CHANGED'
		})
	} catch (verificationError) {
		if (presentRiskBlock(verificationError)) throw verificationError
		if (isWebRtcFailureCode(verificationError.code)) {
			presentWebRtcFailure(verificationError)
		}
		throw verificationError
	}
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
			await refreshSession(true)
		}
		try {
			await publicRequest('/api/auth/session/logout', { headers, data })
		} catch (error) {
			if (platform !== 'H5' || error.code !== 'CSRF_INVALID') throw error
			await refreshSession(true)
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

import {
	applyBrowserCsrfHeader,
	browserCsrfToken,
	requiresCsrf
} from './browser-cookies.js'
import { AUTH_API_BASE_URL, AUTH_ROUTES, clientPlatform } from './config.js'
import { getDeviceInstallationId } from './device-installation.js'
import { hasCompleteSessionCredentials } from './session-credentials.js'
import { SessionRenewalMode, sessionRenewalMode } from './session-retry-policy.js'
import { clearSession, currentSession, saveSession } from './session-vault.js'
import { clearAuthUiPreviewSession, isAuthUiPreviewEnabled } from './ui-preview-session.js'
import {
	applyDiagnosticsToError,
	inspectAuthResponse,
	networkFailureDiagnostics
} from './turnstile-response-diagnostics.js'

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

function requestTask(options) {
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
					const edgeError = new Error('Cloudflare 安全检查未完成，请重新完成人机验证。')
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

function notifyResponseObserver(observer, diagnostics) {
	if (typeof observer !== 'function') return
	try {
		observer(diagnostics)
	} catch (_) {
		// 诊断观察器异常不能覆盖原始网络响应或认证结果。
	}
}

function clientContextHeaders(includeClientContext = true) {
	const headers = { 'Content-Type': 'application/json' }
	if (includeClientContext) {
		headers['X-Device-Installation-Id'] = getDeviceInstallationId()
		headers['X-Client-Platform'] = clientPlatform()
	}
	return headers
}

export async function initializeBrowserCsrf() {
	if (clientPlatform() !== 'H5') return ''
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
	return browserCsrfToken()
}

export async function publicRequest(path, options = {}) {
	const method = String(options.method || 'POST').toUpperCase()
	const headers = clientContextHeaders(options.includeClientContext !== false)
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
	return requestTask({
		path,
		method,
		data: options.data,
		headers,
		timeout: options.timeout,
		onResponse: options.onResponse
	})
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
	if (isAuthUiPreviewEnabled()) return Promise.resolve({ restored: true, preview: true })
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

export async function logoutSession() {
	if (isAuthUiPreviewEnabled()) {
		clearAuthUiPreviewSession()
		clearSession()
		return
	}
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
	}
}

export async function logoutAllSessions() {
	if (isAuthUiPreviewEnabled()) {
		clearAuthUiPreviewSession()
		clearSession()
		return
	}

	// 全设备撤销失败时保留本地凭据，让用户留在当前页重试而不是误报已退出。
	await authorizedRequest('/api/auth/session/logout-all', {
		preserveSessionOnFailure: true
	})
	clearSession()
}

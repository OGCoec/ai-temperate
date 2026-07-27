import { ADMIN_API_BASE_URL, adminClientPlatform } from './admin-config.js'
import {
	ensureAdminCookieScopeMigration,
	invalidateAdminCookieScopeMigration
} from './admin-cookie-scope-migration.js'
import { adminDeviceInstallationId } from './admin-device.js'
import {
	adminCsrfCookieUnavailableError,
	expectedAdminCsrfCookieAfterSuccess,
	hasReadableAdminFlowCsrf,
	requiredAdminCsrfToken
} from './admin-csrf-policy.js'
import {
	currentAdminPreAuthToken,
	ensureAdminPreAuth,
	invalidateAdminPreAuth
} from './admin-pre-auth.js'
import { presentAdminRiskBlock } from './admin-risk-block-navigation.js'
import { beginAdminRiskChallenge } from './admin-risk-challenge-navigation.js'
import {
	clearAdminFlow,
	clearAdminSession,
	loadAdminSecureState
} from './admin-secure-vault.js'
import {
	isWebRtcFailureCode,
	isWebRtcRetryCode
} from '@shared-auth/webrtc-verification-core.js'
import {
	ensureAdminWebRtcVerified,
	invalidateAdminWebRtcVerification,
	presentAdminWebRtcFailure
} from './admin-webrtc-verification.js'
import { serializeStructuredJsonRequestBody } from './admin-request-body.js'

function browserCookie(name) {
	// #ifdef H5
	const prefix = `${encodeURIComponent(name)}=`
	const item = document.cookie.split(';').map(value => value.trim())
		.find(value => value.startsWith(prefix))
	return item ? decodeURIComponent(item.slice(prefix.length)) : ''
	// #endif
	// #ifndef H5
	return ''
	// #endif
}

export function hasBrowserAdminFlow(kind) {
	return hasReadableAdminFlowCsrf(kind, browserCookie)
}

function androidFlowHeaders(path, headers) {
	const state = loadAdminSecureState()
	const flow = path.startsWith('/api/admin/auth/register')
		? state.registerFlow
		: path.startsWith('/api/admin/auth/login')
			? state.loginFlow
			: null
	if (flow?.flowCsrf) headers['X-Admin-CSRF-Token'] = flow.flowCsrf
	if (flow?.challengeId) headers['X-Admin-Challenge'] = flow.challengeId
	if (path.startsWith('/api/admin/auth/register') && flow?.registerToken) {
		headers['X-Admin-Register-Token'] = flow.registerToken
	}
	if (path.startsWith('/api/admin/auth/login') && flow?.loginFlowToken) {
		headers['X-Admin-Login-Flow-Token'] = flow.loginFlowToken
	}
	const publicPath = path === '/api/admin/auth/state'
		|| path === '/api/admin/auth/hcaptcha/config'
	const flowPath = path.startsWith('/api/admin/auth/register')
		|| path.startsWith('/api/admin/auth/login')
	if (state.adminToken && !publicPath && !flowPath) {
		headers.Authorization = `Bearer ${state.adminToken}`
	}
	const preAuthToken = currentAdminPreAuthToken()
	if (preAuthToken) headers['X-AIT-PreAuth'] = preAuthToken
}

function adminHeaders(path, method, options, platform, includeClientContext, jsonContent) {
	const headers = {
		'X-Client-Platform': platform,
		'X-Device-Installation-Id': adminDeviceInstallationId()
	}
	if (jsonContent) headers['Content-Type'] = 'application/json'
	if (includeClientContext) {
		if (platform === 'ANDROID') {
			androidFlowHeaders(path, headers)
		} else {
			const csrf = requiredAdminCsrfToken(path, method, browserCookie)
			if (csrf) headers['X-Admin-CSRF-Token'] = csrf
		}
	} else if (platform === 'ANDROID') {
		const preAuthToken = currentAdminPreAuthToken()
		if (preAuthToken) headers['X-AIT-PreAuth'] = preAuthToken
	}
	Object.assign(headers, options.headers || {})
	return headers
}

export async function adminRequest(path, options = {}, retryState = {}) {
	await ensureAdminCookieScopeMigration()
	await ensureAdminPreAuth()
	const platform = adminClientPlatform()
	try {
		await ensureAdminWebRtcVerified()
		const method = String(options.method || 'POST').toUpperCase()
		const includeClientContext = options.includeClientContext !== false
		const headers = adminHeaders(path, method, options, platform, includeClientContext, true)
		return await request(path, method, options, headers, platform, includeClientContext)
	} catch (error) {
		if (presentAdminRiskBlock(error)) throw error
		if (isWebRtcFailureCode(error.code)) presentAdminWebRtcFailure(error)
		if (!retryState.webRtc && isWebRtcRetryCode(error.code)) {
			await recoverAdminWebRtc(error)
			return adminRequest(path, options, { ...retryState, webRtc: true })
		}
		if (error.code === 'RISK_CHALLENGE_REQUIRED') {
			beginAdminRiskChallenge(error)
		}
		if (!retryState.preAuth
			&& ['PREAUTH_REQUIRED', 'ADMIN_PREAUTH_REQUIRED'].includes(error.code)) {
			invalidateAdminPreAuth()
			invalidateAdminWebRtcVerification()
			await ensureAdminPreAuth()
			return adminRequest(path, options, { ...retryState, preAuth: true })
		}
		if (platform === 'H5'
			&& !retryState.migration
			&& error.code === 'EDGE_COOKIE_SCOPE_RESET_REQUIRED') {
			invalidateAdminCookieScopeMigration()
			invalidateAdminPreAuth()
			invalidateAdminWebRtcVerification()
			await ensureAdminCookieScopeMigration()
			return adminRequest(path, options, { ...retryState, migration: true })
		}
		throw error
	}
}

export async function adminUploadFile(path, options = {}, retryState = {}) {
	await ensureAdminCookieScopeMigration()
	await ensureAdminPreAuth()
	const platform = adminClientPlatform()
	try {
		await ensureAdminWebRtcVerified()
		const method = String(options.method || 'POST').toUpperCase()
		if (method !== 'POST') {
			const error = new Error('当前跨端文件上传通道只支持 POST。')
			error.code = 'ADMIN_UPLOAD_METHOD_UNSUPPORTED'
			throw error
		}
		const includeClientContext = options.includeClientContext !== false
		// Multipart 的 Content-Type 与 boundary 必须交给 uni.uploadFile 生成，安全请求头仍与 JSON 请求共用。
		const headers = adminHeaders(path, method, options, platform, includeClientContext, false)
		return await uploadFile(path, method, options, headers, platform, includeClientContext)
	} catch (error) {
		if (presentAdminRiskBlock(error)) throw error
		if (isWebRtcFailureCode(error.code)) presentAdminWebRtcFailure(error)
		if (!retryState.webRtc && isWebRtcRetryCode(error.code)) {
			await recoverAdminWebRtc(error)
			return adminUploadFile(path, options, { ...retryState, webRtc: true })
		}
		if (error.code === 'RISK_CHALLENGE_REQUIRED') beginAdminRiskChallenge(error)
		if (!retryState.preAuth
			&& ['PREAUTH_REQUIRED', 'ADMIN_PREAUTH_REQUIRED'].includes(error.code)) {
			invalidateAdminPreAuth()
			invalidateAdminWebRtcVerification()
			await ensureAdminPreAuth()
			return adminUploadFile(path, options, { ...retryState, preAuth: true })
		}
		if (platform === 'H5'
			&& !retryState.migration
			&& error.code === 'EDGE_COOKIE_SCOPE_RESET_REQUIRED') {
			invalidateAdminCookieScopeMigration()
			invalidateAdminPreAuth()
			invalidateAdminWebRtcVerification()
			await ensureAdminCookieScopeMigration()
			return adminUploadFile(path, options, { ...retryState, migration: true })
		}
		throw error
	}
}

function request(path, method, options, headers, platform, includeClientContext) {
	return new Promise((resolve, reject) => {
		uni.request({
			url: `${ADMIN_API_BASE_URL}${path}`,
			method,
			data: serializeStructuredJsonRequestBody(options.data, headers),
			header: headers,
			timeout: options.timeout || 10000,
			withCredentials: true,
			success(response) {
				if (response.statusCode >= 200 && response.statusCode < 300) {
					if (platform === 'H5' && includeClientContext) {
						const expectedCookie = expectedAdminCsrfCookieAfterSuccess(path)
						if (expectedCookie && !browserCookie(expectedCookie)) {
							reject(adminCsrfCookieUnavailableError())
							return
						}
					}
					if (options.returnResponse === true) {
						resolve({
							data: response.data,
							statusCode: response.statusCode,
							headers: { ...(response.header || response.headers || {}) }
						})
						return
					}
					resolve(response.data)
					return
				}
				reject(adminResponseError(path, response.statusCode, response.data))
			},
			fail(cause) {
				const error = new Error('网络连接失败，请检查后重试。')
				error.code = 'NETWORK_ERROR'
				error.cause = cause
				reject(error)
			}
		})
	})
}

function uploadFile(path, method, options, headers, platform, includeClientContext) {
	return new Promise((resolve, reject) => {
		uni.uploadFile({
			url: `${ADMIN_API_BASE_URL}${path}`,
			filePath: options.filePath,
			name: options.name || 'file',
			formData: options.formData || {},
			header: headers,
			timeout: options.timeout || 15000,
			withCredentials: true,
			success(response) {
				const data = parseUploadPayload(response.data)
				if (response.statusCode >= 200 && response.statusCode < 300) {
					if (platform === 'H5' && includeClientContext) {
						const expectedCookie = expectedAdminCsrfCookieAfterSuccess(path)
						if (expectedCookie && !browserCookie(expectedCookie)) {
							reject(adminCsrfCookieUnavailableError())
							return
						}
					}
					resolve(data)
					return
				}
				reject(adminResponseError(path, response.statusCode, data))
			},
			fail(cause) {
				const error = new Error('文件上传失败，请检查网络后重试。')
				error.code = 'NETWORK_ERROR'
				error.cause = cause
				reject(error)
			}
		})
	})
}

function parseUploadPayload(data) {
	if (typeof data !== 'string') return data
	try {
		return JSON.parse(data)
	} catch {
		return {}
	}
}

function adminResponseError(path, statusCode, data) {
	const body = data && typeof data === 'object' ? data : {}
	const error = new Error(body.message || '管理员请求未完成，请稍后重试。')
	error.code = body.code || `HTTP_${statusCode}`
	error.statusCode = statusCode
	error.challengeRef = body.challengeRef || ''
	error.challengePath = body.challengePath || ''
	error.expiresAt = body.expiresAt || ''
	error.webRtcStatus = body.webRtcStatus
	error.httpIp = body.httpIp || ''
	error.webRtcIps = Array.isArray(body.webRtcIps) ? [...body.webRtcIps] : []
	error.retryable = body.retryable === true
	error.exceptionType = body.exceptionType || ''
	error.exceptionMessage = body.exceptionMessage || ''
	error.rootCauseType = body.rootCauseType || ''
	error.rootCauseMessage = body.rootCauseMessage || ''
	if (['ADMIN_FLOW_INVALID', 'ADMIN_FLOW_EXPIRED'].includes(error.code)) {
		if (path.startsWith('/api/admin/auth/register')) clearAdminFlow('register')
		if (path.startsWith('/api/admin/auth/login')) clearAdminFlow('login')
	}
	if (error.code === 'ADMIN_SESSION_INVALID') clearAdminSession()
	return error
}

async function recoverAdminWebRtc(error) {
	invalidateAdminWebRtcVerification()
	try {
		return await ensureAdminWebRtcVerified({
			force: error.code === 'WEBRTC_NETWORK_CHANGED'
		})
	} catch (verificationError) {
		if (presentAdminRiskBlock(verificationError)) throw verificationError
		if (isWebRtcFailureCode(verificationError.code)) {
			presentAdminWebRtcFailure(verificationError)
		}
		throw verificationError
	}
}

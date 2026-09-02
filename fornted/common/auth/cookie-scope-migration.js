import { clientPlatform } from './config.js'
import { clearSession } from './session-vault.js'
import {
	authDiagnosticRequestHeaders,
	createAuthRequestDiagnostic,
	recordAuthDiagnosticEvent,
	recordAuthDiagnosticFailure,
	recordAuthDiagnosticResponse
} from './auth-diagnostics.js'

const PUBLIC_HOST = 'niko000o.site'
const MIGRATION_PATH = '/api/_edge/cookie-scope'
const RESET_HEADER = 'X-AIT-Cookie-Scope-Reset'
let migrationInFlight
let migrationSettled = false

/**
 * 在公网 H5 第一次业务请求前清除旧父域 Cookie；本地和 Android 不经过 Worker。
 */
export function ensureCookieScopeMigration(options = {}) {
	const triggerClientRequestId = options?.triggerClientRequestId || ''
	if (!isPublicH5()) {
		recordAuthDiagnosticEvent('COOKIE_SCOPE_MIGRATION_GATE', {
			triggerClientRequestId,
			startDisposition: 'not_public_h5',
			outcome: 'skipped'
		})
		return Promise.resolve({ reset: false })
	}
	if (migrationInFlight) {
		recordAuthDiagnosticEvent('COOKIE_SCOPE_MIGRATION_GATE', {
			triggerClientRequestId,
			startDisposition: migrationSettled
				? 'cached_completed' : 'joined_in_flight',
			outcome: 'reused'
		})
		return migrationInFlight
	}
	recordAuthDiagnosticEvent('COOKIE_SCOPE_MIGRATION_GATE', {
		triggerClientRequestId,
		startDisposition: 'network_started',
		outcome: 'started'
	})
	migrationSettled = false
	migrationInFlight = requestMigration({ triggerClientRequestId }).then(result => {
		migrationSettled = true
		return result
	}).catch(error => {
		migrationInFlight = undefined
		migrationSettled = false
		throw error
	})
	return migrationInFlight
}

export function invalidateCookieScopeMigration() {
	migrationInFlight = undefined
	migrationSettled = false
}

function isPublicH5() {
	if (clientPlatform() !== 'H5' || typeof window === 'undefined') return false
	return window.location?.hostname === PUBLIC_HOST
}

function requestMigration(options = {}) {
	const diagnostic = createAuthRequestDiagnostic(
		MIGRATION_PATH,
		'cookie_scope_migration',
		{ triggerClientRequestId: options.triggerClientRequestId })
	recordAuthDiagnosticEvent('COOKIE_SCOPE_MIGRATION_REQUEST_STARTED', {
		clientRequestId: diagnostic.clientRequestId,
		pageInstanceId: diagnostic.pageInstanceId,
		path: MIGRATION_PATH,
		source: diagnostic.source,
		triggerClientRequestId: diagnostic.triggerClientRequestId
	})
	return new Promise((resolve, reject) => {
		uni.request({
			url: MIGRATION_PATH,
			method: 'POST',
			header: {
				'Content-Type': 'application/json',
				...authDiagnosticRequestHeaders(diagnostic)
			},
			withCredentials: true,
			timeout: 10000,
			success(response) {
				recordAuthDiagnosticResponse(diagnostic, response)
				const reset = responseHeader(response.header, RESET_HEADER) === '1'
				const edgeOutcome = responseHeader(
					response.header,
					'X-AIT-Edge-Outcome')
				const cookieScopeState = responseHeader(
					response.header,
					'X-AIT-Cookie-Scope-State')
				recordAuthDiagnosticEvent('COOKIE_SCOPE_MIGRATION_RESPONSE_RECEIVED', {
					clientRequestId: diagnostic.clientRequestId,
					pageInstanceId: diagnostic.pageInstanceId,
					path: MIGRATION_PATH,
					source: diagnostic.source,
					triggerClientRequestId: diagnostic.triggerClientRequestId,
					status: response.statusCode,
					cookieScopeReset: reset,
					outcome: response.statusCode >= 200 && response.statusCode < 300
						? 'succeeded' : 'rejected',
					errorCode: response.statusCode >= 400
						? response.data?.code || `HTTP_${response.statusCode}` : ''
				})
				if (response.statusCode < 200 || response.statusCode >= 300) {
					reject(migrationError(
						response.data?.code || `HTTP_${response.statusCode}`))
					return
				}
				if (reset) {
					// 父域 Cookie 清理代表旧浏览器会话整体失效，本地会话也必须同步清空。
					clearSession()
				}
				resolve({
					reset,
					clientRequestId: diagnostic.clientRequestId,
					edgeOutcome,
					cookieScopeState
				})
			},
			fail(cause) {
				const error = migrationError('NETWORK_ERROR')
				error.cause = cause
				recordAuthDiagnosticFailure(diagnostic, error)
				reject(error)
			}
		})
	})
}

function responseHeader(headers, name) {
	if (!headers) return ''
	const expected = name.toLowerCase()
	const entry = Object.entries(headers)
		.find(([key]) => key.toLowerCase() === expected)
	return entry ? String(entry[1]) : ''
}

function migrationError(code) {
	const error = new Error('Cookie 安全作用域初始化失败，请稍后重试。')
	error.code = code
	return error
}

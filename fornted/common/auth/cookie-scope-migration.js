import { clientPlatform } from './config.js'
import { clearSession } from './session-vault.js'

const PUBLIC_HOST = 'niko000o.site'
const MIGRATION_PATH = '/api/_edge/cookie-scope'
const RESET_HEADER = 'X-AIT-Cookie-Scope-Reset'
let migrationInFlight

/**
 * 在公网 H5 第一次业务请求前清除旧父域 Cookie；本地和 Android 不经过 Worker。
 */
export function ensureCookieScopeMigration() {
	if (!isPublicH5()) return Promise.resolve({ reset: false })
	if (!migrationInFlight) {
		migrationInFlight = requestMigration().catch(error => {
			migrationInFlight = undefined
			throw error
		})
	}
	return migrationInFlight
}

export function invalidateCookieScopeMigration() {
	migrationInFlight = undefined
}

function isPublicH5() {
	if (clientPlatform() !== 'H5' || typeof window === 'undefined') return false
	return window.location?.hostname === PUBLIC_HOST
}

function requestMigration() {
	return new Promise((resolve, reject) => {
		uni.request({
			url: MIGRATION_PATH,
			method: 'POST',
			header: { 'Content-Type': 'application/json' },
			withCredentials: true,
			timeout: 10000,
			success(response) {
				if (response.statusCode < 200 || response.statusCode >= 300) {
					reject(migrationError(
						response.data?.code || `HTTP_${response.statusCode}`))
					return
				}
				const reset = responseHeader(response.header, RESET_HEADER) === '1'
				if (reset) {
					// 父域 Cookie 清理代表旧浏览器会话整体失效，本地会话也必须同步清空。
					clearSession()
				}
				resolve({ reset })
			},
			fail(cause) {
				const error = migrationError('NETWORK_ERROR')
				error.cause = cause
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

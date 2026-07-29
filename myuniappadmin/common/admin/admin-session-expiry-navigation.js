import { clearAdminMailInspectionSession } from './admin-mail-inspection-session-store.js'
import { invalidateAdminPreAuth } from './admin-pre-auth.js'
import {
	ADMIN_ENTRY_ROUTE,
	isAdminProtectedRoute,
	normalizeAdminPageRoute
} from './admin-route-guard.js'
import { clearAdminSession } from './admin-secure-vault.js'
import { invalidateAdminWebRtcVerification } from './admin-webrtc-verification.js'

const ADMIN_SESSION_EXPIRED_MESSAGE = '管理员会话已失效，请重新登录。'
const PUBLIC_ADMIN_API_PATHS = new Set([
	'/api/admin/auth/state',
	'/api/admin/auth/phone-country',
	'/api/admin/auth/hcaptcha/config'
])
let redirectInFlight = false
let redirectIssuedForInvalidSession = false
let pendingNotice = false

function currentAdminRoute() {
	if (typeof getCurrentPages === 'function') {
		const pages = getCurrentPages()
		if (pages.length) return normalizeAdminPageRoute(pages[pages.length - 1].route || '')
	}
	if (typeof window !== 'undefined') {
		return normalizeAdminPageRoute(window.location.pathname || '')
	}
	return ''
}

/**
 * 管理员接口失效判定只覆盖业务 API，登录、注册、风险边界和公开配置接口
 * 的 401 仍由各自流程处理，避免登录失败被误判成已登录会话过期。
 */
export function isAdminSessionProtectedApiPath(path) {
	const value = String(path || '').split(/[?#]/, 1)[0]
	if (!value.startsWith('/api/admin/')) return false
	if (value.startsWith('/api/admin/_edge/')) return false
	if (value.startsWith('/api/admin/auth/login/')) return false
	if (value.startsWith('/api/admin/auth/register/')) return false
	return !PUBLIC_ADMIN_API_PATHS.has(value)
}

export function isAdminSessionExpiryError(error, path = '') {
	if (error?.code === 'ADMIN_SESSION_INVALID') {
		return !path || isAdminSessionProtectedApiPath(path)
	}
	if (error?.code && error.code !== 'HTTP_401') return false
	return error?.statusCode === 401 && isAdminSessionProtectedApiPath(path)
}

function clearExpiredAdminState() {
	// 会话失效时同步删除独立邮箱检查 Vault，防止敏感凭据残留到下一次登录。
	void clearAdminMailInspectionSession()
	clearAdminSession()
	invalidateAdminPreAuth()
	invalidateAdminWebRtcVerification()
}

export function handleAdminSessionInvalid(error, options = {}) {
	if (!isAdminSessionExpiryError(error, options.path)) return false
	clearExpiredAdminState()

	const route = normalizeAdminPageRoute(options.currentRoute || currentAdminRoute())
	const isEntryRoute = route === ADMIN_ENTRY_ROUTE
	const shouldRedirect = isAdminProtectedRoute(route)
		|| (isEntryRoute && options.forceRedirect === true)
	if (shouldRedirect && !redirectInFlight && !redirectIssuedForInvalidSession) {
		redirectInFlight = true
		redirectIssuedForInvalidSession = true
		pendingNotice = true
		uni.reLaunch({
			url: ADMIN_ENTRY_ROUTE,
			complete() { redirectInFlight = false }
		})
	}
	return true
}

export function takeAdminSessionExpiryNotice() {
	if (!pendingNotice) return ''
	pendingNotice = false
	return ADMIN_SESSION_EXPIRED_MESSAGE
}

export function clearAdminSessionExpiryNotice() {
	pendingNotice = false
}

export function markAdminSessionExpiryRecovered() {
	redirectInFlight = false
	redirectIssuedForInvalidSession = false
	pendingNotice = false
}

export function currentAdminPageRoute() {
	return currentAdminRoute()
}

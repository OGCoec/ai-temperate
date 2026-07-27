const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])
const CSRF_EXEMPT_MUTATIONS = new Set([
	'/api/admin/auth/register/start',
	'/api/admin/auth/login/start',
	'/api/admin/auth/session/bootstrap'
])

const FLOW_CSRF_COOKIES = Object.freeze({
	register: 'admin_register_csrf',
	login: 'admin_login_csrf'
})

export function adminCsrfCookieName(path) {
	const requestPath = String(path || '')
	if (requestPath.startsWith('/api/admin/auth/register/')) {
		return 'admin_register_csrf'
	}
	if (requestPath.startsWith('/api/admin/auth/login/')) {
		return 'admin_login_csrf'
	}
	return 'ADMIN-XSRF-TOKEN'
}

export function requiresAdminCsrf(path, method) {
	const requestMethod = String(method || 'POST').toUpperCase()
	return !SAFE_METHODS.has(requestMethod)
		&& !CSRF_EXEMPT_MUTATIONS.has(String(path || ''))
}

export function requiredAdminCsrfToken(path, method, cookieLookup) {
	if (!requiresAdminCsrf(path, method)) return ''
	const value = cookieLookup(adminCsrfCookieName(path))
	if (!value) throw adminCsrfCookieUnavailableError()
	return value
}

export function expectedAdminCsrfCookieAfterSuccess(path) {
	switch (String(path || '')) {
		case '/api/admin/auth/register/start':
			return 'admin_register_csrf'
		case '/api/admin/auth/login/start':
			return 'admin_login_csrf'
		case '/api/admin/auth/login/complete':
		case '/api/admin/auth/session/bootstrap':
			return 'ADMIN-XSRF-TOKEN'
		default:
			return ''
	}
}

export function hasReadableAdminFlowCsrf(kind, cookieLookup) {
	const cookieName = FLOW_CSRF_COOKIES[String(kind || '')]
	if (!cookieName || typeof cookieLookup !== 'function') return false
	return Boolean(cookieLookup(cookieName))
}

export function adminCsrfCookieUnavailableError() {
	const error = new Error(
		'管理员安全 Cookie 无法读取，请刷新页面；如持续出现请检查站点 Cookie 设置。'
	)
	error.code = 'ADMIN_CSRF_COOKIE_UNAVAILABLE'
	return error
}

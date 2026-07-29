const ADMIN_ENTRY_ROUTE = '/pages/index/index'

const PROTECTED_EXACT_ROUTES = new Set([
	'/pages/risk/ip2location-keys',
	'/pages/ai-model-icons/index'
])

const PROTECTED_ROUTE_PREFIXES = Object.freeze([
	'/pages/ai-models/',
	'/pages/mail-inspection/'
])

const PUBLIC_ROUTES = new Set([
	ADMIN_ENTRY_ROUTE,
	'/pages/risk/challenge-complete',
	'/pages/risk/challenge-failed',
	'/pages/risk/blocked',
	'/pages/risk/webrtc-probe',
	'/pages/risk/webrtc-failed'
])

function normalizeAdminPageRoute(value) {
	const raw = String(value || '').split(/[?#]/, 1)[0]
	if (!raw) return ''
	const path = raw.startsWith('/') ? raw : `/${raw}`
	const normalized = path.replace(/\/+/g, '/')
	return normalized.length > 1 ? normalized.replace(/\/$/, '') : normalized
}

function isAdminProtectedRoute(value) {
	const route = normalizeAdminPageRoute(value)
	return PROTECTED_EXACT_ROUTES.has(route)
		|| PROTECTED_ROUTE_PREFIXES.some(prefix => route.startsWith(prefix))
}

function isAdminPublicRoute(value) {
	return PUBLIC_ROUTES.has(normalizeAdminPageRoute(value))
}

function isAdminSessionInvalidError(error) {
	if (error?.code) return error.code === 'ADMIN_SESSION_INVALID'
	return error?.statusCode === 401
}

/**
 * 管理员前端路由守卫核心，负责把会话验证、路由分类和跳转动作解耦，
 * 运行时依赖通过构造器注入，避免测试必须启动 uni-app 或真实 HTTP 服务。
 */
export function createAdminRouteGuard({
	validateSession,
	navigate,
	onSessionInvalid
}) {
	let sessionInFlight = null
	let invalidNotified = false

	async function ensureAdminSession() {
		if (invalidNotified) return false
		if (!sessionInFlight) {
			sessionInFlight = Promise.resolve()
				.then(() => validateSession())
				.then(() => {
					invalidNotified = false
					return true
				})
				.catch(error => {
					if (!isAdminSessionInvalidError(error)) throw error
					if (!invalidNotified) {
						invalidNotified = true
						onSessionInvalid?.(error)
					}
					return false
				})
				.finally(() => { sessionInFlight = null })
		}
		return sessionInFlight
	}

	async function guardAdminPage(route) {
		if (!isAdminProtectedRoute(route)) return true
		return ensureAdminSession()
	}

	async function guardedAdminNavigate(route, options = {}) {
		if (!(await guardAdminPage(route))) return false
		await Promise.resolve(navigate(route, options))
		return true
	}

	function markAdminSessionAuthenticated() {
		invalidNotified = false
	}

	return Object.freeze({
		ensureAdminSession,
		guardAdminPage,
		guardedAdminNavigate,
		markAdminSessionAuthenticated
	})
}

export {
	ADMIN_ENTRY_ROUTE,
	isAdminProtectedRoute,
	isAdminPublicRoute,
	isAdminSessionInvalidError,
	normalizeAdminPageRoute
}

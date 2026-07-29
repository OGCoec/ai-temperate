import { adminApi } from './admin-api.js'
import {
	createAdminRouteGuard,
	ADMIN_ENTRY_ROUTE,
	isAdminProtectedRoute,
	isAdminPublicRoute,
	normalizeAdminPageRoute
} from './admin-route-guard.js'
import {
	handleAdminSessionInvalid,
	markAdminSessionExpiryRecovered
} from './admin-session-expiry-navigation.js'

function navigate(route, options = {}) {
	const method = options.method || 'navigateTo'
	const { method: _ignored, ...navigationOptions } = options
	const action = uni[method]
	if (typeof action !== 'function') {
		throw new Error(`不支持的管理员导航方法：${method}`)
	}
	return action.call(uni, { ...navigationOptions, url: route })
}

const adminRouteGuard = createAdminRouteGuard({
	validateSession: () => adminApi.bootstrap(),
	navigate,
	// 登录入口与控制台共用同一路由；守卫导航失败时必须强制刷新入口，
	// 才能让仍显示已认证状态的控制台重新落回登录表单。
	onSessionInvalid: error => handleAdminSessionInvalid(error, { forceRedirect: true })
})

export function ensureAdminSession(options = {}) {
	return adminRouteGuard.ensureAdminSession(options)
}

export function guardAdminPage(route) {
	return adminRouteGuard.guardAdminPage(route)
}

export function guardedAdminNavigate(route, options = {}) {
	return adminRouteGuard.guardedAdminNavigate(route, {
		...options,
		method: 'navigateTo'
	})
}

export function guardedAdminRedirect(route, options = {}) {
	return adminRouteGuard.guardedAdminNavigate(route, {
		...options,
		method: 'redirectTo'
	})
}

export function markAdminSessionAuthenticated() {
	adminRouteGuard.markAdminSessionAuthenticated()
	markAdminSessionExpiryRecovered()
}

export function invalidateAdminSessionValidation() {
	adminRouteGuard.invalidateAdminSessionValidation()
}

export function shouldRevalidateAdminSession() {
	return adminRouteGuard.shouldRevalidateAdminSession()
}

export {
	ADMIN_ENTRY_ROUTE,
	isAdminProtectedRoute,
	isAdminPublicRoute,
	normalizeAdminPageRoute
}

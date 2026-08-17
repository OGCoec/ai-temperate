import { requireAuthenticatedPage } from './page-guard.js'
import { isProtectedRoute, normalizeRoutePath } from './protected-routes.js'
import { isRuntimeSessionAuthenticated } from './authenticated-session-state.js'

const guardedMethods = ['navigateTo', 'redirectTo', 'reLaunch']

let installed = false
let bypassMethod = ''

function cloneNavigationOptions(options) {
	return { ...(options || {}) }
}

function invokeWithoutGuard(method, options) {
	bypassMethod = method
	try {
		uni[method](cloneNavigationOptions(options))
	} finally {
		bypassMethod = ''
	}
}

function installMethodGuard(method) {
	uni.addInterceptor(method, {
		invoke(options) {
			if (bypassMethod === method) return true
			const route = normalizeRoutePath(options?.url)
			if (!isProtectedRoute(route)) return true
			if (isRuntimeSessionAuthenticated()) return true
			requireAuthenticatedPage(route).then((allowed) => {
				if (allowed) invokeWithoutGuard(method, options)
			})
			return false
		}
	})
}

export function installAuthenticatedNavigationGuard() {
	if (installed) return
	if (typeof uni === 'undefined' || typeof uni.addInterceptor !== 'function') return
	installed = true
	guardedMethods.forEach(installMethodGuard)
}

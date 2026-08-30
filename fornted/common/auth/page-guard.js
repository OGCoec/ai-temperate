import { AUTH_ROUTES } from './config.js'
import { restorePersistedSession } from './http-client.js'
import { isProtectedRoute, normalizeRoutePath } from './protected-routes.js'
import { clearSession } from './session-vault.js'
import { loadCurrentUserProfile } from '../user/current-user-profile.js'
import {
	beginRuntimeTerminalSessionTransition,
	claimRuntimeTerminalSessionRedirect,
	isRuntimeSessionAuthenticated,
	markRuntimeSessionAuthenticated
} from './authenticated-session-state.js'
import { recordAuthDiagnosticEvent } from './auth-diagnostics.js'

let authenticationInFlight = null
let loginRedirectInFlight = false

function sessionNotFoundError() {
	const error = new Error('SESSION_NOT_FOUND')
	error.code = 'SESSION_NOT_FOUND'
	return error
}

async function confirmAuthenticatedSession() {
	const restored = await restorePersistedSession()
	if (!restored) throw sessionNotFoundError()
	await loadCurrentUserProfile({ force: true })
	markRuntimeSessionAuthenticated()
	return true
}

function redirectToLogin(errorCode = '') {
	if (loginRedirectInFlight || !claimRuntimeTerminalSessionRedirect()) return
	loginRedirectInFlight = true
	recordAuthDiagnosticEvent('LOGIN_REDIRECT_TRIGGERED', {
		source: 'page_guard',
		errorCode,
		route: AUTH_ROUTES.login
	})
	uni.reLaunch({
		url: AUTH_ROUTES.login,
		complete: () => { loginRedirectInFlight = false }
	})
}

export async function requireAuthenticatedPage(url) {
	const route = normalizeRoutePath(url)
	if (!isProtectedRoute(route)) return true
	if (isRuntimeSessionAuthenticated()) return true
	const owner = !authenticationInFlight
	recordAuthDiagnosticEvent('AUTH_GUARD_STARTED', {
		route,
		source: 'require_authenticated_page',
		authReady: false,
		owner,
		waiter: !owner
	})
	try {
		if (!authenticationInFlight) {
			authenticationInFlight = confirmAuthenticatedSession()
				.finally(() => { authenticationInFlight = null })
		}
		await authenticationInFlight
		recordAuthDiagnosticEvent('AUTH_GUARD_COMPLETED', {
			route,
			source: 'require_authenticated_page',
			authReady: true,
			outcome: 'succeeded'
		})
		return true
	} catch (error) {
		recordAuthDiagnosticEvent('AUTH_GUARD_COMPLETED', {
			route,
			source: 'require_authenticated_page',
			authReady: false,
			outcome: 'failed',
			errorCode: error?.code || 'SESSION_NOT_FOUND'
		})
		if (beginRuntimeTerminalSessionTransition()) {
			recordAuthDiagnosticEvent('SESSION_CLEAR_TRIGGERED', {
				route,
				source: 'page_guard',
				errorCode: error?.code || 'SESSION_NOT_FOUND'
			})
			clearSession()
		} else {
			recordAuthDiagnosticEvent('SESSION_CLEAR_COALESCED', {
				route,
				source: 'page_guard',
				errorCode: error?.code || 'SESSION_NOT_FOUND',
				outcome: 'joined'
			})
		}
		redirectToLogin(error?.code || 'SESSION_NOT_FOUND')
		return false
	}
}

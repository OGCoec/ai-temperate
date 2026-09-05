import { assertAuthorizedSessionCurrent, handleTerminalSessionError, redirectTerminalSessionToLogin, restorePersistedSession } from './http-client.js'
import { SessionRequestPurpose } from './session-retry-policy.js'
import { isProtectedRoute, normalizeRoutePath } from './protected-routes.js'
import { clearSession } from './session-vault.js'
import { loadCurrentUserProfile } from '../user/current-user-profile.js'
import {
	beginRuntimeTerminalSessionTransition,
	isRuntimeTerminalSessionActive,
	isRuntimeSessionAuthenticated,
	markRuntimeSessionAuthenticated,
	runtimeSessionRequestGeneration
} from './authenticated-session-state.js'
import { recordAuthDiagnosticEvent } from './auth-diagnostics.js'

let authenticationInFlight = null
let authenticationGeneration = null

function sessionNotFoundError() {
	const error = new Error('SESSION_NOT_FOUND')
	error.code = 'SESSION_NOT_FOUND'
	return error
}

async function confirmAuthenticatedSession(sessionGeneration) {
	const restored = await restorePersistedSession(null, sessionGeneration)
	assertAuthorizedSessionCurrent(sessionGeneration)
	if (!restored) throw sessionNotFoundError()
	await loadCurrentUserProfile({ force: true })
	assertAuthorizedSessionCurrent(sessionGeneration)
	markRuntimeSessionAuthenticated()
	return true
}

function redirectToLogin(error, sessionGeneration) {
	redirectTerminalSessionToLogin(error, { source: 'page_guard' }, sessionGeneration)
}

export async function requireAuthenticatedPage(url) {
	const route = normalizeRoutePath(url)
	if (!isProtectedRoute(route)) return true
	const sessionGeneration = runtimeSessionRequestGeneration()
	if (isRuntimeTerminalSessionActive()) {
		redirectToLogin({ code: 'SESSION_TERMINATED' }, sessionGeneration)
		return false
	}
	if (isRuntimeSessionAuthenticated()) return true
	const owner = !authenticationInFlight || authenticationGeneration !== sessionGeneration
	recordAuthDiagnosticEvent('AUTH_GUARD_STARTED', {
		route,
		source: 'require_authenticated_page',
		authReady: false,
		owner,
		waiter: !owner
	})
	try {
		if (owner) {
			const task = confirmAuthenticatedSession(sessionGeneration)
				.finally(() => {
					if (authenticationInFlight === task) authenticationInFlight = null
				})
			authenticationInFlight = task
			authenticationGeneration = sessionGeneration
		}
		await authenticationInFlight
		assertAuthorizedSessionCurrent(sessionGeneration)
		recordAuthDiagnosticEvent('AUTH_GUARD_COMPLETED', {
			route,
			source: 'require_authenticated_page',
			authReady: true,
			outcome: 'succeeded'
		})
		return true
	} catch (error) {
		if (sessionGeneration !== runtimeSessionRequestGeneration()) return false
		recordAuthDiagnosticEvent('AUTH_GUARD_COMPLETED', {
			route,
			source: 'require_authenticated_page',
			authReady: false,
			outcome: 'failed',
			errorCode: error?.code || 'SESSION_NOT_FOUND'
		})
		if (handleTerminalSessionError(error, { source: 'page_guard' }, sessionGeneration, SessionRequestPurpose.SESSION_RECOVERY)) return false
		// 暂时性的 428、网络或服务异常保留会话；只有确实没有持久会话才进入登录页。
		if (error?.code !== 'SESSION_NOT_FOUND') return false
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
		redirectToLogin(error, sessionGeneration)
		return false
	}
}

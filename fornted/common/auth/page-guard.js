import { AUTH_ROUTES } from './config.js'
import { restorePersistedSession } from './http-client.js'
import { isProtectedRoute, normalizeRoutePath } from './protected-routes.js'
import { clearSession } from './session-vault.js'
import { loadCurrentUserProfile } from '../user/current-user-profile.js'

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
	return true
}

function redirectToLogin() {
	if (loginRedirectInFlight) return
	loginRedirectInFlight = true
	uni.reLaunch({
		url: AUTH_ROUTES.login,
		complete: () => { loginRedirectInFlight = false }
	})
}

export async function requireAuthenticatedPage(url) {
	const route = normalizeRoutePath(url)
	if (!isProtectedRoute(route)) return true
	try {
		if (!authenticationInFlight) {
			authenticationInFlight = confirmAuthenticatedSession()
				.finally(() => { authenticationInFlight = null })
		}
		await authenticationInFlight
		return true
	} catch (error) {
		clearSession()
		redirectToLogin()
		return false
	}
}

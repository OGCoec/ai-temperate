export const PUBLIC_ROUTES = Object.freeze([
	'/pages/launch/session-gate',
	'/pages/auth/login',
	'/pages/auth/totp-login',
	'/pages/auth/register',
	'/pages/auth/password-reset',
	'/pages/risk/blocked',
	'/pages/risk/challenge-complete',
	'/pages/risk/challenge-failed',
	'/pages/risk/webrtc-failed'
])

const publicRouteSet = new Set(PUBLIC_ROUTES)

export function normalizeRoutePath(url) {
	const raw = String(url || '').trim()
	if (!raw) return ''
	const withoutHash = raw.split('#')[0]
	const withoutQuery = withoutHash.split('?')[0]
	let pathname = withoutQuery
	try {
		pathname = new URL(raw, 'https://localhost').pathname
	} catch (error) {
		pathname = withoutQuery
	}
	if (!pathname.startsWith('/')) pathname = `/${pathname}`
	return pathname.replace(/\/+$/, '') || '/'
}

export function isPublicRoute(url) {
	return publicRouteSet.has(normalizeRoutePath(url))
}

export function isProtectedRoute(url) {
	const route = normalizeRoutePath(url)
	return Boolean(route) && !isPublicRoute(route)
}

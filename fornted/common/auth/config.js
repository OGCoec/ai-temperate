let authApiBaseUrl = 'https://api.niko000o.site'
// #ifdef H5
// H5 本地开发仍直连本机后端；正式根域通过 Cloudflare Worker 使用同源 /api。
const h5Hostname = typeof window !== 'undefined' && window.location ? window.location.hostname : ''
if (h5Hostname === 'localhost' || h5Hostname === '127.0.0.1') {
	authApiBaseUrl = 'https://localhost:6655'
} else if (h5Hostname === 'niko000o.site') {
	authApiBaseUrl = ''
}
// #endif

export const AUTH_API_BASE_URL = authApiBaseUrl

export const AUTH_ROUTES = Object.freeze({
	sessionGate: '/pages/launch/session-gate',
	login: '/pages/auth/login',
	register: '/pages/auth/register',
	passwordReset: '/pages/auth/password-reset',
	home: '/pages/account/profile',
	profile: '/pages/account/profile'
})

export function clientPlatform() {
	// #ifdef APP-PLUS
	return 'ANDROID'
	// #endif
	// #ifndef APP-PLUS
	return 'H5'
	// #endif
}

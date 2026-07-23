let authApiBaseUrl = 'https://api.niko000o.site'
// #ifdef H5
// H5 本地开发仍直连本机后端；公网 H5 页面使用 API 二级域名，避免一级域名同时承担前后端入口。
const h5Hostname = typeof window !== 'undefined' && window.location ? window.location.hostname : ''
if (h5Hostname === 'localhost' || h5Hostname === '127.0.0.1') {
	authApiBaseUrl = 'https://localhost:6655'
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

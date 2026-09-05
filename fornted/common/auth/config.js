let authApiBaseUrl = 'https://niko000o.site'
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

export const ClientPlatform = Object.freeze({
	H5: 'H5',
	ANDROID: 'ANDROID',
	WECHAT_MINI_PROGRAM: 'WECHAT_MINI_PROGRAM'
})

export const AUTH_ROUTES = Object.freeze({
	sessionGate: '/pages/launch/session-gate',
	login: '/pages/auth/login',
	totpLogin: '/pages/auth/totp-login',
	oauthReturn: '/pages/auth/oauth-return',
	oauthPhone: '/pages/auth/oauth-phone',
	register: '/pages/auth/register',
	passwordReset: '/pages/auth/password-reset',
	home: '/pages/ai-chat/index',
	chat: '/pages/ai-chat/index',
	profile: '/pages/account/profile',
	apiKeys: '/pages/account/api-keys',
	membershipPlans: '/pages/account/membership-plans',
	paymentResult: '/pages/account/payment-result',
	totpSecurity: '/pages/account/totp-security',
	models: '/pages/ai-models/catalog'
})

export function resolveClientPlatform(platform) {
	const normalized = String(platform || '').trim().toLowerCase()
	return normalized === 'android' ? ClientPlatform.ANDROID : ClientPlatform.H5
}

export function clientPlatform() {
	// #ifdef MP-WEIXIN
	return ClientPlatform.WECHAT_MINI_PROGRAM
	// #endif

	// #ifdef APP-PLUS
	return resolveClientPlatform(uni.getSystemInfoSync()?.platform)
	// #endif

	// #ifdef H5
	return ClientPlatform.H5
	// #endif
}

export function usesBrowserCookieTransport(platform = clientPlatform()) {
	return platform === ClientPlatform.H5
}

export function usesExplicitTokenTransport(platform = clientPlatform()) {
	return platform === ClientPlatform.ANDROID
		|| platform === ClientPlatform.WECHAT_MINI_PROGRAM
}

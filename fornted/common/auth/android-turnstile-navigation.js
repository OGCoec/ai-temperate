const TURNSTILE_PAGE_PATH = '/api/auth/turnstile/page'
const CHALLENGE_PATTERN = /^[A-Za-z0-9_-]{38}$/
const SITE_KEY_PATTERN = /^[A-Za-z0-9_-]{20,200}$/
const CHANNEL_PATTERN = /^[A-Za-z0-9_-]{8,80}$/
const PRE_AUTH_PATTERN = /^[A-Za-z0-9_-]{43}$/
const DEVICE_INSTALLATION_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const HTTPS_ORIGIN_PATTERN = /^https:\/\/([A-Za-z0-9.-]+)(?::([0-9]{1,5}))?$/
const HOST_LABEL_PATTERN = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/
const NON_VISIBLE_ASCII_PATTERN = /[^\x21-\x7e]/
const ALLOWED_ACTIONS = new Set([
	'register',
	'login',
	'password_reset'
])

function navigationError(code) {
	const error = new Error('Android Turnstile navigation is unavailable.')
	error.code = code
	return error
}

function normalizeHttpsOrigin(value) {
	if (
		typeof value !== 'string' ||
		value.length === 0 ||
		value.length > 2048 ||
		NON_VISIBLE_ASCII_PATTERN.test(value)
	) return ''
	const match = value.match(HTTPS_ORIGIN_PATTERN)
	if (!match) return ''

	const hostname = match[1]
	if (hostname.length > 253 || hostname.split('.').some((label) => !HOST_LABEL_PATTERN.test(label))) {
		return ''
	}

	if (match[2]) {
		const port = Number(match[2])
		if (!Number.isInteger(port) || port < 1 || port > 65535) return ''
	}

	return value
}

/**
 * Android首次页面导航必须同时携带设备绑定的PreAuth上下文；任何缺失都直接停止打开页面。
 */
export function loadAndroidTurnstilePage(webview, options = {}) {
	if (!webview || typeof webview.loadURL !== 'function') {
		throw navigationError('TURNSTILE_NAVIGATION_INVALID')
	}

	const baseUrl = normalizeHttpsOrigin(options.baseUrl)
	if (
		!baseUrl ||
		typeof options.challenge !== 'string' ||
		options.challenge.length !== 38 ||
		!CHALLENGE_PATTERN.test(options.challenge) ||
		typeof options.action !== 'string' ||
		!ALLOWED_ACTIONS.has(options.action) ||
		typeof options.siteKey !== 'string' ||
		!SITE_KEY_PATTERN.test(options.siteKey) ||
		typeof options.channel !== 'string' ||
		!CHANNEL_PATTERN.test(options.channel)
	) {
		throw navigationError('TURNSTILE_NAVIGATION_INVALID')
	}

	if (
		typeof options.preAuthToken !== 'string' ||
		options.preAuthToken.length !== 43 ||
		!PRE_AUTH_PATTERN.test(options.preAuthToken) ||
		typeof options.deviceInstallationId !== 'string' ||
		options.deviceInstallationId.length !== 36 ||
		!DEVICE_INSTALLATION_ID_PATTERN.test(options.deviceInstallationId)
	) {
		throw navigationError('TURNSTILE_SECURITY_CONTEXT_UNAVAILABLE')
	}

	const url = `${baseUrl}${TURNSTILE_PAGE_PATH}` +
		`?challenge=${encodeURIComponent(options.challenge)}` +
		`&action=${encodeURIComponent(options.action)}` +
		`#siteKey=${encodeURIComponent(options.siteKey)}` +
		`&channel=${encodeURIComponent(options.channel)}`

	// 附加Header只用于受保护HTML的首次同源导航，不覆盖Cookie仓库，也不注入跨域子资源。
	webview.loadURL(url, {
		'X-AIT-PreAuth': options.preAuthToken,
		'X-Device-Installation-Id': options.deviceInstallationId,
		'X-Client-Platform': 'ANDROID'
	})
}

export const XSRF_COOKIE_NAME = 'XSRF-TOKEN'
const LEGACY_CSRF_STORAGE_KEY = 'ait.auth.csrf.v1'
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

export function cookieValue(cookieHeader, name) {
	if (!cookieHeader || !name) return ''
	const prefix = `${name}=`
	const entry = String(cookieHeader)
		.split(';')
		.map(value => value.trim())
		.find(value => value.startsWith(prefix))
	if (!entry) return ''
	const value = entry.slice(prefix.length)
	try {
		return decodeURIComponent(value)
	} catch (error) {
		return value
	}
}

export function requiresCsrf(method) {
	return !SAFE_METHODS.has(String(method || 'POST').toUpperCase())
}

export function applyBrowserCsrfHeader(headers, method, csrfToken) {
	if (requiresCsrf(method) && csrfToken) headers['X-CSRF-Token'] = csrfToken
	return headers
}

export function browserCsrfToken() {
	// #ifdef H5
	return cookieValue(document.cookie, XSRF_COOKIE_NAME)
	// #endif
	// #ifndef H5
	return ''
	// #endif
}

export function clearLegacyBrowserSession() {
	// #ifdef H5
	try {
		window.sessionStorage.removeItem(LEGACY_CSRF_STORAGE_KEY)
	} catch (error) {
		// 浏览器禁用存储时不能影响新的 Cookie 会话。
	}
	// #endif
}

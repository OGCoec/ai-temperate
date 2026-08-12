import { parseAbsoluteHttpUrl } from './http-url.js'

/**
 * 把平台打开能力包裹为安全外链函数，确保未经校验的协议不会进入系统浏览器。
 */
export function createExternalHttpUrlOpener(platformOpen) {
	return value => {
		const parsed = parseAbsoluteHttpUrl(value)
		if (!parsed || typeof platformOpen !== 'function') return false
		try {
			platformOpen(parsed.href)
			return true
		} catch (_) {
			return false
		}
	}
}

let platformOpen = null
// #ifdef H5
platformOpen = url => window.open(url, '_blank', 'noopener,noreferrer')
// #endif
// #ifdef APP-PLUS
platformOpen = url => plus.runtime.openURL(url)
// #endif

const defaultOpenExternalHttpUrl = createExternalHttpUrlOpener(platformOpen)

export function openExternalHttpUrl(value) {
	return defaultOpenExternalHttpUrl(value)
}

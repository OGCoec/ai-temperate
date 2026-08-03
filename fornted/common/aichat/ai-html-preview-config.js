export const AI_HTML_PREVIEW_PRODUCTION_ORIGIN = 'https://ai-temperate-html-preview.pages.dev'

export function isAiHtmlPreviewLoopbackHostname(value) {
	const hostname = String(value || '').trim().toLowerCase().replace(/^\[|\]$/g, '')
	return hostname === 'localhost'
		|| hostname.endsWith('.localhost')
		|| hostname === '::1'
		|| /^127(?:\.\d{1,3}){3}$/.test(hostname)
}

function isAiHtmlPreviewLoopbackOrigin(value) {
	try {
		return isAiHtmlPreviewLoopbackHostname(new URL(String(value || '').trim()).hostname)
	} catch (_) {
		return false
	}
}

export function normalizeAiHtmlPreviewOrigin(value) {
	const candidate = String(value || '').trim()
	if (!candidate) return ''
	try {
		const url = new URL(candidate)
		if (url.protocol !== 'https:') return ''
		if (url.username || url.password) return ''
		if (url.pathname !== '/' || url.search || url.hash) return ''
		if (isAiHtmlPreviewLoopbackHostname(url.hostname)) return ''
		return url.origin
	} catch (_) {
		return ''
	}
}

export function createAiHtmlPreviewConfig(value) {
	const rawValue = String(value || '').trim()
	if (!rawValue) {
		return {
			enabled: false,
			origin: '',
			error: 'HTML 安全预览地址尚未配置'
		}
	}
	if (isAiHtmlPreviewLoopbackOrigin(rawValue)) {
		return {
			enabled: false,
			origin: '',
			error: '公网页面禁止连接本机 HTML 预览服务'
		}
	}
	const origin = normalizeAiHtmlPreviewOrigin(rawValue)
	if (!origin) {
		return {
			enabled: false,
			origin: '',
			error: 'HTML 安全预览地址必须是独立的 HTTPS Origin'
		}
	}
	return { enabled: true, origin, error: '' }
}

export function getAiHtmlPreviewConfig() {
	const configuredOrigin = typeof __AI_HTML_PREVIEW_ORIGIN__ !== 'undefined'
		? __AI_HTML_PREVIEW_ORIGIN__
		: ''
	return createAiHtmlPreviewConfig(configuredOrigin)
}

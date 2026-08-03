export const AI_HTML_PREVIEW_MESSAGE_SOURCE = 'ait-html-preview'
export const AI_HTML_PREVIEW_RUNTIME_SOURCE = 'ait-html-preview-runtime'
export const AI_HTML_PREVIEW_PROTOCOL_VERSION = 1
export const AI_HTML_PREVIEW_MAX_HTML_BYTES = 1024 * 1024
export const AI_HTML_PREVIEW_MAX_ERROR_CHARACTERS = 4096

const HOST_MESSAGE_TYPES = new Set(['render', 'dispose'])
const SHELL_MESSAGE_TYPES = new Set(['ready', 'rendered', 'runtime-error', 'navigation'])
const RUNTIME_MESSAGE_TYPES = new Set(['rendered', 'runtime-error', 'navigation'])
const ALLOWED_PARENT_ORIGINS = new Set([
	'https://niko000o.site',
	'https://dev.niko000o.site',
	'https://localhost:3000',
	'https://127.0.0.1:3000'
])

function isSecureId(value) {
	return /^[a-f0-9]{32}$/.test(String(value || ''))
}

function utf8ByteLength(value) {
	if (typeof TextEncoder === 'undefined') return Number.POSITIVE_INFINITY
	return new TextEncoder().encode(String(value || '')).byteLength
}

function finiteInteger(value, minimum, maximum, fallback = 0) {
	const number = Number(value)
	if (!Number.isFinite(number)) return fallback
	return Math.min(maximum, Math.max(minimum, Math.round(number)))
}

function cleanText(value, maximum = AI_HTML_PREVIEW_MAX_ERROR_CHARACTERS) {
	return String(value || '')
		.replace(/[?#][^\s)]*/g, '')
		.replace(/\s+/g, ' ')
		.trim()
		.slice(0, maximum)
}

function safeNavigationUrl(value) {
	try {
		const url = new URL(String(value || ''))
		if (!['https:', 'http:'].includes(url.protocol)) return ''
		return (url.origin + url.pathname).slice(0, 2048)
	} catch (_) {
		return ''
	}
}

export function isAllowedParentOrigin(value) {
	return ALLOWED_PARENT_ORIGINS.has(String(value || ''))
}

export function parseShellLocationHash(value) {
	const parameters = new URLSearchParams(String(value || '').replace(/^#/, ''))
	const channelId = parameters.get('channelId') || ''
	const parentOrigin = parameters.get('parentOrigin') || ''
	if (!isSecureId(channelId) || !isAllowedParentOrigin(parentOrigin)) {
		return { channelId: '', parentOrigin: '' }
	}
	return { channelId, parentOrigin }
}

export function isHostMessage(value, channelId) {
	if (!value || typeof value !== 'object') return false
	if (
		value.source !== AI_HTML_PREVIEW_MESSAGE_SOURCE ||
		value.version !== AI_HTML_PREVIEW_PROTOCOL_VERSION ||
		value.channelId !== channelId ||
		!HOST_MESSAGE_TYPES.has(value.type) ||
		!isSecureId(value.renderId)
	) return false
	if (value.type === 'dispose') return true
	return (
		typeof value.html === 'string' &&
		utf8ByteLength(value.html) <= AI_HTML_PREVIEW_MAX_HTML_BYTES &&
		(value.theme === 'dark' || value.theme === 'light')
	)
}

export function createShellMessage(channelId, type, payload = {}) {
	if (!isSecureId(channelId) || !SHELL_MESSAGE_TYPES.has(type)) {
		throw new Error('HTML 预览沙箱消息无效')
	}
	return {
		...payload,
		source: AI_HTML_PREVIEW_MESSAGE_SOURCE,
		version: AI_HTML_PREVIEW_PROTOCOL_VERSION,
		channelId,
		type
	}
}

export function isRuntimeMessage(value, channelId, renderId) {
	return Boolean(
		value &&
		typeof value === 'object' &&
		value.source === AI_HTML_PREVIEW_RUNTIME_SOURCE &&
		value.version === AI_HTML_PREVIEW_PROTOCOL_VERSION &&
		value.channelId === channelId &&
		value.renderId === renderId &&
		RUNTIME_MESSAGE_TYPES.has(value.type)
	)
}

export function sanitizeRuntimeMessage(value) {
	const base = {
		type: RUNTIME_MESSAGE_TYPES.has(value?.type) ? value.type : 'runtime-error',
		renderId: isSecureId(value?.renderId) ? value.renderId : ''
	}
	if (base.type === 'rendered') {
		return {
			...base,
			height: finiteInteger(value.height, 120, 2400, 520),
			backgroundColor: cleanText(value.backgroundColor, 64) || '#ffffff'
		}
	}
	if (base.type === 'navigation') {
		return { ...base, url: safeNavigationUrl(value.url) }
	}
	return {
		...base,
		message: cleanText(value?.message) || 'HTML 运行时发生未知错误',
		line: finiteInteger(value?.line, 0, 1000000),
		column: finiteInteger(value?.column, 0, 1000000)
	}
}

import { buildQueryString } from '../platform/query-string.js'

export const AI_HTML_PREVIEW_MESSAGE_SOURCE = 'ait-html-preview'
export const AI_HTML_PREVIEW_PROTOCOL_VERSION = 1
export const AI_HTML_PREVIEW_MAX_HTML_BYTES = 1024 * 1024
export const AI_HTML_PREVIEW_MAX_ERROR_CHARACTERS = 4096
export const AI_HTML_PREVIEW_READY_TIMEOUT_MS = 8000
export const AI_HTML_PREVIEW_RENDER_TIMEOUT_MS = 15000

const SHELL_MESSAGE_TYPES = new Set(['ready', 'rendered', 'runtime-error', 'navigation'])

function secureCrypto(cryptoLike) {
	const value = cryptoLike || globalThis.crypto
	if (!value || typeof value.getRandomValues !== 'function') {
		throw new Error('当前浏览器缺少安全随机数能力')
	}
	return value
}

export function createAiHtmlPreviewSecureId(cryptoLike) {
	const bytes = new Uint8Array(16)
	secureCrypto(cryptoLike).getRandomValues(bytes)
	return Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
}

export function isAiHtmlPreviewSecureId(value) {
	return /^[a-f0-9]{32}$/.test(String(value || ''))
}

export function aiHtmlPreviewUtf8ByteLength(value) {
	if (typeof TextEncoder === 'undefined') return Number.POSITIVE_INFINITY
	return new TextEncoder().encode(String(value || '')).byteLength
}

function exactOrigin(value) {
	try {
		const url = new URL(String(value || ''))
		if (url.protocol !== 'https:' || url.username || url.password) return ''
		if (url.pathname !== '/' || url.search || url.hash) return ''
		return url.origin
	} catch (_) {
		return ''
	}
}

export function createAiHtmlPreviewFrameUrl({ previewOrigin, parentOrigin, channelId }) {
	const normalizedPreviewOrigin = exactOrigin(previewOrigin)
	const normalizedParentOrigin = exactOrigin(parentOrigin)
	if (!normalizedPreviewOrigin || !normalizedParentOrigin) {
		throw new Error('HTML 预览 iframe 需要精确的 HTTPS Origin')
	}
	if (!isAiHtmlPreviewSecureId(channelId)) {
		throw new Error('HTML 预览 channelId 无效')
	}
	const hash = buildQueryString([
		['channelId', channelId],
		['parentOrigin', normalizedParentOrigin]
	])
	return normalizedPreviewOrigin + '/#' + hash
}

export function createAiHtmlPreviewRenderMessage({ channelId, renderId, html, theme = 'dark' }) {
	if (!isAiHtmlPreviewSecureId(channelId) || !isAiHtmlPreviewSecureId(renderId)) {
		throw new Error('HTML 预览渲染标识无效')
	}
	const source = String(html || '')
	if (aiHtmlPreviewUtf8ByteLength(source) > AI_HTML_PREVIEW_MAX_HTML_BYTES) {
		throw new Error('HTML 预览内容超过 1 MiB 限制')
	}
	return {
		source: AI_HTML_PREVIEW_MESSAGE_SOURCE,
		version: AI_HTML_PREVIEW_PROTOCOL_VERSION,
		channelId,
		type: 'render',
		renderId,
		html: source,
		theme: theme === 'light' ? 'light' : 'dark'
	}
}

export function createAiHtmlPreviewDisposeMessage({ channelId, renderId }) {
	if (!isAiHtmlPreviewSecureId(channelId) || !isAiHtmlPreviewSecureId(renderId)) {
		throw new Error('HTML 预览销毁标识无效')
	}
	return {
		source: AI_HTML_PREVIEW_MESSAGE_SOURCE,
		version: AI_HTML_PREVIEW_PROTOCOL_VERSION,
		channelId,
		type: 'dispose',
		renderId
	}
}

export function isAiHtmlPreviewShellMessage(value, channelId) {
	return Boolean(
		value &&
		typeof value === 'object' &&
		value.source === AI_HTML_PREVIEW_MESSAGE_SOURCE &&
		value.version === AI_HTML_PREVIEW_PROTOCOL_VERSION &&
		value.channelId === channelId &&
		SHELL_MESSAGE_TYPES.has(value.type)
	)
}

export function sanitizeAiHtmlPreviewRuntimeError(value) {
	const compact = String(value || 'HTML 运行时发生未知错误')
		.replace(/[?#][^\s)]*/g, '')
		.replace(/\s+/g, ' ')
		.trim()
	return compact.slice(0, AI_HTML_PREVIEW_MAX_ERROR_CHARACTERS) || 'HTML 运行时发生未知错误'
}

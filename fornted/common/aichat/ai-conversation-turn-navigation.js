const DEFAULT_WINDOW_SIZE = 50
const DEFAULT_WINDOW_SHIFT = 25
const ANSWER_SUMMARY_LENGTH = 180
const TURN_MARKER_COLLAPSED_WIDTH = 8
const TURN_MARKER_INTERACTION_WIDTHS = Object.freeze([20, 16, 13, 10])

function boundedInteger(value, minimum, maximum) {
	const number = Number(value)
	if (!Number.isFinite(number)) return minimum
	return Math.max(minimum, Math.min(maximum, Math.trunc(number)))
}

function plainText(value) {
	return String(value || '')
		.replace(/```[^\n]*\n([\s\S]*?)```/g, '$1')
		.replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
		.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
		.replace(/<[^>]+>/g, ' ')
		.replace(/^\s{0,3}(?:#{1,6}|>|[-+*]|\d+[.)])\s+/gm, '')
		.replace(/[`*_~]+/g, '')
		.replace(/\\([\\`*{}\[\]()#+\-.!_>])/g, '$1')
		.replace(/\s+/g, ' ')
		.trim()
}

function truncateText(value, maximum) {
	const characters = Array.from(plainText(value))
	if (characters.length <= maximum) return characters.join('')
	return `${characters.slice(0, maximum).join('').trimEnd()}…`
}

function stableHash(value) {
	let hash = 2166136261
	for (const character of String(value || '')) {
		hash ^= character.codePointAt(0)
		hash = Math.imul(hash, 16777619)
	}
	return (hash >>> 0).toString(36)
}

function safeElementToken(value) {
	const source = String(value || '')
	const token = source.replace(/[^A-Za-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'turn'
	return token === source ? token : `${token}-${stableHash(source)}`
}

function attachmentKind(attachment) {
	const type = String(attachment?.contentType || '').toLowerCase()
	if (type.startsWith('image/')) return 'image'
	if (type.startsWith('video/')) return 'video'
	return 'file'
}

function summarizeAttachments(message) {
	const counts = { image: 0, video: 0, file: 0 }
	const attachments = [
		...(Array.isArray(message?.contentAttachments) ? message.contentAttachments : []),
		...(Array.isArray(message?.responseAttachments) ? message.responseAttachments : [])
	]
	for (const attachment of attachments) counts[attachmentKind(attachment)] += 1
	return [
		counts.image ? `图片 ${counts.image}` : '',
		counts.video ? `视频 ${counts.video}` : '',
		counts.file ? `文件 ${counts.file}` : ''
	].filter(Boolean).join(' · ')
}

function turnStatus(message) {
	if (message?.error) return 'failed'
	if (message?.stopped) return 'stopped'
	if (message?.saving) return 'saving'
	if (message?.streaming) return 'streaming'
	return 'complete'
}

export function messageTurnKey(message, index = 0) {
	// 流式消息完成后才会补上服务端 ID；优先沿用本地 ID，避免同一轮在落库瞬间更换 DOM 锚点。
	return String(message?.localId || message?.messagePublicId || `turn-${index + 1}`)
}

export function messageTurnElementId(message, index = 0) {
	return `message-${safeElementToken(messageTurnKey(message, index))}`
}

export function createTurnNavigationItem(message, index = 0) {
	const status = turnStatus(message)
	const fallbackAnswer = ({
		streaming: '正在生成回答…',
		saving: '正在保存回答…',
		stopped: '回答已停止。',
		failed: '回答生成失败。'
	})[status] || '暂时没有文字回答。'
	return Object.freeze({
		key: messageTurnKey(message, index),
		elementId: messageTurnElementId(message, index),
		position: index + 1,
		question: plainText(message?.contentText) || '附件消息',
		answerSummary: truncateText(message?.responseText || fallbackAnswer, ANSWER_SUMMARY_LENGTH),
		attachmentSummary: summarizeAttachments(message),
		createdAt: String(message?.createdAt || ''),
		status
	})
}

export function turnMarkerWidth(index, interactionIndex) {
	if (!Number.isInteger(index) || index < 0
		|| !Number.isInteger(interactionIndex) || interactionIndex < 0) {
		return TURN_MARKER_COLLAPSED_WIDTH
	}
	const distance = Math.abs(index - interactionIndex)
	return TURN_MARKER_INTERACTION_WIDTHS[distance] ?? TURN_MARKER_COLLAPSED_WIDTH
}

export function createInitialTurnWindow(total, limit = DEFAULT_WINDOW_SIZE) {
	const safeTotal = Math.max(0, Math.trunc(Number(total) || 0))
	const safeLimit = Math.max(1, Math.trunc(Number(limit) || DEFAULT_WINDOW_SIZE))
	return Object.freeze({
		start: Math.max(0, safeTotal - safeLimit),
		end: safeTotal
	})
}

export function shiftTurnWindow(
	window,
	direction,
	total,
	step = DEFAULT_WINDOW_SHIFT,
	limit = DEFAULT_WINDOW_SIZE
) {
	const safeTotal = Math.max(0, Math.trunc(Number(total) || 0))
	const safeLimit = Math.max(1, Math.trunc(Number(limit) || DEFAULT_WINDOW_SIZE))
	const safeStep = Math.max(1, Math.trunc(Number(step) || DEFAULT_WINDOW_SHIFT))
	const currentSize = Math.min(safeLimit, safeTotal)
	const currentStart = boundedInteger(window?.start, 0, Math.max(0, safeTotal - currentSize))
	const delta = direction === 'before' ? -safeStep : direction === 'after' ? safeStep : 0
	const start = boundedInteger(currentStart + delta, 0, Math.max(0, safeTotal - currentSize))
	return Object.freeze({ start, end: Math.min(safeTotal, start + currentSize) })
}

export function windowAfterPrepend(
	window,
	incomingCount,
	total,
	step = DEFAULT_WINDOW_SHIFT,
	limit = DEFAULT_WINDOW_SIZE
) {
	const added = Math.max(0, Math.trunc(Number(incomingCount) || 0))
	const shiftedWindow = {
		start: Math.max(0, Number(window?.start || 0) + added),
		end: Math.max(0, Number(window?.end || 0) + added)
	}
	return shiftTurnWindow(shiftedWindow, 'before', total, step, limit)
}

export function centerTurnWindow(targetIndex, total, limit = DEFAULT_WINDOW_SIZE) {
	const safeTotal = Math.max(0, Math.trunc(Number(total) || 0))
	const safeLimit = Math.max(1, Math.trunc(Number(limit) || DEFAULT_WINDOW_SIZE))
	const size = Math.min(safeLimit, safeTotal)
	const target = boundedInteger(targetIndex, 0, Math.max(0, safeTotal - 1))
	const start = boundedInteger(target - Math.floor(size / 2), 0, Math.max(0, safeTotal - size))
	return Object.freeze({ start, end: start + size })
}

export function restoreAnchoredScrollTop(currentScrollTop, previousOffset, nextOffset) {
	const current = Number(currentScrollTop)
	const before = Number(previousOffset)
	const after = Number(nextOffset)
	if (![current, before, after].every(Number.isFinite)) {
		return Number.isFinite(current) ? Math.max(0, current) : 0
	}
	return Math.max(0, current + after - before)
}

export function resolveTurnScrollElement(reference, fallbackHost, styleResolver) {
	const exposedMain = typeof reference?.$getMain === 'function'
		? reference.$getMain()
		: null
	if (exposedMain) return exposedMain

	const host = reference?.$el || reference || fallbackHost || null
	if (!host) return null
	const candidates = Array.from(host.querySelectorAll?.('.uni-scroll-view') || [])
	const resolveStyle = typeof styleResolver === 'function'
		? styleResolver
		: element => globalThis.getComputedStyle?.(element)
	const scrollable = candidates.find(element =>
		['auto', 'scroll'].includes(String(resolveStyle(element)?.overflowY || '').toLowerCase()))
	return scrollable || candidates[candidates.length - 1] || host
}

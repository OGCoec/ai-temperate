const STORAGE_KEY = 'ait.user.ai.stopped-draft.v1'
const SCHEMA_VERSION = 1
const MAX_DRAFTS = 8
const MAX_TEXT_CHARACTERS = 4 * 1024 * 1024

function storage() {
	try {
		return globalThis.sessionStorage || null
	} catch (_) {
		return null
	}
}

function readAll() {
	const value = storage()
	if (!value) return []
	try {
		const parsed = JSON.parse(value.getItem(STORAGE_KEY) || '[]')
		if (!Array.isArray(parsed)) return []
		return parsed.filter(item => item && item.schemaVersion === SCHEMA_VERSION)
	} catch (_) {
		return []
	}
}

function writeAll(items) {
	const value = storage()
	if (!value) return
	try {
		value.setItem(STORAGE_KEY, JSON.stringify(items.slice(-MAX_DRAFTS)))
	} catch (_) {
		// sessionStorage 配额不足不能影响已经停止的本地消息显示。
	}
}

function text(value, maximum = MAX_TEXT_CHARACTERS) {
	const normalized = String(value || '')
	return normalized.length > maximum ? normalized.slice(0, maximum) : normalized
}

function normalizedDraft(draft) {
	if (!draft || typeof draft !== 'object') return null
	const idempotencyKey = text(draft.idempotencyKey, 64)
	if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(idempotencyKey)) return null
	return Object.freeze({
		schemaVersion: SCHEMA_VERSION,
		conversationPublicId: draft.conversationPublicId ? text(draft.conversationPublicId, 22) : null,
		localId: text(draft.localId, 128),
		idempotencyKey,
		inputText: text(draft.inputText),
		responseText: text(draft.responseText),
		stoppedAt: text(draft.stoppedAt, 64)
	})
}

export function saveAiConversationStoppedDraft(draft) {
	const normalized = normalizedDraft(draft)
	if (!normalized?.localId || !normalized.stoppedAt) return false
	const next = readAll().filter(item => item.idempotencyKey !== normalized.idempotencyKey)
	next.push(normalized)
	writeAll(next)
	return true
}

export function findAiConversationStoppedDraft(conversationPublicId = null) {
	const matches = readAll().filter(item =>
		(item.conversationPublicId || null) === (conversationPublicId || null))
	return matches.length ? matches[matches.length - 1] : null
}

export function listAiConversationStoppedDrafts() {
	return Object.freeze(readAll().map(normalizedDraft).filter(Boolean))
}

export function removeAiConversationStoppedDraft(idempotencyKey) {
	const key = text(idempotencyKey, 64)
	writeAll(readAll().filter(item => item.idempotencyKey !== key))
}

export function clearAiConversationStoppedDrafts() {
	const value = storage()
	try { value?.removeItem(STORAGE_KEY) } catch (_) { /* 仅清理当前标签页临时数据。 */ }
}

export const AI_CONVERSATION_STOPPED_DRAFT_SCHEMA_VERSION = SCHEMA_VERSION

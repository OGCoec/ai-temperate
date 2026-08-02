const STORAGE_KEY = 'ait.user.ai.research.v1'
const SCHEMA_VERSION = 1
const MAX_RECORDS = 20
const MAX_STORAGE_BYTES = 2 * 1024 * 1024
const MAX_ACTIVITIES = 500
const MAX_SOURCES = 200
const MAX_SUMMARIES = 1000
const MAX_SUMMARY_CHARACTERS = 256 * 1024

function storage() {
	try { return globalThis.sessionStorage || null } catch (_) { return null }
}

function text(value, maximum) {
	const normalized = String(value || '')
	return normalized.length > maximum ? normalized.slice(0, maximum) : normalized
}

function safeSequence(value) {
	const normalized = Number(value)
	return Number.isSafeInteger(normalized) && normalized >= 0 ? normalized : null
}

function safeHttpUrl(value) {
	try {
		const parsed = new URL(text(value, 4096))
		return ['http:', 'https:'].includes(parsed.protocol) ? parsed.href : null
	} catch (_) {
		return null
	}
}

function normalizeActivity(value) {
	const sequence = safeSequence(value?.sequence)
	if (sequence == null) return null
	return {
		sequence,
		activityId: text(value.activityId, 128),
		phase: text(value.phase, 32),
		status: text(value.status, 32),
		query: value.query == null ? null : text(value.query, 1024),
		occurredAt: text(value.occurredAt, 64)
	}
}

function normalizeSource(value) {
	const sequence = safeSequence(value?.sequence)
	const url = safeHttpUrl(value?.url)
	if (sequence == null || !url) return null
	return {
		sequence,
		activityId: text(value.activityId, 128),
		sourceId: text(value.sourceId, 128),
		title: text(value.title, 512),
		url,
		domain: text(value.domain, 253),
		role: text(value.role, 32),
		occurredAt: text(value.occurredAt, 64)
	}
}

function normalizeSummary(value) {
	const sequence = safeSequence(value?.sequence)
	if (sequence == null) return null
	return {
		sequence,
		activityId: text(value.activityId, 128),
		textDelta: text(value.textDelta, 16384),
		occurredAt: text(value.occurredAt, 64)
	}
}

function normalizeRecord(value) {
	if (!value || value.schemaVersion !== SCHEMA_VERSION) return null
	const idempotencyKey = text(value.idempotencyKey, 64)
	if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(idempotencyKey)) return null
	return {
		schemaVersion: SCHEMA_VERSION,
		conversationPublicId: value.conversationPublicId
			? text(value.conversationPublicId, 22) : null,
		localId: text(value.localId, 128),
		idempotencyKey,
		webSearchMode: ['OFF', 'AUTO', 'REQUIRED'].includes(value.webSearchMode)
			? value.webSearchMode : 'OFF',
		activities: (Array.isArray(value.activities) ? value.activities : [])
			.map(normalizeActivity).filter(Boolean).slice(-MAX_ACTIVITIES),
		sources: (Array.isArray(value.sources) ? value.sources : [])
			.map(normalizeSource).filter(Boolean).slice(-MAX_SOURCES),
		reasoningSummaries: boundedSummaries(
			(Array.isArray(value.reasoningSummaries)
				? value.reasoningSummaries : [])
				.map(normalizeSummary).filter(Boolean).slice(-MAX_SUMMARIES)),
		updatedAt: text(value.updatedAt, 64),
		terminalState: text(value.terminalState || 'ACTIVE', 32)
	}
}

function boundedSummaries(values) {
	const next = [...values]
	let characters = next.reduce((total, item) =>
		total + item.textDelta.length, 0)
	while (characters > MAX_SUMMARY_CHARACTERS && next.length) {
		characters -= next.shift().textDelta.length
	}
	return next
}

function readAll() {
	try {
		const parsed = JSON.parse(storage()?.getItem(STORAGE_KEY) || '[]')
		return (Array.isArray(parsed) ? parsed : [])
			.map(normalizeRecord).filter(Boolean)
	} catch (_) {
		return []
	}
}

function utf8Bytes(value) {
	try { return new TextEncoder().encode(value).length } catch (_) {
		return unescape(encodeURIComponent(value)).length
	}
}

function writeAll(records) {
	const target = storage()
	if (!target) return false
	const next = records.slice(-MAX_RECORDS)
	let encoded = JSON.stringify(next)
	while (utf8Bytes(encoded) > MAX_STORAGE_BYTES && next.length > 1) {
		const terminalIndex = next.findIndex(item => item.terminalState !== 'ACTIVE')
		next.splice(terminalIndex >= 0 ? terminalIndex : 0, 1)
		encoded = JSON.stringify(next)
	}
	try {
		if (utf8Bytes(encoded) > MAX_STORAGE_BYTES) return false
		target.setItem(STORAGE_KEY, encoded)
		return true
	} catch (_) {
		// 当前标签页存储不可用时只关闭恢复能力，实时 SSE 和正文显示继续运行。
		return false
	}
}

function persistRecord(record) {
	const normalized = normalizeRecord(record)
	if (!normalized) return false
	const next = readAll().filter(item =>
		item.idempotencyKey !== normalized.idempotencyKey)
	next.push(normalized)
	next.sort((left, right) => String(left.updatedAt)
		.localeCompare(String(right.updatedAt)))
	return writeAll(next)
}

export function createAiConversationResearchSession(initial) {
	const record = normalizeRecord({
		schemaVersion: SCHEMA_VERSION,
		conversationPublicId: initial?.conversationPublicId || null,
		localId: initial?.localId,
		idempotencyKey: initial?.idempotencyKey,
		webSearchMode: initial?.webSearchMode || 'OFF',
		activities: [],
		sources: [],
		reasoningSummaries: [],
		updatedAt: new Date().toISOString(),
		terminalState: 'ACTIVE'
	})
	if (!record) throw new Error('Invalid AI conversation research session')
	let lastSequence = 0
	let immediateQueued = false
	let summaryTimer = null
	const sourceKeys = new Set()

	function touch() { record.updatedAt = new Date().toISOString() }
	function accepts(value) {
		const sequence = safeSequence(value?.sequence)
		if (sequence == null || sequence <= lastSequence) return false
		lastSequence = sequence
		return true
	}
	function flush() {
		immediateQueued = false
		if (summaryTimer != null) {
			clearTimeout(summaryTimer)
			summaryTimer = null
		}
		return persistRecord(record)
	}
	function queueImmediate() {
		if (immediateQueued) return
		immediateQueued = true
		Promise.resolve().then(flush)
	}
	function queueSummary() {
		if (summaryTimer != null) return
		summaryTimer = setTimeout(flush, 500)
	}
	function snapshot() {
		return JSON.parse(JSON.stringify(record))
	}

	return Object.freeze({
		bindConversation(conversationPublicId) {
			record.conversationPublicId = conversationPublicId
				? text(conversationPublicId, 22) : null
			touch(); queueImmediate()
		},
		appendActivity(value) {
			const normalized = normalizeActivity(value)
			if (!normalized || !accepts(normalized)) return false
			record.activities.push(normalized)
			record.activities = record.activities.slice(-MAX_ACTIVITIES)
			touch(); queueImmediate(); return true
		},
		appendSource(value) {
			const normalized = normalizeSource(value)
			if (!normalized || !accepts(normalized)) return false
			const key = `${normalized.role}\n${normalized.url}`
			if (sourceKeys.has(key)) return false
			sourceKeys.add(key)
			record.sources.push(normalized)
			record.sources = record.sources.slice(-MAX_SOURCES)
			touch(); queueImmediate(); return true
		},
		appendReasoningSummary(value) {
			const normalized = normalizeSummary(value)
			if (!normalized || !accepts(normalized)) return false
			record.reasoningSummaries.push(normalized)
			record.reasoningSummaries = boundedSummaries(
				record.reasoningSummaries.slice(-MAX_SUMMARIES))
			touch(); queueSummary(); return true
		},
		markTerminal(terminalState) {
			record.terminalState = text(terminalState || 'COMPLETED', 32)
			touch(); return flush()
		},
		flush,
		close: flush,
		snapshot
	})
}

export function findAiConversationResearchSession({
	localId = '', idempotencyKey = '', conversationPublicId = null
} = {}) {
	const records = readAll().filter(item => {
		if (idempotencyKey && item.idempotencyKey === idempotencyKey) return true
		if (localId && item.localId === localId) return true
		return conversationPublicId != null
			&& item.conversationPublicId === conversationPublicId
	})
	return records.length ? records[records.length - 1] : null
}

export function removeAiConversationResearchSession(idempotencyKey) {
	writeAll(readAll().filter(item => item.idempotencyKey !== idempotencyKey))
}

export function clearAiConversationResearchSessions() {
	try { storage()?.removeItem(STORAGE_KEY) } catch (_) {}
}

export const AI_CONVERSATION_RESEARCH_SCHEMA_VERSION = SCHEMA_VERSION

const SAFE_PHASES = new Set([
	'CLIENT_STREAM_CREATED', 'CLIENT_FETCH_SENT', 'CLIENT_RESPONSE_HEADERS',
	'CLIENT_SSE_ACCEPTED', 'CLIENT_FIRST_DELTA', 'CLIENT_STOP_REQUESTED',
	'CLIENT_ABORT_CALLED', 'CLIENT_TERMINAL_RENDERED',
	'PROFILE_REFRESH_STARTED', 'PROFILE_REFRESH_COMPLETED',
	'PROFILE_REFRESH_FAILED', 'PROFILE_QUOTA_CHANGED'
])
const SAFE_CANCEL_REASONS = new Set([
	'USER_STOP', 'PAGE_HIDDEN', 'PAGE_UNLOAD', 'COMPONENT_UNMOUNT'
])
const SAFE_OUTCOMES = new Set([
	'COMPLETE', 'SSE_ERROR', 'TRANSPORT_ERROR', 'CANCEL', 'UNMOUNT', 'FAILED'
])
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const SAFE_PUBLIC_ID = /^[A-Za-z0-9_-]{1,64}$/

let latestLifecycle = null

const NOOP_DIAGNOSTICS = Object.freeze({
	clientRequestId: 'unavailable',
	bindServerTraceId() {},
	bindUsagePublicId() {},
	reportedUsageObserved() {},
	observeVisibleOutput() {},
	record() { return false },
	stopRequested() {},
	abortCalled() {},
	finish() {},
	snapshot() { return null }
})

function buildDiagnosticsEnabled() {
	try {
		if (typeof __AI_CONVERSATION_LIFECYCLE_DIAGNOSTICS_ENABLED__ !== 'undefined') {
			return Boolean(__AI_CONVERSATION_LIFECYCLE_DIAGNOSTICS_ENABLED__)
		}
	} catch (_) {}
	return globalThis.__AI_CONVERSATION_LIFECYCLE_DIAGNOSTICS_ENABLED__ === true
}

function defaultNow() {
	return typeof globalThis.performance?.now === 'function'
		? globalThis.performance.now()
		: Date.now()
}

function defaultUuid() {
	if (typeof globalThis.crypto?.randomUUID === 'function') {
		return globalThis.crypto.randomUUID()
	}
	if (typeof globalThis.crypto?.getRandomValues !== 'function') {
		return 'unavailable'
	}
	const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16))
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0'))
	return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}`
		+ `-${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}`
		+ `-${hex.slice(10).join('')}`
}

function defaultSink(entry) {
	globalThis.console?.debug?.('[ai-conversation-lifecycle]', entry)
}

function safeUuid(value) {
	const normalized = String(value || '').trim().toLowerCase()
	return UUID_V4.test(normalized) ? normalized : 'unavailable'
}

function safePublicId(value) {
	const normalized = String(value || '').trim()
	return SAFE_PUBLIC_ID.test(normalized) ? normalized : 'unavailable'
}

function safeCount(value) {
	const number = Number(value)
	return Number.isSafeInteger(number) && number >= 0 ? number : 0
}

/**
 * 创建只输出固定低敏字段的前端生命周期诊断器，关闭时复用无状态空实现。
 */
export function createAiConversationLifecycleDiagnostics(options = {}) {
	const enabled = options.enabled === undefined
		? buildDiagnosticsEnabled()
		: Boolean(options.enabled)
	if (!enabled) return NOOP_DIAGNOSTICS

	const now = typeof options.now === 'function' ? options.now : defaultNow
	const uuid = typeof options.uuid === 'function' ? options.uuid : defaultUuid
	const sink = typeof options.sink === 'function' ? options.sink : defaultSink
	const startedAt = now()
	const clientRequestId = safeUuid(uuid())
	let traceId = 'unavailable'
	let usagePublicId = 'unavailable'
	let stopRecorded = false
	let abortRecorded = false
	let terminal = false
	let hasVisibleOutput = false
	let hasReportedUsage = false
	let emittedTextCharacters = 0

	function emit(phase, details = {}) {
		if (!SAFE_PHASES.has(phase)) return false
		try {
			sink(Object.freeze({
				event: 'ai_conversation_lifecycle',
				traceId,
				clientRequestId,
				usagePublicId,
				phase,
				outcome: SAFE_OUTCOMES.has(details.outcome)
					? details.outcome : 'unavailable',
				cancelReason: SAFE_CANCEL_REASONS.has(details.cancelReason)
					? details.cancelReason : 'unavailable',
				hasVisibleOutput: Boolean(details.hasVisibleOutput),
				hasReportedUsage: Boolean(details.hasReportedUsage),
				emittedTextCharacters: safeCount(details.emittedTextCharacters),
				statusCode: Number.isInteger(details.statusCode)
					? details.statusCode : -1,
				contentType: ['text/event-stream', 'application/json'].includes(
					details.contentType)
					? details.contentType : 'other',
				quotaChanged: typeof details.quotaChanged === 'boolean'
					? details.quotaChanged : 'unavailable',
				elapsedMs: Math.max(0, now() - startedAt)
			}))
			return true
		} catch (_) {
			// 诊断输出失败不能改变流读取、取消或页面展示。
			return false
		}
	}

	const diagnostics = Object.freeze({
		clientRequestId,
		bindServerTraceId(value) {
			traceId = safeUuid(value)
		},
		bindUsagePublicId(value) {
			usagePublicId = safePublicId(value)
		},
		reportedUsageObserved() {
			hasReportedUsage = true
		},
		observeVisibleOutput(characters) {
			const count = safeCount(characters)
			if (count > 0) hasVisibleOutput = true
			emittedTextCharacters += count
		},
		record(phase, details = {}) {
			if (terminal) return false
			return emit(phase, {
				...details,
				hasVisibleOutput,
				hasReportedUsage,
				emittedTextCharacters
			})
		},
		stopRequested(reason, details = {}) {
			if (terminal || stopRecorded) return
			stopRecorded = true
			hasVisibleOutput = hasVisibleOutput
				|| Boolean(details.hasVisibleOutput)
			emittedTextCharacters = Math.max(
				emittedTextCharacters,
				safeCount(details.emittedTextCharacters)
			)
			emit('CLIENT_STOP_REQUESTED', {
				cancelReason: reason,
				hasVisibleOutput,
				hasReportedUsage,
				emittedTextCharacters
			})
		},
		abortCalled() {
			if (terminal || abortRecorded) return
			abortRecorded = true
			emit('CLIENT_ABORT_CALLED', {
				hasVisibleOutput,
				hasReportedUsage,
				emittedTextCharacters
			})
		},
		finish(outcome = 'FAILED', details = {}) {
			if (terminal) return
			hasVisibleOutput = hasVisibleOutput
				|| Boolean(details.hasVisibleOutput)
			emittedTextCharacters = Math.max(
				emittedTextCharacters,
				safeCount(details.emittedTextCharacters)
			)
			hasReportedUsage = hasReportedUsage
				|| Boolean(details.hasReportedUsage)
			terminal = true
			emit('CLIENT_TERMINAL_RENDERED', {
				outcome,
				hasVisibleOutput,
				hasReportedUsage,
				emittedTextCharacters
			})
			latestLifecycle = { emit }
		},
		snapshot() {
			return Object.freeze({
				clientRequestId,
				traceId,
				usagePublicId,
				terminal,
				hasVisibleOutput,
				hasReportedUsage,
				emittedTextCharacters
			})
		}
	})
	latestLifecycle = { emit }
	return diagnostics
}

/**
 * 把后续个人资料读取关联到最近一次 AI 生命周期，只记录额度是否变化而不记录额度值。
 */
export function recordAiConversationProfileRefresh(phase, details = {}) {
	return latestLifecycle?.emit?.(phase, {
		quotaChanged: details.quotaChanged
	}) || false
}

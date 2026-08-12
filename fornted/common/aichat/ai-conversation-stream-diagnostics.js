const BOUNDARIES = Object.freeze([
	'BROWSER_READ',
	'BROWSER_SSE_PARSED',
	'FRONTEND_RENDERED'
])
const BOUNDARY_SET = new Set(BOUNDARIES)
const EVENT_TYPES = new Set([
	'HEADERS', 'BYTES', 'accepted', 'heartbeat', 'delta',
	'activity', 'source', 'reasoning_summary',
	'completed', 'error', 'message', 'unknown'
])
const SEQUENCED_EVENT_TYPES = new Set([
	'accepted', 'delta', 'activity', 'source', 'reasoning_summary',
	'completed', 'error'
])

const NOOP_DIAGNOSTICS = Object.freeze({
	bindUsagePublicId() {},
	bindGenerationPublicId() {},
	bindTraceId() {},
	record() { return false },
	finish() {},
	snapshot() { return null }
})

function buildDiagnosticsEnabled() {
	try {
		if (typeof __AI_CONVERSATION_STREAM_DIAGNOSTICS_ENABLED__ !== 'undefined') {
			return Boolean(__AI_CONVERSATION_STREAM_DIAGNOSTICS_ENABLED__)
		}
	} catch (_) {}
	return globalThis.__AI_CONVERSATION_STREAM_DIAGNOSTICS_ENABLED__ === true
}

function defaultNow() {
	return typeof globalThis.performance?.now === 'function'
		? globalThis.performance.now()
		: Date.now()
}

function defaultSink(entry) {
	globalThis.console?.debug?.('[ai-stream-timing]', entry)
}

function nonNegative(value) {
	const number = Number(value || 0)
	return Number.isFinite(number) && number > 0 ? number : 0
}

function safeUsagePublicId(value) {
	const normalized = String(value || '')
	return /^[A-Za-z0-9_-]{1,64}$/.test(normalized)
		? normalized
		: 'unavailable'
}

function safeGenerationPublicId(value) {
	const normalized = String(value || '')
	return /^[A-Za-z0-9_-]{22}$/.test(normalized)
		? normalized
		: 'unavailable'
}

function safeTraceId(value) {
	const normalized = String(value || '').toLowerCase()
	return /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(normalized)
		? normalized
		: 'unavailable'
}

function safeEventType(value) {
	const normalized = String(value || 'unknown')
	return EVENT_TYPES.has(normalized) ? normalized : 'unknown'
}

function safeStatusCode(value) {
	const normalized = Number(value)
	return Number.isInteger(normalized) && normalized >= 100 && normalized <= 599
		? normalized
		: -1
}

function safeContentType(value) {
	const normalized = String(value || '').split(';', 1)[0].trim().toLowerCase()
	return normalized === 'text/event-stream' || normalized === 'application/json'
		? normalized
		: 'other'
}

function safeDeltaSequence(value) {
	const normalized = Number(value)
	return Number.isSafeInteger(normalized) && normalized > 0
		? normalized
		: null
}

/**
 * 创建仅记录字节数、事件数和耗时的跨平台流式诊断；关闭时复用无状态空实现。
 */
export function createAiConversationStreamDiagnostics(options = {}) {
	const enabled = options.enabled === undefined
		? buildDiagnosticsEnabled()
		: Boolean(options.enabled)
	if (!enabled) return NOOP_DIAGNOSTICS

	const now = typeof options.now === 'function' ? options.now : defaultNow
	const sink = typeof options.sink === 'function' ? options.sink : defaultSink
	const onSummary = typeof options.onSummary === 'function' ? options.onSummary : null
	const windowMs = Math.max(100, Number(options.windowMs || 1000))
	const logEveryChunks = Math.max(1, Number(options.logEveryChunks || 100))
	const startedAt = now()
	let usagePublicId = 'unavailable'
	let generationPublicId = 'unavailable'
	let traceId = 'unavailable'
	let terminal = false
	let responseHeadersAt = null
	let firstByteAt = null
	let firstHeartbeatAt = null
	let firstDeltaAt = null
	let completedAt = null
	let lastNetworkByteAt = null
	let responseStatusCode = -1
	let responseContentType = 'other'
	let lastDeltaSequence = null
	let deltaSequenceGapCount = 0
	const pendingEntries = []
	const states = Object.fromEntries(BOUNDARIES.map(boundary => [boundary, {
		boundary,
		windowStartedAt: startedAt,
		windowChunks: 0,
		windowBytes: 0,
		windowTextCharacters: 0,
		totalChunks: 0,
		totalBytes: 0,
		totalTextCharacters: 0,
		lastAt: startedAt,
		maximumGapMs: 0
	}]))

	function emit(entry) {
		if (usagePublicId === 'unavailable') {
			// accepted 通常位于第一个网络块；短暂暂存纯时序元数据，绑定 Usage 后再输出以便跨层合并。
			if (pendingEntries.length < 32) pendingEntries.push(entry)
			return
		}
		const safeEntry = Object.freeze({
			...entry,
			usagePublicId
		})
		try {
			sink(safeEntry)
		} catch (_) {
			// 诊断输出失败不能改变流读取、解析或页面渲染。
		}
		if (safeEntry.event === 'ai_stream_client_timing_summary' && onSummary) {
			try { onSummary(safeEntry) } catch (_) {}
		}
	}

	function flushPendingEntries() {
		if (!pendingEntries.length) return
		const entries = pendingEntries.splice(0)
		entries.forEach(entry => emit(entry))
	}

	function flushWindow(state, timestamp, terminalWindow = false) {
		if (!state.windowChunks) return
		emit({
			event: 'ai_stream_client_timing_window',
			boundary: state.boundary,
			elapsedMs: Math.max(0, timestamp - startedAt),
			windowMs: Math.max(0, timestamp - state.windowStartedAt),
			chunkCount: state.windowChunks,
			byteCount: state.windowBytes,
			textCharacters: state.windowTextCharacters,
			maximumGapMs: state.maximumGapMs,
			terminal: terminalWindow
		})
		state.windowStartedAt = timestamp
		state.windowChunks = 0
		state.windowBytes = 0
		state.windowTextCharacters = 0
		state.maximumGapMs = 0
	}

	function record(boundary, details = {}) {
		if (terminal || !BOUNDARY_SET.has(boundary)) return false
		const timestamp = now()
		const state = states[boundary]
		const byteCount = nonNegative(details.byteCount)
		const textCharacters = nonNegative(details.textCharacters)
		const eventType = safeEventType(details.eventType)
		const sequence = safeDeltaSequence(details.sequence)
		const gapMs = Math.max(0, timestamp - state.lastAt)
		state.lastAt = timestamp
		state.maximumGapMs = Math.max(state.maximumGapMs, gapMs)
		state.windowChunks += 1
		state.windowBytes += byteCount
		state.windowTextCharacters += textCharacters
		state.totalChunks += 1
		state.totalBytes += byteCount
		state.totalTextCharacters += textCharacters
		if (boundary === 'BROWSER_READ' && eventType === 'HEADERS'
			&& responseHeadersAt === null) {
			responseHeadersAt = timestamp
			responseStatusCode = safeStatusCode(details.statusCode)
			responseContentType = safeContentType(details.contentType)
		}

		if (boundary === 'BROWSER_READ' && eventType === 'BYTES'
			&& byteCount > 0 && firstByteAt === null) {
			firstByteAt = timestamp
			lastNetworkByteAt = timestamp
			emit({
				event: 'ai_stream_client_timing_first_byte',
				boundary,
				elapsedMs: Math.max(0, timestamp - startedAt),
				byteCount
			})
		}
		if (boundary === 'BROWSER_READ' && eventType === 'BYTES' && byteCount > 0) {
			lastNetworkByteAt = timestamp
		}
		if (boundary === 'BROWSER_SSE_PARSED' && eventType === 'heartbeat'
			&& firstHeartbeatAt === null) firstHeartbeatAt = timestamp
		if (boundary === 'BROWSER_SSE_PARSED' && eventType === 'delta'
			&& textCharacters > 0 && firstDeltaAt === null) firstDeltaAt = timestamp
		if (boundary === 'BROWSER_SSE_PARSED'
			&& SEQUENCED_EVENT_TYPES.has(eventType)
			&& sequence !== null) {
			if (lastDeltaSequence !== null && sequence !== lastDeltaSequence + 1) {
				deltaSequenceGapCount += 1
			}
			lastDeltaSequence = sequence
		}
		if (boundary === 'BROWSER_SSE_PARSED'
			&& (eventType === 'completed' || eventType === 'error')) {
			completedAt = timestamp
		}
		if (state.windowChunks >= logEveryChunks
			|| timestamp - state.windowStartedAt >= windowMs) {
			flushWindow(state, timestamp)
		}
		return true
	}

	function finish(outcome = 'UNKNOWN') {
		if (terminal) return
		terminal = true
		const timestamp = now()
		Object.values(states).forEach(state =>
			flushWindow(state, timestamp, true))
		emit({
			event: 'ai_stream_client_timing_summary',
			generationPublicId,
			traceId,
			outcome: String(outcome || 'UNKNOWN').slice(0, 32),
			elapsedMs: Math.max(0, timestamp - startedAt),
			responseHeadersMs: responseHeadersAt === null
				? -1 : responseHeadersAt - startedAt,
			responseStatusCode,
			responseContentType,
			firstByteMs: firstByteAt === null ? -1 : firstByteAt - startedAt,
			lastNetworkByteMs: lastNetworkByteAt === null
				? -1 : lastNetworkByteAt - startedAt,
			firstHeartbeatMs: firstHeartbeatAt === null
				? -1 : firstHeartbeatAt - startedAt,
			firstDeltaMs: firstDeltaAt === null ? -1 : firstDeltaAt - startedAt,
			completedMs: completedAt === null ? -1 : completedAt - startedAt,
			networkReads: states.BROWSER_READ.totalChunks,
			networkBytes: states.BROWSER_READ.totalBytes,
			parsedEvents: states.BROWSER_SSE_PARSED.totalChunks,
			lastDeltaSequence: lastDeltaSequence === null ? -1 : lastDeltaSequence,
			deltaSequenceGapCount,
			renderedUpdates: states.FRONTEND_RENDERED.totalChunks,
			renderedTextCharacters: states.FRONTEND_RENDERED.totalTextCharacters
		})
		if (usagePublicId === 'unavailable') {
			// 未收到 accepted 的失败仍输出安全诊断，但明确保持 unavailable，避免伪造关联 ID。
			usagePublicId = 'unavailable-terminal'
		}
		flushPendingEntries()
	}

	return Object.freeze({
		bindUsagePublicId(value) {
			usagePublicId = safeUsagePublicId(value)
			if (usagePublicId !== 'unavailable') flushPendingEntries()
		},
		bindGenerationPublicId(value) {
			generationPublicId = safeGenerationPublicId(value)
		},
		bindTraceId(value) {
			traceId = safeTraceId(value)
		},
		record,
		finish,
		snapshot() {
			return Object.freeze({
				usagePublicId,
				generationPublicId,
				traceId,
				terminal,
				startedAt,
				responseHeadersAt,
				firstByteAt,
				firstHeartbeatAt,
				firstDeltaAt,
				completedAt,
				responseStatusCode,
				responseContentType,
				lastNetworkByteAt,
				lastDeltaSequence,
				deltaSequenceGapCount
			})
		}
	})
}

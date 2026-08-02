import { authorizedRequest } from '../auth/http-client.js'
import { clientPlatform } from '../auth/config.js'

const GENERATION_ID_PATTERN = /^[A-Za-z0-9_-]{22}$/
const USAGE_ID_PATTERN = /^[A-Za-z0-9_-]{1,64}$/
const TRACE_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const OUTCOME_PATTERN = /^[A-Z_]{1,32}$/

function boundedInteger(value, minimum, maximum, fallback = -1) {
	const parsed = Number(value)
	return Number.isSafeInteger(parsed) && parsed >= minimum && parsed <= maximum
		? parsed
		: fallback
}

function optionalTraceId(value) {
	const normalized = String(value || '').toLowerCase()
	return TRACE_ID_PATTERN.test(normalized) ? normalized : undefined
}

/**
 * 回传一次已结束流的安全时间摘要。请求本身不参与页面主流程，认证、CSRF 或网络失败
 * 都被吞掉，避免诊断上传反过来影响用户已经完成的对话。
 */
export function reportAiConversationStreamDiagnostics(summary) {
	if (clientPlatform() !== 'H5') return
	const generationPublicId = String(summary?.generationPublicId || '')
	const usagePublicId = String(summary?.usagePublicId || '')
	if (!GENERATION_ID_PATTERN.test(generationPublicId)
		|| !USAGE_ID_PATTERN.test(usagePublicId)
		|| usagePublicId.startsWith('unavailable')) return
	const payload = {
		usagePublicId,
		traceId: optionalTraceId(summary.traceId),
		outcome: OUTCOME_PATTERN.test(String(summary.outcome || ''))
			? summary.outcome : 'UNKNOWN',
		responseHeadersMs: boundedInteger(summary.responseHeadersMs, -1, 3_600_000),
		firstByteMs: boundedInteger(summary.firstByteMs, -1, 3_600_000),
		lastNetworkByteMs: boundedInteger(summary.lastNetworkByteMs, -1, 3_600_000),
		firstHeartbeatMs: boundedInteger(summary.firstHeartbeatMs, -1, 3_600_000),
		firstDeltaMs: boundedInteger(summary.firstDeltaMs, -1, 3_600_000),
		completedMs: boundedInteger(summary.completedMs, -1, 3_600_000),
		networkReads: boundedInteger(summary.networkReads, 0, 1_000_000, 0),
		networkBytes: boundedInteger(summary.networkBytes, 0, 100 * 1024 * 1024, 0),
		parsedEvents: boundedInteger(summary.parsedEvents, 0, 1_000_000, 0),
		renderedUpdates: boundedInteger(summary.renderedUpdates, 0, 1_000_000, 0),
		renderedTextCharacters: boundedInteger(
			summary.renderedTextCharacters, 0, 10_000_000, 0),
		lastDeltaSequence: boundedInteger(summary.lastDeltaSequence, -1, 1_000_000_000),
		deltaSequenceGapCount: boundedInteger(summary.deltaSequenceGapCount, 0, 1_000_000, 0)
	}
	void authorizedRequest(
		`/api/ai/conversations/generations/${encodeURIComponent(generationPublicId)}/stream-diagnostics`,
		{
			method: 'POST',
			data: payload,
			timeout: 3_000,
			preserveSessionOnFailure: true
		}
	).catch(() => undefined)
}

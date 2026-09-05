const LOG_PREFIX = '[ait-webrtc]'
const MAX_SAFE_DIAGNOSTIC_NUMBER = 86400000
const MAX_NAME_LENGTH = 64
const NAME_PATTERN = /^[A-Za-z0-9:_-]+$/
const CODE_PATTERN = /^[A-Za-z0-9_-]+$/

const NAME_FIELDS = new Set([
	'probeRunId'
])
const CODE_FIELDS = new Set([
	'reason',
	'mode',
	'state',
	'errorCode'
])
const NUMBER_FIELDS = new Set([
	'elapsedMs',
	'stunCount',
	'timeoutMillis',
	'remainingMillis',
	'iceTimeoutMillis',
	'candidateCount',
	'hostCount',
	'srflxCount',
	'relayCount',
	'prflxCount',
	'unknownCount',
	'acceptedCount',
	'acceptedHostCount',
	'acceptedSrflxCount',
	'ignoredRelayCount',
	'rejectedNonPublicCount',
	'rejectedCount',
	'ipv4Count',
	'ipv6Count',
	'callbackCount',
	'urlLength',
	'ivLength',
	'payloadLength',
	'plaintextLength'
])
const BOOLEAN_FIELDS = new Set([
	'retryable',
	'webRtcStatus',
	'timerActive',
	'hasChannel',
	'hasIv',
	'hasPayload'
])
const ERROR_STAGES = new Set([
	'webview_setup_failed',
	'decrypt_exception',
	'peer_connection_failed',
	'encryption_failed',
	'verification_exception'
])
const WARNING_STAGE_PATTERN = /(failed|timeout|unavailable|invalid|mismatch|error|closed|closed_early|exhausted)$/

/**
 * 创建仅在开发构建生效的WebRTC阶段日志器，并通过字段白名单阻止敏感运行数据进入控制台。
 */
export function createWebRtcDiagnosticLogger(scope, enabled = false) {
	const safeScope = safeName(scope)
	return (stage, fields = {}) => {
		if (enabled !== true || !safeScope) return
		const safeStage = safeName(stage)
		if (!safeStage) return
		const event = sanitizeEvent(safeScope, safeStage, fields)
		const level = diagnosticLevel(safeStage)
		try {
			if (typeof console === 'undefined' || typeof console[level] !== 'function') return
			console[level](`${LOG_PREFIX} ${JSON.stringify(event)}`)
		} catch (_) {
			// 诊断输出失败不得改变认证或WebRTC探针的控制流。
		}
	}
}

function sanitizeEvent(scope, stage, fields) {
	const event = { scope, stage }
	if (!fields || typeof fields !== 'object') return event
	for (const field of NAME_FIELDS) {
		const value = safeName(fields[field])
		if (value) event[field] = value
	}
	for (const field of CODE_FIELDS) {
		const value = safeCode(fields[field])
		if (value) event[field] = value
	}
	for (const field of NUMBER_FIELDS) {
		const value = safeNumber(fields[field])
		if (value !== null) event[field] = value
	}
	for (const field of BOOLEAN_FIELDS) {
		if (typeof fields[field] === 'boolean') event[field] = fields[field]
	}
	const sourceIndexes = safeSourceIndexes(fields.sourceIndexes)
	if (sourceIndexes.length) event.sourceIndexes = sourceIndexes
	return event
}

function safeName(value) {
	if (typeof value !== 'string') return ''
	const normalized = value.slice(0, MAX_NAME_LENGTH)
	return NAME_PATTERN.test(normalized) ? normalized : ''
}

function safeCode(value) {
	if (typeof value !== 'string') return ''
	const normalized = value.slice(0, MAX_NAME_LENGTH)
	return CODE_PATTERN.test(normalized) ? normalized : ''
}

function safeNumber(value) {
	return Number.isFinite(value) && value >= 0 && value <= MAX_SAFE_DIAGNOSTIC_NUMBER
		? Math.floor(value)
		: null
}

function safeSourceIndexes(value) {
	if (!Array.isArray(value)) return []
	return [...new Set(value.filter(item => Number.isInteger(item) && item >= 1 && item <= 4))]
		.sort((left, right) => left - right)
}

function diagnosticLevel(stage) {
	if (ERROR_STAGES.has(stage)) return 'error'
	return WARNING_STAGE_PATTERN.test(stage) ? 'warn' : 'info'
}

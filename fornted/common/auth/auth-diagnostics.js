const STORAGE_KEY = 'ait:auth:diagnostics:v1'
const CONSOLE_ENABLED_KEY = 'ait:auth:diagnostics:console'
const MAX_RECORDS = 500
const MAX_TEXT_LENGTH = 160
const CONSOLE_PREFIX = '[AIT_WEBRTC]'
const CONSOLE_STYLE = 'color:#37d39a;font-weight:700'
const SAFE_FIELD_NAMES = new Set([
	'acceptedCount',
	'acceptedHostCount',
	'acceptedSrflxCount',
	'activeTaskCount',
	'authReady',
	'candidateCount',
	'cancelReason',
	'classification',
	'clientRequestId',
	'deadlineRemainingMs',
	'documentVisibility',
	'durationMs',
	'errorCode',
	'finishReason',
	'generation',
	'hostCount',
	'ignoredRelayCount',
	'ipv4Count',
	'ipv6Count',
	'method',
	'mode',
	'outcome',
	'owner',
	'pageInstanceId',
	'pageState',
	'path',
	'pendingRemainingMs',
	'persisted',
	'phase',
	'preAuthEpoch',
	'preAuthReady',
	'probeBudgetMs',
	'probeDurationMs',
	'probeRunId',
	'queueMs',
	'reason',
	'rejectedNonPublicCount',
	'relayCount',
	'reportDispatched',
	'reportGraceMs',
	'requestEpoch',
	'retry',
	'retryable',
	'route',
	'serverTiming',
	'source',
	'srflxCount',
	'stage',
	'startDisposition',
	'status',
	'stunCount',
	'timeoutMs',
	'traceId',
	'triggerClientRequestId',
	'verificationState',
	'waiter',
	'webRtcGeneration',
	'webRtcStatus'
])

let sequence = 0
let pageInstanceId = createUuid()
let records = readStoredRecords()
sequence = records.reduce((maximum, record) => {
	const value = Number(record?.sequence)
	return Number.isSafeInteger(value) && value > maximum ? value : maximum
}, 0)
let consoleEnabledOverride = null
let currentWebRtcProbeRunId = ''
let persistTimer = null

applyConsoleQueryOverride()
syncDiagnosticBridge()

export function isAuthDiagnosticsEnabled() {
	return true
}

export function setAuthDiagnosticsEnabled() {
	// 兼容旧调用方，但有界脱敏环形日志属于常驻诊断能力，只允许单独关闭 Console 镜像。
	return true
}

export function isAuthDiagnosticsConsoleEnabled() {
	const value = readStorage(CONSOLE_ENABLED_KEY)
	if (value === 'true') return true
	if (value === 'false') return false
	return consoleEnabledOverride === true
}

export function setAuthDiagnosticsConsoleEnabled(enabled) {
	consoleEnabledOverride = enabled === true
	writeStorage(CONSOLE_ENABLED_KEY, consoleEnabledOverride ? 'true' : 'false')
	syncDiagnosticBridge()
	return isAuthDiagnosticsConsoleEnabled()
}

export function createAuthDiagnosticId() {
	return createUuid()
}

export function setCurrentAuthDiagnosticWebRtcProbeRunId(value) {
	currentWebRtcProbeRunId = validatedUuid(value)
	return currentWebRtcProbeRunId
}

export function currentAuthDiagnosticPageId() {
	return pageInstanceId
}

export function renewAuthDiagnosticPage(route = '', source = 'page_created') {
	pageInstanceId = createUuid()
	recordAuthDiagnosticEvent('PAGE_CREATED', { route, source })
	return pageInstanceId
}

export function recordAuthDiagnosticEvent(event, fields = {}) {
	sequence = sequence >= Number.MAX_SAFE_INTEGER ? 1 : sequence + 1
	const record = {
		schemaVersion: 1,
		sequence,
		event: safeCode(event, 'UNKNOWN_EVENT'),
		occurredAt: new Date().toISOString(),
		monotonicMs: roundedMillis(monotonicNow()),
		pageInstanceId
	}
	for (const [name, value] of Object.entries(fields || {})) {
		if (!SAFE_FIELD_NAMES.has(name) || value == null) continue
		const normalized = normalizeField(name, value)
		if (normalized !== undefined) record[name] = normalized
	}
	records.push(Object.freeze(record))
	if (records.length > MAX_RECORDS) records = records.slice(-MAX_RECORDS)
	persistRecords()
	mirrorRecordToConsole(record)
	return record
}

export function createAuthRequestDiagnostic(path, source = 'auth_http_client') {
	const diagnostic = {
		clientRequestId: createUuid(),
		pageInstanceId,
		path: safePath(path),
		source: safeCode(source, 'auth_http_client'),
		createdAt: monotonicNow(),
		sentAt: null
	}
	recordAuthDiagnosticEvent('REQUEST_CREATED', diagnostic)
	return diagnostic
}

export async function runAuthDiagnosticStage(diagnostic, stage, operation, fields = {}) {
	const normalizedStage = safeCode(stage, 'UNKNOWN')
	const startedAt = monotonicNow()
	recordAuthDiagnosticEvent(`${normalizedStage}_WAIT_STARTED`, {
		...requestFields(diagnostic),
		...fields,
		stage: normalizedStage
	})
	try {
		const result = await operation()
		recordAuthDiagnosticEvent(`${normalizedStage}_WAIT_COMPLETED`, {
			...requestFields(diagnostic),
			...fields,
			stage: normalizedStage,
			outcome: 'succeeded',
			durationMs: monotonicNow() - startedAt
		})
		return result
	} catch (error) {
		recordAuthDiagnosticEvent(`${normalizedStage}_WAIT_COMPLETED`, {
			...requestFields(diagnostic),
			...fields,
			stage: normalizedStage,
			outcome: 'failed',
			errorCode: error?.code || 'UNEXPECTED_ERROR',
			durationMs: monotonicNow() - startedAt
		})
		throw error
	}
}

export function authDiagnosticRequestHeaders(diagnostic, correlation = {}) {
	if (!diagnostic) return {}
	const probeRunId = validatedUuid(
		correlation?.probeRunId
		|| diagnostic?.probeRunId
		|| currentWebRtcProbeRunId)
	if (probeRunId) diagnostic.probeRunId = probeRunId
	const sentAt = monotonicNow()
	diagnostic.sentAt = sentAt
	const queueMs = boundedMillis(sentAt - Number(diagnostic.createdAt || sentAt))
	recordAuthDiagnosticEvent('NETWORK_REQUEST_SENT', {
		...requestFields(diagnostic),
		queueMs
	})
	const headers = {
		'X-AIT-Client-Request-Id': diagnostic.clientRequestId,
		'X-AIT-Page-Instance-Id': diagnostic.pageInstanceId,
		'X-AIT-Client-Queue-Ms': String(queueMs)
	}
	if (probeRunId) headers['X-AIT-WebRTC-Probe-Run-Id'] = probeRunId
	return headers
}

export function recordAuthDiagnosticResponse(diagnostic, response = {}) {
	if (diagnostic) diagnostic.completed = true
	const headers = response.header || response.headers || {}
	const status = Number(response.statusCode || response.status || 0)
	const errorCode = status >= 400
		? response.data?.code || `HTTP_${status || 0}`
		: ''
	recordAuthDiagnosticEvent('NETWORK_RESPONSE_RECEIVED', {
		...requestFields(diagnostic),
		status,
		errorCode,
		traceId: responseHeader(headers, 'X-Trace-Id'),
		serverTiming: responseHeader(headers, 'Server-Timing'),
		durationMs: diagnostic?.sentAt == null
			? 0
			: monotonicNow() - diagnostic.sentAt
	})
	recordAuthDiagnosticEvent('REQUEST_COMPLETED', {
		...requestFields(diagnostic),
		status,
		errorCode,
		outcome: status >= 200 && status < 300 ? 'succeeded' : 'rejected'
	})
}

export function recordAuthDiagnosticFailure(diagnostic, error) {
	if (diagnostic?.completed) return
	if (diagnostic) diagnostic.completed = true
	recordAuthDiagnosticEvent('REQUEST_REJECTED', {
		...requestFields(diagnostic),
		status: error?.statusCode || 0,
		errorCode: error?.code || 'NETWORK_ERROR',
		outcome: 'failed',
		durationMs: diagnostic?.sentAt == null
			? 0
			: monotonicNow() - diagnostic.sentAt
	})
}

export function exportAuthDiagnostics() {
	return Object.freeze({
		schemaVersion: 1,
		enabled: isAuthDiagnosticsEnabled(),
		pageInstanceId,
		consoleEnabled: isAuthDiagnosticsConsoleEnabled(),
		exportedAt: new Date().toISOString(),
		records: Object.freeze(records.map(record => Object.freeze({ ...record })))
	})
}

export function flushAuthDiagnostics() {
	flushPersistRecords()
}

export function clearAuthDiagnostics() {
	records = []
	sequence = 0
	if (persistTimer != null && typeof globalThis.clearTimeout === 'function') {
		globalThis.clearTimeout(persistTimer)
	}
	persistTimer = null
	removeStorage(STORAGE_KEY)
}

export function installH5AuthDiagnosticLifecycle() {
	if (typeof globalThis.window?.addEventListener !== 'function'
		|| globalThis.window.__aitAuthDiagnosticLifecycleInstalled === true) return false
	globalThis.window.__aitAuthDiagnosticLifecycleInstalled = true
	globalThis.window.addEventListener('pageshow', event => {
		recordAuthDiagnosticEvent('PAGE_PAGESHOW', {
			pageState: event?.persisted === true ? 'back_forward_cache' : 'active',
			source: 'window_pageshow'
		})
	})
	globalThis.window.addEventListener('pagehide', event => {
		recordAuthDiagnosticEvent('PAGE_PAGEHIDE', {
			pageState: event?.persisted === true ? 'back_forward_cache' : 'unloaded',
			source: 'window_pagehide'
		})
		flushPersistRecords()
	})
	globalThis.document?.addEventListener?.('visibilitychange', () => {
		recordAuthDiagnosticEvent('PAGE_VISIBILITY_CHANGED', {
			pageState: globalThis.document?.visibilityState || 'unknown',
			source: 'document_visibility'
		})
	})
	return true
}

function requestFields(diagnostic) {
	if (!diagnostic) return {}
	return {
		clientRequestId: diagnostic.clientRequestId,
		pageInstanceId: diagnostic.pageInstanceId,
		path: diagnostic.path,
		probeRunId: diagnostic.probeRunId,
		requestEpoch: diagnostic.requestEpoch,
		source: diagnostic.source
	}
}

function normalizeField(name, value) {
	if ([
		'authReady',
		'owner',
		'persisted',
		'preAuthReady',
		'reportDispatched',
		'retry',
		'retryable',
		'waiter',
		'webRtcStatus'
	].includes(name)) {
		return value === true
	}
	if (['durationMs', 'probeDurationMs', 'queueMs'].includes(name)) {
		return roundedMillis(value)
	}
	if ([
		'acceptedCount',
		'acceptedHostCount',
		'acceptedSrflxCount',
		'activeTaskCount',
		'candidateCount',
		'deadlineRemainingMs',
		'hostCount',
		'ignoredRelayCount',
		'ipv4Count',
		'ipv6Count',
		'pendingRemainingMs',
		'preAuthEpoch',
		'probeBudgetMs',
		'rejectedNonPublicCount',
		'relayCount',
		'reportGraceMs',
		'requestEpoch',
		'srflxCount',
		'status',
		'stunCount',
		'timeoutMs'
	].includes(name)) {
		const numeric = Number(value)
		return Number.isFinite(numeric) && numeric >= 0
			? Math.min(Math.trunc(numeric), Number.MAX_SAFE_INTEGER)
			: 0
	}
	if (name === 'path' || name === 'route') return safePath(value)
	if (name === 'serverTiming') {
		return safeText(value).replace(/[^A-Za-z0-9._;,= -]/g, '')
	}
	if (name === 'clientRequestId' || name === 'pageInstanceId'
		|| name === 'triggerClientRequestId') {
		return safeUuid(value)
	}
	if (name === 'probeRunId') return validatedUuid(value) || undefined
	return safeCode(value, 'unavailable')
}

function applyConsoleQueryOverride() {
	const search = String(
		globalThis.window?.location?.search
		|| globalThis.location?.search
		|| '')
	const match = search.match(/(?:^|[?&])aitAuthDiagnostics=(1|0)(?:&|$)/)
	if (!match) return
	consoleEnabledOverride = match[1] === '1'
	writeStorage(CONSOLE_ENABLED_KEY, consoleEnabledOverride ? 'true' : 'false')
}

function syncDiagnosticBridge() {
	const target = globalThis.window
	if (!target || typeof target !== 'object') return
	if (!isAuthDiagnosticsConsoleEnabled()) {
		try { delete target.__AIT_AUTH_DIAGNOSTICS__ } catch (_) {}
		return
	}
	const bridge = Object.freeze({
		export: exportAuthDiagnostics,
		clear: clearAuthDiagnostics,
		setConsoleEnabled: setAuthDiagnosticsConsoleEnabled
	})
	try {
		Object.defineProperty(target, '__AIT_AUTH_DIAGNOSTICS__', {
			configurable: true,
			enumerable: false,
			value: bridge,
			writable: false
		})
	} catch (_) {
		// 调试桥接失败只影响手工导出，不影响环形日志或认证请求。
	}
}

function mirrorRecordToConsole(record) {
	if (!isAuthDiagnosticsConsoleEnabled()) return
	try {
		if (typeof globalThis.console?.info !== 'function') return
		globalThis.console.info(`%c${CONSOLE_PREFIX}`, CONSOLE_STYLE, { ...record })
	} catch (_) {
		// Console 被禁用或代理异常时继续保留 sessionStorage 中的脱敏记录。
	}
}

function responseHeader(headers, expectedName) {
	const entry = Object.entries(headers || {})
		.find(([name]) => String(name).toLowerCase() === expectedName.toLowerCase())
	return entry ? safeText(entry[1]) : ''
}

function safePath(value) {
	const path = String(value || '').split(/[?#]/, 1)[0]
	if (!path.startsWith('/') || path.length > MAX_TEXT_LENGTH) return 'unavailable'
	return path.replace(/[^A-Za-z0-9._~!$&'()*+,;=:@%/-]/g, '_')
}

function safeCode(value, fallback) {
	const normalized = String(value || '')
		.replace(/[^A-Za-z0-9._:-]/g, '_')
		.slice(0, MAX_TEXT_LENGTH)
	return normalized || fallback
}

function safeText(value) {
	return String(value || '')
		.replace(/[\r\n]/g, '_')
		.slice(0, MAX_TEXT_LENGTH)
}

function safeUuid(value) {
	return validatedUuid(value) || createUuid()
}

function validatedUuid(value) {
	const normalized = String(value || '').toLowerCase()
	return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(normalized)
		? normalized
		: ''
}

function createUuid() {
	if (typeof globalThis.crypto?.randomUUID === 'function') {
		return globalThis.crypto.randomUUID()
	}
	const bytes = new Uint8Array(16)
	if (typeof globalThis.crypto?.getRandomValues === 'function') {
		globalThis.crypto.getRandomValues(bytes)
	} else {
		for (let index = 0; index < bytes.length; index += 1) {
			bytes[index] = Math.floor(Math.random() * 256)
		}
	}
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('')
	return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-` +
		`${hex.slice(16, 20)}-${hex.slice(20)}`
}

function monotonicNow() {
	return typeof globalThis.performance?.now === 'function'
		? globalThis.performance.now()
		: Date.now()
}

function boundedMillis(value) {
	return Math.min(86400000, Math.max(0, Math.round(Number(value) || 0)))
}

function roundedMillis(value) {
	return Math.round(Math.max(0, Number(value) || 0) * 10) / 10
}

function readStoredRecords() {
	try {
		const parsed = JSON.parse(readStorage(STORAGE_KEY) || '[]')
		return Array.isArray(parsed)
			? parsed.slice(-MAX_RECORDS)
				.map(sanitizeStoredRecord)
				.filter(Boolean)
			: []
	} catch (_) {
		return []
	}
}

function sanitizeStoredRecord(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) return null
	const sequenceValue = Number(value.sequence)
	const monotonicValue = Number(value.monotonicMs)
	const occurredAtValue = Date.parse(String(value.occurredAt || ''))
	const record = {
		schemaVersion: 1,
		sequence: Number.isSafeInteger(sequenceValue) && sequenceValue > 0
			? sequenceValue
			: 0,
		event: safeCode(value.event, 'UNKNOWN_EVENT'),
		occurredAt: Number.isFinite(occurredAtValue)
			? new Date(occurredAtValue).toISOString()
			: new Date(0).toISOString(),
		monotonicMs: Number.isFinite(monotonicValue) && monotonicValue >= 0
			? roundedMillis(monotonicValue)
			: 0,
		pageInstanceId: validatedUuid(value.pageInstanceId) || pageInstanceId
	}
	for (const [name, fieldValue] of Object.entries(value)) {
		if (!SAFE_FIELD_NAMES.has(name) || fieldValue == null) continue
		const normalized = normalizeField(name, fieldValue)
		if (normalized !== undefined) record[name] = normalized
	}
	return Object.freeze(record)
}

function persistRecords() {
	if (persistTimer != null) return
	if (typeof globalThis.setTimeout !== 'function') {
		flushPersistRecords()
		return
	}
	// sessionStorage 是同步 API；短延迟批量落盘避免诊断本身进入认证请求的前置排队时间。
	persistTimer = globalThis.setTimeout(flushPersistRecords, 25)
}

function flushPersistRecords() {
	persistTimer = null
	try {
		writeStorage(STORAGE_KEY, JSON.stringify(records))
	} catch (_) {
		// 浏览器禁用存储或配额不足时仍保留当前内存环形记录，不能影响认证请求。
	}
}

function readStorage(key) {
	try {
		return globalThis.sessionStorage?.getItem?.(key) ?? null
	} catch (_) {
		return null
	}
}

function writeStorage(key, value) {
	try {
		globalThis.sessionStorage?.setItem?.(key, value)
	} catch (_) {
		// 诊断开关和记录均为旁路能力，存储失败不得阻止页面继续运行。
	}
}

function removeStorage(key) {
	try {
		globalThis.sessionStorage?.removeItem?.(key)
	} catch (_) {
		// 清理失败不改变认证状态，下一次页面会话仍受有界条数保护。
	}
}

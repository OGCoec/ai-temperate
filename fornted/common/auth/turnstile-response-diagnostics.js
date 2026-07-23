let fallbackAttemptCounter = 0

function headerValue(headers, expectedName) {
	if (!headers || typeof headers !== 'object') return ''
	const expected = expectedName.toLowerCase()
	const entry = Object.entries(headers).find(([name]) => name.toLowerCase() === expected)
	if (!entry) return ''
	const value = Array.isArray(entry[1]) ? entry[1][0] : entry[1]
	return typeof value === 'string' ? value.trim() : String(value || '').trim()
}

function boundedIdentifier(value, pattern, maxLength = 128) {
	return value && value.length <= maxLength && pattern.test(value) ? value : ''
}

export function inspectAuthResponse(response = {}) {
	const contentType = headerValue(response.header, 'content-type').toLowerCase().slice(0, 128)
	const cfMitigated = headerValue(response.header, 'cf-mitigated').toLowerCase().slice(0, 32)
	const traceId = boundedIdentifier(
		headerValue(response.header, 'x-trace-id'),
		/^[A-Za-z0-9_-]+$/
	)
	const cfRay = boundedIdentifier(
		headerValue(response.header, 'cf-ray'),
		/^[A-Za-z0-9-]+$/
	)
	const statusCode = Number.isInteger(response.statusCode) ? response.statusCode : 0
	let classification = 'OTHER'
	if (cfMitigated === 'challenge' || contentType.includes('text/html')) {
		classification = 'EDGE_CHALLENGE'
	} else if (contentType.includes('application/json') || isJsonValue(response.data)) {
		classification = 'BACKEND_JSON'
	}
	return Object.freeze({
		statusCode,
		contentType,
		cfMitigated,
		traceId,
		cfRay,
		classification
	})
}

export function networkFailureDiagnostics() {
	return Object.freeze({
		statusCode: 0,
		contentType: '',
		cfMitigated: '',
		traceId: '',
		cfRay: '',
		classification: 'NETWORK_ERROR'
	})
}

export function applyDiagnosticsToError(error, diagnostics) {
	if (!error || !diagnostics) return error
	error.traceId = diagnostics.traceId
	error.cfRay = diagnostics.cfRay
	error.contentType = diagnostics.contentType
	error.cfMitigated = diagnostics.cfMitigated
	error.responseClassification = diagnostics.classification
	return error
}

export function createTurnstileAttemptId() {
	const randomUuid = globalThis.crypto?.randomUUID?.()
	if (typeof randomUuid === 'string' && /^[A-Za-z0-9_-]{8,80}$/.test(randomUuid)) {
		return randomUuid
	}
	fallbackAttemptCounter = (fallbackAttemptCounter + 1) % 1679616
	const timestamp = Date.now().toString(36)
	const counter = fallbackAttemptCounter.toString(36).padStart(4, '0')
	const random = Math.random().toString(36).slice(2, 14)
	return `attempt_${timestamp}_${counter}_${random}`.slice(0, 80)
}

function isJsonValue(value) {
	return value !== null && typeof value === 'object'
}

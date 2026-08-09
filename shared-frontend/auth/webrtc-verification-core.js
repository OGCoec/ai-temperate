export const WEBRTC_DEFAULT_TIMEOUT_MILLIS = 12000

export const WEBRTC_FAILURE_CODES = Object.freeze([
	'WEBRTC_IP_MISMATCH',
	'WEBRTC_VERIFICATION_FAILED',
	'WEBRTC_VERIFICATION_TIMEOUT'
])

export const WEBRTC_RETRY_CODES = Object.freeze([
	'WEBRTC_NETWORK_CHANGED',
	'WEBRTC_REPORT_STALE'
])

export const WEBRTC_PENDING_CODE = 'WEBRTC_VERIFICATION_PENDING'

export function webRtcTriggerFromHeaders(headers = {}) {
	const state = responseHeader(headers, 'X-AIT-WebRTC-State').toUpperCase()
	const generation = responseHeader(headers, 'X-AIT-WebRTC-Generation')
	if (!['REQUIRED', 'PENDING', 'VERIFIED', 'FAILED'].includes(state)
		|| !/^[1-9][0-9]{0,18}$/.test(generation)) return null
	return Object.freeze({ state, generation })
}

export function webRtcErrorFromResponse(response, fallbackMessage) {
	const data = response?.data && typeof response.data === 'object'
		? response.data
		: {}
	const error = new Error(data.message || fallbackMessage || 'WebRTC 网络校验失败。')
	error.code = data.code || `HTTP_${response?.statusCode || 0}`
	error.statusCode = response?.statusCode || 0
	error.webRtcStatus = data.webRtcStatus
	error.httpIp = typeof data.httpIp === 'string' ? data.httpIp : ''
	error.webRtcIps = Array.isArray(data.webRtcIps) ? [...data.webRtcIps] : []
	error.retryable = data.retryable === true
	return error
}

export function isWebRtcFailureCode(code) {
	return WEBRTC_FAILURE_CODES.includes(code)
}

export function isWebRtcRetryCode(code) {
	return WEBRTC_RETRY_CODES.includes(code)
}

function responseHeader(headers, expected) {
	const entry = Object.entries(headers || {})
		.find(([name]) => name.toLowerCase() === expected.toLowerCase())
	return entry ? String(entry[1] ?? '') : ''
}

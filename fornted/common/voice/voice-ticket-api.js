import { AUTH_API_BASE_URL } from '@/common/auth/config.js'
import { authorizedRequest } from '@/common/auth/http-client.js'

const TICKET_PATH = '/api/users/me/voice/session-tickets'
const TICKET_PATTERN = /^[A-Za-z0-9_-]{43}$/

export function normalizeVoiceTicketResponse(value) {
	const ticket = String(value?.ticket || '')
	const protocolVersion = Number(value?.protocolVersion)
	const maxDurationMs = Number(value?.maxDurationMs)
	const partialIntervalMs = Number(value?.partialIntervalMs)
	const expiresAt = String(value?.expiresAt || '')
	if (!TICKET_PATTERN.test(ticket)
		|| protocolVersion !== 1
		|| maxDurationMs !== 300000
		|| !Number.isInteger(partialIntervalMs)
		|| partialIntervalMs < 250
		|| !Number.isFinite(Date.parse(expiresAt))) {
		const error = new Error('语音会话票据响应无效。')
		error.code = 'VOICE_PROTOCOL_INVALID'
		throw error
	}
	return Object.freeze({ ticket, protocolVersion, expiresAt, maxDurationMs, partialIntervalMs })
}

export async function issueVoiceSessionTicket() {
	return normalizeVoiceTicketResponse(await authorizedRequest(TICKET_PATH, {
		method: 'POST',
		data: {},
		timeout: 10000
	}))
}

export function voiceWebSocketUrl(apiBaseUrl = AUTH_API_BASE_URL) {
	const base = String(apiBaseUrl || '').trim()
	if (base) {
		const url = new URL(base)
		if (url.protocol !== 'https:') throw new Error('语音 WebSocket 只允许 HTTPS 对应的 WSS 地址。')
		url.protocol = 'wss:'
		url.pathname = '/ws/voice'
		url.search = ''
		url.hash = ''
		return url.toString()
	}
	// #ifdef H5
	if (typeof window !== 'undefined' && window.location?.protocol === 'https:') {
		return `wss://${window.location.host}/ws/voice`
	}
	// #endif
	throw new Error('当前环境没有可用的安全语音 WebSocket 地址。')
}

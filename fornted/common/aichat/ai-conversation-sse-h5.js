import { createAiConversationSseParser } from './ai-conversation-sse-parser.js'

const MAX_ERROR_BYTES = 16 * 1024

function diagnosticContentType(value) {
	const mediaType = String(value || '').split(';', 1)[0].trim().toLowerCase()
	return mediaType === 'text/event-stream' || mediaType === 'application/json'
		? mediaType
		: 'other'
}

async function boundedResponseText(response) {
	const reader = response.body?.getReader?.()
	if (!reader) return ''
	const chunks = []
	let total = 0
	try {
		while (total < MAX_ERROR_BYTES) {
			const next = await reader.read()
			if (next.done) break
			const remaining = MAX_ERROR_BYTES - total
			const chunk = next.value.subarray(0, remaining)
			chunks.push(chunk)
			total += chunk.byteLength
			if (next.value.byteLength > remaining) break
		}
		if (total >= MAX_ERROR_BYTES) await reader.cancel()
	} finally {
		reader.releaseLock()
	}
	const bytes = new Uint8Array(total)
	let offset = 0
	chunks.forEach(chunk => {
		bytes.set(chunk, offset)
		offset += chunk.byteLength
	})
	return new TextDecoder('utf-8').decode(bytes)
}

async function responseError(response) {
	let payload = null
	try {
		const text = await boundedResponseText(response)
		payload = text ? JSON.parse(text) : null
	} catch (_) {}
	const error = new Error(payload?.message || '无法建立模型流式响应。')
	error.code = payload?.code || `HTTP_${response.status}`
	error.statusCode = response.status
	return error
}

export function openAiConversationSseH5(request, handlers = {}) {
	const controller = new AbortController()
	let closed = false
	let terminalReceived = false
	const completed = (async () => {
		const response = await fetch(request.url, {
			method: request.method || 'POST',
			headers: request.headers,
			body: request.body == null ? undefined : JSON.stringify(request.body),
			credentials: 'include',
			cache: 'no-store',
			signal: controller.signal
		})
		const responseContentType = response.headers?.get?.('content-type') || ''
		handlers.lifecycleDiagnostics?.bindServerTraceId?.(
			response.headers?.get?.('x-trace-id'))
		handlers.diagnostics?.bindTraceId?.(
			response.headers?.get?.('x-trace-id'))
		handlers.diagnostics?.bindUsagePublicId?.(
			response.headers?.get?.('x-ai-usage-id'))
		handlers.onGenerationId?.(
			response.headers?.get?.('x-ai-generation-id'))
		handlers.lifecycleDiagnostics?.record?.('CLIENT_RESPONSE_HEADERS', {
			statusCode: response.status,
			contentType: diagnosticContentType(responseContentType)
		})
		handlers.diagnostics?.record?.('BROWSER_READ', {
			eventType: 'HEADERS',
			statusCode: response.status,
			contentType: diagnosticContentType(responseContentType)
		})
		if (!response.ok) throw await responseError(response)
		if (!responseContentType.toLowerCase().includes('text/event-stream')) {
			const error = new Error('服务端未返回事件流。')
			error.code = 'AI_CONVERSATION_SSE_CONTENT_TYPE_INVALID'
			error.statusCode = response.status
			throw error
		}
		const reader = response.body?.getReader?.()
		if (!reader) {
			const error = new Error('当前浏览器不支持增量事件流。')
			error.code = 'AI_CONVERSATION_SSE_UNSUPPORTED'
			throw error
		}
		handlers.onOpen?.()
		const decoder = new TextDecoder('utf-8', { fatal: true })
		const parser = createAiConversationSseParser(event => {
			const terminal = typeof handlers.isTerminalEvent === 'function'
				? handlers.isTerminalEvent(event)
				: ['completed', 'error', 'video_ready', 'video_failed']
					.includes(event.type)
			if (terminal) terminalReceived = true
			handlers.onEvent?.(event)
		})
		try {
			while (!closed) {
				const next = await reader.read()
				if (next.done) break
				handlers.diagnostics?.record?.('BROWSER_READ', {
					eventType: 'BYTES',
					byteCount: next.value?.byteLength || 0
				})
				parser.push(decoder.decode(next.value, { stream: true }))
			}
			parser.push(decoder.decode())
			parser.finish()
		} finally {
			reader.releaseLock()
		}
		if (!closed && !terminalReceived) {
			const error = new Error('模型流在终态事件前关闭。')
			error.code = 'AI_CONVERSATION_SSE_CLOSED'
			throw error
		}
	})()
	return Object.freeze({
		completed,
		close(reason = 'USER_STOP', details = {}) {
			handlers.lifecycleDiagnostics?.stopRequested?.(reason, details)
			handlers.lifecycleDiagnostics?.abortCalled?.()
			closed = true
			controller.abort()
		}
	})
}

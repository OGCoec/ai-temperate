import { createAiConversationSseParser } from './ai-conversation-sse-parser.js'
import { applySessionRenewalHeaders } from '../auth/http-client.js'
// #ifdef APP-PLUS
import { openSseRequest } from '@/uni_modules/ait-sse'
// #endif

export function openAiConversationSseApp(request, handlers = {}) {
	// #ifdef APP-PLUS
	let closed = false
	let terminalReceived = false
	let rejectCompleted
	let resolveCompleted
	const completed = new Promise((resolve, reject) => {
		resolveCompleted = resolve
		rejectCompleted = reject
	})
	const parser = createAiConversationSseParser(event => {
		const terminal = typeof handlers.isTerminalEvent === 'function'
			? handlers.isTerminalEvent(event)
			: ['completed', 'error', 'video_ready', 'video_failed']
				.includes(event.type)
		if (terminal) terminalReceived = true
		handlers.onEvent?.(event)
	})
	const connection = openSseRequest({
		url: request.url,
		method: request.method || 'POST',
		headers: { ...request.headers },
		body: request.body == null ? undefined : JSON.stringify(request.body),
		onOpen(renewal) {
			applySessionRenewalHeaders({
				'X-Session-Renewed': renewal?.sessionRenewed || '',
				'X-New-Access-Token': renewal?.newAccessToken || ''
			})
			handlers.lifecycleDiagnostics?.record?.('CLIENT_RESPONSE_HEADERS', {
				statusCode: Number(renewal?.statusCode || 0),
				contentType: String(renewal?.contentType || '')
			})
			const isEventStream = String(renewal?.contentType || '')
				.toLowerCase().includes('text/event-stream')
			const isSuccessful = Number(renewal?.statusCode || 0) >= 200
				&& Number(renewal?.statusCode || 0) < 300
			if (isSuccessful && isEventStream) handlers.onOpen?.()
		},
		onChunk(chunk) { if (!closed) parser.push(String(chunk || '')) },
		onError(failure) {
			if (closed) return
			const error = new Error(failure?.message || 'Android 模型流已中断。')
			error.code = String(failure?.code || 'AI_CONVERSATION_SSE_ANDROID_IO')
			error.statusCode = Number(failure?.statusCode || 0)
			error.cfMitigated = String(failure?.cfMitigated || '')
			error.contentType = String(failure?.contentType || '')
			error.cfRay = String(failure?.cfRay || '')
			rejectCompleted(error)
		},
		onClosed() {
			if (closed) return
			try {
				parser.finish()
				if (!terminalReceived) {
					const error = new Error('模型流在终态事件前关闭。')
					error.code = 'AI_CONVERSATION_SSE_CLOSED'
					rejectCompleted(error)
					return
				}
				resolveCompleted()
			} catch (error) {
				rejectCompleted(error)
			}
		}
	})
	return Object.freeze({
		completed,
		close() {
			handlers.lifecycleDiagnostics?.abortCalled?.()
			closed = true
			connection.close()
			resolveCompleted()
		}
	})
	// #endif
	// #ifndef APP-PLUS
	throw Object.assign(new Error('当前平台不支持 Android 模型流。'), {
		code: 'AI_CONVERSATION_SSE_ANDROID_UNSUPPORTED'
	})
	// #endif
}

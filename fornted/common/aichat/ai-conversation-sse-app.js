import { createAiConversationSseParser } from './ai-conversation-sse-parser.js'
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
		if (event.type === 'completed' || event.type === 'error') terminalReceived = true
		handlers.onEvent?.(event)
	})
	const connection = openSseRequest({
		url: request.url,
		method: request.method || 'POST',
		headers: { ...request.headers },
		body: request.body == null ? undefined : JSON.stringify(request.body),
		onOpen() {
			// 原生插件已在回调前校验成功状态和 SSE Content-Type，但当前接口不暴露具体响应头。
			handlers.lifecycleDiagnostics?.record?.('CLIENT_RESPONSE_HEADERS', {
				contentType: 'text/event-stream'
			})
		},
		onChunk(chunk) { if (!closed) parser.push(String(chunk || '')) },
		onError(failure) {
			if (closed) return
			const error = new Error(failure?.message || 'Android 模型流已中断。')
			error.code = String(failure?.code || 'AI_CONVERSATION_SSE_ANDROID_IO')
			error.statusCode = Number(failure?.statusCode || 0)
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

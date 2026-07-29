// #ifdef APP-PLUS
import { openMailInspectionSse } from '@/uni_modules/ait-sse'
// #endif

export function openMailInspectionSseApp(request, handlers = {}) {
	// #ifdef APP-PLUS
	let closed = false
	let failureNotified = false
	const connection = openMailInspectionSse({
		url: request.url,
		headers: { ...request.headers },
		onOpen(traceId) {
			if (!closed) handlers.onOpen?.({ traceId: String(traceId || '') })
		},
		onChunk(chunk) {
			if (!closed) handlers.onChunk?.(String(chunk || ''))
		},
		onError(failure) {
			if (closed || failureNotified) return
			failureNotified = true
			const message = typeof failure?.message === 'string' && failure.message
				? failure.message
				: 'Android 实时连接已中断。'
			const error = new Error(message)
			error.code = String(
				failure?.code || 'MAIL_INSPECTION_SSE_ANDROID_ERROR')
			const statusCode = Number(failure?.statusCode)
			error.statusCode = Number.isInteger(statusCode) ? statusCode : 0
			handlers.onError?.(error)
		},
		onClosed() {
			if (!closed && !failureNotified) handlers.onClosed?.()
		}
	})
	return Object.freeze({
		completed: connection.completed,
		close() {
			closed = true
			connection.close()
		}
	})
	// #endif
	// #ifndef APP-PLUS
	const error = new Error('当前平台不支持 Android 邮件检查实时连接。')
	error.code = 'MAIL_INSPECTION_SSE_ANDROID_UNSUPPORTED'
	throw error
	// #endif
}

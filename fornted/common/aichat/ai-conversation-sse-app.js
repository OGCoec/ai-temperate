import { createAiConversationSseParser } from './ai-conversation-sse-parser.js'
import { applySessionRenewalHeaders, assertAuthorizedSessionCurrent, handleAuthorizedStreamingFailure, isAuthorizedSessionTermination } from '../auth/http-client.js'
// #ifdef APP-PLUS
import { openSseRequest } from '@/uni_modules/ait-sse'
// #endif

function diagnosticContentType(value) {
	const mediaType = String(value || '').split(';', 1)[0].trim().toLowerCase()
	return mediaType === 'text/event-stream' || mediaType === 'application/json'
		? mediaType
		: 'other'
}

function utf8ByteLength(value) {
	const text = String(value || '')
	let bytes = 0
	for (let index = 0; index < text.length; index += 1) {
		const code = text.charCodeAt(index)
		if (code <= 0x7f) bytes += 1
		else if (code <= 0x7ff) bytes += 2
		else if (code >= 0xd800 && code <= 0xdbff
			&& index + 1 < text.length
			&& text.charCodeAt(index + 1) >= 0xdc00
			&& text.charCodeAt(index + 1) <= 0xdfff) {
			bytes += 4
			index += 1
		} else bytes += 3
	}
	return bytes
}

function callbackFailure() {
	const error = new Error('Android 模型流回调失败。')
	error.code = 'AI_CONVERSATION_SSE_ANDROID_CALLBACK'
	error.stage = 'JS_CALLBACK'
	return error
}

function protocolFailure(value) {
	if (isAuthorizedSessionTermination(value)) return value
	if (value?.code === 'AI_CONVERSATION_SSE_ANDROID_CALLBACK') return value
	const error = new Error('模型流事件协议无效。')
	error.code = 'AI_CONVERSATION_SSE_PROTOCOL_INVALID'
	error.stage = 'RESPONSE_BODY'
	return error
}

function nativeFailure(failure) {
	const error = new Error(failure?.message || 'Android 模型流已中断。')
	error.code = String(failure?.code || 'AI_CONVERSATION_SSE_ANDROID_IO')
	error.stage = String(failure?.stage || 'RESPONSE_BODY')
	error.exceptionType = String(failure?.exceptionType || 'UNKNOWN')
	error.statusCode = Number(failure?.statusCode || 0)
	error.cfMitigated = String(failure?.cfMitigated || '')
	error.contentType = String(failure?.contentType || '')
	error.cfRay = String(failure?.cfRay || '')
	error.elapsedMs = Math.max(0, Number(failure?.elapsedMs || 0))
	error.readCount = Math.max(0, Number(failure?.readCount || 0))
	error.byteCount = Math.max(0, Number(failure?.byteCount || 0))
	error.closedByCaller = failure?.closedByCaller === true
	error.retryable = failure?.retryable === true
	return error
}

export function openAiConversationSseApp(request, handlers = {}) {
	// #ifdef APP-PLUS
	assertAuthorizedSessionCurrent(request.sessionGeneration)
	let callerClosed = false
	let settled = false
	let terminalReceived = false
	let nativeConnection = null
	let rejectCompleted
	let resolveCompleted
	const completed = new Promise((resolve, reject) => {
		resolveCompleted = resolve
		rejectCompleted = reject
	})

	function resolveOnce() {
		if (settled) return
		settled = true
		resolveCompleted()
	}

	function rejectOnce(error, closeNative = false) {
		if (settled || callerClosed) return
		settled = true
		// 先占用 settled，再清理会话；清理可能同步关闭观察器，不能把 401 改成成功。
		rejectCompleted(handleAuthorizedStreamingFailure(error, request))
		if (closeNative) nativeConnection?.close?.(false)
	}

	const parser = createAiConversationSseParser(event => {
		assertAuthorizedSessionCurrent(request.sessionGeneration)
		try {
			const terminal = typeof handlers.isTerminalEvent === 'function'
				? handlers.isTerminalEvent(event)
				: ['completed', 'error', 'video_ready', 'video_failed']
					.includes(event.type)
			if (terminal) terminalReceived = true
			handlers.onEvent?.(event)
		} catch (_) {
			throw callbackFailure()
		}
	})

	nativeConnection = openSseRequest({
		url: request.url,
		method: request.method || 'POST',
		headers: { ...request.headers },
		body: request.body == null ? '' : JSON.stringify(request.body),
		onOpen(renewal) {
			if (callerClosed || settled) return
			try {
				assertAuthorizedSessionCurrent(request.sessionGeneration, null, Number(renewal?.statusCode) === 401)
				// 非认证业务错误也可能携带有效续签；401 与边缘挑战不能提交续签凭据。
				if (Number(renewal?.statusCode) !== 401 && renewal?.cfMitigated !== 'challenge') {
					applySessionRenewalHeaders({
						'X-Session-Renewed': renewal?.sessionRenewed || '',
						'X-New-Access-Token': renewal?.newAccessToken || ''
					}, request.sessionGeneration)
				}
			} catch (error) {
				rejectOnce(error, true)
				return
			}
			handlers.lifecycleDiagnostics?.bindServerTraceId?.(
				renewal?.traceId)
			handlers.diagnostics?.bindTraceId?.(renewal?.traceId)
			handlers.diagnostics?.bindUsagePublicId?.(renewal?.usagePublicId)
			handlers.lifecycleDiagnostics?.record?.('CLIENT_RESPONSE_HEADERS', {
				statusCode: Number(renewal?.statusCode || 0),
				contentType: diagnosticContentType(renewal?.contentType)
			})
			handlers.diagnostics?.record?.('BROWSER_READ', {
				eventType: 'HEADERS',
				statusCode: Number(renewal?.statusCode || 0),
				contentType: diagnosticContentType(renewal?.contentType)
			})
			const isEventStream = String(renewal?.contentType || '')
				.toLowerCase().includes('text/event-stream')
			const isSuccessful = Number(renewal?.statusCode || 0) >= 200
				&& Number(renewal?.statusCode || 0) < 300
			if (isSuccessful && isEventStream) handlers.onOpen?.()
		},
		onChunk(value) {
			if (callerClosed || settled) return
			try { assertAuthorizedSessionCurrent(request.sessionGeneration) } catch (error) {
				rejectOnce(error, true)
				return
			}
			const chunk = String(value || '')
			if (!chunk) return
			handlers.diagnostics?.record?.('BROWSER_READ', {
				eventType: 'BYTES',
				byteCount: utf8ByteLength(chunk)
			})
			try {
				parser.push(chunk)
			} catch (error) {
				rejectOnce(protocolFailure(error), true)
			}
		},
		onDiagnostic(diagnostic) {
			try { handlers.onNativeDiagnostic?.(diagnostic) } catch (_) {}
		},
		onError(failure) {
			if (callerClosed || settled) return
			if (terminalReceived) {
				resolveOnce()
				return
			}
			rejectOnce(nativeFailure(failure))
		},
		onClosed() {
			if (callerClosed || settled) return
			try {
				assertAuthorizedSessionCurrent(request.sessionGeneration)
				parser.finish()
				if (!terminalReceived) {
					const error = new Error('模型流在终态事件前关闭。')
					error.code = 'AI_CONVERSATION_SSE_CLOSED'
					error.stage = 'RESPONSE_BODY'
					rejectOnce(error)
					return
				}
				resolveOnce()
			} catch (error) {
				rejectOnce(protocolFailure(error))
			}
		}
	})
	return Object.freeze({
		completed,
		close(reason = 'USER_STOP', details = {}) {
			if (callerClosed || settled) return
			handlers.lifecycleDiagnostics?.stopRequested?.(reason, details)
			handlers.lifecycleDiagnostics?.abortCalled?.()
			callerClosed = true
			nativeConnection?.close?.(true)
			resolveOnce()
		}
	})
	// #endif
	// #ifndef APP-PLUS
	throw Object.assign(new Error('当前平台不支持 Android 模型流。'), {
		code: 'AI_CONVERSATION_SSE_ANDROID_UNSUPPORTED'
	})
	// #endif
}

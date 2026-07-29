import { createMailInspectionSseParser } from './mail-inspection-sse-parser.js'

const MAX_ERROR_BODY_BYTES = 16 * 1024
const MISSING_JOB_CODE = 'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND'
const MISSING_JOB_MESSAGE = '原检查任务已过期或不存在，请重新创建检查任务。'

async function readBoundedErrorBody(response) {
	const reader = response.body?.getReader?.()
	if (!reader) return ''
	const chunks = []
	let total = 0
	try {
		while (true) {
			const next = await reader.read()
			if (next.done) break
			const chunk = next.value instanceof Uint8Array
				? next.value
				: new Uint8Array(next.value || [])
			if (total + chunk.byteLength > MAX_ERROR_BODY_BYTES) {
				await reader.cancel?.()
				return ''
			}
			chunks.push(chunk)
			total += chunk.byteLength
		}
	} catch (_) {
		return ''
	} finally {
		try {
			reader.releaseLock()
		} catch (_) {}
	}
	if (!total) return ''
	const bytes = new Uint8Array(total)
	let offset = 0
	for (const chunk of chunks) {
		bytes.set(chunk, offset)
		offset += chunk.byteLength
	}
	try {
		return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
	} catch (_) {
		return ''
	}
}

function responseError(response, errorBody) {
	let payload = null
	if (errorBody) {
		try {
			const parsed = JSON.parse(errorBody)
			if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
				payload = parsed
			}
		} catch (_) {}
	}
	const fallbackCode = response.status === 404
		? MISSING_JOB_CODE
		: `HTTP_${response.status}`
	const fallbackMessage = response.status === 404
		? MISSING_JOB_MESSAGE
		: '实时连接建立失败。'
	const code = typeof payload?.code === 'string' && payload.code
		? payload.code
		: fallbackCode
	const message = typeof payload?.message === 'string' && payload.message
		? payload.message
		: fallbackMessage
	const error = new Error(message)
	error.code = code
	error.statusCode = response.status
	return error
}

export function openMailInspectionSseH5(request, handlers = {}) {
	const abortController = new AbortController()
	let closed = false
	const completed = (async () => {
		const response = await fetch(request.url, {
			method: 'GET',
			headers: request.headers,
			credentials: 'include',
			cache: 'no-store',
			signal: abortController.signal
		})
		if (!response.ok) {
			const errorBody = await readBoundedErrorBody(response)
			throw responseError(response, errorBody)
		}
		const contentType = response.headers.get('content-type') || ''
		if (!contentType.toLowerCase().includes('text/event-stream')) {
			const error = new Error('服务端未返回事件流。')
			error.code = 'MAIL_INSPECTION_SSE_CONTENT_TYPE_INVALID'
			throw error
		}
		if (!response.body?.getReader) {
			const error = new Error('当前浏览器不支持增量事件流。')
			error.code = 'MAIL_INSPECTION_SSE_STREAM_UNSUPPORTED'
			throw error
		}
		handlers.onOpen?.({
			traceId: response.headers.get('x-trace-id') || ''
		})
		const reader = response.body.getReader()
		const decoder = new TextDecoder('utf-8', { fatal: true })
		const parser = createMailInspectionSseParser({
			onEvent: handlers.onEvent
		})
		try {
			while (!closed) {
				const next = await reader.read()
				if (next.done) break
				parser.push(decoder.decode(next.value, { stream: true }))
			}
			parser.push(decoder.decode())
			parser.finish()
		} finally {
			reader.releaseLock()
		}
		if (!closed) {
			const error = new Error('实时连接已由服务端关闭。')
			error.code = 'MAIL_INSPECTION_SSE_CLOSED'
			throw error
		}
	})()
	return Object.freeze({
		completed,
		close() {
			closed = true
			abortController.abort()
		}
	})
}

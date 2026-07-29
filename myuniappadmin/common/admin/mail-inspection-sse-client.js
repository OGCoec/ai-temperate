import { prepareAdminEventStream } from './admin-http.js'
import { createMailInspectionSseParser } from './mail-inspection-sse-parser.js'
import { openMailInspectionSseH5 } from './mail-inspection-sse-h5.js'
import { openMailInspectionSseApp } from './mail-inspection-sse-app.js'

export const MAIL_INSPECTION_CONNECTION_STATES = Object.freeze({
	CONNECTING: 'CONNECTING',
	SYNCING: 'SYNCING',
	STREAMING: 'STREAMING',
	RECONNECTING: 'RECONNECTING',
	COMPLETED: 'COMPLETED',
	FAILED: 'FAILED',
	EXPIRED: 'EXPIRED'
})

const RECONNECT_DELAYS = Object.freeze([1000, 2000, 5000, 10000, 30000])
const IDLE_RECONNECT_MILLIS = 45000
const MISSING_JOB_CODES = new Set([
	'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND',
	'ADMIN_MAIL_INSPECTION_JOB_EXPIRED',
	'MAIL_INSPECTION_JOB_NOT_FOUND',
	'MAIL_INSPECTION_JOB_EXPIRED'
])

function isMissingJob(error) {
	return MISSING_JOB_CODES.has(String(error?.code || ''))
		|| Number(error?.statusCode) === 404
}

function defaultTransport(request, handlers) {
	// #ifdef H5
	return openMailInspectionSseH5(request, handlers)
	// #endif
	// #ifdef APP-PLUS
	const parser = createMailInspectionSseParser({ onEvent: handlers.onEvent })
	return openMailInspectionSseApp(request, {
		...handlers,
		onChunk(chunk) {
			parser.push(chunk)
		},
		onClosed() {
			parser.finish()
			handlers.onClosed?.()
		}
	})
	// #endif
	// #ifndef H5
	// #ifndef APP-PLUS
	const error = new Error('当前平台不支持邮箱检查实时连接。')
	error.code = 'MAIL_INSPECTION_SSE_PLATFORM_UNSUPPORTED'
	throw error
	// #endif
	// #endif
}

export function createMailInspectionSseClient(options = {}) {
	const prepare = options.prepare || prepareAdminEventStream
	const openTransport = options.openTransport || defaultTransport
	const setTimer = options.setTimer || setTimeout
	const clearTimer = options.clearTimer || clearTimeout

	return Object.freeze({
		connect(configuration) {
			let closed = false
			let terminal = false
			let transport = null
			let reconnectTimer = null
			let idleTimer = null
			let attempt = 0

			function state(value) {
				configuration.onState?.(value)
			}

			function clearTimers() {
				if (reconnectTimer !== null) clearTimer(reconnectTimer)
				if (idleTimer !== null) clearTimer(idleTimer)
				reconnectTimer = null
				idleTimer = null
			}

			function closeTransport() {
				transport?.close()
				transport = null
			}

			function armIdle() {
				if (idleTimer !== null) clearTimer(idleTimer)
				idleTimer = setTimer(() => {
					if (closed || terminal) return
					closeTransport()
					reconnect()
				}, IDLE_RECONNECT_MILLIS)
			}

			function acceptEvent(event) {
				armIdle()
				configuration.onEvent?.(event)
				if (event.type === 'sync-complete') {
					attempt = 0
					state(MAIL_INSPECTION_CONNECTION_STATES.STREAMING)
				}
				if (event.type === 'terminal') {
					terminal = true
					clearTimers()
					closeTransport()
				}
			}

			function reconnect(error) {
				if (closed || terminal) return
				if (isMissingJob(error)) {
					terminal = true
					clearTimers()
					closeTransport()
					configuration.onError?.(error)
					if (!closed) {
						state(MAIL_INSPECTION_CONNECTION_STATES.EXPIRED)
					}
					return
				}
				if (reconnectTimer !== null) return
				if (attempt >= RECONNECT_DELAYS.length) {
					state(MAIL_INSPECTION_CONNECTION_STATES.FAILED)
					configuration.onError?.(error)
					return
				}
				const delay = RECONNECT_DELAYS[attempt]
				attempt += 1
				state(MAIL_INSPECTION_CONNECTION_STATES.RECONNECTING)
				reconnectTimer = setTimer(() => {
					reconnectTimer = null
					void open()
				}, delay)
			}

			async function open() {
				if (closed || terminal) return
				clearTimers()
				closeTransport()
				state(attempt ? MAIL_INSPECTION_CONNECTION_STATES.RECONNECTING
					: MAIL_INSPECTION_CONNECTION_STATES.CONNECTING)
				try {
					const request = await prepare(
						configuration.path,
						configuration.lastRevision())
					if (closed || terminal) return
					state(MAIL_INSPECTION_CONNECTION_STATES.SYNCING)
					transport = openTransport(request, {
						onOpen: armIdle,
						onEvent: acceptEvent,
						onError: reconnect,
						onClosed: reconnect
					})
					armIdle()
					Promise.resolve(transport.completed).catch(reconnect)
				} catch (error) {
					reconnect(error)
				}
			}

			void open()
			return Object.freeze({
				close() {
					closed = true
					clearTimers()
					closeTransport()
				}
			})
		}
	})
}

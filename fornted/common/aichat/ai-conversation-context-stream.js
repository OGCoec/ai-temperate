import { assertAuthorizedSessionCurrent, isAuthorizedSessionTermination, prepareAuthorizedStreamingRequest } from '../auth/http-client.js'
import { clientPlatform } from '../auth/config.js'
import { buildQueryString } from '../platform/query-string.js'
import { openAiConversationSseH5 } from './ai-conversation-sse-h5.js'
// #ifdef APP-PLUS
import { openAiConversationSseApp } from './ai-conversation-sse-app.js'
// #endif

const TERMINAL_EVENTS = new Set([
	'compaction_completed', 'compaction_failed', 'timeout'
])
const RECONNECT_DELAYS = Object.freeze([250, 750, 1500])

function wait(milliseconds) {
	return new Promise(resolve => setTimeout(resolve, milliseconds))
}

async function openOnce(command, handlers, sessionGeneration) {
	const query = buildQueryString([
		['modelPublicId', command.modelPublicId],
		['afterRevision', command.afterRevision || 0]
	])
	const prepared = await prepareAuthorizedStreamingRequest(
		`/api/ai/conversations/${encodeURIComponent(command.conversationPublicId)}/context/events?${query}`,
		{ method: 'GET', headers: { Accept: 'text/event-stream' }, sessionGeneration }
	)
	let resolveReady
	let rejectReady
	let opened = false
	const ready = new Promise((resolve, reject) => {
		resolveReady = resolve
		rejectReady = reject
	})
	const openingHandlers = {
		...handlers,
		onOpen() {
			resolveReady()
			handlers.onOpen?.()
		}
	}
	let connection
	if (clientPlatform() === 'ANDROID') {
		// #ifdef APP-PLUS
		connection = openAiConversationSseApp(prepared, openingHandlers)
		// #endif
	} else {
		connection = openAiConversationSseH5(prepared, openingHandlers)
	}
	// 建连失败必须反馈给 Stop 调用方；这里显式消费 completed 的拒绝，避免竞态产生未处理 Promise。
	void connection.completed.then(
		() => {
			if (!opened) rejectReady(new Error('上下文事件流在建立前关闭。'))
		},
		failure => {
			if (!opened) rejectReady(failure)
		}
	)
	await ready
	opened = true
	return connection
}

/**
 * 按需观察一个会话在当前模型窗口下的权威用量；断线只用最后 eventRevision 有限重连，
 * 关闭句柄不会触发模型取消，也不会影响发送能力。
 */
export async function openAiConversationContextStream(command, handlers = {}) {
	const sessionGeneration = assertAuthorizedSessionCurrent()
	let closed = false
	let active = null
	let lastRevision = Math.max(0, Number(command.afterRevision || 0))
	const wrapped = {
		...handlers,
		isTerminalEvent: event => TERMINAL_EVENTS.has(event.type),
		onEvent(event) {
			assertAuthorizedSessionCurrent(sessionGeneration)
			const eventRevision = Number(event.data?.eventRevision || event.id || 0)
			if (Number.isSafeInteger(eventRevision) && eventRevision > lastRevision) {
				lastRevision = eventRevision
			}
			handlers.onEvent?.(event)
		}
	}
	active = await openOnce({ ...command, afterRevision: lastRevision }, wrapped, sessionGeneration)
	const completed = (async () => {
		let lastFailure = null
		for (let attempt = 0; attempt <= RECONNECT_DELAYS.length; attempt++) {
			try {
				await active.completed
				return
			} catch (failure) {
				if (isAuthorizedSessionTermination(failure)) throw failure
				assertAuthorizedSessionCurrent(sessionGeneration)
				lastFailure = failure
				if (closed || attempt === RECONNECT_DELAYS.length) break
				await wait(RECONNECT_DELAYS[attempt])
				if (closed) return
				assertAuthorizedSessionCurrent(sessionGeneration)
				active = await openOnce({
					...command,
					afterRevision: lastRevision
				}, wrapped, sessionGeneration)
				if (closed) { active.close?.('CONTEXT_OBSERVER_CLOSED'); return }
			}
		}
		if (!closed) throw lastFailure
	})()
	return Object.freeze({
		completed,
		close() {
			closed = true
			active?.close?.('CONTEXT_OBSERVER_CLOSED')
		},
		lastRevision() { return lastRevision }
	})
}

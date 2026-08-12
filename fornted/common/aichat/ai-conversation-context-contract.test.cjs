const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadContextStream() {
	const nonce = `${Date.now()}-${Math.random()}`
	const httpClient = sourceUrl(`
		export async function prepareAuthorizedStreamingRequest(url, options) {
			globalThis.__contextPreparedRequests.push([url, options])
			return { url, options }
		}
	`)
	const config = sourceUrl("export const clientPlatform = () => 'ANDROID'")
	const h5Stream = sourceUrl(`
		export function openAiConversationSseH5(prepared, handlers) {
			return globalThis.__openContextConnection(prepared, handlers, 'H5')
		}
	`)
	const appStream = sourceUrl(`
		export function openAiConversationSseApp(prepared, handlers) {
			return globalThis.__openContextConnection(prepared, handlers, 'ANDROID')
		}
	`)
	const queryString = sourceUrl(fs.readFileSync(path.resolve(
		__dirname, '../platform/query-string.js'), 'utf8'))
	const source = fs.readFileSync(path.resolve(
		__dirname, 'ai-conversation-context-stream.js'), 'utf8')
		.replace("from '../auth/http-client.js'", `from '${httpClient}#${nonce}'`)
		.replace("from '../auth/config.js'", `from '${config}#${nonce}'`)
		.replace("from './ai-conversation-sse-h5.js'", `from '${h5Stream}#${nonce}'`)
		.replace("from './ai-conversation-sse-app.js'", `from '${appStream}#${nonce}'`)
		.replace("from '../platform/query-string.js'", `from '${queryString}#${nonce}'`)
		.replace(
			'const RECONNECT_DELAYS = Object.freeze([250, 750, 1500])',
			'const RECONNECT_DELAYS = Object.freeze([0])'
		)
	return import(`${sourceUrl(source)}#${nonce}`)
}

async function withoutUrlSearchParams(callback) {
	const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'URLSearchParams')
	try {
		Object.defineProperty(globalThis, 'URLSearchParams', {
			configurable: true,
			writable: true,
			value: undefined
		})
		return await callback()
	} finally {
		if (descriptor) Object.defineProperty(globalThis, 'URLSearchParams', descriptor)
		else delete globalThis.URLSearchParams
	}
}

async function waitFor(predicate) {
	for (let attempt = 0; attempt < 20; attempt += 1) {
		if (predicate()) return
		await new Promise(resolve => setTimeout(resolve, 0))
	}
	throw new Error('Timed out waiting for context stream state')
}

test('context stream reconnects with eventRevision and stop subscribes before cancel', () => {
	const stream = fs.readFileSync(path.resolve(
		__dirname, 'ai-conversation-context-stream.js'), 'utf8')
	const panel = fs.readFileSync(path.resolve(
		__dirname, '../../components/user/workspace/user-chat-panel.vue'), 'utf8')
	const stop = panel.slice(panel.indexOf('async stop()'))

	assert.match(stream, /afterRevision:\s*lastRevision/)
	assert.match(stream, /compaction_completed/)
	assert.match(stream, /compaction_failed/)
	assert.match(stream, /timeout/)
	assert.match(stop, /await this\.openContextObserver/)
	assert.match(stop, /cancelDirectResponseWithRetry/)
	assert.ok(stop.indexOf('await this.openContextObserver')
		< stop.indexOf('cancelDirectResponseWithRetry'))
})

test('context stream builds initial and reconnect URLs without URLSearchParams', async () => {
	await withoutUrlSearchParams(async () => {
		globalThis.__contextPreparedRequests = []
		globalThis.__contextConnections = []
		globalThis.__openContextConnection = (prepared, handlers, platform) => {
			let resolveCompleted
			let rejectCompleted
			const completed = new Promise((resolve, reject) => {
				resolveCompleted = resolve
				rejectCompleted = reject
			})
			const record = {
				prepared,
				handlers,
				platform,
				resolveCompleted,
				rejectCompleted
			}
			globalThis.__contextConnections.push(record)
			handlers.onOpen?.()
			return {
				completed,
				close() { resolveCompleted() }
			}
		}

		try {
			const module = await loadContextStream()
			const conversationPublicId = 'AAAAAAAAAAAAAAAAAAAAAQ'
			const modelPublicId = 'AAAAAAAAAAE'
			const observer = await module.openAiConversationContextStream({
				conversationPublicId,
				modelPublicId,
				afterRevision: 4
			})

			assert.equal(globalThis.__contextPreparedRequests[0][0],
				`/api/ai/conversations/${conversationPublicId}/context/events?modelPublicId=${modelPublicId}&afterRevision=4`)
			assert.equal(globalThis.__contextConnections[0].platform, 'ANDROID')

			globalThis.__contextConnections[0].handlers.onEvent({
				type: 'context_updated',
				data: { eventRevision: 9 }
			})
			globalThis.__contextConnections[0].rejectCompleted(new Error('disconnect'))
			await waitFor(() => globalThis.__contextConnections.length === 2)

			assert.equal(globalThis.__contextPreparedRequests[1][0],
				`/api/ai/conversations/${conversationPublicId}/context/events?modelPublicId=${modelPublicId}&afterRevision=9`)
			globalThis.__contextConnections[1].resolveCompleted()
			await observer.completed
		} finally {
			delete globalThis.__contextPreparedRequests
			delete globalThis.__contextConnections
			delete globalThis.__openContextConnection
		}
	})
})

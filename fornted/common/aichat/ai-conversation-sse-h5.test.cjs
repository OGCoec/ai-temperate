const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadTransport() {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-conversation-sse-h5.js'),
		'utf8'
	).replace(
		/import \{ createAiConversationSseParser \} from '.\/ai-conversation-sse-parser\.js'/,
		'const createAiConversationSseParser = () => ({ push() {}, finish() {} })'
	)
	const isolated = source.replace(/import \{[^\n]+\} from '\.\.\/auth\/http-client\.js'/, 'const assertAuthorizedSessionCurrent = () => 0; const handleAuthorizedStreamingFailure = error => error')
	const url = `data:text/javascript;base64,${Buffer.from(isolated).toString('base64')}`
	return import(`${url}#${Date.now()}-${Math.random()}`)
}

function bodyReader(text) {
	const bytes = new TextEncoder().encode(text)
	let consumed = false
	return {
		getReader() {
			return {
				async read() {
					if (consumed) return { done: true }
					consumed = true
					return { done: false, value: bytes }
				},
				async cancel() {},
				releaseLock() {}
			}
		}
	}
}

test('preserves 402 status, code and message from a bounded JSON body', async () => {
	const transport = await loadTransport()
	const originalFetch = global.fetch
	global.fetch = async () => ({
		ok: false,
		status: 402,
		body: bodyReader(JSON.stringify({
			code: 'AI_QUOTA_INSUFFICIENT',
			message: '额度不足，请充值。'
		}))
	})
	try {
		const connection = transport.openAiConversationSseH5({
			url: 'https://example.test/api/ai/conversations/responses',
			headers: {},
			body: {}
		})
		await assert.rejects(connection.completed, error =>
			error.statusCode === 402
				&& error.code === 'AI_QUOTA_INSUFFICIENT'
				&& error.message === '额度不足，请充值。'
		)
	} finally {
		global.fetch = originalFetch
	}
})

test('rejects a successful non-SSE response as a protocol error', async () => {
	const transport = await loadTransport()
	const originalFetch = global.fetch
	global.fetch = async () => ({
		ok: true,
		status: 200,
		headers: { get: () => 'application/json' }
	})
	try {
		const connection = transport.openAiConversationSseH5({
			url: 'https://example.test/api/ai/conversations/responses',
			headers: {},
			body: {}
		})
		await assert.rejects(connection.completed, error =>
			error.statusCode === 200
				&& error.code === 'AI_CONVERSATION_SSE_CONTENT_TYPE_INVALID'
		)
	} finally {
		global.fetch = originalFetch
	}
})

test('records response headers and each network read without exposing bytes', async () => {
	const transport = await loadTransport()
	const originalFetch = global.fetch
	const records = []
	let boundTraceId = null
	global.fetch = async () => ({
		ok: true,
		status: 200,
		headers: {
			get: name => name === 'x-trace-id'
				? 'f47ac10b-58cc-4372-a567-0e02b2c3d479'
				: 'text/event-stream'
		},
		body: bodyReader('event: completed\ndata: {}\n\n')
	})
	try {
		const connection = transport.openAiConversationSseH5({
			url: 'https://example.test/api/ai/conversations/responses',
			headers: {},
			body: {}
		}, {
			lifecycleDiagnostics: {
				bindServerTraceId(value) { boundTraceId = value },
				record() {}
			},
			diagnostics: {
				record(boundary, details) { records.push({ boundary, details }) },
				finish() {}
			}
		})
		await assert.rejects(connection.completed, error =>
			error.code === 'AI_CONVERSATION_SSE_CLOSED'
		)
		assert.deepEqual(records.map(record => record.details.eventType), [
			'HEADERS', 'BYTES'
		])
		assert.equal(records[0].details.statusCode, 200)
		assert.equal(records[0].details.contentType, 'text/event-stream')
		assert.equal(records[1].details.byteCount > 0, true)
		assert.equal(JSON.stringify(records).includes('completed'), false)
		assert.equal(boundTraceId, 'f47ac10b-58cc-4372-a567-0e02b2c3d479')
	} finally {
		global.fetch = originalFetch
	}
})

test('records abort before cancelling fetch', async () => {
	const transport = await loadTransport()
	const originalFetch = global.fetch
	const phases = []
	let observedSignal = null
	global.fetch = async (_url, options) => {
		observedSignal = options.signal
		return new Promise((_resolve, reject) => {
			options.signal.addEventListener('abort', () => {
				const error = new Error('aborted')
				error.name = 'AbortError'
				reject(error)
			})
		})
	}
	try {
		const connection = transport.openAiConversationSseH5({
			url: 'https://example.test/api/ai/conversations/responses',
			headers: {},
			body: {}
		}, {
			lifecycleDiagnostics: {
				stopRequested() { phases.push('CLIENT_STOP_REQUESTED') },
				abortCalled() { phases.push('CLIENT_ABORT_CALLED') }
			}
		})

		connection.close('PAGE_HIDDEN')

		await assert.rejects(connection.completed, error =>
			error.name === 'AbortError')
		assert.equal(observedSignal.aborted, true)
		assert.deepEqual(phases, [
			'CLIENT_STOP_REQUESTED',
			'CLIENT_ABORT_CALLED'
		])
	} finally {
		global.fetch = originalFetch
	}
})

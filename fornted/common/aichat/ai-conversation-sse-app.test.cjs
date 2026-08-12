const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function importSource(file) {
	const source = fs.readFileSync(file, 'utf8')
	const url = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(`${url}#${Date.now()}-${Math.random()}`)
}

async function loadTransport(openSseRequest) {
	const parser = await importSource(
		path.join(__dirname, 'ai-conversation-sse-parser.js'))
	globalThis.__aitCreateSseParser = parser.createAiConversationSseParser
	globalThis.__aitApplySessionRenewal = () => {}
	globalThis.__aitOpenSseRequest = openSseRequest
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-conversation-sse-app.js'),
		'utf8'
	).replace(
		/import \{ createAiConversationSseParser \} from '.\/ai-conversation-sse-parser\.js'/,
		'const createAiConversationSseParser = globalThis.__aitCreateSseParser'
	).replace(
		/import \{ applySessionRenewalHeaders \} from '\.\.\/auth\/http-client\.js'/,
		'const applySessionRenewalHeaders = globalThis.__aitApplySessionRenewal'
	).replace(
		/import \{ openSseRequest \} from '@\/uni_modules\/ait-sse'/,
		'const openSseRequest = globalThis.__aitOpenSseRequest'
	)
	const url = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(`${url}#${Date.now()}-${Math.random()}`)
}

function nativeHarness() {
	let options = null
	let closeCount = 0
	return {
		openSseRequest(value) {
			options = value
			return { close() { closeCount += 1 } }
		},
		options() { return options },
		closeCount() { return closeCount }
	}
}

function openHeaders(overrides = {}) {
	return {
		statusCode: 200,
		sessionRenewed: '',
		newAccessToken: '',
		contentType: 'text/event-stream; charset=utf-8',
		cfMitigated: '',
		cfRay: 'safe-ray-id',
		traceId: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
		usagePublicId: 'usage-public-id',
		...overrides
	}
}

test('dispatches split Android chunks immediately through the shared parser', async () => {
	const native = nativeHarness()
	const transport = await loadTransport(native.openSseRequest)
	const events = []
	const records = []
	let boundTraceId = ''
	let boundUsagePublicId = ''
	const connection = transport.openAiConversationSseApp({
		url: 'https://example.test/api/ai/conversations/responses',
		headers: {},
		body: { input: 'not logged' }
	}, {
		onEvent(event) { events.push(event) },
		lifecycleDiagnostics: {
			bindServerTraceId(value) { boundTraceId = value },
			record() {}
		},
		diagnostics: {
			bindTraceId() {},
			bindUsagePublicId(value) { boundUsagePublicId = value },
			record(boundary, details) { records.push({ boundary, details }) }
		}
	})
	const callbacks = native.options()
	callbacks.onOpen(openHeaders())
	callbacks.onChunk('event: activity\ndata: {"sequence":1,"phase":"WEB_')
	callbacks.onChunk('SEARCH"}\n\nevent: source\ndata: {"sequence":2,"title":"文档"}\n\n')
	callbacks.onChunk('event: reasoning_summary\ndata: {"sequence":3,"text":"整理中"}\n\n')
	callbacks.onChunk('event: delta\ndata: {"sequence":4,"type":"TEXT","text":"答')
	callbacks.onChunk('案"}\n\nevent: completed\ndata: {"sequence":5}\n\n')
	callbacks.onClosed()

	await connection.completed
	assert.deepEqual(events.map(event => event.type), [
		'activity', 'source', 'reasoning_summary', 'delta', 'completed'
	])
	assert.equal(boundTraceId, openHeaders().traceId)
	assert.equal(boundUsagePublicId, openHeaders().usagePublicId)
	assert.deepEqual(records.map(record => record.details.eventType), [
		'HEADERS', 'BYTES', 'BYTES', 'BYTES', 'BYTES', 'BYTES'
	])
	assert.equal(records.every(record => !('chunk' in record.details)), true)
	assert.equal(JSON.stringify(records).includes('整理中'), false)
})

test('classifies invalid SSE data as a protocol failure and closes native input', async () => {
	const native = nativeHarness()
	const transport = await loadTransport(native.openSseRequest)
	const connection = transport.openAiConversationSseApp({
		url: 'https://example.test/api/ai/conversations/responses',
		headers: {},
		body: {}
	})
	const callbacks = native.options()
	callbacks.onOpen(openHeaders())
	callbacks.onChunk('event: delta\ndata: {invalid}\n\n')

	await assert.rejects(connection.completed, error =>
		error.code === 'AI_CONVERSATION_SSE_PROTOCOL_INVALID')
	assert.equal(native.closeCount(), 1)
})

test('classifies an event handler exception as an Android callback failure', async () => {
	const native = nativeHarness()
	const transport = await loadTransport(native.openSseRequest)
	const connection = transport.openAiConversationSseApp({
		url: 'https://example.test/api/ai/conversations/responses',
		headers: {},
		body: {}
	}, {
		onEvent() { throw new Error('private callback detail') }
	})
	const callbacks = native.options()
	callbacks.onOpen(openHeaders())
	callbacks.onChunk('event: activity\ndata: {"sequence":1}\n\n')

	await assert.rejects(connection.completed, error =>
		error.code === 'AI_CONVERSATION_SSE_ANDROID_CALLBACK'
			&& !error.message.includes('private callback detail'))
	assert.equal(native.closeCount(), 1)
})

test('preserves bounded native failure metadata without logging response content', async () => {
	const native = nativeHarness()
	const transport = await loadTransport(native.openSseRequest)
	const diagnostics = []
	const connection = transport.openAiConversationSseApp({
		url: 'https://example.test/api/ai/conversations/responses',
		headers: {},
		body: {}
	}, {
		onNativeDiagnostic(value) { diagnostics.push(value) }
	})
	const callbacks = native.options()
	callbacks.onDiagnostic({
		stage: 'RESPONSE_BODY', event: 'READ', statusCode: 200,
		contentType: 'text/event-stream', cfRay: 'safe-ray-id',
		elapsedMs: 1200, readCount: 3, byteCount: 512,
		lastReadElapsedMs: 1100, closedByCaller: false
	})
	callbacks.onError({
		code: 'AI_CONVERSATION_SSE_ANDROID_IO',
		stage: 'RESPONSE_BODY', exceptionType: 'SocketException',
		statusCode: 200, message: 'Android 模型流已中断。',
		contentType: 'text/event-stream', cfMitigated: '', cfRay: 'safe-ray-id',
		elapsedMs: 1300, readCount: 3, byteCount: 512,
		closedByCaller: false, retryable: true
	})

	await assert.rejects(connection.completed, error =>
		error.code === 'AI_CONVERSATION_SSE_ANDROID_IO'
			&& error.stage === 'RESPONSE_BODY'
			&& error.exceptionType === 'SocketException'
			&& error.byteCount === 512
			&& error.retryable === true)
	assert.equal(diagnostics.length, 1)
})

test('explicit close resolves once and ignores the native close callback', async () => {
	const native = nativeHarness()
	const transport = await loadTransport(native.openSseRequest)
	const connection = transport.openAiConversationSseApp({
		url: 'https://example.test/api/ai/conversations/responses',
		headers: {},
		body: {}
	})
	connection.close()
	native.options().onClosed()

	await connection.completed
	assert.equal(native.closeCount(), 1)
})

test('a native close failure after the terminal event cannot replace completion', async () => {
	const native = nativeHarness()
	const transport = await loadTransport(native.openSseRequest)
	const events = []
	const connection = transport.openAiConversationSseApp({
		url: 'https://example.test/api/ai/conversations/responses',
		headers: {},
		body: {}
	}, {
		onEvent(event) { events.push(event.type) }
	})
	const callbacks = native.options()
	callbacks.onOpen(openHeaders())
	callbacks.onChunk('event: completed\ndata: {"sequence":1}\n\n')
	callbacks.onError({
		code: 'AI_CONVERSATION_SSE_ANDROID_IO',
		stage: 'RESPONSE_BODY', exceptionType: 'SocketException',
		statusCode: 200, message: 'Android 模型流已中断。',
		contentType: 'text/event-stream', cfMitigated: '', cfRay: 'safe-ray-id',
		elapsedMs: 100, readCount: 1, byteCount: 42,
		closedByCaller: false, retryable: true
	})

	await connection.completed
	assert.deepEqual(events, ['completed'])
})

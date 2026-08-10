const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadModule() {
	let source = fs.readFileSync(path.join(__dirname, 'voice-websocket-session.js'), 'utf8')
	source = source.replace(
		"import { voiceWebSocketUrl } from './voice-ticket-api.js'",
		"const voiceWebSocketUrl = () => 'wss://localhost:6655/ws/voice'")
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function fakeSocket() {
	const handlers = {}
	const sent = []
	return {
		handlers,
		sent,
		onOpen(callback) { handlers.open = callback },
		onMessage(callback) { handlers.message = callback },
		onError(callback) { handlers.error = callback },
		onClose(callback) { handlers.close = callback },
		send(options) { sent.push(options.data); options.success?.() },
		close(options) { this.closed = options }
	}
}

test('passes a callback so UniApp returns SocketTask instead of a Promise wrapper', async () => {
	const module = await loadModule()
	const socket = fakeSocket()
	let connectOptions
	global.uni = {
		connectSocket(options) {
			connectOptions = options
			return socket
		}
	}
	const session = new module.VoiceWebSocketSession({ url: 'wss://localhost:6655/ws/voice' })
	const connected = session.connect({
		ticket: 'A'.repeat(43), protocolVersion: 1, maxDurationMs: 300000
	})

	assert.equal(typeof connectOptions.complete, 'function')
	assert.deepEqual(connectOptions.header, { 'X-Client-Platform': 'ANDROID' })
	socket.handlers.open()
	await new Promise(resolve => setImmediate(resolve))
	socket.handlers.message({ data: JSON.stringify({ type: 'session.ready' }) })
	await connected
	await session.stop()
	delete global.uni
})

test('keeps the ticket in the first JSON frame and sends audio as binary data', async () => {
	const module = await loadModule()
	const socket = fakeSocket()
	global.uni = { connectSocket: () => socket }
	const events = []
	const session = new module.VoiceWebSocketSession({
		url: 'wss://localhost:6655/ws/voice',
		onEvent: event => events.push(event)
	})
	const connected = session.connect({
		ticket: 'A'.repeat(43), protocolVersion: 1, maxDurationMs: 300000
	})
	socket.handlers.open()
	await new Promise(resolve => setImmediate(resolve))
	const start = JSON.parse(socket.sent[0])
	assert.equal(start.ticket, 'A'.repeat(43))
	assert.equal(start.format, 'pcm_s16le')
	socket.handlers.message({ data: JSON.stringify({ type: 'session.ready' }) })
	await connected

	const frame = new ArrayBuffer(3200)
	await session.sendAudio(frame)
	assert.equal(socket.sent[1], frame)
	await session.commit()
	assert.deepEqual(JSON.parse(socket.sent[2]), { type: 'input.commit' })

	socket.handlers.message({ data: JSON.stringify({
		type: 'transcript.final', sequence: 1, text: '你好', startMs: 0, endMs: 100
	}) })
	assert.equal(events.at(-1).text, '你好')
	assert.equal(socket.closed.code, 1000)
	delete global.uni
})

test('rejects oversized frames before they enter the WebSocket queue', async () => {
	const module = await loadModule()
	const socket = fakeSocket()
	global.uni = { connectSocket: () => socket }
	const session = new module.VoiceWebSocketSession({ url: 'wss://localhost:6655/ws/voice' })
	const connected = session.connect({
		ticket: 'A'.repeat(43), protocolVersion: 1, maxDurationMs: 300000
	})
	socket.handlers.open()
	socket.handlers.message({ data: JSON.stringify({ type: 'session.ready' }) })
	await connected

	await assert.rejects(
		session.sendAudio(new ArrayBuffer(128 * 1024 + 2)),
		error => error.code === 'VOICE_FRAME_TOO_LARGE')
	delete global.uni
})

test('keeps connect pending while queued and becomes recordable after promotion', async () => {
	const module = await loadModule()
	const socket = fakeSocket()
	global.uni = { connectSocket: () => socket }
	const events = []
	const session = new module.VoiceWebSocketSession({
		url: 'wss://localhost:6655/ws/voice',
		onEvent: event => events.push(event)
	})
	let ready = false
	const connected = session.connect({
		ticket: 'A'.repeat(43), protocolVersion: 1, maxDurationMs: 300000
	}).then(() => { ready = true })
	socket.handlers.open()
	socket.handlers.message({ data: JSON.stringify({
		type: 'session.queued',
		sessionId: '0123456789abcdef0123456789abcdef',
		position: 2,
		queueCapacity: 5,
		maxWaitMs: 90000
	}) })
	await new Promise(resolve => setImmediate(resolve))

	assert.equal(session.state, module.VOICE_SESSION_STATES.QUEUED)
	assert.equal(ready, false)
	assert.equal(events.at(-1).position, 2)

	socket.handlers.message({ data: JSON.stringify({ type: 'session.ready' }) })
	await connected
	assert.equal(session.state, module.VOICE_SESSION_STATES.RECORDING)
	await session.stop()
	delete global.uni
})

test('explicit stop cancels a queued session without sending audio', async () => {
	const module = await loadModule()
	const socket = fakeSocket()
	global.uni = { connectSocket: () => socket }
	const session = new module.VoiceWebSocketSession({ url: 'wss://localhost:6655/ws/voice' })
	void session.connect({
		ticket: 'A'.repeat(43), protocolVersion: 1, maxDurationMs: 300000
	})
	socket.handlers.open()
	socket.handlers.message({ data: JSON.stringify({
		type: 'session.queued',
		sessionId: '0123456789abcdef0123456789abcdef',
		position: 1,
		queueCapacity: 5,
		maxWaitMs: 90000
	}) })
	await session.stop()

	assert.deepEqual(JSON.parse(socket.sent.at(-1)), { type: 'session.stop' })
	assert.equal(socket.closed.code, 1000)
	delete global.uni
})

test('queue capacity errors close with retry-later status', async () => {
	const module = await loadModule()
	const socket = fakeSocket()
	global.uni = { connectSocket: () => socket }
	let failure
	const session = new module.VoiceWebSocketSession({
		url: 'wss://localhost:6655/ws/voice',
		onError: error => { failure = error }
	})
	void session.connect({
		ticket: 'A'.repeat(43), protocolVersion: 1, maxDurationMs: 300000
	}).catch(() => {})
	socket.handlers.open()
	socket.handlers.message({ data: JSON.stringify({
		type: 'error', code: 'VOICE_QUEUE_FULL', message: 'full', retryable: true
	}) })

	assert.equal(failure.code, 'VOICE_QUEUE_FULL')
	assert.equal(socket.closed.code, 1013)
	delete global.uni
})

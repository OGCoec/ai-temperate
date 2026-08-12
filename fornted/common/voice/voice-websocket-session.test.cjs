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

test('distinguishes an Android bridge failure from a real PCM format failure', async () => {
	const module = await loadModule()

	assert.equal(
		module.voiceErrorMessage({ code: 'VOICE_AUDIO_BRIDGE_INVALID' }),
		'Android 音频通道转换失败，请重新启动录音。')
	assert.equal(
		module.voiceErrorMessage({ code: 'VOICE_AUDIO_FORMAT_INVALID' }),
		'录音格式不受支持。')
})

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
		ticket: 'A'.repeat(43), protocolVersion: 2, maxDurationMs: 300000
	})

	assert.equal(typeof connectOptions.complete, 'function')
	assert.deepEqual(connectOptions.header, { 'X-Client-Platform': 'ANDROID' })
	assert.deepEqual(connectOptions.protocols, [
		'ait-voice-v2',
		`ait-ticket.${'A'.repeat(43)}`
	])
	socket.handlers.open()
	await new Promise(resolve => setImmediate(resolve))
	socket.handlers.message({ data: JSON.stringify({ type: 'session.ready' }) })
	await connected
	await session.stop()
	delete global.uni
})

test('fails closed before opening a socket when the v2 handshake ticket is invalid', async () => {
	const module = await loadModule()
	let connectCalls = 0
	global.uni = { connectSocket: () => { connectCalls += 1 } }
	const session = new module.VoiceWebSocketSession({ url: 'wss://localhost:6655/ws/voice' })

	await assert.rejects(
		session.connect({ ticket: 'invalid', protocolVersion: 2 }),
		error => error.code === 'VOICE_TICKET_INVALID')
	assert.equal(connectCalls, 0)
	delete global.uni
})

test('delivers the ticket only through the handshake and sends audio as binary data', async () => {
	const module = await loadModule()
	const socket = fakeSocket()
	let connectOptions
	global.uni = { connectSocket: options => { connectOptions = options; return socket } }
	const events = []
	const session = new module.VoiceWebSocketSession({
		url: 'wss://localhost:6655/ws/voice',
		onEvent: event => events.push(event)
	})
	const connected = session.connect({
		ticket: 'A'.repeat(43), protocolVersion: 2, maxDurationMs: 300000
	})
	assert.deepEqual(connectOptions.protocols, [
		'ait-voice-v2',
		`ait-ticket.${'A'.repeat(43)}`
	])
	socket.handlers.open()
	await new Promise(resolve => setImmediate(resolve))
	const start = JSON.parse(socket.sent[0])
	assert.equal(Object.hasOwn(start, 'ticket'), false)
	assert.equal(start.protocolVersion, 2)
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
		ticket: 'A'.repeat(43), protocolVersion: 2, maxDurationMs: 300000
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
		ticket: 'A'.repeat(43), protocolVersion: 2, maxDurationMs: 300000
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
		ticket: 'A'.repeat(43), protocolVersion: 2, maxDurationMs: 300000
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
		ticket: 'A'.repeat(43), protocolVersion: 2, maxDurationMs: 300000
	}).catch(() => {})
	socket.handlers.open()
	socket.handlers.message({ data: JSON.stringify({
		type: 'error', code: 'VOICE_QUEUE_FULL', message: 'full', retryable: true
	}) })

	assert.equal(failure.code, 'VOICE_QUEUE_FULL')
	assert.equal(socket.closed.code, 1013)
	delete global.uni
})

test('abort closes immediately without sending commit or graceful stop messages', async () => {
	const module = await loadModule()
	const socket = fakeSocket()
	global.uni = { connectSocket: () => socket }
	const events = []
	const failures = []
	const session = new module.VoiceWebSocketSession({
		url: 'wss://localhost:6655/ws/voice',
		onEvent: event => events.push(event),
		onError: error => failures.push(error)
	})
	const connected = session.connect({
		ticket: 'A'.repeat(43), protocolVersion: 2, maxDurationMs: 300000
	})
	socket.handlers.open()
	await new Promise(resolve => setImmediate(resolve))
	socket.handlers.message({ data: JSON.stringify({ type: 'session.ready' }) })
	await connected
	const sentBeforeAbort = socket.sent.length

	const result = session.abort('USER_DISCARD')

	assert.equal(result, undefined)
	assert.equal(session.state, module.VOICE_SESSION_STATES.CLOSED)
	assert.equal(socket.sent.length, sentBeforeAbort)
	assert.equal(socket.closed.code, 1000)
	assert.equal(socket.closed.reason, 'USER_DISCARD')
	assert.equal(socket.sent.some(value => {
		if (typeof value !== 'string') return false
		const message = JSON.parse(value)
		return message.type === 'input.commit' || message.type === 'session.stop'
	}), false)

	socket.handlers.message({ data: JSON.stringify({
		type: 'transcript.final', sequence: 1, text: '不得回写', startMs: 0, endMs: 100
	}) })
	socket.handlers.error()
	socket.handlers.close()
	assert.deepEqual(events, [{ type: 'session.ready' }])
	assert.deepEqual(failures, [])
	delete global.uni
})

test('abort before socket open prevents session.start and resolves the pending connection', async () => {
	const module = await loadModule()
	const socket = fakeSocket()
	global.uni = { connectSocket: () => socket }
	const session = new module.VoiceWebSocketSession({ url: 'wss://localhost:6655/ws/voice' })
	const connected = session.connect({
		ticket: 'A'.repeat(43), protocolVersion: 2, maxDurationMs: 300000
	})

	session.abort('USER_DISCARD')
	socket.handlers.open()
	await connected

	assert.deepEqual(socket.sent, [])
	assert.equal(socket.closed.reason, 'USER_DISCARD')
	delete global.uni
})

test('logs the first binary send boundary without changing the WebSocket payload', async () => {
	const module = await loadModule()
	const socket = fakeSocket()
	const logs = []
	const previousLog = console.log
	console.log = message => logs.push(String(message))
	global.uni = { connectSocket: () => socket }

	try {
		const session = new module.VoiceWebSocketSession({ url: 'wss://localhost:6655/ws/voice' })
		const connected = session.connect({
			ticket: 'A'.repeat(43), protocolVersion: 2, maxDurationMs: 300000
		})
		socket.handlers.open()
		await new Promise(resolve => setImmediate(resolve))
		socket.handlers.message({ data: JSON.stringify({ type: 'session.ready' }) })
		await connected

		const frame = new ArrayBuffer(3200)
		await session.sendAudio(frame)
		await session.stop('RUNTIME_FAILURE')
		const output = logs.join('\n')

		assert.equal(socket.sent[1], frame)
		assert.deepEqual(JSON.parse(socket.sent.at(-1)), { type: 'session.stop' })
		assert.match(output, /event=voice_android_socket_audio phase=FIRST_SEND_ENTERED state=RECORDING bytes=3200/)
		assert.match(output, /event=voice_android_socket_audio phase=FIRST_BINARY_SENT bytes=3200/)
		assert.match(output, /event=voice_android_socket_audio phase=CLIENT_STOP source=RUNTIME_FAILURE/)
	} finally {
		console.log = previousLog
		delete global.uni
	}
})

test('logs binary SocketTask failures without changing the existing error behavior', async () => {
	const module = await loadModule()
	const socket = fakeSocket()
	const warnings = []
	const previousWarn = console.warn
	console.warn = message => warnings.push(String(message))
	socket.send = options => {
		socket.sent.push(options.data)
		if (options.data instanceof ArrayBuffer) options.fail?.()
		else options.success?.()
	}
	global.uni = { connectSocket: () => socket }

	try {
		let failure
		const session = new module.VoiceWebSocketSession({
			url: 'wss://localhost:6655/ws/voice',
			onError: error => { failure = error }
		})
		const connected = session.connect({
			ticket: 'A'.repeat(43), protocolVersion: 2, maxDurationMs: 300000
		})
		socket.handlers.open()
		await new Promise(resolve => setImmediate(resolve))
		socket.handlers.message({ data: JSON.stringify({ type: 'session.ready' }) })
		await connected

		await assert.rejects(
			session.sendAudio(new ArrayBuffer(3200)),
			error => error.code === 'VOICE_UPSTREAM_UNAVAILABLE')
		await new Promise(resolve => setImmediate(resolve))

		assert.equal(failure, undefined)
		assert.match(warnings.join('\n'), /event=voice_android_socket_audio phase=BINARY_SEND_FAILED frameNumber=1 bytes=3200/)
		assert.equal(socket.closed, undefined)
	} finally {
		console.warn = previousWarn
		delete global.uni
	}
})

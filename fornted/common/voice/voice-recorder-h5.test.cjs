const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'voice-recorder-h5.js'), 'utf8')
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

test('permission probe releases its stream and recording reacquires a fresh stream', async () => {
	const module = await loadModule()
	const stopped = []
	const streams = [0, 1].map(index => ({
		getTracks: () => [{ stop: () => stopped.push(index) }]
	}))
	let calls = 0
	Object.defineProperty(globalThis, 'navigator', {
		configurable: true,
		value: { mediaDevices: { getUserMedia: async () => streams[calls++] } }
	})
	Object.defineProperty(globalThis, 'isSecureContext', {
		configurable: true,
		value: true
	})
	globalThis.AudioContext = class {
		constructor() {
			this.state = 'running'
			this.destination = {}
			this.audioWorklet = { addModule: async () => {} }
		}
		createMediaStreamSource() { return { connect() {}, disconnect() {} } }
		createGain() { return { gain: { value: 1 }, connect() {}, disconnect() {} } }
		async close() {}
	}
	globalThis.AudioWorkletNode = class {
		constructor() { this.port = {} }
		connect() {}
		disconnect() {}
	}

	const recorder = new module.H5VoiceRecorder()
	await recorder.requestPermission()
	assert.deepEqual(stopped, [0])
	assert.equal(recorder.stream, null)

	await recorder.start(() => {})
	assert.equal(calls, 2)
	assert.equal(recorder.stream, streams[1])
	await recorder.destroy()
	assert.deepEqual(stopped, [0, 1])

	delete globalThis.AudioContext
	delete globalThis.AudioWorkletNode
	delete globalThis.navigator
	delete globalThis.isSecureContext
})

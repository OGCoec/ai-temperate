const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const renderModulePath = path.resolve(
	__dirname,
	'../../components/user/workspace/user-voice-waveform-render.js')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadRenderer() {
	const source = fs.readFileSync(renderModulePath, 'utf8')
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function recordingContext() {
	const lines = []
	let start = null
	return {
		lines,
		__hidpi__: true,
		lineWidth: 0,
		lineCap: '',
		strokeStyle: '',
		setTransform() {},
		clearRect() {},
		beginPath() { start = null },
		moveTo(x, y) { start = { x, y } },
		lineTo(x, y) {
			lines.push({ ...start, x2: x, y2: y, width: this.lineWidth })
		},
		stroke() {}
	}
}

test('canvas metrics cap DPR and derive a bounded visible bar capacity', async () => {
	const { resolveVoiceWaveformCanvasMetrics } = await loadRenderer()
	const metrics = resolveVoiceWaveformCanvasMetrics(103, 3)

	assert.deepEqual(metrics, {
		cssWidth: 103,
		cssHeight: 24,
		dpr: 2,
		pixelWidth: 206,
		pixelHeight: 48,
		visibleBars: 20
	})
})

test('packets are epoch and sequence guarded, clamped, and limited to five levels', async () => {
	const {
		acceptVoiceWaveformPacket,
		createVoiceWaveformRenderState
	} = await loadRenderer()
	const state = createVoiceWaveformRenderState(4, 7)

	assert.equal(acceptVoiceWaveformPacket(state, {
		epoch: 7,
		sequence: 1,
		levels: [-1, 0.25, 2, 0.5, 0.75, 0.9]
	}, 7, 100), true)
	assert.deepEqual(state.queue, [0, 0.25, 1, 0.5, 0.75])
	assert.equal(acceptVoiceWaveformPacket(state, {
		epoch: 6,
		sequence: 2,
		levels: [1]
	}, 7, 110), false)
	assert.equal(acceptVoiceWaveformPacket(state, {
		epoch: 7,
		sequence: 1,
		levels: [1]
	}, 7, 120), false)
})

test('first and later bars advance only on strict three hundred millisecond boundaries', async () => {
	const {
		acceptVoiceWaveformPacket,
		advanceVoiceWaveformState,
		createVoiceWaveformRenderState,
		voiceWaveformHistory
	} = await loadRenderer()
	const state = createVoiceWaveformRenderState(4, 2)

	assert.equal(advanceVoiceWaveformState(state, 'RECORDING', 0), false)
	for (let sequence = 1; sequence <= 3; sequence += 1) {
		acceptVoiceWaveformPacket(state, {
			epoch: 2,
			sequence,
			levels: [0.4, 0.4, 0.4, 0.4, 0.4]
		}, 2, sequence * 100)
	}

	assert.equal(advanceVoiceWaveformState(state, 'RECORDING', 299), false)
	assert.deepEqual(voiceWaveformHistory(state), [])
	assert.equal(advanceVoiceWaveformState(state, 'RECORDING', 300), true)
	assert.deepEqual(voiceWaveformHistory(state), [Math.fround(0.4)])

	acceptVoiceWaveformPacket(state, {
		epoch: 2,
		sequence: 4,
		levels: [0.8, 0.8, 0.8, 0.8, 0.8]
	}, 2, 400)
	assert.equal(advanceVoiceWaveformState(state, 'RECORDING', 599), false)
	assert.equal(voiceWaveformHistory(state).length, 1)
	assert.equal(advanceVoiceWaveformState(state, 'RECORDING', 600), true)
	assert.deepEqual(voiceWaveformHistory(state), [Math.fround(0.4), Math.fround(0.8)])
})

test('fifteen twenty millisecond values collapse into one RMS bar', async () => {
	const {
		acceptVoiceWaveformPacket,
		advanceVoiceWaveformState,
		createVoiceWaveformRenderState,
		voiceWaveformHistory
	} = await loadRenderer()
	const state = createVoiceWaveformRenderState(3, 4)
	const values = [
		0.1, 0.2, 0.3, 0.4, 0.5,
		0.6, 0.7, 0.8, 0.9, 1,
		0.9, 0.8, 0.7, 0.6, 0.5
	]
	const expected = Math.sqrt(
		values.reduce((sum, value) => sum + value * value, 0) / values.length)

	advanceVoiceWaveformState(state, 'RECORDING', 1000)
	for (let index = 0; index < 3; index += 1) {
		acceptVoiceWaveformPacket(state, {
			epoch: 4,
			sequence: index + 1,
			levels: values.slice(index * 5, index * 5 + 5)
		}, 4, 1100 + index * 100)
	}
	advanceVoiceWaveformState(state, 'RECORDING', 1300)

	assert.equal(state.queue.length, 0)
	assert.ok(Math.abs(voiceWaveformHistory(state)[0] - expected) < 1e-6)
})

test('visual backlog and history stay bounded without catch-up bursts', async () => {
	const {
		acceptVoiceWaveformPacket,
		advanceVoiceWaveformState,
		createVoiceWaveformRenderState,
		voiceWaveformHistory
	} = await loadRenderer()
	const state = createVoiceWaveformRenderState(3, 2)
	advanceVoiceWaveformState(state, 'RECORDING', 0)

	for (let sequence = 1; sequence <= 4; sequence += 1) {
		acceptVoiceWaveformPacket(state, {
			epoch: 2,
			sequence,
			levels: [sequence / 10, sequence / 10, sequence / 10, sequence / 10, sequence / 10]
		}, 2, sequence * 100)
	}
	assert.equal(state.queue.length, 15)

	assert.equal(advanceVoiceWaveformState(state, 'RECORDING', 950), true)
	assert.equal(voiceWaveformHistory(state).length, 1)
	assert.equal(advanceVoiceWaveformState(state, 'RECORDING', 951), false)
	assert.equal(voiceWaveformHistory(state).length, 1)
	for (let cycle = 0; cycle < 5; cycle += 1) {
		acceptVoiceWaveformPacket(state, {
			epoch: 2,
			sequence: 5 + cycle,
			levels: [0.5, 0.5, 0.5, 0.5, 0.5]
		}, 2, 1000 + cycle * 300)
		advanceVoiceWaveformState(state, 'RECORDING', 1250 + cycle * 300)
	}
	assert.ok(voiceWaveformHistory(state).length <= 3)
	assert.equal(state.queue.length, 0)
})

test('finalizing clears visual history, rejects late packets, and stays on a static baseline', async () => {
	const { default: renderer, voiceWaveformHistory } = await loadRenderer()
	const originalRaf = globalThis.requestAnimationFrame
	const originalCancelRaf = globalThis.cancelAnimationFrame
	let rafCount = 0
	globalThis.requestAnimationFrame = () => ++rafCount
	globalThis.cancelAnimationFrame = () => {}

	try {
		const instance = {
			...renderer.data(),
			...renderer.methods,
			config: {
				state: 'RECORDING',
				sessionEpoch: 3,
				packet: null,
				reduced: false
			},
			visible: true,
			hidden: false,
			connectCanvas() {},
			draw() {}
		}
		instance.renderState.sessionEpoch = 3
		instance.renderState.queue.push(0.8)
		instance.renderState.history[0] = 0.8
		instance.renderState.historyLength = 1

		instance.update({
			state: 'FINALIZING',
			sessionEpoch: 3,
			packet: { epoch: 3, sequence: 9, levels: [1] },
			reduced: false
		})

		assert.deepEqual(instance.renderState.queue, [])
		assert.deepEqual(voiceWaveformHistory(instance.renderState), [])
		assert.equal(instance.renderState.currentLevel, 0)
		assert.equal(rafCount, 0)
		assert.equal(instance.running, false)
	} finally {
		globalThis.requestAnimationFrame = originalRaf
		globalThis.cancelAnimationFrame = originalCancelRaf
	}
})

test('waveform bars remain centered and between the two and twenty pixel limits', async () => {
	const { drawVoiceWaveformFrame } = await loadRenderer()
	const context = recordingContext()

	drawVoiceWaveformFrame(context, {
		width: 50,
		height: 24,
		levels: [0, 0.5, 1]
	})

	assert.equal(context.lines.length, 10)
	for (const line of context.lines) {
		const height = line.y2 - line.y
		assert.ok(height >= 2 && height <= 20)
		assert.equal((line.y + line.y2) / 2, 12)
		assert.equal(line.width, 2)
	}
})

test('reduced motion uses the same three hundred millisecond cadence without continuous RAF', async () => {
	const { default: renderer } = await loadRenderer()
	const originalRaf = globalThis.requestAnimationFrame
	const originalCancelRaf = globalThis.cancelAnimationFrame
	const originalSetTimeout = globalThis.setTimeout
	const originalClearTimeout = globalThis.clearTimeout
	let rafCount = 0
	let timeoutCount = 0
	let timeoutDelay = 0
	globalThis.requestAnimationFrame = () => ++rafCount
	globalThis.cancelAnimationFrame = () => {}
	globalThis.setTimeout = (callback, delay) => {
		timeoutDelay = delay
		return ++timeoutCount
	}
	globalThis.clearTimeout = () => {}

	try {
		const instance = {
			...renderer.data(),
			...renderer.methods,
			config: {
				state: 'RECORDING',
				sessionEpoch: 1,
				packet: null,
				reduced: true
			},
			visible: true,
			hidden: false,
			draw() {}
		}
		instance.restart()
		assert.equal(rafCount, 0)
		assert.equal(timeoutCount, 1)
		assert.equal(timeoutDelay, 300)
		instance.stop()
	} finally {
		globalThis.requestAnimationFrame = originalRaf
		globalThis.cancelAnimationFrame = originalCancelRaf
		globalThis.setTimeout = originalSetTimeout
		globalThis.clearTimeout = originalClearTimeout
	}
})

test('component passes only visual levels and keeps PCM out of the view layer', () => {
	const component = fs.readFileSync(path.resolve(
		__dirname,
		'../../components/user/workspace/user-voice-waveform.vue'), 'utf8')

	assert.match(component, /aria-hidden="true"/)
	assert.match(component, /levels\.slice\(0, 5\)/)
	assert.match(component, /:hidpi="false"/)
	assert.doesNotMatch(component, /ArrayBuffer|DataView|PCM|sendAudio|WebSocket/)
})

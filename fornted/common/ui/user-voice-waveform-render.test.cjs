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
	}, 100), true)
	assert.deepEqual(state.queue, [0, 0.25, 1, 0.5, 0.75])
	assert.equal(acceptVoiceWaveformPacket(state, {
		epoch: 6,
		sequence: 2,
		levels: [1]
	}, 110), false)
	assert.equal(acceptVoiceWaveformPacket(state, {
		epoch: 7,
		sequence: 1,
		levels: [1]
	}, 120), false)
})

test('visual backlog drops old levels while audio-independent history stays bounded', async () => {
	const {
		acceptVoiceWaveformPacket,
		advanceVoiceWaveformState,
		createVoiceWaveformRenderState,
		voiceWaveformHistory
	} = await loadRenderer()
	const state = createVoiceWaveformRenderState(3, 2)

	for (let sequence = 1; sequence <= 4; sequence += 1) {
		acceptVoiceWaveformPacket(state, {
			epoch: 2,
			sequence,
			levels: [sequence / 10, sequence / 10, sequence / 10, sequence / 10, sequence / 10]
		}, sequence * 100)
	}
	assert.equal(state.queue.length, 15)

	advanceVoiceWaveformState(state, 'RECORDING', 400)
	advanceVoiceWaveformState(state, 'RECORDING', 440)
	assert.ok(voiceWaveformHistory(state).length <= 3)
	assert.ok(state.queue.length < 15)
})

test('stalled recording decays to real silence and finalizing reaches zero in 180ms', async () => {
	const {
		acceptVoiceWaveformPacket,
		advanceVoiceWaveformState,
		createVoiceWaveformRenderState,
		resolveVoiceWaveformFinalizingScale,
		voiceWaveformHistory
	} = await loadRenderer()
	const state = createVoiceWaveformRenderState(5, 3)
	acceptVoiceWaveformPacket(state, {
		epoch: 3,
		sequence: 1,
		levels: [1]
	}, 100)

	advanceVoiceWaveformState(state, 'RECORDING', 100)
	advanceVoiceWaveformState(state, 'RECORDING', 360)
	const history = voiceWaveformHistory(state)
	assert.ok(history.at(-1) < history[0])
	assert.equal(resolveVoiceWaveformFinalizingScale(500, 500), 1)
	assert.equal(resolveVoiceWaveformFinalizingScale(500, 680), 0)
})

test('waveform bars remain centered and between the two and twenty pixel limits', async () => {
	const { drawVoiceWaveformFrame } = await loadRenderer()
	const context = recordingContext()

	drawVoiceWaveformFrame(context, {
		width: 50,
		height: 24,
		levels: [0, 0.5, 1],
		finalizingScale: 1
	})

	assert.equal(context.lines.length, 10)
	for (const line of context.lines) {
		const height = line.y2 - line.y
		assert.ok(height >= 2 && height <= 20)
		assert.equal((line.y + line.y2) / 2, 12)
		assert.equal(line.width, 2)
	}
})

test('reduced motion schedules a five hertz timer without continuous RAF', async () => {
	const { default: renderer } = await loadRenderer()
	const originalRaf = globalThis.requestAnimationFrame
	const originalCancelRaf = globalThis.cancelAnimationFrame
	const originalSetTimeout = globalThis.setTimeout
	const originalClearTimeout = globalThis.clearTimeout
	let rafCount = 0
	let timeoutCount = 0
	globalThis.requestAnimationFrame = () => ++rafCount
	globalThis.cancelAnimationFrame = () => {}
	globalThis.setTimeout = () => ++timeoutCount
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

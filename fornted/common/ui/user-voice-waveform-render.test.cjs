const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const renderModulePath = path.resolve(
	__dirname,
	'../../components/user/workspace/user-voice-waveform-render.js')
const timelineModulePath = path.resolve(
	__dirname,
	'../voice/voice-waveform-timeline.js')
const presentationModulePath = path.resolve(
	__dirname,
	'../voice/voice-waveform-presentation.js')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadRenderer() {
	const timelineUrl = sourceUrl(fs.readFileSync(timelineModulePath, 'utf8'))
	const presentationUrl = sourceUrl(fs.readFileSync(presentationModulePath, 'utf8')
		.replace('./voice-waveform-timeline.js', timelineUrl))
	const source = fs.readFileSync(renderModulePath, 'utf8')
		.replace('../../../common/voice/voice-waveform-timeline.js', timelineUrl)
		.replace('../../../common/voice/voice-waveform-presentation.js', presentationUrl)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function recordingContext() {
	const lines = []
	const setTransformCalls = []
	const clearRectCalls = []
	let start = null
	return {
		lines,
		setTransformCalls,
		clearRectCalls,
		lineWidth: 0,
		lineCap: '',
		strokeStyle: '',
		setTransform(...values) { setTransformCalls.push(values) },
		clearRect(...values) { clearRectCalls.push(values) },
		beginPath() { start = null },
		moveTo(x, y) { start = { x, y } },
		lineTo(x, y) {
			lines.push({ ...start, x2: x, y2: y, width: this.lineWidth })
		},
		stroke() { lines.at(-1).color = this.strokeStyle }
	}
}

function createCanvasFixture(renderer, state = 'IDLE') {
	const context = recordingContext()
	const canvas = {
		tagName: 'CANVAS',
		width: 80,
		height: 24,
		clientWidth: 80,
		isConnected: true,
		style: {},
		getContext: () => context
	}
	const root = {
		clientWidth: 80,
		querySelector: selector => selector === '.user-voice-waveform-canvas'
			? canvas : null
	}
	return {
		context,
		canvas,
		instance: {
			...renderer.data(),
			...renderer.methods,
			root,
			config: {
				state,
				sessionEpoch: 1,
				packet: null,
				reduced: false
			}
		}
	}
}

test('canvas metrics use full DPR storage and the shared 5.5px pitch', async () => {
	const { resolveVoiceWaveformCanvasMetrics } = await loadRenderer()
	assert.deepEqual(resolveVoiceWaveformCanvasMetrics(103, 3), {
		cssWidth: 103,
		cssHeight: 24,
		dpr: 3,
		pixelWidth: 309,
		pixelHeight: 72,
		visibleBars: 18
	})
})

test('wide H5 Canvas uses its full width beyond the shared 192-bar default', async () => {
	const { resolveVoiceWaveformCanvasMetrics } = await loadRenderer()

	assert.deepEqual(resolveVoiceWaveformCanvasMetrics(1600, 2), {
		cssWidth: 1600,
		cssHeight: 24,
		dpr: 2,
		pixelWidth: 3200,
		pixelHeight: 48,
		visibleBars: 290
	})
})

test('wide H5 drawing does not clip after the first 192 bars', async () => {
	const { drawVoiceWaveformFrame } = await loadRenderer()
	const context = recordingContext()
	const bars = Array.from({ length: 290 }, (_, index) => ({
		id: index + 1,
		level: 0,
		recorded: false
	}))

	drawVoiceWaveformFrame(context, {
		width: 1600,
		height: 24,
		bars,
		progress: 0
	})

	assert.equal(context.lines.length, 290)
	assert.ok(context.lines.at(-1).x > 1500)
})

test('H5 native Canvas keeps its DPR-owned first draw', async () => {
	const { default: renderer } = await loadRenderer()
	const previousDpr = globalThis.devicePixelRatio
	globalThis.devicePixelRatio = 2
	const { canvas, context, instance } = createCanvasFixture(renderer)

	try {
		assert.equal(instance.connectCanvas(), true)
		assert.equal(instance.draw(0), true)
		assert.equal(canvas.width, 160)
		assert.equal(canvas.height, 48)
		assert.deepEqual(context.setTransformCalls.slice(0, 2), [
			[1, 0, 0, 1, 0, 0],
			[2, 0, 0, 2, 0, 0]
		])
		assert.deepEqual(context.clearRectCalls[0], [0, 0, 160, 48])
	} finally {
		if (previousDpr == null) delete globalThis.devicePixelRatio
		else globalThis.devicePixelRatio = previousDpr
	}
})

test('H5 discovery keeps only the inner native Canvas', async () => {
	const { default: renderer } = await loadRenderer()
	const context = recordingContext()
	const nativeCanvas = {
		tagName: 'CANVAS',
		clientWidth: 80,
		isConnected: true,
		style: {},
		getContext: () => context
	}
	const host = {
		tagName: 'DIV',
		clientWidth: 80,
		style: {},
		getContext: () => context,
		querySelector: selector => selector.includes('canvas') ? nativeCanvas : null
	}
	const instance = {
		...renderer.data(),
		...renderer.methods,
		root: {
			clientWidth: 80,
			querySelector: selector => selector === '.user-voice-waveform-canvas'
				? host : null
		},
		config: { state: 'IDLE', sessionEpoch: 1, packet: null, reduced: false }
	}

	assert.equal(instance.connectCanvas(), true)
	assert.equal(instance.canvas, nativeCanvas)
	assert.notEqual(instance.canvas, host)
})

test('the integrated Canvas loop draws a full gray baseline', async () => {
	const { drawVoiceWaveformFrame } = await loadRenderer()
	const context = recordingContext()
	const bars = Array.from({ length: 10 }, (_, index) => ({
		id: index + 1,
		level: 0,
		recorded: false
	}))

	drawVoiceWaveformFrame(context, {
		width: 50,
		height: 24,
		bars,
		progress: 0
	})

	assert.equal(context.lines.length, 9)
	assert.ok(context.lines.every(line => line.y2 - line.y === 2))
	assert.ok(context.lines.every(line => line.color === 'rgba(174,185,179,0.24)'))
	assert.ok(context.lines.every(line => line.width === 2.5))
})

test('the first recorded bar appears at the right edge and moves left with progress', async () => {
	const { drawVoiceWaveformFrame } = await loadRenderer()
	const bars = Array.from({ length: 10 }, (_, index) => ({
		id: index + 1,
		level: index === 8 ? 1 : 0,
		recorded: index === 8
	}))
	const atBoundary = recordingContext()
	const halfCycle = recordingContext()

	drawVoiceWaveformFrame(atBoundary, {
		width: 50,
		height: 24,
		bars,
		progress: 0
	})
	drawVoiceWaveformFrame(halfCycle, {
		width: 50,
		height: 24,
		bars,
		progress: 0.5
	})

	const firstSound = atBoundary.lines.find(line => line.y2 - line.y === 20)
	const movedSound = halfCycle.lines.find(line => line.y2 - line.y === 20)
	assert.equal(firstSound.x, 45.25)
	assert.equal(movedSound.x, 42.5)
	assert.equal(firstSound.color, 'rgba(205,211,208,0.88)')
	assert.equal(halfCycle.lines.at(-1).x, 48)
})

test('the unsettled trailing slot keeps movement geometry but never draws a zero-level bar', async () => {
	const { drawVoiceWaveformFrame } = await loadRenderer()
	const context = recordingContext()
	const bars = Array.from({ length: 10 }, (_, index) => ({
		id: index + 1,
		level: index === 8 ? 0.7 : 0,
		recorded: index === 8
	}))

	drawVoiceWaveformFrame(context, {
		width: 50,
		height: 24,
		bars,
		progress: 0.5,
		pendingBarId: bars.at(-1).id
	})

	assert.equal(context.lines.length, 8)
	assert.doesNotMatch(
		context.lines.map(line => String(line.x)).join(','),
		/(?:^|,)48(?:,|$)/)
	assert.equal(context.lines.filter(line => line.y2 - line.y > 2).length, 1)
})

test('H5 renderer consumes the shared timeline instead of owning a second 300ms state machine', () => {
	const source = fs.readFileSync(renderModulePath, 'utf8')

	assert.match(source, /createVoiceWaveformTimeline/)
	assert.match(source, /H5_VOICE_WAVEFORM_MAX_CAPACITY\s*=\s*512/)
	assert.match(source,
		/createVoiceWaveformTimeline\(\{[\s\S]*maxCapacity:\s*H5_VOICE_WAVEFORM_MAX_CAPACITY/)
	assert.match(source, /voice-waveform-presentation\.js/)
	assert.doesNotMatch(source, /VISUAL_INTERVAL_MS|MAXIMUM_QUEUE_LEVELS/)
	assert.doesNotMatch(source, /aggregateVoiceWaveformLevels|createVoiceWaveformRenderState/)
	assert.doesNotMatch(source, /117,\s*223,\s*183|55,\s*211,\s*154/)
})

test('finalizing clears the timeline and never starts another RAF', async () => {
	const { default: renderer } = await loadRenderer()
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
			connectCanvas() { return false },
			draw() { return false }
		}
		instance.timeline.start(3, 0)

		instance.update({
			state: 'FINALIZING',
			sessionEpoch: 3,
			packet: { epoch: 3, sequence: 9, levels: [1] },
			reduced: false
		})

		assert.deepEqual(instance.timeline.snapshot(0).movingBars, [])
		assert.equal(rafCount, 0)
		assert.equal(instance.running, false)
	} finally {
		globalThis.requestAnimationFrame = originalRaf
		globalThis.cancelAnimationFrame = originalCancelRaf
	}
})

test('reduced motion keeps the 300ms cadence without continuous RAF', async () => {
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
			draw() { return true }
		}
		instance.timeline.start(1, 0)
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

test('a Canvas exception fails open without entering the voice business path', async () => {
	const { default: renderer } = await loadRenderer()
	const { canvas, context, instance } = createCanvasFixture(renderer, 'RECORDING')
	context.clearRect = () => { throw new Error('synthetic canvas failure') }
	instance.canvasHost = canvas
	instance.canvas = canvas
	instance.context = context
	instance.timeline.start(1, 0)

	assert.equal(instance.draw(0), false)
	assert.equal(instance.running, false)
})

test('component input stays visual-only and H5 Canvas lifecycle stays native', () => {
	const component = fs.readFileSync(path.resolve(
		__dirname,
		'../../components/user/workspace/user-voice-waveform.vue'), 'utf8')
	const renderer = fs.readFileSync(renderModulePath, 'utf8')

	assert.match(component, /aria-hidden="true"/)
	assert.match(component, /levels\.slice\(0, 5\)/)
	assert.match(component, /:hidpi="false"/)
	assert.doesNotMatch(component, /ArrayBuffer|DataView|PCM|sendAudio|WebSocket/)
	assert.doesNotMatch(renderer, /handleVoiceFailure|sendAudio|WebSocket/)
	assert.match(renderer, /IntersectionObserver/)
	assert.match(renderer, /ResizeObserver/)
	assert.match(renderer, /this\.canvas\.width\s*=\s*metrics\.pixelWidth/)
	assert.match(renderer, /setTransform\(metrics\.dpr/)
})

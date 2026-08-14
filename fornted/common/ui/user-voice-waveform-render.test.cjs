const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const renderModulePath = path.resolve(
	__dirname,
	'../../components/user/workspace/user-voice-waveform-render.js')
const debugModulePath = path.resolve(
	__dirname,
	'../../components/user/workspace/user-voice-waveform-debug.js')
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
	const debugUrl = sourceUrl(fs.readFileSync(debugModulePath, 'utf8'))
	const source = fs.readFileSync(renderModulePath, 'utf8')
		.replace('../../../common/voice/voice-waveform-timeline.js', timelineUrl)
		.replace('../../../common/voice/voice-waveform-presentation.js', presentationUrl)
		.replace('./user-voice-waveform-debug.js', debugUrl)
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

function createNativeCanvasHostFixture(width = 80) {
	const context = recordingContext()
	const children = []
	const host = {
		clientWidth: width,
		clientHeight: 24,
		style: {},
		children,
		getBoundingClientRect: () => ({ width, height: 24, left: 0, right: width }),
		querySelector(selector) {
			return selector === 'canvas.user-voice-waveform-native-canvas'
				? children[0] || null : null
		},
		appendChild(child) {
			child.parentElement = this
			children.push(child)
			return child
		}
	}
	const documentRef = {
		createElement(tagName) {
			assert.equal(tagName, 'canvas')
			return {
				tagName: 'CANVAS',
				width: 300,
				height: 150,
				clientWidth: width,
				clientHeight: 24,
				offsetWidth: width,
				offsetHeight: 24,
				isConnected: true,
				style: {},
				attributes: {},
				getBoundingClientRect: () => ({ width, height: 24, left: 0, right: width }),
				getContext: () => context,
				setAttribute(name, value) { this.attributes[name] = String(value) },
				remove() { this.removed = true }
			}
		}
	}
	return { context, host, documentRef }
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

test('Android phone width resolves directly to visible slots without a 192-bar backlog', async () => {
	const { resolveVoiceWaveformCanvasMetrics } = await loadRenderer()

	assert.deepEqual(resolveVoiceWaveformCanvasMetrics(440, 3), {
		cssWidth: 440,
		cssHeight: 24,
		dpr: 3,
		pixelWidth: 1320,
		pixelHeight: 72,
		visibleBars: 80
	})
})

test('Android display level uses exponent 1.3 and caps the visual score at 0.82', async () => {
	const { resolveVoiceWaveformDisplayLevel } = await loadRenderer()

	assert.equal(resolveVoiceWaveformDisplayLevel(0, 'android'), 0)
	assert.equal(resolveVoiceWaveformDisplayLevel(0.5, 'android'), Math.pow(0.5, 1.3))
	assert.equal(resolveVoiceWaveformDisplayLevel(1, 'android'), 0.82)
	assert.equal(resolveVoiceWaveformDisplayLevel(10, 'android'), 0.82)
	assert.equal(resolveVoiceWaveformDisplayLevel(0.5, 'h5'), 0.5)
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

test('H5 creates one renderer-owned native Canvas and reuses it', async () => {
	const { ensureH5NativeCanvas } = await loadRenderer()
	const { host, documentRef } = createNativeCanvasHostFixture(120)

	const first = ensureH5NativeCanvas(host, documentRef)
	const second = ensureH5NativeCanvas(host, documentRef)

	assert.ok(first)
	assert.equal(first, second)
	assert.equal(host.children.length, 1)
	assert.equal(first.className, 'user-voice-waveform-native-canvas')
	assert.equal(first.attributes['aria-hidden'], 'true')
	assert.equal(first.attributes['data-voice-waveform-owner'], 'renderer-native')
})

test('H5 renderer-owned Canvas uses host geometry and configures DPR before drawing', async () => {
	const { default: renderer, ensureH5NativeCanvas } = await loadRenderer()
	const previousDpr = globalThis.devicePixelRatio
	globalThis.devicePixelRatio = 1.5
	const { context, host, documentRef } = createNativeCanvasHostFixture(103)
	const canvas = ensureH5NativeCanvas(host, documentRef)
	const root = {
		clientWidth: 999,
		getBoundingClientRect: () => ({ width: 999, height: 24 }),
		querySelector(selector) {
			if (selector === '.user-voice-waveform-native-host') return host
			return null
		}
	}
	const instance = {
		...renderer.data(),
		...renderer.methods,
		root,
		canvasHost: host,
		canvas,
		context,
		canvasOwnedByRenderer: true,
		config: { state: 'IDLE', sessionEpoch: 1, packet: null, reduced: false, profile: 'h5' }
	}

	try {
		const metrics = instance.configureCanvas()
		assert.equal(metrics.cssWidth, 103)
		assert.equal(metrics.pixelWidth, 154)
		assert.equal(metrics.pixelHeight, 36)
		assert.equal(canvas.width, 154)
		assert.equal(canvas.height, 36)
		assert.equal(canvas.style.width, '103px')
		assert.equal(canvas.style.height, '24px')
		assert.equal(Object.hasOwn(context, '__hidpi__'), false)
		assert.equal(instance.draw(0), true)
		assert.deepEqual(context.setTransformCalls.slice(-2), [
			[1, 0, 0, 1, 0, 0],
			[1.5, 0, 0, 1.5, 0, 0]
		])
	} finally {
		if (previousDpr == null) delete globalThis.devicePixelRatio
		else globalThis.devicePixelRatio = previousDpr
	}
})

test('cached H5 animation frames do not remeasure or rewrite Canvas dimensions', async () => {
	const { default: renderer, ensureH5NativeCanvas } = await loadRenderer()
	const { context, host, documentRef } = createNativeCanvasHostFixture(100)
	let measurements = 0
	host.getBoundingClientRect = () => {
		measurements += 1
		return { width: 100, height: 24, left: 0, right: 100 }
	}
	const canvas = ensureH5NativeCanvas(host, documentRef)
	let widthWrites = 0
	let heightWrites = 0
	let pixelWidth = canvas.width
	let pixelHeight = canvas.height
	Object.defineProperty(canvas, 'width', {
		get: () => pixelWidth,
		set(value) { widthWrites += 1; pixelWidth = value },
		configurable: true
	})
	Object.defineProperty(canvas, 'height', {
		get: () => pixelHeight,
		set(value) { heightWrites += 1; pixelHeight = value },
		configurable: true
	})
	const instance = {
		...renderer.data(),
		...renderer.methods,
		root: { clientWidth: 999 },
		canvasHost: host,
		canvas,
		context,
		canvasOwnedByRenderer: true,
		config: { state: 'IDLE', sessionEpoch: 1, packet: null, reduced: false, profile: 'h5' }
	}

	assert.ok(instance.configureCanvas())
	const measuredOnce = measurements
	const widthWritesOnce = widthWrites
	const heightWritesOnce = heightWrites
	assert.ok(instance.configureCanvas())
	assert.equal(measurements, measuredOnce)
	assert.equal(widthWrites, widthWritesOnce)
	assert.equal(heightWrites, heightWritesOnce)
})

test('H5 keeps zero-width metrics dirty and configures immediately when the host becomes visible', async () => {
	const { default: renderer, ensureH5NativeCanvas } = await loadRenderer()
	const { context, host, documentRef } = createNativeCanvasHostFixture(0)
	let width = 0
	host.clientWidth = 0
	host.getBoundingClientRect = () => ({ width, height: 24, left: 0, right: width })
	const canvas = ensureH5NativeCanvas(host, documentRef)
	const instance = {
		...renderer.data(),
		...renderer.methods,
		root: { clientWidth: 500 },
		canvasHost: host,
		canvas,
		context,
		canvasOwnedByRenderer: true,
		config: { state: 'IDLE', sessionEpoch: 1, packet: null, reduced: false, profile: 'h5' }
	}

	assert.equal(instance.configureCanvas(), null)
	assert.equal(instance.canvasMetrics, null)
	assert.equal(instance.metricsDirty, true)
	width = 90
	const metrics = instance.configureCanvas()
	assert.equal(metrics.cssWidth, 90)
	assert.equal(instance.metricsDirty, false)
	assert.equal(canvas.width, 90)
})

test('H5 debug snapshots reread actual backing dimensions and teardown removes the owned Canvas', async () => {
	const { default: renderer, ensureH5NativeCanvas } = await loadRenderer()
	const { context, host, documentRef } = createNativeCanvasHostFixture(100)
	const canvas = ensureH5NativeCanvas(host, documentRef)
	const instance = {
		...renderer.data(),
		...renderer.methods,
		root: { clientWidth: 100, getBoundingClientRect: () => ({ width: 100, height: 24 }) },
		canvasHost: host,
		canvas,
		context,
		canvasOwnedByRenderer: true,
		canvasOwner: 'RENDERER_NATIVE',
		debugController: { update() {}, record() {}, destroy() {} },
		config: { state: 'RECORDING', sessionEpoch: 1, packet: null, reduced: false, profile: 'h5' }
	}
	instance.timeline.dispose = () => {}
	instance.configureCanvas()
	instance.debugLastGeometryReadAt = -Infinity
	assert.equal(instance.buildDebugSnapshot(100).geometry.canvas.pixelWidth, 100)
	canvas.width = 50
	instance.debugLastGeometryReadAt = 0
	assert.equal(instance.buildDebugSnapshot(200).geometry.canvas.pixelWidth, 50)
	instance.teardown()
	assert.equal(canvas.removed, true)
})

test('Android avoids a second DPR transform supplied by the App-Plus Canvas', async () => {
	const { default: renderer, resolveVoiceWaveformContextScale } = await loadRenderer()
	const previousDpr = globalThis.devicePixelRatio
	globalThis.devicePixelRatio = 2
	const { canvas, context, instance } = createCanvasFixture(renderer, 'RECORDING')
	const originalLog = console.log

	console.log = () => {}
	try {
		instance.config.profile = 'android'
		assert.equal(resolveVoiceWaveformContextScale('android', 2), 1)
		assert.equal(instance.connectCanvas(), true)
		assert.equal(instance.draw(0), true)
		assert.equal(canvas.width, 160)
		assert.equal(canvas.height, 48)
		assert.deepEqual(context.setTransformCalls.slice(0, 2), [
			[1, 0, 0, 1, 0, 0],
			[1, 0, 0, 1, 0, 0]
		])
	} finally {
		console.log = originalLog
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

test('renderer consumes the shared timeline and uses an explicit max capacity constant', () => {
	const source = fs.readFileSync(renderModulePath, 'utf8')

	assert.match(source, /createVoiceWaveformTimeline/)
	assert.match(source, /CANVAS_VOICE_WAVEFORM_MAX_CAPACITY\s*=\s*512/)
	assert.match(source,
		/createVoiceWaveformTimeline\([\s\S]*maxCapacity:\s*CANVAS_VOICE_WAVEFORM_MAX_CAPACITY/)
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
	assert.match(component, /#ifdef H5[\s\S]*class="user-voice-waveform-native-host"[\s\S]*#endif/)
	assert.match(component, /#ifdef APP-PLUS[\s\S]*<canvas[\s\S]*:hidpi="false"[\s\S]*#endif/)
	const h5TemplateBlock = component.match(
		/<!-- #ifdef H5 -->([\s\S]*?)<!-- #endif -->/)?.[1] || ''
	assert.doesNotMatch(h5TemplateBlock, /<canvas/)
	assert.doesNotMatch(component, /ArrayBuffer|DataView|PCM|sendAudio|WebSocket/)
	assert.doesNotMatch(renderer, /handleVoiceFailure|sendAudio|WebSocket/)
	assert.match(renderer, /IntersectionObserver/)
	assert.match(renderer, /ResizeObserver/)
	assert.match(renderer, /ensureH5NativeCanvas/)
	assert.match(renderer, /data-voice-waveform-owner/)
	assert.match(renderer, /this\.canvas\.width\s*=\s*metrics\.pixelWidth/)
	assert.match(renderer, /resolveVoiceWaveformContextScale\(profile, metrics\.dpr\)/)
	assert.match(component, /isVoiceWaveformDebugEnabled/)
	assert.match(component, /debug:\s*H5_VOICE_WAVEFORM_DEBUG_ENABLED/)
	assert.match(renderer, /createVoiceWaveformDebugController/)
	assert.match(renderer, /config\?\.profile\s*!==\s*'android'/)
	assert.doesNotMatch(component, /debug:\s*true/)
	assert.doesNotMatch(component, /debug:\s*\{\s*type:/)
})

test('Android profile applies the capped display score to symmetric bars', async () => {
	const { drawVoiceWaveformFrame } = await loadRenderer()
	const context = recordingContext()
	const bars = Array.from({ length: 5 }, (_, index) => ({
		id: index + 1,
		level: index === 3 ? 1 : 0,
		recorded: index === 3
	}))

	drawVoiceWaveformFrame(context, {
		width: 30,
		height: 24,
		bars,
		progress: 0,
		profile: 'android'
	})

	// 不再额外绘制贯穿整个波形区的水平中轴线。
	const midline = context.lines.find(line =>
		line.width === 1 && line.x === 0)
	assert.equal(midline, undefined)

	// 最大柱高 14px，柱宽 2px。
	const soundBar = context.lines.find(line =>
		Math.abs((line.y2 - line.y) - 11.84) < 0.001)
	assert.ok(soundBar, 'max-volume Android bar height must be capped at 11.84px')
	assert.equal(soundBar.width, 2)

	// 14px 柱的端点为 5px 和 19px（围绕中轴线 12px 上下各 7px）。
	assert.ok(Math.abs(soundBar.y - 6.08) < 0.001)
	assert.ok(Math.abs(soundBar.y2 - 17.92) < 0.001)
})

test('Android newest settled bar is immediately inside the rightmost visible slot', async () => {
	const { drawVoiceWaveformFrame } = await loadRenderer()
	const context = recordingContext()
	const bars = Array.from({ length: 81 }, (_, index) => ({
		id: index + 1,
		level: index === 79 ? 1 : 0,
		recorded: index === 79
	}))

	drawVoiceWaveformFrame(context, {
		width: 440,
		height: 24,
		bars,
		progress: 0,
		pendingBarId: bars.at(-1).id,
		profile: 'android'
	})

	const newestBar = context.lines.find(line =>
		Math.abs((line.y2 - line.y) - 11.84) < 0.001)
	assert.ok(newestBar)
	assert.equal(newestBar.x, 435.5)
	assert.ok(newestBar.x < 440)
})

test('Android logs one privacy-safe green diagnostic per committed 300ms bar', async () => {
	const { default: renderer } = await loadRenderer()
	const { canvas, context, instance } = createCanvasFixture(renderer, 'RECORDING')
	const originalLog = console.log
	const originalDateNow = Date.now
	const logs = []
	const settledBars = Array.from({ length: 14 }, (_, index) => ({
		id: index + 1,
		level: index === 13 ? 1 : 0,
		recorded: index === 13
	}))

	console.log = line => logs.push(String(line))
	Date.now = () => 1300
	try {
		instance.config = {
			state: 'RECORDING',
			sessionEpoch: 4,
			packet: null,
			reduced: false,
			profile: 'android'
		}
		instance.canvasHost = canvas
		instance.canvas = canvas
		instance.context = context
		instance.metricsDirty = false
		instance.canvasMetrics = {
			cssWidth: 80,
			cssHeight: 24,
			dpr: 1,
			pixelWidth: 80,
			pixelHeight: 24,
			visibleBars: 14
		}
		instance.timeline = {
			advance: () => true,
			snapshot: () => ({
				epoch: 4,
				cycle: 1,
				capacity: 14,
				settledBars,
				movingBars: [...settledBars, { id: 15, level: 0, recorded: false }],
				progress: 0
			})
		}
		instance.resetAndroidWaveformDiagnostics(4)
		instance.recordAndroidWaveformDiagnosticPacket({
			epoch: 4,
			sequence: 7,
			publishedAtMs: 1000,
			levels: [0, 0, 0, 0, 0]
		}, 1010)

		assert.equal(instance.draw(300), true)
		assert.equal(instance.draw(301), true)
	} finally {
		console.log = originalLog
		Date.now = originalDateNow
	}

	const barLogs = logs.filter(line => line.includes('phase=BAR_DRAWN'))
	assert.equal(barLogs.length, 1)
	assert.match(barLogs[0], /epoch=4 cycle=1 sequence=7/)
	assert.match(barLogs[0], /tickGapMs=-1 bridgeMs=10/)
	assert.match(barLogs[0], /firstPacketAgeMs=300 latestPacketAgeMs=300/)
	assert.match(barLogs[0], /packetCount=1 levelCount=5/)
	assert.match(barLogs[0], /cssWidth=80 capacity=14 spanPx=77 unusedPx=3 latestSlotX=72\.5/)
	assert.doesNotMatch(barLogs[0],
		/(?:^|\s)(?:level|levels|barHeight|pcm|base64|transcript)=/i)
})

test('Android emits centered Canvas metrics on initial measurement', async () => {
	const { default: renderer } = await loadRenderer()
	const { canvas, context, instance } = createCanvasFixture(renderer, 'RECORDING')
	const originalLog = console.log
	const logs = []

	console.log = line => logs.push(String(line))
	try {
		instance.config = {
			state: 'RECORDING',
			sessionEpoch: 5,
			packet: null,
			reduced: false,
			profile: 'android'
		}
		instance.canvasHost = canvas
		instance.canvas = canvas
		instance.context = context
		instance.metricsDirty = true

		assert.ok(instance.configureCanvas())
	} finally {
		console.log = originalLog
	}

	const metricsLogs = logs.filter(line => line.includes('phase=CANVAS_METRICS'))
	assert.equal(metricsLogs.length, 1)
	assert.match(metricsLogs[0], /profile=android cssWidth=80 cssHeight=24/)
	assert.match(metricsLogs[0],
		/pixelWidth=80 pixelHeight=24 dpr=1 contextScale=1 capacity=14/)
	assert.match(metricsLogs[0],
		/spanPx=77 unusedPx=3 centerY=12 levelExponent=1.3 levelCeiling=0.82/)
	assert.match(metricsLogs[0],
		/maxBarHeight=11.84 maxTopY=6.08 maxBottomY=17.92 symmetricBounds=true/)
})

test('zero-width Canvas remains dirty until a later measurable layout', async () => {
	const { default: renderer } = await loadRenderer()
	const context = recordingContext()
	const originalLog = console.log
	const canvas = {
		tagName: 'CANVAS',
		width: 0,
		height: 24,
		clientWidth: 0,
		offsetWidth: 0,
		isConnected: true,
		style: {},
		getBoundingClientRect: () => ({ width: 0, height: 24 }),
		getContext: () => context
	}
	const instance = {
		...renderer.data(),
		...renderer.methods,
		root: {
			clientWidth: 0,
			getBoundingClientRect: () => ({ width: 0, height: 24 }),
			querySelector: () => canvas
		},
		canvasHost: canvas,
		canvas,
		context,
		config: {
			state: 'RECORDING',
			sessionEpoch: 6,
			packet: null,
			reduced: false,
			profile: 'android'
		}
	}

	console.log = () => {}
	try {
		assert.equal(instance.configureCanvas(), null)
		assert.equal(instance.canvasMetrics, null)
		assert.equal(instance.metricsDirty, true)

		instance.root.clientWidth = 110
		assert.deepEqual(instance.configureCanvas(), {
			cssWidth: 110,
			cssHeight: 24,
			dpr: 1,
			pixelWidth: 110,
			pixelHeight: 24,
			visibleBars: 20
		})
		assert.equal(instance.metricsDirty, false)
	} finally {
		console.log = originalLog
	}
})

test('DPR changes invalidate cached Android metrics and resize the backing store', async () => {
	const { default: renderer } = await loadRenderer()
	const { canvas, context, instance } = createCanvasFixture(renderer, 'RECORDING')
	const originalDpr = globalThis.devicePixelRatio
	const originalLog = console.log

	console.log = () => {}
	try {
		globalThis.devicePixelRatio = 1
		instance.config.profile = 'android'
		instance.canvasHost = canvas
		instance.canvas = canvas
		instance.context = context
		assert.equal(instance.configureCanvas().dpr, 1)

		globalThis.devicePixelRatio = 3
		const metrics = instance.configureCanvas()
		assert.equal(metrics.dpr, 3)
		assert.equal(metrics.pixelWidth, metrics.cssWidth * 3)
		assert.equal(metrics.pixelHeight, 72)
		assert.equal(canvas.width, metrics.pixelWidth)
		assert.equal(canvas.height, 72)
	} finally {
		console.log = originalLog
		if (originalDpr === undefined) delete globalThis.devicePixelRatio
		else globalThis.devicePixelRatio = originalDpr
	}
})

test('Android Canvas failures emit one controlled warning without exception content', async () => {
	const { default: renderer } = await loadRenderer()
	const { canvas, context, instance } = createCanvasFixture(renderer, 'RECORDING')
	const originalLog = console.log
	const originalWarn = console.warn
	const warnings = []
	context.clearRect = () => { throw new Error('synthetic private failure') }
	instance.config.profile = 'android'
	instance.canvasHost = canvas
	instance.canvas = canvas
	instance.context = context
	instance.timeline.start(1, 0)
	console.log = () => {}
	console.warn = line => warnings.push(String(line))

	try {
		assert.equal(instance.draw(0), false)
		assert.equal(instance.draw(1), false)
	} finally {
		console.log = originalLog
		console.warn = originalWarn
	}

	assert.deepEqual(warnings, [
		'event=voice_android_waveform phase=RENDER_FAILED'
	])
	assert.doesNotMatch(warnings[0], /synthetic|private|error/i)
})

test('H5 profile does not draw a midline and keeps 20px max bar height', async () => {
	const { drawVoiceWaveformFrame } = await loadRenderer()
	const context = recordingContext()
	const bars = Array.from({ length: 5 }, (_, index) => ({
		id: index + 1,
		level: index === 3 ? 1 : 0,
		recorded: index === 3
	}))

	drawVoiceWaveformFrame(context, {
		width: 30,
		height: 24,
		bars,
		progress: 0,
		profile: 'h5'
	})

	// H5 不绘制中轴线。
	const midline = context.lines.find(line =>
		line.width === 1 && line.x === 0)
	assert.equal(midline, undefined)

	// 最大柱高 20px，柱宽 2.5px。
	const soundBar = context.lines.find(line =>
		line.y2 - line.y === 20)
	assert.ok(soundBar, 'max-volume H5 bar height must be 20px')
	assert.equal(soundBar.width, 2.5)
})

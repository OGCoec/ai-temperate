const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('../aichat/ai-code-test-loader.cjs')

const renderModulePath = path.resolve(
	__dirname,
	'../../components/user/workspace/user-thinking-orb-render.js'
)

async function loadRenderer() {
	return loadEsmModule(renderModulePath)
}

function createRecordingContext({ initialHidpi = false, uniHidpiDpr = 1 } = {}) {
	let pendingArc = null
	let pendingLine = null
	let transform = [1, 0, 0, 1, 0, 0]
	const stack = []
	const resolveCoordinates = values => {
		const multiplier = context.__hidpi__ ? uniHidpiDpr : 1
		return values.map(value => value * multiplier)
	}
	const transformPoint = (x, y) => ({
		x: transform[0] * x + transform[2] * y + transform[4],
		y: transform[1] * x + transform[3] * y + transform[5]
	})
	const context = {
		__hidpi__: initialHidpi,
		arcs: [],
		lines: [],
		setTransformCalls: [],
		clearRectCalls: [],
		fillStyle: '',
		strokeStyle: '',
		lineWidth: 1,
		setTransform(a, b, c, d, e, f) {
			transform = [a, b, c, d, e, f]
			this.setTransformCalls.push([...transform])
		},
		save() {
			stack.push([...transform])
		},
		restore() {
			transform = stack.pop() || [1, 0, 0, 1, 0, 0]
		},
		translate(x, y) {
			transform[4] += transform[0] * x + transform[2] * y
			transform[5] += transform[1] * x + transform[3] * y
		},
		scale(x, y) {
			transform[0] *= x
			transform[1] *= x
			transform[2] *= y
			transform[3] *= y
		},
		clearRect(...values) {
			this.clearRectCalls.push(resolveCoordinates(values))
		},
		beginPath() {
			pendingArc = null
			pendingLine = null
		},
		arc(x, y, radius) {
			[x, y, radius] = resolveCoordinates([x, y, radius])
			const point = transformPoint(x, y)
			const radiusScale = Math.max(
				Math.hypot(transform[0], transform[1]),
				Math.hypot(transform[2], transform[3])
			)
			pendingArc = { ...point, radius: radius * radiusScale }
		},
		fill() {
			if (pendingArc) this.arcs.push({ ...pendingArc, style: this.fillStyle })
		},
		moveTo(x, y) {
			[x, y] = resolveCoordinates([x, y])
			const point = transformPoint(x, y)
			pendingLine = { x1: point.x, y1: point.y }
		},
		lineTo(x, y) {
			if (pendingLine) {
				[x, y] = resolveCoordinates([x, y])
				const point = transformPoint(x, y)
				pendingLine = { ...pendingLine, x2: point.x, y2: point.y }
			}
		},
		stroke() {
			if (pendingLine?.x2 != null) {
				const widthScale = Math.max(
					Math.hypot(transform[0], transform[1]),
					Math.hypot(transform[2], transform[3])
				)
				this.lines.push({
					...pendingLine,
					width: this.lineWidth * widthScale,
					style: this.strokeStyle
				})
			}
		}
	}
	return context
}

function parseRgba(style) {
	const match = /^rgba\((\d+),(\d+),(\d+),([\d.]+)\)$/.exec(style)
	assert.ok(match, `Expected rgba color, received: ${style}`)
	return {
		red: Number(match[1]),
		green: Number(match[2]),
		blue: Number(match[3]),
		alpha: Number(match[4])
	}
}

const MESSAGE_BACKGROUND = Object.freeze({ red: 9, green: 13, blue: 11 })
const COMPOSER_BACKGROUND = Object.freeze({ red: 30, green: 35, blue: 32 })
const TEST_BACKGROUNDS = Object.freeze([MESSAGE_BACKGROUND, COMPOSER_BACKGROUND])
const ORB_STATES = Object.freeze([
	'working', 'searching', 'solving', 'listening', 'connecting',
	'weaving', 'composing', 'breathing', 'shaping'
])
const FULL_SPHERE_STATES = new Set(['searching', 'solving', 'listening', 'breathing'])
const SPARSE_SPHERE_STATES = new Set(['working', 'connecting', 'weaving'])
const FRAME_TIMES = Object.freeze(Array.from({ length: 49 }, (_, index) => index * 0.25))
const EDGE_INSETS = Object.freeze({ 20: 0.75, 40: 1, 64: 1.5 })

function compositeChannel(foreground, background, alpha) {
	return foreground * alpha + background * (1 - alpha)
}

function relativeLuminance({ red, green, blue }) {
	const linear = value => {
		const normalized = value / 255
		return normalized <= 0.04045
			? normalized / 12.92
			: ((normalized + 0.055) / 1.055) ** 2.4
	}
	return linear(red) * 0.2126 + linear(green) * 0.7152 + linear(blue) * 0.0722
}

function distanceToSegment(px, py, x1, y1, x2, y2) {
	const dx = x2 - x1
	const dy = y2 - y1
	const lengthSquared = dx * dx + dy * dy
	if (!lengthSquared) return Math.hypot(px - x1, py - y1)
	const projection = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / lengthSquared))
	return Math.hypot(px - (x1 + dx * projection), py - (y1 + dy * projection))
}

function rasterizeFrame(context, size, background, supersample = 2) {
	const width = size * supersample
	const pixels = new Float64Array(width * width * 3)
	for (let index = 0; index < width * width; index++) {
		pixels[index * 3] = background.red
		pixels[index * 3 + 1] = background.green
		pixels[index * 3 + 2] = background.blue
	}

	const blend = (x, y, foreground) => {
		if (x < 0 || y < 0 || x >= width || y >= width) return
		const index = (y * width + x) * 3
		pixels[index] = compositeChannel(foreground.red, pixels[index], foreground.alpha)
		pixels[index + 1] = compositeChannel(foreground.green, pixels[index + 1], foreground.alpha)
		pixels[index + 2] = compositeChannel(foreground.blue, pixels[index + 2], foreground.alpha)
	}

	const paintLine = line => {
		const foreground = parseRgba(line.style)
		const radius = line.width / 2
		const minX = Math.max(0, Math.floor((Math.min(line.x1, line.x2) - radius) * supersample))
		const maxX = Math.min(width - 1, Math.ceil((Math.max(line.x1, line.x2) + radius) * supersample))
		const minY = Math.max(0, Math.floor((Math.min(line.y1, line.y2) - radius) * supersample))
		const maxY = Math.min(width - 1, Math.ceil((Math.max(line.y1, line.y2) + radius) * supersample))
		for (let y = minY; y <= maxY; y++) {
			for (let x = minX; x <= maxX; x++) {
				const px = (x + 0.5) / supersample
				const py = (y + 0.5) / supersample
				if (distanceToSegment(px, py, line.x1, line.y1, line.x2, line.y2) <= radius) blend(x, y, foreground)
			}
		}
	}

	const paintDot = dot => {
		const foreground = parseRgba(dot.style)
		const minX = Math.max(0, Math.floor((dot.x - dot.radius) * supersample))
		const maxX = Math.min(width - 1, Math.ceil((dot.x + dot.radius) * supersample))
		const minY = Math.max(0, Math.floor((dot.y - dot.radius) * supersample))
		const maxY = Math.min(width - 1, Math.ceil((dot.y + dot.radius) * supersample))
		for (let y = minY; y <= maxY; y++) {
			for (let x = minX; x <= maxX; x++) {
				const px = (x + 0.5) / supersample
				const py = (y + 0.5) / supersample
				if (Math.hypot(px - dot.x, py - dot.y) <= dot.radius) blend(x, y, foreground)
			}
		}
	}

	for (const line of context.lines) paintLine(line)
	for (const dot of context.arcs) paintDot(dot)

	const backgroundLuminance = relativeLuminance(background)
	const energy = new Float64Array(width * width)
	for (let index = 0; index < width * width; index++) {
		energy[index] = Math.max(0, relativeLuminance({
			red: pixels[index * 3],
			green: pixels[index * 3 + 1],
			blue: pixels[index * 3 + 2]
		}) - backgroundLuminance)
	}
	return { energy, supersample, width }
}

function analyzeVisibleEnergy(raster, size) {
	const center = size / 2
	const band = Math.max(1.5, size * 0.1)
	const quadrants = [0, 0, 0, 0]
	const arms = { left: 0, right: 0, top: 0, bottom: 0 }
	let total = 0
	let edge = 0
	for (let y = 0; y < raster.width; y++) {
		for (let x = 0; x < raster.width; x++) {
			const value = raster.energy[y * raster.width + x]
			if (!value) continue
			const px = (x + 0.5) / raster.supersample
			const py = (y + 0.5) / raster.supersample
			const horizontal = px < center ? 0 : 1
			const vertical = py < center ? 0 : 2
			quadrants[horizontal + vertical] += value
			total += value
			if (px < 0.5 || py < 0.5 || px > size - 0.5 || py > size - 0.5) edge += value
			if (Math.abs(py - center) <= band) arms[px < center ? 'left' : 'right'] += value
			if (Math.abs(px - center) <= band) arms[py < center ? 'top' : 'bottom'] += value
		}
	}
	return { total, edge, quadrants, arms }
}

function assertPerceptualContinuity(state, size, time, background, context) {
	const analysis = analyzeVisibleEnergy(rasterizeFrame(context, size, background), size)
	assert.ok(analysis.total > 0, `${state}/${size}/${time} should remain visible`)
	assert.ok(analysis.edge <= analysis.total * 0.002, `${state}/${size}/${time} paints into the outer bitmap edge`)
	const shares = analysis.quadrants.map(value => value / analysis.total)

	if (FULL_SPHERE_STATES.has(state)) {
		assert.ok(shares.every(value => value >= 0.12), `${state}/${size}/${time} loses a full-sphere quadrant: ${shares.join(',')}`)
		const armValues = Object.values(analysis.arms).sort((left, right) => left - right)
		const median = (armValues[1] + armValues[2]) / 2
		assert.ok(
			armValues[0] >= median * 0.45,
			`${state}/${size}/${time} forms an axis-aligned arm gap: ${JSON.stringify(analysis.arms)}`
		)
		return
	}

	if (SPARSE_SPHERE_STATES.has(state)) {
		assert.ok(shares.every(value => value >= 0.08), `${state}/${size}/${time} loses a sparse-mode quadrant: ${shares.join(',')}`)
		for (const [left, right] of [[0, 1], [1, 3], [3, 2], [2, 0]]) {
			assert.ok(shares[left] + shares[right] >= 0.25, `${state}/${size}/${time} forms a right-angle empty region`)
		}
		return
	}

	assert.ok(shares.every(value => value >= 0.03), `${state}/${size}/${time} open shape is visually truncated: ${shares.join(',')}`)
}

test('20px, 40px, and 64px use independently tuned render profiles', async () => {
	const { resolveOrbRenderProfile } = await loadRenderer()
	for (const state of ORB_STATES) {
		const small = resolveOrbRenderProfile(state, 20)
		const medium = resolveOrbRenderProfile(state, 40)
		const large = resolveOrbRenderProfile(state, 64)
		assert.equal(small.mode, medium.mode)
		assert.equal(medium.mode, large.mode)
		assert.notStrictEqual(medium.options, large.options)
		assert.notDeepEqual(medium.options, large.options, `${state} must not reuse the 64px values at 40px`)
		assert.notDeepEqual(medium.options, small.options, `${state} must not reuse the 20px values at 40px`)
	}
})

test('all mode primitives stay inside their exact bitmap without edge clamping', async () => {
	const { renderOrbFrame } = await loadRenderer()
	for (const size of [20, 40, 64]) {
		const inset = EDGE_INSETS[size]
		for (const state of ORB_STATES) {
			for (const time of FRAME_TIMES) {
				const context = createRecordingContext()
				const frame = renderOrbFrame(context, { state, size, dark: true }, time)
				assert.equal(frame.inset, inset)
				assert.equal(frame.scale, 1)
				assert.equal(frame.offset, 0)
				assert.ok(context.arcs.length > 0, `${state}/${size}/${time} should draw dots`)

				for (const dot of context.arcs) {
					assert.ok(dot.x - dot.radius >= inset - 1e-6, `${state} dot crosses left inset`)
					assert.ok(dot.y - dot.radius >= inset - 1e-6, `${state} dot crosses top inset`)
					assert.ok(dot.x + dot.radius <= size - inset + 1e-6, `${state} dot crosses right inset`)
					assert.ok(dot.y + dot.radius <= size - inset + 1e-6, `${state} dot crosses bottom inset`)
				}

				for (const line of context.lines) {
					const halfWidth = line.width / 2
					for (const [x, y] of [[line.x1, line.y1], [line.x2, line.y2]]) {
						assert.ok(x - halfWidth >= inset - 1e-6, `${state} line crosses left inset`)
						assert.ok(y - halfWidth >= inset - 1e-6, `${state} line crosses top inset`)
						assert.ok(x + halfWidth <= size - inset + 1e-6, `${state} line crosses right inset`)
						assert.ok(y + halfWidth <= size - inset + 1e-6, `${state} line crosses bottom inset`)
					}
				}
				if (size === 20) assert.ok(context.arcs.every(dot => dot.radius >= 0.5), `${state} has sub-visible 20px dots`)
				if (size === 40) assert.ok(context.arcs.every(dot => dot.radius >= 0.45), `${state} has sub-visible 40px dots`)
			}
		}
	}
})

test('all animation frames avoid axis-aligned right-angle gaps after background compositing', async () => {
	const { renderOrbFrame } = await loadRenderer()
	for (const size of [20, 40, 64]) {
		for (const state of ORB_STATES) {
			for (const time of FRAME_TIMES) {
				const context = createRecordingContext()
				renderOrbFrame(context, { state, size, dark: true }, time)
				for (const background of TEST_BACKGROUNDS) {
					assertPerceptualContinuity(state, size, time, background, context)
				}
			}
		}
	}
})

function createRenderInstance(renderer, size, dpr, reduced = false, contextOptions = {}) {
	const context = createRecordingContext(contextOptions)
	const canvas = {
		width: 0,
		height: 0,
		style: {},
		getContext: () => context
	}
	const instance = {
		...renderer.data(),
		...renderer.methods,
		canvas,
		context,
		dpr,
		config: {
			state: 'working',
			size,
			speed: 1,
			paused: false,
			reduced,
			dark: true
		}
	}
	return { canvas, context, instance }
}

function createNestedCanvasInstance(renderer, size, dpr) {
	const context = createRecordingContext()
	const canvas = {
		width: 20,
		height: 20,
		style: { width: '20px', height: '20px' },
		getContext: () => context
	}
	const canvasHost = {
		style: { width: '20px', height: '20px' },
		querySelector: selector => selector === 'canvas' ? canvas : null
	}
	const root = {
		querySelector(selector) {
			if (selector === '.user-thinking-orb-canvas') return canvasHost
			if (selector === 'canvas') return canvas
			return null
		}
	}
	const instance = {
		...renderer.data(),
		...renderer.methods,
		root,
		dpr,
		config: {
			state: 'listening',
			size,
			speed: 1,
			paused: false,
			reduced: false,
			dark: true
		}
	}
	return { canvas, canvasHost, context, instance }
}

test('canvas lifecycle keeps display and internal drawing sizes synchronized', async () => {
	const { default: renderer, resolveOrbCanvasMetrics } = await loadRenderer()
	for (const size of [20, 40, 64]) {
		for (const dpr of [1, 1.25, 1.5, 2]) {
			const { canvas, context, instance } = createRenderInstance(renderer, size, dpr)
			instance.draw(1)
			const metrics = resolveOrbCanvasMetrics(size, dpr)
			assert.equal(canvas.width, metrics.pixelSize)
			assert.equal(canvas.height, metrics.pixelSize)
			assert.equal(canvas.style.width, metrics.canvasCssSize)
			assert.equal(canvas.style.height, metrics.canvasCssSize)
			assert.equal(context.__hidpi__, false)
			assert.deepEqual(context.setTransformCalls.slice(0, 2), [
				[1, 0, 0, 1, 0, 0],
				[metrics.contextScale, 0, 0, metrics.contextScale, 0, 0]
			])
			assert.deepEqual(context.clearRectCalls[0], [0, 0, metrics.pixelSize, metrics.pixelSize])
			assert.equal(metrics.renderSize, size)
		}
	}
})

test('simulated UniApp HiDPI plus manual DPR reproduces the former quarter-frame overflow', async () => {
	const { renderOrbFrame } = await loadRenderer()
	const context = createRecordingContext({ initialHidpi: true, uniHidpiDpr: 2 })
	context.setTransform(2, 0, 0, 2, 0, 0)
	renderOrbFrame(context, {
		state: 'listening',
		size: 40,
		dark: true
	}, 1, 40)

	assert.ok(context.arcs.length > 0)
	assert.ok(context.arcs.some(arc => arc.x + arc.radius > 80 || arc.y + arc.radius > 80))
})

test('renderer disables UniApp HiDPI before the first primitive is painted', async () => {
	const { default: renderer, resolveOrbCanvasMetrics } = await loadRenderer()
	const { canvas, context, instance } = createRenderInstance(
		renderer,
		40,
		2,
		false,
		{ initialHidpi: true, uniHidpiDpr: 2 }
	)
	instance.draw(1)
	const metrics = resolveOrbCanvasMetrics(40, 2)

	assert.equal(context.__hidpi__, false)
	assert.equal(canvas.width, 80)
	assert.equal(canvas.height, 80)
	assert.ok(context.arcs.length > 0)
	for (const arc of context.arcs) {
		assert.ok(arc.x - arc.radius >= 0 && arc.x + arc.radius <= metrics.pixelSize)
		assert.ok(arc.y - arc.radius >= 0 && arc.y + arc.radius <= metrics.pixelSize)
	}
})

test('repeated frames reset the transform instead of accumulating DPR', async () => {
	const { default: renderer } = await loadRenderer()
	const { context, instance } = createRenderInstance(renderer, 40, 2)
	for (let frame = 0; frame < 100; frame++) instance.draw(frame / 10)

	assert.equal(context.setTransformCalls.length, 200)
	assert.equal(context.clearRectCalls.length, 100)
	for (let frame = 0; frame < 100; frame++) {
		assert.deepEqual(context.setTransformCalls[frame * 2], [1, 0, 0, 1, 0, 0])
		assert.deepEqual(context.setTransformCalls[frame * 2 + 1], [2, 0, 0, 2, 0, 0])
		assert.deepEqual(context.clearRectCalls[frame], [0, 0, 80, 80])
	}
})

test('size and DPR changes reconfigure the native bitmap from the same logical size', async () => {
	const { default: renderer } = await loadRenderer()
	const { canvas, context, instance } = createRenderInstance(renderer, 20, 1)
	for (const [size, dpr, pixelSize] of [[20, 1, 20], [40, 1.5, 60], [64, 2, 128]]) {
		instance.config.size = size
		instance.dpr = dpr
		instance.draw(1)
		assert.equal(canvas.style.width, `${size}px`)
		assert.equal(canvas.style.height, `${size}px`)
		assert.equal(canvas.width, pixelSize)
		assert.equal(canvas.height, pixelSize)
		assert.deepEqual(context.setTransformCalls.slice(-2), [
			[1, 0, 0, 1, 0, 0],
			[dpr, 0, 0, dpr, 0, 0]
		])
	}
})

test('nested UniApp canvas keeps host and native drawing sizes synchronized', async () => {
	const { default: renderer, resolveOrbCanvasMetrics } = await loadRenderer()
	const originalDocument = globalThis.document
	const originalIntersectionObserver = globalThis.IntersectionObserver
	globalThis.document = {
		visibilityState: 'visible',
		addEventListener() {},
		removeEventListener() {}
	}
	delete globalThis.IntersectionObserver

	try {
		for (const size of [20, 40, 64]) {
			const { canvas, canvasHost, context, instance } = createNestedCanvasInstance(renderer, size, 2)
			instance.connectCanvas()
			instance.draw(1)
			const metrics = resolveOrbCanvasMetrics(size, 2)

			assert.equal(instance.canvasHost, canvasHost)
			assert.equal(canvasHost.style.width, metrics.hostCssSize)
			assert.equal(canvasHost.style.height, metrics.hostCssSize)
			assert.equal(canvasHost.style.minWidth, metrics.hostCssSize)
			assert.equal(canvasHost.style.minHeight, metrics.hostCssSize)
			assert.equal(canvas.width, metrics.pixelSize)
			assert.equal(canvas.height, metrics.pixelSize)
			assert.equal(canvas.style.width, metrics.canvasCssSize)
			assert.equal(canvas.style.height, metrics.canvasCssSize)
			assert.equal(context.__hidpi__, false)
			assert.deepEqual(context.setTransformCalls.slice(0, 2), [
				[1, 0, 0, 1, 0, 0],
				[metrics.contextScale, 0, 0, metrics.contextScale, 0, 0]
			])
			assert.deepEqual(context.clearRectCalls[0], [0, 0, metrics.pixelSize, metrics.pixelSize])
			if (size === 40) {
				assert.equal(metrics.displaySize, 40)
				assert.equal(metrics.renderSize, 40)
				assert.equal(metrics.hostCssSize, '40px')
				assert.equal(metrics.canvasCssSize, '40px')
				assert.equal(metrics.contextScale, metrics.dpr)
				assert.equal(metrics.pixelSize, 80)
			}
		}
	} finally {
		if (originalDocument === undefined) delete globalThis.document
		else globalThis.document = originalDocument
		if (originalIntersectionObserver === undefined) delete globalThis.IntersectionObserver
		else globalThis.IntersectionObserver = originalIntersectionObserver
	}
})

test('native canvas resolution prefers a valid shadow canvas over light-DOM decoys', async () => {
	const { default: renderer } = await loadRenderer()
	const context = createRecordingContext({ initialHidpi: true, uniHidpiDpr: 2 })
	const nativeCanvas = {
		width: 20,
		height: 20,
		style: {},
		getContext: () => context
	}
	const lightDomDecoy = { width: 20, height: 20, style: {} }
	const canvasHost = {
		style: {},
		shadowRoot: { querySelector: selector => selector === 'canvas' ? nativeCanvas : null },
		querySelector: () => lightDomDecoy
	}
	const root = {
		querySelector: selector => selector === '.user-thinking-orb-canvas' ? canvasHost : null
	}
	const instance = {
		...renderer.data(),
		...renderer.methods,
		root,
		dpr: 2,
		config: { state: 'listening', size: 40, speed: 1, paused: false, reduced: false, dark: true }
	}

	instance.connectCanvas()
	instance.draw(1)

	assert.equal(instance.canvasHost, canvasHost)
	assert.equal(instance.canvas, nativeCanvas)
	assert.equal(instance.context, context)
	assert.equal(context.__hidpi__, false)
})

test('reduced restart draws the fixed 0.6 frame without scheduling RAF', async () => {
	const { default: renderer } = await loadRenderer()
	const { instance } = createRenderInstance(renderer, 20, 2, true)
	const drawnTimes = []
	let scheduledFrames = 0
	const hadRequestAnimationFrame = Object.prototype.hasOwnProperty.call(globalThis, 'requestAnimationFrame')
	const hadCancelAnimationFrame = Object.prototype.hasOwnProperty.call(globalThis, 'cancelAnimationFrame')
	const originalRequestAnimationFrame = globalThis.requestAnimationFrame
	const originalCancelAnimationFrame = globalThis.cancelAnimationFrame
	globalThis.requestAnimationFrame = () => {
		scheduledFrames++
		return scheduledFrames
	}
	globalThis.cancelAnimationFrame = () => {}
	instance.draw = time => drawnTimes.push(time)

	try {
		instance.restart()
		assert.deepEqual(drawnTimes, [0.6])
		assert.equal(scheduledFrames, 0)
		assert.equal(instance.running, false)
	} finally {
		if (hadRequestAnimationFrame) globalThis.requestAnimationFrame = originalRequestAnimationFrame
		else delete globalThis.requestAnimationFrame
		if (hadCancelAnimationFrame) globalThis.cancelAnimationFrame = originalCancelAnimationFrame
		else delete globalThis.cancelAnimationFrame
	}
})

test('visible listening orb keeps one RAF loop and teardown releases its lifecycle', async () => {
	const { default: renderer } = await loadRenderer()
	const { instance } = createRenderInstance(renderer, 40, 2)
	const originalRequestAnimationFrame = globalThis.requestAnimationFrame
	const originalCancelAnimationFrame = globalThis.cancelAnimationFrame
	const callbacks = []
	const cancelled = []
	const drawnTimes = []
	globalThis.requestAnimationFrame = callback => {
		callbacks.push(callback)
		return callbacks.length
	}
	globalThis.cancelAnimationFrame = id => cancelled.push(id)
	instance.config.state = 'listening'
	instance.draw = time => drawnTimes.push(time)

	try {
		instance.restart()
		assert.equal(instance.running, true)
		assert.equal(callbacks.length, 1)
		callbacks[0]()
		assert.equal(callbacks.length, 2)
		assert.equal(drawnTimes.length, 2)

		instance.teardown()
		assert.equal(instance.running, false)
		assert.ok(cancelled.length >= 1)
		assert.equal(instance.canvas, null)
		assert.equal(instance.context, null)
	} finally {
		globalThis.requestAnimationFrame = originalRequestAnimationFrame
		globalThis.cancelAnimationFrame = originalCancelAnimationFrame
	}
})

test('visibility restoration restarts a listening orb after a hidden pause', async () => {
	const { default: renderer } = await loadRenderer()
	const originalDocument = globalThis.document
	const originalIntersectionObserver = globalThis.IntersectionObserver
	const originalRequestAnimationFrame = globalThis.requestAnimationFrame
	const originalCancelAnimationFrame = globalThis.cancelAnimationFrame
	let visibilityHandler = null
	let scheduledFrames = 0
	globalThis.document = {
		visibilityState: 'visible',
		addEventListener(type, handler) {
			if (type === 'visibilitychange') visibilityHandler = handler
		},
		removeEventListener() {}
	}
	delete globalThis.IntersectionObserver
	globalThis.requestAnimationFrame = () => ++scheduledFrames
	globalThis.cancelAnimationFrame = () => {}
	const { instance } = createNestedCanvasInstance(renderer, 40, 2)

	try {
		instance.connectCanvas()
		instance.restart()
		assert.equal(scheduledFrames, 1)
		globalThis.document.visibilityState = 'hidden'
		visibilityHandler()
		assert.equal(instance.running, false)
		globalThis.document.visibilityState = 'visible'
		visibilityHandler()
		assert.equal(instance.running, true)
		assert.equal(scheduledFrames, 2)
	} finally {
		instance.teardown()
		if (originalDocument === undefined) delete globalThis.document
		else globalThis.document = originalDocument
		if (originalIntersectionObserver === undefined) delete globalThis.IntersectionObserver
		else globalThis.IntersectionObserver = originalIntersectionObserver
		globalThis.requestAnimationFrame = originalRequestAnimationFrame
		globalThis.cancelAnimationFrame = originalCancelAnimationFrame
	}
})

test('orb renderer stays platform-neutral and shared by activity and voice surfaces', () => {
	const fs = require('node:fs')
	const rendererSource = fs.readFileSync(renderModulePath, 'utf8')
	const componentSource = fs.readFileSync(path.resolve(__dirname, '../../components/user/workspace/user-thinking-orb.vue'), 'utf8')
	const chatSource = fs.readFileSync(path.resolve(__dirname, '../../components/user/workspace/user-chat-panel.vue'), 'utf8')
	assert.doesNotMatch(rendererSource, /APP-PLUS|ANDROID|\bH5\b/)
	assert.doesNotMatch(componentSource, /APP-PLUS|ANDROID|\bH5\b/)
	assert.doesNotMatch(componentSource, /clip-path|mask\s*:|transform\s*:\s*scale/)
	assert.match(componentSource, /<canvas[\s\S]*?:hidpi="false"/)
	assert.doesNotMatch(componentSource, /<canvas[\s\S]*?\shidpi="false"/)
	assert.match(componentSource, /:style="orbSizeStyle"[\s\S]*?<canvas[\s\S]*?:style="orbSizeStyle"/)
	assert.match(componentSource, /\.user-thinking-orb\s*\{[^}]*overflow:\s*visible[^}]*box-sizing:\s*content-box/s)
	assert.match(chatSource, /class="model-activity"[\s\S]*?<user-thinking-orb/)
	assert.match(chatSource, /class="voice-transcript-row"[\s\S]*?<user-thinking-orb/)
	assert.match(chatSource,
		/<user-thinking-orb\s+v-if="voiceInteractionActive && voiceActivityPresentation"/)
	assert.doesNotMatch(chatSource,
		/<user-thinking-orb\s+v-if="[^"]*motionReduced[^"]*"/)
	assert.match(chatSource, /class="voice-transcript-row"[\s\S]*?<user-thinking-orb[\s\S]*?class="voice-live-transcript"/)
	assert.match(chatSource, /class="voice-transcript-row"[\s\S]*?:size="40"/)
	assert.doesNotMatch(componentSource, /motionPreference|AI_MOTION_PREFERENCES|toggleManualReduce/)
	assert.match(chatSource, /\.model-activity\s*\{[^}]*overflow:\s*visible/s)
	assert.match(chatSource, /\.voice-transcript-row\s*\{[^}]*overflow:\s*visible/s)
	assert.match(chatSource, /\.voice-transcript-row \.user-thinking-orb\s*\{[^}]*flex:\s*0 0 40px/s)
})

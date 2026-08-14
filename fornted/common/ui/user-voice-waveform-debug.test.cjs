const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const debugModulePath = path.resolve(
	__dirname,
	'../../components/user/workspace/user-voice-waveform-debug.js')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadDebugModule() {
	const source = fs.readFileSync(debugModulePath, 'utf8')
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function createElement(tagName) {
	const listeners = new Map()
	return {
		tagName: String(tagName || '').toUpperCase(),
		children: [],
		style: {},
		textContent: '',
		className: '',
		listeners,
		append(...children) { this.children.push(...children) },
		appendChild(child) { this.children.push(child); return child },
		setAttribute() {},
		addEventListener(type, listener) { listeners.set(type, listener) },
		removeEventListener(type) { listeners.delete(type) },
		remove() { this.removed = true }
	}
}

function createDomFixture() {
	const body = createElement('body')
	const windowListeners = new Map()
	const documentRef = {
		body,
		visibilityState: 'visible',
		createElement
	}
	const windowRef = {
		innerWidth: 1280,
		devicePixelRatio: 2,
		addEventListener(type, listener) { windowListeners.set(type, listener) },
		removeEventListener(type) { windowListeners.delete(type) },
		getComputedStyle() { return { overflowX: 'visible' } },
		navigator: { clipboard: { writeText: async () => {} } }
	}
	return { body, documentRef, windowRef, windowListeners }
}

function baseSnapshot(overrides = {}) {
	return {
		state: 'RECORDING',
		sessionEpoch: 3,
		packetSequence: 7,
		packetLevelCount: 5,
		maxLevel: 0.7,
		viewportWidth: 1280,
		dpr: 2,
		metrics: {
			owner: 'RENDERER_NATIVE',
			cssWidth: 900,
			cssHeight: 24,
			pixelWidth: 1800,
			pixelHeight: 48,
			visibleBars: 163,
			selectedWidthSource: 'rootClientWidth'
		},
		geometry: {
			root: { clientWidth: 900, rectWidth: 900, rectHeight: 24 },
			host: { clientWidth: 900, rectWidth: 900, rectHeight: 24 },
			canvas: {
				clientWidth: 900,
				clientHeight: 24,
				offsetWidth: 900,
				offsetHeight: 24,
				rectWidth: 900,
				rectHeight: 24,
				visibleWidth: 900,
				visibleLeft: 0,
				visibleRight: 900,
				pixelWidth: 1800,
				pixelHeight: 48
			}
		},
		bars: {
			total: 164,
			data: 3,
			visibleData: 3,
			leftmostDataX: 850,
			rightmostDataX: 894
		},
		running: true,
		documentVisible: true,
		lastDrawAt: 1000,
		renderFailed: false,
		counters: { resize: 0, resizeObserver: 0, draw: 20 },
		...overrides
	}
}

test('debug mode requires the exact H5 query flag', async () => {
	const { isVoiceWaveformDebugEnabled } = await loadDebugModule()

	assert.equal(isVoiceWaveformDebugEnabled('?voiceWaveDebug=1'), true)
	assert.equal(isVoiceWaveformDebugEnabled('?x=1&voiceWaveDebug=1'), true)
	assert.equal(isVoiceWaveformDebugEnabled('?voiceWaveDebug=0'), false)
	assert.equal(isVoiceWaveformDebugEnabled(''), false)
})

test('health separates missing data, geometry mismatch, offscreen bars and stopped drawing', async () => {
	const { resolveVoiceWaveformDebugHealth } = await loadDebugModule()

	assert.equal(resolveVoiceWaveformDebugHealth({ state: 'IDLE' }, 1200), 'WAITING_STATE')
	assert.equal(resolveVoiceWaveformDebugHealth(baseSnapshot({
		renderFailed: true
	}), 1200), 'RENDER_FAILED')
	assert.equal(resolveVoiceWaveformDebugHealth(baseSnapshot({
		packetSequence: -1,
		maxLevel: 0,
		bars: { total: 163, data: 0, visibleData: 0 }
	}), 1200), 'WAITING_DATA')
	assert.equal(resolveVoiceWaveformDebugHealth(baseSnapshot({
		geometry: {
			...baseSnapshot().geometry,
			canvas: { ...baseSnapshot().geometry.canvas, visibleWidth: 700 }
		}
	}), 1200), 'SIZE_MISMATCH')
	assert.equal(resolveVoiceWaveformDebugHealth(baseSnapshot({
		metrics: { ...baseSnapshot().metrics, pixelWidth: 1700 }
	}), 1200), 'PIXEL_MISMATCH')
	assert.equal(resolveVoiceWaveformDebugHealth(baseSnapshot({
		geometry: {
			...baseSnapshot().geometry,
			canvas: { ...baseSnapshot().geometry.canvas, pixelWidth: 900 }
		}
	}), 1200), 'PIXEL_MISMATCH')
	assert.equal(resolveVoiceWaveformDebugHealth(baseSnapshot({
		bars: { total: 164, data: 3, visibleData: 0, rightmostDataX: 894 }
	}), 1200), 'OFFSCREEN')
	assert.equal(resolveVoiceWaveformDebugHealth(baseSnapshot({
		lastDrawAt: 500
	}), 1200), 'DRAW_STOPPED')
	assert.equal(resolveVoiceWaveformDebugHealth(baseSnapshot(), 1200), 'OK')
})

test('bar summary reports nonzero bars outside the actual visible width', async () => {
	const { summarizeVoiceWaveformDebugBars } = await loadDebugModule()
	const bars = [
		{ id: 1, level: 0 },
		{ id: 2, level: 0.4 },
		{ id: 3, level: 0.8 },
		{ id: 4, level: 0 }
	]

	assert.deepEqual(summarizeVoiceWaveformDebugBars({
		bars,
		progress: 0,
		pitch: 5.5,
		barWidth: 2.5,
		visibleWidth: 7,
		pendingBarId: 4
	}), {
		total: 3,
		data: 2,
		visibleData: 1,
		leftmostDataX: 6.75,
		rightmostDataX: 12.25
	})

	assert.equal(summarizeVoiceWaveformDebugBars({
		bars,
		progress: 0,
		pitch: 5.5,
		barWidth: 2.5,
		visibleWidth: 7,
		visibleLeft: 7,
		visibleRight: 14,
		pendingBarId: 4
	}).visibleData, 2)
})

test('controller caps history, preserves resize snapshots, throttles rendering and removes its UI', async () => {
	const { createVoiceWaveformDebugController } = await loadDebugModule()
	const { body, documentRef, windowRef, windowListeners } = createDomFixture()
	let now = 1000
	let nextTimerId = 1
	const timers = new Map()
	const controller = createVoiceWaveformDebugController({
		documentRef,
		windowRef,
		now: () => now,
		setTimeoutFn(callback, delay) {
			const id = nextTimerId++
			timers.set(id, { callback, delay })
			return id
		},
		clearTimeoutFn(id) { timers.delete(id) }
	})

	assert.ok(controller)
	assert.equal(body.children.length, 1)
	controller.update({
		...baseSnapshot(),
		token: 'must-not-appear',
		transcript: 'must-not-appear'
	})
	for (let index = 0; index < 55; index += 1) {
		controller.record('STATE_CHANGED', { sequence: index })
	}
	const before = baseSnapshot()
	const after = baseSnapshot({ viewportWidth: 1600 })
	controller.record('WINDOW_RESIZE', { before, after })

	const exported = controller.exportText()
	assert.equal(JSON.parse(exported).events.length, 50)
	assert.match(exported, /WINDOW_RESIZE/)
	assert.match(exported, /RENDERER_NATIVE/)
	assert.match(exported, /"before"/)
	assert.match(exported, /"after"/)
	assert.doesNotMatch(exported, /token|transcript|websocket/i)
	assert.ok([...timers.values()].some(timer => timer.delay <= 100))
	assert.equal(windowListeners.has('keydown'), true)

	controller.destroy()
	assert.equal(body.children[0].removed, true)
	assert.equal(windowListeners.has('keydown'), false)
	assert.equal(timers.size, 0)
})

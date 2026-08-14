const DEBUG_QUERY_KEY = 'voiceWaveDebug'
const DEBUG_QUERY_VALUE = '1'
const DEBUG_EVENT_LIMIT = 50
const DEBUG_RENDER_INTERVAL_MS = 100
const DEBUG_WATCHDOG_INTERVAL_MS = 250
const DRAW_STOPPED_THRESHOLD_MS = 500
const GEOMETRY_TOLERANCE_PX = 1

function finiteNumber(value, fallback = 0) {
	const numeric = Number(value)
	return Number.isFinite(numeric) ? numeric : fallback
}

function monotonicNow() {
	return typeof performance !== 'undefined' && typeof performance.now === 'function'
		? performance.now() : Date.now()
}

function nonNegativeNumber(value) {
	return Math.max(0, finiteNumber(value))
}

function safeState(value) {
	return String(value || 'IDLE').toUpperCase().slice(0, 32)
}

function safeSource(value) {
	return String(value || '').replace(/[^A-Za-z0-9_.-]/g, '').slice(0, 48)
}

function normalizeElementGeometry(value = {}) {
	return {
		clientWidth: nonNegativeNumber(value.clientWidth),
		clientHeight: nonNegativeNumber(value.clientHeight),
		offsetWidth: nonNegativeNumber(value.offsetWidth),
		offsetHeight: nonNegativeNumber(value.offsetHeight),
		rectWidth: nonNegativeNumber(value.rectWidth),
		rectHeight: nonNegativeNumber(value.rectHeight),
		visibleWidth: nonNegativeNumber(value.visibleWidth),
		visibleLeft: nonNegativeNumber(value.visibleLeft),
		visibleRight: nonNegativeNumber(value.visibleRight),
		pixelWidth: nonNegativeNumber(value.pixelWidth),
		pixelHeight: nonNegativeNumber(value.pixelHeight)
	}
}

function normalizeSnapshot(value = {}) {
	return {
		state: safeState(value.state),
		sessionEpoch: finiteNumber(value.sessionEpoch, -1),
		elapsedMs: nonNegativeNumber(value.elapsedMs),
		packetSequence: finiteNumber(value.packetSequence, -1),
		packetLevelCount: nonNegativeNumber(value.packetLevelCount),
		maxLevel: Math.max(0, Math.min(1, finiteNumber(value.maxLevel))),
		viewportWidth: nonNegativeNumber(value.viewportWidth),
		dpr: Math.max(1, finiteNumber(value.dpr, 1)),
		metrics: {
			owner: safeSource(value.metrics?.owner),
			cssWidth: nonNegativeNumber(value.metrics?.cssWidth),
			cssHeight: nonNegativeNumber(value.metrics?.cssHeight),
			pixelWidth: nonNegativeNumber(value.metrics?.pixelWidth),
			pixelHeight: nonNegativeNumber(value.metrics?.pixelHeight),
			visibleBars: nonNegativeNumber(value.metrics?.visibleBars),
			selectedWidthSource: safeSource(value.metrics?.selectedWidthSource)
		},
		geometry: {
			root: normalizeElementGeometry(value.geometry?.root),
			host: normalizeElementGeometry(value.geometry?.host),
			canvas: normalizeElementGeometry(value.geometry?.canvas)
		},
		bars: {
			total: nonNegativeNumber(value.bars?.total),
			data: nonNegativeNumber(value.bars?.data),
			visibleData: nonNegativeNumber(value.bars?.visibleData),
			leftmostDataX: finiteNumber(value.bars?.leftmostDataX, -1),
			rightmostDataX: finiteNumber(value.bars?.rightmostDataX, -1)
		},
		running: Boolean(value.running),
		documentVisible: value.documentVisible !== false,
		lastDrawAt: nonNegativeNumber(value.lastDrawAt),
		renderFailed: Boolean(value.renderFailed),
		counters: {
			resize: nonNegativeNumber(value.counters?.resize),
			resizeObserver: nonNegativeNumber(value.counters?.resizeObserver),
			draw: nonNegativeNumber(value.counters?.draw)
		}
	}
}

function normalizeEventDetails(value = {}) {
	const details = {}
	if (value.reason != null) details.reason = safeSource(value.reason)
	if (value.from != null) details.from = safeState(value.from)
	if (value.to != null) details.to = safeState(value.to)
	if (value.sequence != null) details.sequence = finiteNumber(value.sequence, -1)
	if (value.before) details.before = normalizeSnapshot(value.before)
	if (value.after) details.after = normalizeSnapshot(value.after)
	return details
}

function rounded(value, digits = 2) {
	const numeric = finiteNumber(value)
	return Number(numeric.toFixed(digits))
}

function formatElapsed(milliseconds) {
	const totalSeconds = Math.floor(nonNegativeNumber(milliseconds) / 1000)
	const minutes = String(Math.floor(totalSeconds / 60)).padStart(2, '0')
	const seconds = String(totalSeconds % 60).padStart(2, '0')
	return `${minutes}:${seconds}`
}

function formatClock(milliseconds) {
	const date = new Date(finiteNumber(milliseconds, Date.now()))
	return [date.getHours(), date.getMinutes(), date.getSeconds()]
		.map(value => String(value).padStart(2, '0')).join(':')
		+ `.${String(date.getMilliseconds()).padStart(3, '0')}`
}

function readRect(element) {
	try {
		const rect = element?.getBoundingClientRect?.()
		return {
			left: finiteNumber(rect?.left),
			right: finiteNumber(rect?.right),
			width: nonNegativeNumber(rect?.width),
			height: nonNegativeNumber(rect?.height)
		}
	} catch (_) {
		return { left: 0, right: 0, width: 0, height: 0 }
	}
}

function readElementGeometry(element) {
	const rect = readRect(element)
	return {
		clientWidth: nonNegativeNumber(element?.clientWidth),
		clientHeight: nonNegativeNumber(element?.clientHeight),
		offsetWidth: nonNegativeNumber(element?.offsetWidth),
		offsetHeight: nonNegativeNumber(element?.offsetHeight),
		rectWidth: rect.width,
		rectHeight: rect.height,
		visibleWidth: rect.width,
		visibleLeft: 0,
		visibleRight: rect.width,
		pixelWidth: nonNegativeNumber(element?.width),
		pixelHeight: nonNegativeNumber(element?.height)
	}
}

function visibleElementBounds(element, windowRef) {
	const elementRect = readRect(element)
	if (!(elementRect.width > 0)) return { left: 0, right: 0, width: 0 }
	let left = Math.max(0, elementRect.left)
	let right = Math.min(
		nonNegativeNumber(windowRef?.innerWidth) || elementRect.right,
		elementRect.right)
	let parent = element?.parentElement || null
	while (parent) {
		try {
			const overflowX = String(windowRef?.getComputedStyle?.(parent)?.overflowX || '')
			if (['auto', 'clip', 'hidden', 'scroll'].includes(overflowX)) {
				const parentRect = readRect(parent)
				left = Math.max(left, parentRect.left)
				right = Math.min(right, parentRect.right)
			}
		} catch (_) {}
		parent = parent.parentElement || null
	}
	const visibleLeft = Math.max(0, left - elementRect.left)
	const visibleRight = Math.max(visibleLeft, right - elementRect.left)
	return {
		left: visibleLeft,
		right: visibleRight,
		width: Math.max(0, visibleRight - visibleLeft)
	}
}

export function isVoiceWaveformDebugEnabled(search = '') {
	try {
		return new URLSearchParams(String(search || '')).get(DEBUG_QUERY_KEY)
			=== DEBUG_QUERY_VALUE
	} catch (_) {
		return false
	}
}

export function measureVoiceWaveformDebugGeometry({
	root,
	host,
	canvas,
	windowRef = globalThis.window
} = {}) {
	const rootGeometry = readElementGeometry(root)
	const hostGeometry = readElementGeometry(host)
	const canvasGeometry = readElementGeometry(canvas)
	const canvasVisibleBounds = visibleElementBounds(canvas, windowRef)
	canvasGeometry.visibleWidth = canvasVisibleBounds.width
	canvasGeometry.visibleLeft = canvasVisibleBounds.left
	canvasGeometry.visibleRight = canvasVisibleBounds.right
	return {
		root: rootGeometry,
		host: hostGeometry,
		canvas: canvasGeometry
	}
}

export function summarizeVoiceWaveformDebugBars({
	bars = [],
	progress = 0,
	pitch = 0,
	barWidth = 0,
	visibleWidth = 0,
	visibleLeft = 0,
	visibleRight = null,
	pendingBarId = null
} = {}) {
	const source = Array.isArray(bars) ? bars : []
	const shift = Math.max(0, Math.min(1, finiteNumber(progress)))
		* nonNegativeNumber(pitch)
	const width = nonNegativeNumber(barWidth)
	const visibleStart = nonNegativeNumber(visibleLeft)
	const visibleEnd = visibleRight == null
		? visibleStart + nonNegativeNumber(visibleWidth)
		: Math.max(visibleStart, nonNegativeNumber(visibleRight))
	let total = 0
	let data = 0
	let visibleData = 0
	let leftmostDataX = Infinity
	let rightmostDataX = -Infinity

	for (let index = 0; index < source.length; index += 1) {
		const bar = source[index]
		if (pendingBarId != null && bar?.id === pendingBarId) continue
		total += 1
		if (!(finiteNumber(bar?.level) > 0)) continue
		const x = index * nonNegativeNumber(pitch) - shift + width / 2
		data += 1
		leftmostDataX = Math.min(leftmostDataX, x)
		rightmostDataX = Math.max(rightmostDataX, x)
		if (x + width / 2 > visibleStart && x - width / 2 < visibleEnd) {
			visibleData += 1
		}
	}

	return {
		total,
		data,
		visibleData,
		leftmostDataX: data ? rounded(leftmostDataX) : -1,
		rightmostDataX: data ? rounded(rightmostDataX) : -1
	}
}

export function resolveVoiceWaveformDebugHealth(snapshot, nowMillis = Date.now()) {
	const current = normalizeSnapshot(snapshot)
	if (current.renderFailed) return 'RENDER_FAILED'
	if (current.state !== 'RECORDING') return 'WAITING_STATE'

	const expectedPixelWidth = Math.floor(current.metrics.cssWidth * current.dpr)
	const expectedPixelHeight = Math.floor(current.metrics.cssHeight * current.dpr)
	const actualPixelWidth = current.geometry.canvas.pixelWidth
	const actualPixelHeight = current.geometry.canvas.pixelHeight
	if (Math.abs(current.metrics.pixelWidth - expectedPixelWidth) > GEOMETRY_TOLERANCE_PX
		|| Math.abs(current.metrics.pixelHeight - expectedPixelHeight) > GEOMETRY_TOLERANCE_PX
		|| Math.abs(actualPixelWidth - expectedPixelWidth) > GEOMETRY_TOLERANCE_PX
		|| Math.abs(actualPixelHeight - expectedPixelHeight) > GEOMETRY_TOLERANCE_PX) {
		return 'PIXEL_MISMATCH'
	}

	const actualVisibleWidth = current.geometry.canvas.visibleWidth
	if (Math.abs(current.metrics.cssWidth - actualVisibleWidth) > GEOMETRY_TOLERANCE_PX
		|| Math.abs(current.geometry.root.rectWidth - current.geometry.host.rectWidth)
			> GEOMETRY_TOLERANCE_PX) return 'SIZE_MISMATCH'

	if (current.bars.data === 0 && current.maxLevel <= 0) return 'WAITING_DATA'
	if (current.bars.data > 0 && current.bars.visibleData === 0) return 'OFFSCREEN'
	if (current.documentVisible && current.lastDrawAt > 0
		&& finiteNumber(nowMillis) - current.lastDrawAt > DRAW_STOPPED_THRESHOLD_MS) {
		return 'DRAW_STOPPED'
	}
	return 'OK'
}

function createButton(documentRef, label, title) {
	const button = documentRef.createElement('button')
	button.type = 'button'
	button.textContent = label
	button.title = title
	button.style.cssText = [
		'min-width:28px',
		'height:24px',
		'padding:0 6px',
		'border:1px solid rgba(143,220,190,.38)',
		'border-radius:6px',
		'background:#18211d',
		'color:#bfe8d7',
		'font:600 10px/1 monospace',
		'cursor:pointer'
	].join(';')
	return button
}

function formatSnapshot(snapshot, health, nowMillis) {
	const current = normalizeSnapshot(snapshot)
	const actualWidth = current.geometry.canvas.visibleWidth
	const actualPixelWidth = current.geometry.canvas.pixelWidth
	const actualPixelHeight = current.geometry.canvas.pixelHeight
	const lastDrawAge = current.lastDrawAt > 0
		? Math.max(0, finiteNumber(nowMillis) - current.lastDrawAt) : -1
	return [
		`Health: ${health}`,
		`State: ${current.state}  Time: ${formatElapsed(current.elapsedMs)}  Epoch: ${current.sessionEpoch}`,
		`Packet: ${current.packetSequence}  Levels: ${current.packetLevelCount}  Max: ${rounded(current.maxLevel, 3)}`,
		`Viewport: ${rounded(current.viewportWidth)}  DPR: ${rounded(current.dpr)}`,
		`Canvas Owner: ${current.metrics.owner || '-'}`,
		`Root: ${rounded(current.geometry.root.rectWidth)} x ${rounded(current.geometry.root.rectHeight)}  client=${rounded(current.geometry.root.clientWidth)}`,
		`Host: ${rounded(current.geometry.host.rectWidth)} x ${rounded(current.geometry.host.rectHeight)}  client=${rounded(current.geometry.host.clientWidth)}`,
		`Canvas CSS: ${rounded(current.metrics.cssWidth)} x ${rounded(current.metrics.cssHeight)}`,
		`Canvas Pixel: ${rounded(actualPixelWidth)} x ${rounded(actualPixelHeight)}`,
		`Canvas Rect: ${rounded(current.geometry.canvas.rectWidth)} x ${rounded(current.geometry.canvas.rectHeight)}  VisibleW: ${rounded(actualWidth)}`,
		`Canvas VisibleX: ${rounded(current.geometry.canvas.visibleLeft)} .. ${rounded(current.geometry.canvas.visibleRight)}`,
		`Canvas Client: ${rounded(current.geometry.canvas.clientWidth)} x ${rounded(current.geometry.canvas.clientHeight)}`,
		`Canvas Offset: ${rounded(current.geometry.canvas.offsetWidth)} x ${rounded(current.geometry.canvas.offsetHeight)}`,
		`Width Source: ${current.metrics.selectedWidthSource || '-'}`,
		`VisibleBars: ${current.metrics.visibleBars}  TimelineBars: ${current.bars.total}`,
		`DataBars: ${current.bars.data}  VisibleDataBars: ${current.bars.visibleData}`,
		`DataX: ${current.bars.leftmostDataX} .. ${current.bars.rightmostDataX}`,
		`RAF: ${current.running ? 'RUNNING' : 'STOPPED'}  LastDrawAgeMs: ${rounded(lastDrawAge)}`,
		`Resize: ${current.counters.resize}  Observer: ${current.counters.resizeObserver}  Draw: ${current.counters.draw}`
	].join('\n')
}

function formatEvent(event) {
	const details = event.details || {}
	const snapshot = normalizeSnapshot(event.snapshot)
	const stateChange = details.from || details.to
		? ` ${details.from || '-'}>${details.to || '-'}` : ''
	const sequence = details.sequence != null ? ` seq=${details.sequence}` : ''
	const beforeWidth = details.before?.geometry?.canvas?.visibleWidth
	const afterWidth = details.after?.geometry?.canvas?.visibleWidth
	const resize = details.before || details.after
		? ` visibleW=${beforeWidth || 0}>${afterWidth || 0}` : ''
	const geometry = ` viewport=${rounded(snapshot.viewportWidth)}`
		+ ` root=${rounded(snapshot.geometry.root.rectWidth)}`
		+ ` host=${rounded(snapshot.geometry.host.rectWidth)}`
		+ ` canvas=${rounded(snapshot.geometry.canvas.rectWidth)}`
		+ ` visible=${rounded(snapshot.geometry.canvas.visibleWidth)}`
		+ ` css=${rounded(snapshot.metrics.cssWidth)}`
		+ ` pixel=${rounded(snapshot.geometry.canvas.pixelWidth)}`
		+ ` bars=${snapshot.bars.data}/${snapshot.bars.visibleData}`
		+ ` draw=${snapshot.counters.draw}`
	return `${formatClock(event.at)} ${event.type}${stateChange}${sequence}${resize}${geometry}`
}

export function createVoiceWaveformDebugController({
	documentRef = globalThis.document,
	windowRef = globalThis.window,
	now = monotonicNow,
	wallNow = () => Date.now(),
	setTimeoutFn = globalThis.setTimeout?.bind(globalThis),
	clearTimeoutFn = globalThis.clearTimeout?.bind(globalThis)
} = {}) {
	if (!documentRef?.body || typeof documentRef.createElement !== 'function') return null
	if (typeof setTimeoutFn !== 'function' || typeof clearTimeoutFn !== 'function') return null

	let destroyed = false
	let frozen = false
	let renderTimer = 0
	let watchdogTimer = 0
	let lastRenderAt = -Infinity
	let lastHealth = ''
	let current = normalizeSnapshot()
	let events = []

	const overlay = documentRef.createElement('section')
	overlay.setAttribute?.('aria-label', 'Voice waveform diagnostics')
	overlay.style.cssText = [
		'position:fixed',
		'top:8px',
		'left:8px',
		'z-index:99999',
		'width:min(420px,calc(100vw - 16px))',
		'max-height:calc(100vh - 16px)',
		'overflow:hidden',
		'box-sizing:border-box',
		'padding:8px',
		'border:1px solid rgba(143,220,190,.45)',
		'border-radius:10px',
		'background:rgba(8,13,11,.94)',
		'box-shadow:0 10px 30px rgba(0,0,0,.38)',
		'color:#cce8dc',
		'font:11px/1.35 monospace',
		'contain:layout paint',
		'pointer-events:auto'
	].join(';')

	const toolbar = documentRef.createElement('div')
	toolbar.style.cssText = 'display:flex;align-items:center;gap:5px;margin-bottom:6px'
	const title = documentRef.createElement('strong')
	title.textContent = 'VOICE WAVE DEBUG'
	title.style.cssText = 'margin-right:auto;color:#75dfb7;font:700 11px/1 monospace'
	const snapshotButton = createButton(documentRef, 'S', '保存当前现场（Alt+Shift+S）')
	const freezeButton = createButton(documentRef, 'LIVE', '冻结或恢复屏幕显示')
	const copyButton = createButton(documentRef, 'COPY', '复制当前状态和历史')
	const clearButton = createButton(documentRef, 'CLEAR', '清空历史')
	toolbar.append(title, snapshotButton, freezeButton, copyButton, clearButton)

	const statusNode = documentRef.createElement('pre')
	statusNode.style.cssText = 'margin:0;white-space:pre-wrap;color:#d8e7e0'
	const historyNode = documentRef.createElement('pre')
	historyNode.style.cssText = [
		'margin:7px 0 0',
		'padding-top:7px',
		'border-top:1px solid rgba(143,220,190,.2)',
		'max-height:220px',
		'overflow:auto',
		'white-space:pre-wrap',
		'color:#9fb8ad'
	].join(';')
	overlay.append(toolbar, statusNode, historyNode)
	documentRef.body.appendChild(overlay)

	function appendEvent(type, details = {}) {
		events.push({
			at: finiteNumber(wallNow(), Date.now()),
			type: safeSource(type) || 'UNKNOWN',
			details: normalizeEventDetails(details),
			snapshot: normalizeSnapshot(current)
		})
		if (events.length > DEBUG_EVENT_LIMIT) {
			events.splice(0, events.length - DEBUG_EVENT_LIMIT)
		}
	}

	function refreshHealth() {
		const health = resolveVoiceWaveformDebugHealth(current, now())
		if (health !== lastHealth) {
			appendEvent('HEALTH_CHANGED', { from: lastHealth, to: health })
			lastHealth = health
		}
		return health
	}

	function renderNow() {
		if (destroyed || frozen) return
		if (renderTimer) clearTimeoutFn(renderTimer)
		renderTimer = 0
		const currentTime = now()
		const health = refreshHealth()
		statusNode.textContent = formatSnapshot(current, health, currentTime)
		historyNode.textContent = events.slice(-DEBUG_EVENT_LIMIT)
			.map(formatEvent).join('\n')
		lastRenderAt = finiteNumber(now(), Date.now())
	}

	function scheduleRender() {
		if (destroyed || frozen || renderTimer) return
		const remaining = DEBUG_RENDER_INTERVAL_MS
			- (finiteNumber(now(), Date.now()) - lastRenderAt)
		if (remaining <= 0) {
			renderNow()
			return
		}
		renderTimer = setTimeoutFn(renderNow, remaining)
	}

	function exportText() {
		return JSON.stringify({
			health: resolveVoiceWaveformDebugHealth(current, now()),
			current: normalizeSnapshot(current),
			events
		}, null, 2)
	}

	function capture() {
		appendEvent('MANUAL_SNAPSHOT')
		scheduleRender()
	}

	function toggleFrozen() {
		frozen = !frozen
		freezeButton.textContent = frozen ? 'FROZEN' : 'LIVE'
		if (!frozen) renderNow()
	}

	function copy() {
		const copyOperation = windowRef?.navigator?.clipboard?.writeText?.(exportText())
		copyOperation?.catch?.(() => {})
	}

	function clear() {
		events = []
		scheduleRender()
	}

	function onKeydown(event) {
		if (!event?.altKey || !event?.shiftKey
			|| String(event.key || '').toLowerCase() !== 's') return
		event.preventDefault?.()
		capture()
	}

	function scheduleWatchdog() {
		if (destroyed) return
		watchdogTimer = setTimeoutFn(() => {
			watchdogTimer = 0
			scheduleRender()
			scheduleWatchdog()
		}, DEBUG_WATCHDOG_INTERVAL_MS)
	}

	snapshotButton.addEventListener('click', capture)
	freezeButton.addEventListener('click', toggleFrozen)
	copyButton.addEventListener('click', copy)
	clearButton.addEventListener('click', clear)
	windowRef?.addEventListener?.('keydown', onKeydown)
	renderNow()
	scheduleWatchdog()

	return {
		update(snapshot) {
			if (destroyed) return
			current = normalizeSnapshot(snapshot)
			refreshHealth()
			scheduleRender()
		},
		record(type, details = {}) {
			if (destroyed) return
			appendEvent(type, details)
			scheduleRender()
		},
		capture,
		exportText,
		destroy() {
			if (destroyed) return
			destroyed = true
			if (renderTimer) clearTimeoutFn(renderTimer)
			if (watchdogTimer) clearTimeoutFn(watchdogTimer)
			renderTimer = 0
			watchdogTimer = 0
			windowRef?.removeEventListener?.('keydown', onKeydown)
			overlay.remove?.()
		}
	}
}

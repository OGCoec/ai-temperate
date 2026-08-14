import {
	VOICE_WAVEFORM_INTERVAL_MS,
	createVoiceWaveformTimeline
} from '../../../common/voice/voice-waveform-timeline.js'
import {
	VOICE_WAVEFORM_BAR_PITCH,
	VOICE_WAVEFORM_BAR_WIDTH,
	VOICE_WAVEFORM_HEIGHT,
	VOICE_WAVEFORM_MINIMUM_HEIGHT,
	VOICE_WAVEFORM_MAXIMUM_HEIGHT,
	VOICE_WAVEFORM_BASELINE_COLOR,
	presentVoiceWaveformBar,
	resolveVoiceWaveformCapacity
} from '../../../common/voice/voice-waveform-presentation.js'
import {
	createVoiceWaveformDebugController,
	measureVoiceWaveformDebugGeometry,
	summarizeVoiceWaveformDebugBars
} from './user-voice-waveform-debug.js'

const REDUCED_INTERVAL_MS = VOICE_WAVEFORM_INTERVAL_MS
const CANVAS_VOICE_WAVEFORM_MAX_CAPACITY = 512
const H5_NATIVE_CANVAS_HOST_SELECTOR = '.user-voice-waveform-native-host'
const H5_NATIVE_CANVAS_SELECTOR = 'canvas.user-voice-waveform-native-canvas'
const H5_NATIVE_CANVAS_CLASS = 'user-voice-waveform-native-canvas'
const H5_NATIVE_CANVAS_OWNER = 'RENDERER_NATIVE'
const APP_PLUS_CANVAS_OWNER = 'APP_PLUS_UNI_CANVAS'
const DEBUG_GEOMETRY_REFRESH_MS = 100

// Android 平台视觉参数：柱形更细更短，保持紧凑的移动端密度。
const ANDROID_BAR_WIDTH = 2
const ANDROID_MIN_HEIGHT = 2
const ANDROID_MAX_HEIGHT = 14
const ANDROID_LEVEL_EXPONENT = 1.3
const ANDROID_LEVEL_CEILING = 0.82
const ANDROID_WAVEFORM_DIAGNOSTIC_EVENT = 'voice_android_waveform'

function clamp01(value) {
	return Math.max(0, Math.min(1, Number(value) || 0))
}

function currentTimeMillis() {
	return typeof performance !== 'undefined' && typeof performance.now === 'function'
		? performance.now() : Date.now()
}

function wallTimeMillis() {
	try {
		const value = Number(Date.now())
		return Number.isFinite(value) ? value : 0
	} catch (_) {
		return 0
	}
}

function diagnosticInteger(value, fallback = -1) {
	const numeric = Number(value)
	return Number.isFinite(numeric) ? Math.round(numeric) : fallback
}

function diagnosticDecimal(value) {
	const numeric = Number(value)
	if (!Number.isFinite(numeric)) return '-1'
	return String(Number(numeric.toFixed(2)))
}

function diagnosticElapsed(nowMillis, startedAtMillis) {
	const now = Number(nowMillis)
	const startedAt = Number(startedAtMillis)
	if (!Number.isFinite(now) || !Number.isFinite(startedAt) || !(startedAt > 0)) return -1
	return Math.max(0, Math.round(now - startedAt))
}

function isCanvasElement(candidate) {
	return Boolean(
		candidate
		&& String(candidate.tagName || '').toUpperCase() === 'CANVAS'
		&& typeof candidate.getContext === 'function')
}

function isNativeCanvasCandidate(candidate) {
	return isCanvasElement(candidate) && candidate.isConnected !== false
}

export function ensureH5NativeCanvas(host, documentRef = globalThis.document) {
	if (!host) return null
	const existing = host.querySelector?.(H5_NATIVE_CANVAS_SELECTOR) || null
	if (isCanvasElement(existing)) return existing
	if (typeof documentRef?.createElement !== 'function'
		|| typeof host.appendChild !== 'function') return null

	const canvas = documentRef.createElement('canvas')
	canvas.className = H5_NATIVE_CANVAS_CLASS
	canvas.setAttribute?.('aria-hidden', 'true')
	canvas.setAttribute?.('data-voice-waveform-owner', 'renderer-native')
	host.appendChild(canvas)
	return isCanvasElement(canvas) ? canvas : null
}

function resolveNativeCanvas(root) {
	const nativeHost = root?.querySelector?.(H5_NATIVE_CANVAS_HOST_SELECTOR) || null
	if (nativeHost) {
		const canvas = ensureH5NativeCanvas(nativeHost)
		return {
			canvasHost: nativeHost,
			canvas,
			canvasOwnedByRenderer: Boolean(canvas)
		}
	}
	const canvasHost = root?.querySelector?.('.user-voice-waveform-canvas') || null
	const candidates = [
		canvasHost?.shadowRoot?.querySelector?.('canvas'),
		canvasHost?.querySelector?.('canvas.uni-canvas-canvas'),
		canvasHost?.querySelector?.('canvas'),
		canvasHost,
		root?.querySelector?.('canvas'),
		root
	]
	const canvas = candidates.find(isNativeCanvasCandidate) || null
	return isNativeCanvasCandidate(canvas)
		? { canvasHost: canvasHost || canvas, canvas, canvasOwnedByRenderer: false }
		: { canvasHost: null, canvas: null, canvasOwnedByRenderer: false }
}

function measureWidth(root, canvasHost, canvas, preferHost = false) {
	const rootCandidates = [
		{ source: 'rootClientWidth', value: Number(root?.clientWidth) },
		{ source: 'rootRectWidth', value: Number(root?.getBoundingClientRect?.()?.width) },
	]
	const hostCandidates = [
		{ source: 'hostClientWidth', value: Number(canvasHost?.clientWidth) },
		{ source: 'hostRectWidth', value: Number(canvasHost?.getBoundingClientRect?.()?.width) },
	]
	const canvasCandidates = [
		{ source: 'canvasOffsetWidth', value: Number(canvas?.offsetWidth) },
		{ source: 'canvasClientWidth', value: Number(canvas?.clientWidth) },
		{ source: 'canvasRectWidth', value: Number(canvas?.getBoundingClientRect?.()?.width) }
	]
	const candidates = preferHost
		? hostCandidates.slice().reverse()
		: [...rootCandidates, ...hostCandidates, ...canvasCandidates]
	const selected = candidates.find(candidate =>
		Number.isFinite(candidate.value) && candidate.value > 0)
	return {
		width: Math.max(0, selected?.value || 0),
		selectedWidthSource: selected?.source || ''
	}
}

export function resolveVoiceWaveformCanvasMetrics(width, dpr = 1) {
	const cssWidth = Math.max(0, Math.floor(Number(width) || 0))
	const numericDpr = Number(dpr)
	const resolvedDpr = Number.isFinite(numericDpr)
		? Math.max(1, numericDpr) : 1
	return {
		cssWidth,
		cssHeight: VOICE_WAVEFORM_HEIGHT,
		dpr: resolvedDpr,
		pixelWidth: Math.floor(cssWidth * resolvedDpr),
		pixelHeight: Math.floor(VOICE_WAVEFORM_HEIGHT * resolvedDpr),
		visibleBars: resolveVoiceWaveformCapacity(
			cssWidth,
			CANVAS_VOICE_WAVEFORM_MAX_CAPACITY)
	}
}

export function resolveVoiceWaveformContextScale(profile, dpr = 1) {
	const numericDpr = Number(dpr)
	const resolvedDpr = Number.isFinite(numericDpr)
		? Math.max(1, numericDpr) : 1
	// App-Plus Canvas 已把逻辑坐标映射到物理像素；再次乘 DPR 会让横纵几何同时放大两倍。
	return profile === 'android' ? 1 : resolvedDpr
}

/**
 * 只调整 Android 波形的视觉响应，不改变 PCM、录音数据或语音识别输入。
 * 指数压低中低分值，上限限制最大柱长；H5 继续使用原始归一化分值。
 */
export function resolveVoiceWaveformDisplayLevel(level, profile = 'h5') {
	const normalizedLevel = clamp01(level)
	if (profile !== 'android') return normalizedLevel
	return Math.min(
		ANDROID_LEVEL_CEILING,
		Math.pow(normalizedLevel, ANDROID_LEVEL_EXPONENT))
}

/**
 * 根据平台 profile 返回对应的柱宽、最小柱高和最大柱高。
 * Android 使用更细更短的柱子以适应较小的屏幕密度，H5 保持原有参数。
 */
function resolveBarParams(profile) {
	if (profile === 'android') {
		return {
			barWidth: ANDROID_BAR_WIDTH,
			minHeight: ANDROID_MIN_HEIGHT,
			maxHeight: ANDROID_MAX_HEIGHT
		}
	}
	return {
		barWidth: VOICE_WAVEFORM_BAR_WIDTH,
		minHeight: VOICE_WAVEFORM_MINIMUM_HEIGHT,
		maxHeight: VOICE_WAVEFORM_MAXIMUM_HEIGHT
	}
}

export function drawVoiceWaveformFrame(context, {
	width,
	height = VOICE_WAVEFORM_HEIGHT,
	bars = [],
	progress = 0,
	pendingBarId = null,
	profile = 'h5'
}) {
	if (!context) return
	const cssWidth = Math.max(0, Number(width) || 0)
	const cssHeight = Math.max(0, Number(height) || VOICE_WAVEFORM_HEIGHT)
	const clipWidth = resolveVoiceWaveformCapacity(
		cssWidth,
		CANVAS_VOICE_WAVEFORM_MAX_CAPACITY) * VOICE_WAVEFORM_BAR_PITCH
	const shift = clamp01(progress) * VOICE_WAVEFORM_BAR_PITCH
	const centerY = cssHeight / 2
	const source = Array.isArray(bars) ? bars : []
	const { barWidth, minHeight, maxHeight } = resolveBarParams(profile)

	context.save?.()
	if (typeof context.rect === 'function' && typeof context.clip === 'function') {
		context.beginPath()
		context.rect(0, 0, clipWidth, cssHeight)
		context.clip()
	}

	context.lineWidth = barWidth
	context.lineCap = 'round'
	for (let index = 0; index < source.length; index += 1) {
		if (pendingBarId != null && source[index]?.id === pendingBarId) continue
		const x = index * VOICE_WAVEFORM_BAR_PITCH
			- shift
			+ barWidth / 2
		if (x + barWidth / 2 <= 0
			|| x - barWidth / 2 >= clipWidth) continue
		// 使用平台参数计算柱高，而非共享 presentVoiceWaveformBar 的固定 2-20px。
		const level = resolveVoiceWaveformDisplayLevel(source[index]?.level, profile)
		const barHeight = minHeight + (maxHeight - minHeight) * level
		const color = level > 0
			? presentVoiceWaveformBar({ ...source[index], level }).color
			: VOICE_WAVEFORM_BASELINE_COLOR
		context.strokeStyle = color
		context.beginPath()
		context.moveTo(x, centerY - barHeight / 2)
		context.lineTo(x, centerY + barHeight / 2)
		context.stroke()
	}
	context.restore?.()
}

export default {
	data() {
		return {
			root: null,
			canvasHost: null,
			canvas: null,
			canvasOwnedByRenderer: false,
			canvasOwner: APP_PLUS_CANVAS_OWNER,
			context: null,
			config: null,
			dpr: 1,
			canvasMetrics: null,
			metricsDirty: true,
			timeline: createVoiceWaveformTimeline({
				now: currentTimeMillis,
				maxCapacity: CANVAS_VOICE_WAVEFORM_MAX_CAPACITY
			}),
			timelineEpoch: -1,
			raf: 0,
			reducedTimer: 0,
			running: false,
			visible: true,
			hidden: false,
			observer: null,
			resizeObserver: null,
			onVisibilityChange: null,
			onWindowResize: null,
			onOrientationChange: null,
			renderFailed: false,
			debugController: null,
			debugGeometry: null,
			debugBars: null,
			debugSelectedWidthSource: '',
			debugMetricsReason: 'INITIAL',
			debugPendingResizeSnapshot: null,
			debugRecordingStartedAt: 0,
			debugLatestPacketSequence: -1,
			debugLatestPacketLevelCount: 0,
			debugLatestMaxLevel: 0,
			debugFirstPacketSeen: false,
			debugFirstNonZeroPacketSeen: false,
			debugDrawCount: 0,
			debugResizeCount: 0,
			debugResizeObserverCount: 0,
			debugLastDrawAt: 0,
			debugLastGeometryReadAt: -Infinity,
			debugMountedRecorded: false,
			diagnosticEpoch: -1,
			diagnosticFirstPublishedAtMs: 0,
			diagnosticLatestPublishedAtMs: 0,
			diagnosticLatestBridgeMs: -1,
			diagnosticLatestSequence: -1,
			diagnosticPacketCount: 0,
			diagnosticLevelCount: 0,
			diagnosticLastBarDrawAtMs: 0,
			diagnosticLastLoggedCycle: -1,
			diagnosticMetricsSignature: '',
			diagnosticRenderFailureReported: false
		}
	},
	mounted(event, instance, ownerInstance) {
		this.root = ownerInstance?.$el || instance?.$el || this.$el || null
		this.syncDebugController()
		if (this.debugController && !this.debugMountedRecorded) {
			this.debugMountedRecorded = true
			this.debugController.record('MOUNT')
		}
		if (this.connectCanvas() && this.config) this.restart()
	},
	beforeUnmount() {
		this.teardown()
	},
	beforeDestroy() {
		this.teardown()
	},
	methods: {
		syncDebugController() {
			const shouldEnable = Boolean(this.config?.debug)
				&& this.config?.profile !== 'android'
			if (!shouldEnable) {
				this.debugController?.destroy?.()
				this.debugController = null
				this.debugMountedRecorded = false
				return
			}
			if (!this.debugController) {
				this.debugController = createVoiceWaveformDebugController()
				if (this.root && this.debugController && !this.debugMountedRecorded) {
					this.debugMountedRecorded = true
					this.debugController.record('MOUNT')
				}
			}
		},

		resetDebugSession(nowMillis = currentTimeMillis()) {
			if (!this.debugController) return
			this.debugRecordingStartedAt = Number(nowMillis) || 0
			this.debugLatestPacketSequence = -1
			this.debugLatestPacketLevelCount = 0
			this.debugLatestMaxLevel = 0
			this.debugFirstPacketSeen = false
			this.debugFirstNonZeroPacketSeen = false
			this.debugBars = null
		},

		captureDebugPacket(packet) {
			if (!this.debugController) return
			const levels = Array.isArray(packet?.levels) ? packet.levels : []
			this.debugLatestPacketSequence = Number(packet?.sequence)
			this.debugLatestPacketLevelCount = levels.length
			this.debugLatestMaxLevel = levels.reduce((maximum, level) =>
				Math.max(maximum, Math.max(0, Math.min(1, Number(level) || 0))), 0)
			this.updateDebugSnapshot(currentTimeMillis())
			if (!this.debugFirstPacketSeen) {
				this.debugFirstPacketSeen = true
				this.debugController.record('FIRST_PACKET', {
					sequence: this.debugLatestPacketSequence
				})
			}
			if (this.debugLatestMaxLevel > 0 && !this.debugFirstNonZeroPacketSeen) {
				this.debugFirstNonZeroPacketSeen = true
				this.debugController.record('FIRST_NONZERO_PACKET', {
					sequence: this.debugLatestPacketSequence
				})
			}
		},

		buildDebugSnapshot(nowMillis = currentTimeMillis()) {
			const metrics = this.canvasMetrics || {}
			const geometry = this.refreshDebugGeometry(nowMillis)
			return {
				state: this.config?.state || 'IDLE',
				sessionEpoch: Number(this.config?.sessionEpoch),
				elapsedMs: this.debugRecordingStartedAt > 0
					? Math.max(0, Number(nowMillis) - this.debugRecordingStartedAt) : 0,
				packetSequence: this.debugLatestPacketSequence,
				packetLevelCount: this.debugLatestPacketLevelCount,
				maxLevel: this.debugLatestMaxLevel,
				viewportWidth: typeof window !== 'undefined' ? window.innerWidth : 0,
				dpr: Number(globalThis.devicePixelRatio) || this.dpr || 1,
				metrics: {
					owner: this.canvasOwner,
					cssWidth: metrics.cssWidth,
					cssHeight: metrics.cssHeight,
					pixelWidth: metrics.pixelWidth,
					pixelHeight: metrics.pixelHeight,
					visibleBars: metrics.visibleBars,
					selectedWidthSource: this.debugSelectedWidthSource
				},
				geometry,
				bars: this.debugBars || {},
				running: this.running,
				documentVisible: this.visible && !this.hidden && (typeof document === 'undefined'
					|| document.visibilityState !== 'hidden'),
				lastDrawAt: this.debugLastDrawAt,
				renderFailed: this.renderFailed,
				counters: {
					resize: this.debugResizeCount,
					resizeObserver: this.debugResizeObserverCount,
					draw: this.debugDrawCount
				}
			}
		},

		refreshDebugGeometry(nowMillis = currentTimeMillis(), force = false) {
			if (!this.debugController) return this.debugGeometry || {}
			const now = Number(nowMillis) || currentTimeMillis()
			if (!force && this.debugGeometry
				&& now - this.debugLastGeometryReadAt < DEBUG_GEOMETRY_REFRESH_MS) {
				return this.debugGeometry
			}
			this.debugGeometry = measureVoiceWaveformDebugGeometry({
				root: this.root,
				host: this.canvasHost,
				canvas: this.canvas,
				windowRef: typeof window !== 'undefined' ? window : null
			})
			this.debugLastGeometryReadAt = now
			return this.debugGeometry
		},

		updateDebugSnapshot(nowMillis = currentTimeMillis()) {
			if (!this.debugController) return null
			const snapshot = this.buildDebugSnapshot(nowMillis)
			this.debugController.update(snapshot)
			return snapshot
		},

		captureDebugMetrics(measurement, nowMillis = currentTimeMillis()) {
			if (!this.debugController) return
			this.debugSelectedWidthSource = measurement?.selectedWidthSource || ''
			this.refreshDebugGeometry(nowMillis, true)
			const before = this.debugPendingResizeSnapshot
			const after = this.buildDebugSnapshot(nowMillis)
			this.debugController.update(after)
			this.debugController.record('METRICS_CONFIGURED', {
				reason: this.debugMetricsReason,
				before,
				after
			})
			this.debugPendingResizeSnapshot = null
			this.debugMetricsReason = 'DRAW'
		},

		resetAndroidWaveformDiagnostics(epoch = -1) {
			this.diagnosticEpoch = diagnosticInteger(epoch)
			this.diagnosticFirstPublishedAtMs = 0
			this.diagnosticLatestPublishedAtMs = 0
			this.diagnosticLatestBridgeMs = -1
			this.diagnosticLatestSequence = -1
			this.diagnosticPacketCount = 0
			this.diagnosticLevelCount = 0
			this.diagnosticLastBarDrawAtMs = 0
			this.diagnosticLastLoggedCycle = -1
			this.diagnosticMetricsSignature = ''
			this.diagnosticRenderFailureReported = false
		},

		recordAndroidWaveformDiagnosticPacket(packet, acceptedAtMs = wallTimeMillis()) {
			if (this.config?.profile !== 'android' || !packet
				|| diagnosticInteger(packet.epoch) !== this.diagnosticEpoch) return
			const acceptedAtValue = Number(acceptedAtMs)
			const acceptedAt = Number.isFinite(acceptedAtValue)
				? acceptedAtValue : wallTimeMillis()
			const publishedAtValue = Number(packet.publishedAtMs)
			const publishedAt = Number.isFinite(publishedAtValue) && publishedAtValue > 0
				? publishedAtValue : acceptedAt
			if (!(this.diagnosticFirstPublishedAtMs > 0)) {
				this.diagnosticFirstPublishedAtMs = publishedAt
			}
			this.diagnosticLatestPublishedAtMs = publishedAt
			this.diagnosticLatestBridgeMs = diagnosticElapsed(acceptedAt, publishedAt)
			this.diagnosticLatestSequence = diagnosticInteger(packet.sequence)
			this.diagnosticPacketCount += 1
			this.diagnosticLevelCount += Array.isArray(packet.levels)
				? Math.min(5, packet.levels.length) : 0
		},

		logAndroidWaveformMetrics(metrics) {
			if (this.config?.profile !== 'android' || !metrics
				|| typeof console === 'undefined') return
			const capacity = Math.max(1, diagnosticInteger(metrics.visibleBars, 1))
			const spanPx = capacity * VOICE_WAVEFORM_BAR_PITCH
			const unusedPx = Number(metrics.cssWidth) - spanPx
			const centerY = Number(metrics.cssHeight) / 2
			const contextScale = resolveVoiceWaveformContextScale('android', metrics.dpr)
			const maximumDisplayLevel = resolveVoiceWaveformDisplayLevel(1, 'android')
			const maxBarHeight = ANDROID_MIN_HEIGHT
				+ (ANDROID_MAX_HEIGHT - ANDROID_MIN_HEIGHT) * maximumDisplayLevel
			const maxTopY = centerY - maxBarHeight / 2
			const maxBottomY = centerY + maxBarHeight / 2
			const symmetricBounds = Math.abs(
				(centerY - maxTopY) - (maxBottomY - centerY)) < 0.001
			const signature = [
				this.diagnosticEpoch,
				metrics.cssWidth,
				metrics.cssHeight,
				metrics.pixelWidth,
				metrics.pixelHeight,
				metrics.dpr,
				capacity
			].join(':')
			if (signature === this.diagnosticMetricsSignature) return
			this.diagnosticMetricsSignature = signature
			console.log(
				`event=${ANDROID_WAVEFORM_DIAGNOSTIC_EVENT} phase=CANVAS_METRICS`
				+ ` epoch=${this.diagnosticEpoch} profile=android`
				+ ` cssWidth=${diagnosticDecimal(metrics.cssWidth)}`
				+ ` cssHeight=${diagnosticDecimal(metrics.cssHeight)}`
				+ ` pixelWidth=${diagnosticInteger(metrics.pixelWidth)}`
				+ ` pixelHeight=${diagnosticInteger(metrics.pixelHeight)}`
				+ ` dpr=${diagnosticDecimal(metrics.dpr)}`
				+ ` contextScale=${diagnosticDecimal(contextScale)}`
				+ ` capacity=${capacity}`
				+ ` spanPx=${diagnosticDecimal(spanPx)}`
				+ ` unusedPx=${diagnosticDecimal(unusedPx)}`
				+ ` centerY=${diagnosticDecimal(centerY)}`
				+ ` levelExponent=${diagnosticDecimal(ANDROID_LEVEL_EXPONENT)}`
				+ ` levelCeiling=${diagnosticDecimal(ANDROID_LEVEL_CEILING)}`
				+ ` maxBarHeight=${diagnosticDecimal(maxBarHeight)}`
				+ ` maxTopY=${diagnosticDecimal(maxTopY)}`
				+ ` maxBottomY=${diagnosticDecimal(maxBottomY)}`
				+ ` symmetricBounds=${symmetricBounds}`)
		},

		logAndroidWaveformBar(snapshot, metrics, renderedAtMs = wallTimeMillis()) {
			if (this.config?.profile !== 'android' || !snapshot || !metrics
				|| typeof console === 'undefined') return
			const cycle = diagnosticInteger(snapshot.cycle)
			if (cycle < 0 || cycle === this.diagnosticLastLoggedCycle) return
			const capacity = Math.max(1, diagnosticInteger(metrics.visibleBars, 1))
			const spanPx = capacity * VOICE_WAVEFORM_BAR_PITCH
			const unusedPx = Number(metrics.cssWidth) - spanPx
			const settledCount = Array.isArray(snapshot.settledBars)
				? snapshot.settledBars.length : capacity
			const latestSlotX = (Math.max(1, settledCount) - 1) * VOICE_WAVEFORM_BAR_PITCH
				+ ANDROID_BAR_WIDTH / 2
			const tickGapMs = this.diagnosticLastBarDrawAtMs > 0
				? diagnosticElapsed(renderedAtMs, this.diagnosticLastBarDrawAtMs) : -1
			console.log(
				`event=${ANDROID_WAVEFORM_DIAGNOSTIC_EVENT} phase=BAR_DRAWN`
				+ ` epoch=${this.diagnosticEpoch} cycle=${cycle}`
				+ ` sequence=${this.diagnosticLatestSequence}`
				+ ` tickGapMs=${tickGapMs}`
				+ ` bridgeMs=${this.diagnosticLatestBridgeMs}`
				+ ` firstPacketAgeMs=${diagnosticElapsed(renderedAtMs, this.diagnosticFirstPublishedAtMs)}`
				+ ` latestPacketAgeMs=${diagnosticElapsed(renderedAtMs, this.diagnosticLatestPublishedAtMs)}`
				+ ` packetCount=${this.diagnosticPacketCount}`
				+ ` levelCount=${this.diagnosticLevelCount}`
				+ ` cssWidth=${diagnosticDecimal(metrics.cssWidth)}`
				+ ` capacity=${capacity}`
				+ ` spanPx=${diagnosticDecimal(spanPx)}`
				+ ` unusedPx=${diagnosticDecimal(unusedPx)}`
				+ ` latestSlotX=${diagnosticDecimal(latestSlotX)}`)
			this.diagnosticLastBarDrawAtMs = renderedAtMs
			this.diagnosticLastLoggedCycle = cycle
			this.diagnosticFirstPublishedAtMs = 0
			this.diagnosticLatestPublishedAtMs = 0
			this.diagnosticLatestBridgeMs = -1
			this.diagnosticLatestSequence = -1
			this.diagnosticPacketCount = 0
			this.diagnosticLevelCount = 0
		},

		reportAndroidWaveformRenderFailure() {
			if (this.config?.profile !== 'android'
				|| this.diagnosticRenderFailureReported
				|| typeof console === 'undefined') return
			this.diagnosticRenderFailureReported = true
			console.warn(
				`event=${ANDROID_WAVEFORM_DIAGNOSTIC_EVENT} phase=RENDER_FAILED`)
		},

		update(value, oldValue, ownerInstance, instance) {
			this.root = this.root || ownerInstance?.$el || instance?.$el || this.$el || null
			const previous = this.config
			this.config = value || null
			this.syncDebugController()
			const epoch = Number(this.config?.sessionEpoch)
			const recording = this.config?.state === 'RECORDING'
			const epochChanged = epoch !== Number(this.timelineEpoch)
			const stateChanged = this.config?.state !== previous?.state
			const reducedChanged = Boolean(this.config?.reduced) !== Boolean(previous?.reduced)

			if (recording && (epochChanged || previous?.state !== 'RECORDING')) {
				this.timelineEpoch = epoch
				this.timeline.start(epoch)
				this.renderFailed = false
				this.resetDebugSession(currentTimeMillis())
				this.resetAndroidWaveformDiagnostics(epoch)
			} else if (['FINALIZING', 'IDLE', 'ERROR'].includes(this.config?.state)) {
				this.timelineEpoch = epoch
				this.timeline.reset(epoch)
				if (epochChanged || stateChanged || this.diagnosticEpoch !== epoch) {
					this.resetAndroidWaveformDiagnostics(epoch)
				}
			}
			const packetAccepted = recording
				&& this.timeline.accept(this.config?.packet)
			if (packetAccepted) {
				this.captureDebugPacket(this.config?.packet)
				this.recordAndroidWaveformDiagnosticPacket(this.config?.packet)
			}
			this.updateDebugSnapshot(currentTimeMillis())
			if (stateChanged) {
				this.debugController?.record?.('STATE_CHANGED', {
					from: previous?.state || 'IDLE',
					to: this.config?.state || 'IDLE'
				})
			}
			this.connectCanvas()
			if (epochChanged || stateChanged || reducedChanged
				|| (!this.running && !this.reducedTimer)) this.restart()
		},

		connectCanvas() {
			if (!this.root) return false
			const { canvasHost, canvas, canvasOwnedByRenderer } = resolveNativeCanvas(this.root)
			if (!canvas) return false
			this.canvasHost = canvasHost || canvas
			this.canvasOwnedByRenderer = Boolean(canvasOwnedByRenderer)
			this.canvasOwner = this.canvasOwnedByRenderer
				? H5_NATIVE_CANVAS_OWNER : APP_PLUS_CANVAS_OWNER
			if (canvas === this.canvas && this.context) {
				if (this.config?.profile === 'android') this.context.__hidpi__ = false
				return true
			}

			this.stop()
			this.observer?.disconnect?.()
			this.resizeObserver?.disconnect?.()
			this.canvas = canvas
			this.context = canvas.getContext?.('2d') || null
			this.dpr = Math.max(1, Number(globalThis.devicePixelRatio) || 1)
			if (!this.context) return false
			if (this.config?.profile === 'android') this.context.__hidpi__ = false
			this.canvasMetrics = null
			this.metricsDirty = true
			this.debugGeometry = null
			this.debugLastGeometryReadAt = -Infinity
			this.debugController?.record?.('CANVAS_CONNECTED')
			this.diagnosticMetricsSignature = ''

			if (typeof IntersectionObserver !== 'undefined') {
				this.observer = new IntersectionObserver(entries => {
					this.visible = Boolean(entries[0]?.isIntersecting)
					if (this.visible && !this.hidden) this.restart()
					else this.stop()
					this.updateDebugSnapshot(currentTimeMillis())
				})
				this.observer.observe(canvas)
			}
			if (typeof ResizeObserver !== 'undefined') {
				this.resizeObserver = new ResizeObserver(() => {
					if (this.debugController) this.debugResizeObserverCount += 1
					this.handleCanvasMetricsChange('RESIZE_OBSERVER')
				})
				this.resizeObserver.observe(
					this.canvasOwnedByRenderer ? this.canvasHost : this.root)
			}
			if (!this.onVisibilityChange && typeof document !== 'undefined') {
				this.onVisibilityChange = () => {
					this.hidden = document.visibilityState === 'hidden'
					if (this.hidden) this.stop()
					else this.restart()
					this.updateDebugSnapshot(currentTimeMillis())
				}
				document.addEventListener('visibilitychange', this.onVisibilityChange)
			}
			if (!this.onWindowResize && typeof window !== 'undefined') {
				this.onWindowResize = () => {
					if (this.debugController) this.debugResizeCount += 1
					this.handleCanvasMetricsChange('WINDOW_RESIZE')
				}
				this.onOrientationChange = () => this.handleCanvasMetricsChange(
					'ORIENTATION_CHANGE')
				window.addEventListener('resize', this.onWindowResize)
				window.addEventListener('orientationchange', this.onOrientationChange)
			}
			return Boolean(this.context)
		},

		markCanvasMetricsDirty() {
			this.metricsDirty = true
			this.diagnosticMetricsSignature = ''
		},

		handleCanvasMetricsChange(reason = 'METRICS_CHANGE') {
			if (this.debugController) {
				const observedAt = currentTimeMillis()
				this.refreshDebugGeometry(observedAt, true)
				const before = this.buildDebugSnapshot(observedAt)
				if (!this.debugPendingResizeSnapshot) {
					this.debugPendingResizeSnapshot = before
				}
				this.debugMetricsReason = reason
				this.debugController.record(reason, { before })
			}
			this.markCanvasMetricsDirty()
			if (!this.running && !this.reducedTimer && this.config
				&& !this.hidden && this.visible && !this.renderFailed) this.draw(currentTimeMillis())
		},

		configureCanvas() {
			if (!isNativeCanvasCandidate(this.canvas) || !this.context) return null
			const currentDpr = Math.max(
				1,
				Number(globalThis.devicePixelRatio) || this.dpr || 1)
			// 显示密度可能在窗口跨屏或系统缩放后单独变化，不能继续复用旧 backing store。
			if (!this.metricsDirty && this.canvasMetrics
				&& this.canvasMetrics.dpr !== currentDpr) this.markCanvasMetricsDirty()
			if (!this.metricsDirty && this.canvasMetrics) {
				this.logAndroidWaveformMetrics(this.canvasMetrics)
				return this.canvasMetrics
			}
			// 先完成尺寸读取再统一写入，避免动画帧内交错读取和写入布局。
			const measurement = measureWidth(
				this.root,
				this.canvasHost,
				this.canvas,
				this.canvasOwnedByRenderer)
			const width = measurement.width
			// 宽度为 0 时不缓存为有效 metrics，下一帧继续测量。
			if (!(width > 0)) return null
			const metrics = resolveVoiceWaveformCanvasMetrics(
				width,
				currentDpr)
			this.dpr = metrics.dpr
			if (this.canvas.width !== metrics.pixelWidth) this.canvas.width = metrics.pixelWidth
			if (this.canvas.height !== metrics.pixelHeight) this.canvas.height = metrics.pixelHeight
			if (this.canvas.style) {
				const cssWidth = `${metrics.cssWidth}px`
				const cssHeight = `${metrics.cssHeight}px`
				if (this.canvas.style.width !== cssWidth) this.canvas.style.width = cssWidth
				if (this.canvas.style.height !== cssHeight) this.canvas.style.height = cssHeight
				if (this.canvas.style.display !== 'block') this.canvas.style.display = 'block'
			}
			if (this.canvasHost?.style) {
				const cssHeight = `${metrics.cssHeight}px`
				if (this.canvasHost.style.height !== cssHeight) {
					this.canvasHost.style.height = cssHeight
				}
				if (this.canvasHost.style.minHeight !== cssHeight) {
					this.canvasHost.style.minHeight = cssHeight
				}
			}
			this.context = this.canvas.getContext?.('2d') || this.context
			if (!this.context) return null
			if (this.config?.profile === 'android') this.context.__hidpi__ = false
			this.timeline.setCapacity(metrics.visibleBars)
			this.canvasMetrics = metrics
			this.metricsDirty = false
			this.captureDebugMetrics(measurement, currentTimeMillis())
			this.logAndroidWaveformMetrics(this.canvasMetrics)
			return this.canvasMetrics
		},

		draw(nowMillis = currentTimeMillis()) {
			try {
				if (!this.config) return false
				if (!isNativeCanvasCandidate(this.canvas) || !this.context
					|| this.canvas?.isConnected === false) this.connectCanvas()
				const metrics = this.configureCanvas()
				if (!metrics || !this.context) return false
				const advanced = this.config.state === 'RECORDING'
					? this.timeline.advance(nowMillis) : false
				const snapshot = this.timeline.snapshot(nowMillis)
				const pendingBarId = this.config.reduced
					? null
					: snapshot.movingBars[snapshot.movingBars.length - 1]?.id
				// 通过内部 profile 字段区分平台绘制参数。
				const profile = this.config.profile || 'h5'
				const frameBars = this.config.reduced
					? snapshot.settledBars : snapshot.movingBars

				this.context.setTransform(1, 0, 0, 1, 0, 0)
				this.context.clearRect(0, 0, metrics.pixelWidth, metrics.pixelHeight)
				const contextScale = resolveVoiceWaveformContextScale(profile, metrics.dpr)
				this.context.setTransform(contextScale, 0, 0, contextScale, 0, 0)
				drawVoiceWaveformFrame(this.context, {
					width: metrics.cssWidth,
					height: metrics.cssHeight,
					bars: frameBars,
					progress: this.config.reduced ? 0 : snapshot.progress,
					pendingBarId,
					profile
				})
				if (this.debugController) {
					this.debugDrawCount += 1
					this.debugLastDrawAt = Number(nowMillis) || currentTimeMillis()
					this.debugBars = summarizeVoiceWaveformDebugBars({
						bars: frameBars,
						progress: this.config.reduced ? 0 : snapshot.progress,
						pitch: VOICE_WAVEFORM_BAR_PITCH,
						barWidth: VOICE_WAVEFORM_BAR_WIDTH,
						visibleWidth: this.debugGeometry
							? this.debugGeometry.canvas.visibleWidth
							: metrics.cssWidth,
						visibleLeft: this.debugGeometry?.canvas?.visibleLeft || 0,
						visibleRight: this.debugGeometry
							? this.debugGeometry.canvas.visibleRight
							: metrics.cssWidth,
						pendingBarId
					})
					this.updateDebugSnapshot(nowMillis)
				}
				if (advanced) this.logAndroidWaveformBar(snapshot, metrics)
				return true
			} catch (_) {
				this.renderFailed = true
				if (this.debugController) {
					this.updateDebugSnapshot(currentTimeMillis())
					this.debugController.record('RENDER_FAILED')
				}
				this.reportAndroidWaveformRenderFailure()
				// 可视反馈失败时仅停止自身调度，录音、发送和转写主链路继续运行。
				this.stop()
				return false
			}
		},

		restart() {
			this.stop()
			if (!this.config || this.hidden || !this.visible || this.renderFailed) return
			this.draw(currentTimeMillis())
			if (this.renderFailed) return
			if (this.config.state !== 'RECORDING') return
			if (this.config.reduced) {
				this.scheduleReduced()
				return
			}
			this.running = true
			const loop = timestamp => {
				if (!this.running) return
				if (this.canvas?.isConnected === false) {
					this.canvasMetrics = null
					this.markCanvasMetricsDirty()
				}
				const drewFrame = this.draw(timestamp)
				if (!this.running && drewFrame && !this.renderFailed
					&& this.config?.state === 'RECORDING' && !this.config?.reduced
					&& !this.hidden && this.visible) this.running = true
				if (!this.running) return
				this.raf = requestAnimationFrame(loop)
			}
			this.raf = requestAnimationFrame(loop)
		},

		scheduleReduced() {
			if (this.reducedTimer || this.hidden || !this.visible || this.renderFailed) return
			this.reducedTimer = setTimeout(() => {
				this.reducedTimer = 0
				this.draw(currentTimeMillis())
				if (!this.renderFailed && this.config?.state === 'RECORDING') {
					this.scheduleReduced()
				}
			}, REDUCED_INTERVAL_MS)
		},

		stop() {
			this.running = false
			if (this.raf) cancelAnimationFrame(this.raf)
			if (this.reducedTimer) clearTimeout(this.reducedTimer)
			this.raf = 0
			this.reducedTimer = 0
		},

		teardown() {
			const ownedCanvas = this.canvasOwnedByRenderer ? this.canvas : null
			this.stop()
			this.timeline.dispose()
			this.observer?.disconnect?.()
			this.resizeObserver?.disconnect?.()
			if (this.onVisibilityChange && typeof document !== 'undefined') {
				document.removeEventListener('visibilitychange', this.onVisibilityChange)
			}
			if (this.onWindowResize && typeof window !== 'undefined') {
				window.removeEventListener('resize', this.onWindowResize)
				if (this.onOrientationChange) {
					window.removeEventListener('orientationchange', this.onOrientationChange)
				}
			}
			this.debugController?.record?.('UNMOUNT')
			this.debugController?.destroy?.()
			this.debugController = null
			this.observer = null
			this.resizeObserver = null
			this.onVisibilityChange = null
			this.onWindowResize = null
			this.onOrientationChange = null
			ownedCanvas?.remove?.()
			this.canvas = null
			this.canvasHost = null
			this.canvasOwnedByRenderer = false
			this.canvasOwner = APP_PLUS_CANVAS_OWNER
			this.context = null
			this.canvasMetrics = null
			this.metricsDirty = true
			this.debugGeometry = null
			this.debugLastGeometryReadAt = -Infinity
			this.resetAndroidWaveformDiagnostics(-1)
			this.root = null
		}
	}
}

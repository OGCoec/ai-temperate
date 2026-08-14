import {
	VOICE_WAVEFORM_INTERVAL_MS,
	createVoiceWaveformTimeline
} from '../../../common/voice/voice-waveform-timeline.js'
import {
	VOICE_WAVEFORM_BAR_PITCH,
	VOICE_WAVEFORM_BAR_WIDTH,
	VOICE_WAVEFORM_HEIGHT,
	presentVoiceWaveformBar,
	resolveVoiceWaveformCapacity
} from '../../../common/voice/voice-waveform-presentation.js'

const REDUCED_INTERVAL_MS = VOICE_WAVEFORM_INTERVAL_MS
const H5_VOICE_WAVEFORM_MAX_CAPACITY = 512

function clamp01(value) {
	return Math.max(0, Math.min(1, Number(value) || 0))
}

function currentTimeMillis() {
	return typeof performance !== 'undefined' && typeof performance.now === 'function'
		? performance.now() : Date.now()
}

function isNativeCanvasCandidate(candidate) {
	return Boolean(
		candidate
		&& String(candidate.tagName || '').toUpperCase() === 'CANVAS'
		&& typeof candidate.getContext === 'function'
		&& candidate.isConnected !== false)
}

function resolveNativeCanvas(root) {
	const canvasHost = root?.querySelector?.('.user-voice-waveform-canvas') || null
	const candidates = [
		canvasHost?.shadowRoot?.querySelector?.('canvas'),
		canvasHost?.querySelector?.('canvas.uni-canvas-canvas'),
		canvasHost?.querySelector?.('canvas'),
		canvasHost,
		root?.querySelector?.('canvas')
	]
	const canvas = candidates.find(isNativeCanvasCandidate) || null
	return isNativeCanvasCandidate(canvas)
		? { canvasHost: canvasHost || canvas, canvas }
		: { canvasHost: null, canvas: null }
}

function measuredWidth(root, canvasHost, canvas) {
	const candidates = [
		Number(root?.clientWidth),
		Number(root?.getBoundingClientRect?.()?.width),
		Number(canvasHost?.clientWidth),
		Number(canvasHost?.getBoundingClientRect?.()?.width),
		Number(canvas?.offsetWidth),
		Number(canvas?.clientWidth),
		Number(canvas?.getBoundingClientRect?.()?.width)
	]
	return Math.max(0, candidates.find(value => Number.isFinite(value) && value > 0) || 0)
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
			H5_VOICE_WAVEFORM_MAX_CAPACITY)
	}
}

export function drawVoiceWaveformFrame(context, {
	width,
	height = VOICE_WAVEFORM_HEIGHT,
	bars = [],
	progress = 0,
	pendingBarId = null
}) {
	if (!context) return
	const cssWidth = Math.max(0, Number(width) || 0)
	const cssHeight = Math.max(0, Number(height) || VOICE_WAVEFORM_HEIGHT)
	const clipWidth = resolveVoiceWaveformCapacity(
		cssWidth,
		H5_VOICE_WAVEFORM_MAX_CAPACITY) * VOICE_WAVEFORM_BAR_PITCH
	const shift = clamp01(progress) * VOICE_WAVEFORM_BAR_PITCH
	const centerY = cssHeight / 2
	const source = Array.isArray(bars) ? bars : []

	context.save?.()
	if (typeof context.rect === 'function' && typeof context.clip === 'function') {
		context.beginPath()
		context.rect(0, 0, clipWidth, cssHeight)
		context.clip()
	}
	context.lineWidth = VOICE_WAVEFORM_BAR_WIDTH
	context.lineCap = 'round'
	for (let index = 0; index < source.length; index += 1) {
		if (pendingBarId != null && source[index]?.id === pendingBarId) continue
		const x = index * VOICE_WAVEFORM_BAR_PITCH
			- shift
			+ VOICE_WAVEFORM_BAR_WIDTH / 2
		if (x + VOICE_WAVEFORM_BAR_WIDTH / 2 <= 0
			|| x - VOICE_WAVEFORM_BAR_WIDTH / 2 >= clipWidth) continue
		const presentation = presentVoiceWaveformBar(source[index])
		context.strokeStyle = presentation.color
		context.beginPath()
		context.moveTo(x, centerY - presentation.height / 2)
		context.lineTo(x, centerY + presentation.height / 2)
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
			context: null,
			config: null,
			dpr: 1,
			timeline: createVoiceWaveformTimeline({
				now: currentTimeMillis,
				maxCapacity: H5_VOICE_WAVEFORM_MAX_CAPACITY
			}),
			timelineEpoch: -1,
			raf: 0,
			reducedTimer: 0,
			running: false,
			visible: true,
			hidden: false,
			observer: null,
			resizeObserver: null,
			onVisibilityChange: null
		}
	},
	mounted(event, instance, ownerInstance) {
		this.root = ownerInstance?.$el || instance?.$el || this.$el || null
		if (this.connectCanvas() && this.config) this.restart()
	},
	beforeUnmount() {
		this.teardown()
	},
	beforeDestroy() {
		this.teardown()
	},
	methods: {
		update(value, oldValue, ownerInstance, instance) {
			this.root = this.root || ownerInstance?.$el || instance?.$el || this.$el || null
			const previous = this.config
			this.config = value || null
			const epoch = Number(this.config?.sessionEpoch)
			const recording = this.config?.state === 'RECORDING'
			const epochChanged = epoch !== Number(this.timelineEpoch)
			const stateChanged = this.config?.state !== previous?.state
			const reducedChanged = Boolean(this.config?.reduced) !== Boolean(previous?.reduced)

			if (recording && (epochChanged || previous?.state !== 'RECORDING')) {
				this.timelineEpoch = epoch
				this.timeline.start(epoch)
			} else if (['FINALIZING', 'IDLE', 'ERROR'].includes(this.config?.state)) {
				this.timelineEpoch = epoch
				this.timeline.reset(epoch)
			}
			if (recording) this.timeline.accept(this.config?.packet)

			this.connectCanvas()
			if (epochChanged || stateChanged || reducedChanged
				|| (!this.running && !this.reducedTimer)) this.restart()
		},

		connectCanvas() {
			if (!this.root) return false
			const { canvasHost, canvas } = resolveNativeCanvas(this.root)
			if (!canvas) return false
			this.canvasHost = canvasHost || canvas
			if (canvas === this.canvas && this.context) return true

			this.stop()
			this.observer?.disconnect?.()
			this.canvas = canvas
			this.context = canvas.getContext?.('2d') || null
			this.dpr = Math.max(1, Number(globalThis.devicePixelRatio) || 1)
			if (!this.context) return false

			if (typeof IntersectionObserver !== 'undefined') {
				this.observer = new IntersectionObserver(entries => {
					this.visible = Boolean(entries[0]?.isIntersecting)
					if (this.visible && !this.hidden) this.restart()
					else this.stop()
				})
				this.observer.observe(canvas)
			}
			this.resizeObserver?.disconnect?.()
			if (typeof ResizeObserver !== 'undefined') {
				this.resizeObserver = new ResizeObserver(() => this.restart())
				this.resizeObserver.observe(this.root)
			}
			if (!this.onVisibilityChange && typeof document !== 'undefined') {
				this.onVisibilityChange = () => {
					this.hidden = document.visibilityState === 'hidden'
					if (this.hidden) this.stop()
					else this.restart()
				}
				document.addEventListener('visibilitychange', this.onVisibilityChange)
			}
			return Boolean(this.context)
		},

		configureCanvas() {
			if (!isNativeCanvasCandidate(this.canvas) || !this.context) return null
			// 先完成尺寸读取再统一写入，避免动画帧内交错读取和写入布局。
			const width = measuredWidth(this.root, this.canvasHost, this.canvas)
			if (!(width > 0)) return null
			const metrics = resolveVoiceWaveformCanvasMetrics(
				width,
				globalThis.devicePixelRatio || this.dpr)
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
			this.timeline.setCapacity(metrics.visibleBars)
			return metrics
		},

		draw(nowMillis = currentTimeMillis()) {
			try {
				if (!this.config) return false
				if (!isNativeCanvasCandidate(this.canvas) || !this.context
					|| this.canvas?.isConnected === false) this.connectCanvas()
				const metrics = this.configureCanvas()
				if (!metrics || !this.context) return false
				if (this.config.state === 'RECORDING') this.timeline.advance(nowMillis)
				const snapshot = this.timeline.snapshot(nowMillis)
				const pendingBarId = this.config.reduced
					? null
					: snapshot.movingBars[snapshot.movingBars.length - 1]?.id

				this.context.setTransform(1, 0, 0, 1, 0, 0)
				this.context.clearRect(0, 0, metrics.pixelWidth, metrics.pixelHeight)
				this.context.setTransform(metrics.dpr, 0, 0, metrics.dpr, 0, 0)
				drawVoiceWaveformFrame(this.context, {
					width: metrics.cssWidth,
					height: metrics.cssHeight,
					bars: this.config.reduced
						? snapshot.settledBars : snapshot.movingBars,
					progress: this.config.reduced ? 0 : snapshot.progress,
					pendingBarId
				})
				return true
			} catch (_) {
				// 可视反馈失败时仅停止自身调度，录音、发送和转写主链路继续运行。
				this.stop()
				return false
			}
		},

		restart() {
			this.stop()
			if (!this.config || this.hidden || !this.visible) return
			this.draw(currentTimeMillis())
			if (this.config.state !== 'RECORDING') return
			if (this.config.reduced) {
				this.scheduleReduced()
				return
			}
			this.running = true
			const loop = timestamp => {
				if (!this.running) return
				if (this.canvas?.isConnected === false) {
					this.teardown()
					return
				}
				this.draw(timestamp)
				this.raf = requestAnimationFrame(loop)
			}
			this.raf = requestAnimationFrame(loop)
		},

		scheduleReduced() {
			if (this.reducedTimer || this.hidden || !this.visible) return
			this.reducedTimer = setTimeout(() => {
				this.reducedTimer = 0
				this.draw(currentTimeMillis())
				if (this.config?.state === 'RECORDING') this.scheduleReduced()
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
			this.stop()
			this.timeline.dispose()
			this.observer?.disconnect?.()
			this.resizeObserver?.disconnect?.()
			if (this.onVisibilityChange && typeof document !== 'undefined') {
				document.removeEventListener('visibilitychange', this.onVisibilityChange)
			}
			this.observer = null
			this.resizeObserver = null
			this.onVisibilityChange = null
			this.canvas = null
			this.canvasHost = null
			this.context = null
		}
	}
}

const WAVEFORM_HEIGHT = 24
const BAR_WIDTH = 2
const BAR_GAP = 3
const BAR_PITCH = BAR_WIDTH + BAR_GAP
const MINIMUM_BAR_HEIGHT = 2
const MAXIMUM_BAR_HEIGHT = 20
const LEVEL_INTERVAL_MS = 20
const MAXIMUM_QUEUE_LEVELS = 15
const SILENCE_DELAY_MS = 250
const FINALIZING_DURATION_MS = 180
const REDUCED_INTERVAL_MS = 200

function clamp01(value) {
	return Math.max(0, Math.min(1, Number(value) || 0))
}

function currentTimeMillis() {
	return typeof performance !== 'undefined' && performance.now
		? performance.now() : Date.now()
}

function isNativeCanvasCandidate(candidate) {
	return Boolean(
		candidate
		&& typeof candidate.getContext === 'function'
		&& typeof candidate.width === 'number'
		&& typeof candidate.height === 'number')
}

function resolveNativeCanvas(root) {
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
	return { canvasHost: canvasHost || canvas, canvas }
}

function measuredWidth(root, canvasHost, canvas) {
	const candidates = [
		Number(root?.clientWidth),
		Number(root?.getBoundingClientRect?.()?.width),
		Number(canvasHost?.clientWidth),
		Number(canvasHost?.getBoundingClientRect?.()?.width),
		Number(canvas?.clientWidth)
	]
	return Math.max(0, candidates.find(value => Number.isFinite(value) && value > 0) || 0)
}

export function resolveVoiceWaveformCanvasMetrics(width, dpr = 1) {
	const cssWidth = Math.max(0, Math.floor(Number(width) || 0))
	const numericDpr = Number(dpr)
	const resolvedDpr = Number.isFinite(numericDpr)
		? Math.max(1, Math.min(2, numericDpr)) : 1
	return {
		cssWidth,
		cssHeight: WAVEFORM_HEIGHT,
		dpr: resolvedDpr,
		pixelWidth: Math.round(cssWidth * resolvedDpr),
		pixelHeight: Math.round(WAVEFORM_HEIGHT * resolvedDpr),
		visibleBars: Math.max(1, Math.floor(cssWidth / BAR_PITCH))
	}
}

export function createVoiceWaveformRenderState(capacity = 1, sessionEpoch = -1) {
	const normalizedCapacity = Math.max(1, Math.floor(Number(capacity) || 1))
	return {
		sessionEpoch: Number(sessionEpoch),
		packetSequence: -1,
		queue: [],
		history: new Float32Array(normalizedCapacity),
		historyStart: 0,
		historyLength: 0,
		currentLevel: 0,
		lastPacketAt: 0,
		lastConsumeAt: 0
	}
}

export function voiceWaveformHistory(state) {
	const values = []
	for (let index = 0; index < state.historyLength; index += 1) {
		values.push(state.history[(state.historyStart + index) % state.history.length])
	}
	return values
}

function setHistoryCapacity(state, capacity) {
	const normalizedCapacity = Math.max(1, Math.floor(Number(capacity) || 1))
	if (state.history.length === normalizedCapacity) return
	const preserved = voiceWaveformHistory(state).slice(-normalizedCapacity)
	state.history = new Float32Array(normalizedCapacity)
	state.historyStart = 0
	state.historyLength = preserved.length
	state.history.set(preserved)
}

function appendHistory(state, level) {
	const value = clamp01(level)
	if (state.historyLength < state.history.length) {
		state.history[(state.historyStart + state.historyLength) % state.history.length] = value
		state.historyLength += 1
	} else {
		state.history[state.historyStart] = value
		state.historyStart = (state.historyStart + 1) % state.history.length
	}
	state.currentLevel = value
}

export function resetVoiceWaveformRenderState(state, sessionEpoch, capacity = state.history.length) {
	state.sessionEpoch = Number(sessionEpoch)
	state.packetSequence = -1
	state.queue.length = 0
	state.history = new Float32Array(Math.max(1, Math.floor(Number(capacity) || 1)))
	state.historyStart = 0
	state.historyLength = 0
	state.currentLevel = 0
	state.lastPacketAt = 0
	state.lastConsumeAt = 0
}

export function acceptVoiceWaveformPacket(state, packet, sessionEpoch, nowMillis) {
	if (!packet || Number(packet.epoch) !== Number(sessionEpoch)
		|| Number(packet.epoch) !== Number(state.sessionEpoch)) return false
	const sequence = Number(packet.sequence)
	if (!Number.isSafeInteger(sequence) || sequence <= state.packetSequence
		|| !Array.isArray(packet.levels)) return false
	const levels = packet.levels.slice(0, 5).map(clamp01)
	if (levels.length === 0) return false

	const queueWasEmpty = state.queue.length === 0
	state.packetSequence = sequence
	state.queue.push(...levels)
	if (state.queue.length > MAXIMUM_QUEUE_LEVELS) {
		state.queue.splice(0, state.queue.length - MAXIMUM_QUEUE_LEVELS)
	}
	state.lastPacketAt = Number(nowMillis) || 0
	// 音频帧每 100ms 批量到达；空队列恢复时只允许立即消费一个值，避免五根柱子成组跳动。
	if (queueWasEmpty && state.lastConsumeAt
		&& state.lastPacketAt - state.lastConsumeAt > LEVEL_INTERVAL_MS) {
		state.lastConsumeAt = state.lastPacketAt - LEVEL_INTERVAL_MS
	}
	return true
}

function consumeOne(state, nowMillis) {
	if (!state.queue.length) return false
	appendHistory(state, state.queue.shift())
	state.lastConsumeAt = Number(nowMillis) || 0
	return true
}

export function advanceVoiceWaveformState(state, voiceState, nowMillis, reduced = false) {
	if (String(voiceState || '').toUpperCase() !== 'RECORDING') return false
	const now = Number(nowMillis) || 0

	if (reduced) {
		if (state.queue.length) {
			const latest = state.queue[state.queue.length - 1]
			state.queue.length = 0
			appendHistory(state, latest)
			state.lastConsumeAt = now
			return true
		}
	} else if (state.queue.length) {
		if (!state.historyLength) return consumeOne(state, now)
		if (!state.lastConsumeAt) state.lastConsumeAt = now - LEVEL_INTERVAL_MS
		let changed = false
		let iterations = 0
		while (state.queue.length
			&& now - state.lastConsumeAt >= LEVEL_INTERVAL_MS
			&& iterations < MAXIMUM_QUEUE_LEVELS) {
			state.lastConsumeAt += LEVEL_INTERVAL_MS
			appendHistory(state, state.queue.shift())
			changed = true
			iterations += 1
		}
		if (changed) return true
	}

	if (state.lastPacketAt && now - state.lastPacketAt >= SILENCE_DELAY_MS
		&& (!state.lastConsumeAt || now - state.lastConsumeAt >= LEVEL_INTERVAL_MS)) {
		appendHistory(state, state.currentLevel * 0.8)
		state.lastConsumeAt = now
		return true
	}
	return false
}

export function resolveVoiceWaveformFinalizingScale(startedAt, nowMillis) {
	const start = Number(startedAt) || 0
	if (!start) return 1
	return clamp01(1 - ((Number(nowMillis) || 0) - start) / FINALIZING_DURATION_MS)
}

function interpolatedColor(level) {
	const value = clamp01(level)
	if (value <= 0) return 'rgba(174,185,179,0.35)'
	const stops = value < 0.62
		? [[174, 185, 179], [117, 223, 183], value / 0.62]
		: [[117, 223, 183], [55, 211, 154], (value - 0.62) / 0.38]
	const factor = clamp01(stops[2])
	const rgb = stops[0].map((channel, index) =>
		Math.round(channel + (stops[1][index] - channel) * factor))
	return `rgba(${rgb[0]},${rgb[1]},${rgb[2]},${0.45 + value * 0.55})`
}

export function drawVoiceWaveformFrame(context, {
	width,
	height = WAVEFORM_HEIGHT,
	levels = [],
	finalizingScale = 1
}) {
	if (!context) return
	const visibleBars = Math.max(1, Math.floor((Number(width) || 0) / BAR_PITCH))
	const centerY = (Number(height) || WAVEFORM_HEIGHT) / 2
	const source = Array.isArray(levels) ? levels.slice(-visibleBars) : []
	const firstSourceIndex = visibleBars - source.length

	context.lineWidth = BAR_WIDTH
	context.lineCap = 'round'
	for (let index = 0; index < visibleBars; index += 1) {
		const sourceIndex = index - firstSourceIndex
		const rawLevel = sourceIndex >= 0 ? clamp01(source[sourceIndex]) : 0
		const level = clamp01(rawLevel * clamp01(finalizingScale))
		const barHeight = MINIMUM_BAR_HEIGHT
			+ (MAXIMUM_BAR_HEIGHT - MINIMUM_BAR_HEIGHT) * level
		const x = index * BAR_PITCH + BAR_WIDTH / 2
		context.strokeStyle = interpolatedColor(level)
		context.beginPath()
		context.moveTo(x, centerY - barHeight / 2)
		context.lineTo(x, centerY + barHeight / 2)
		context.stroke()
	}
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
			renderState: createVoiceWaveformRenderState(),
			finalizingStartedAt: 0,
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
		this.connectCanvas()
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
			const epochChanged = epoch !== Number(this.renderState.sessionEpoch)
			const stateChanged = this.config?.state !== previous?.state
			const reducedChanged = Boolean(this.config?.reduced) !== Boolean(previous?.reduced)
			if (epochChanged) {
				resetVoiceWaveformRenderState(
					this.renderState,
					epoch,
					this.renderState.history.length)
			}
			if (this.config?.state === 'FINALIZING' && previous?.state !== 'FINALIZING') {
				this.finalizingStartedAt = currentTimeMillis()
			} else if (this.config?.state !== 'FINALIZING') {
				this.finalizingStartedAt = 0
			}
			if (['IDLE', 'ERROR'].includes(this.config?.state)) {
				resetVoiceWaveformRenderState(
					this.renderState,
					epoch,
					this.renderState.history.length)
			}
			acceptVoiceWaveformPacket(
				this.renderState,
				this.config?.packet,
				epoch,
				currentTimeMillis())
			this.connectCanvas()
			if (epochChanged || stateChanged || reducedChanged
				|| (!this.running && !this.reducedTimer)) this.restart()
		},
		connectCanvas() {
			if (!this.root) return
			const { canvasHost, canvas } = resolveNativeCanvas(this.root)
			if (!canvas) return
			this.canvasHost = canvasHost || canvas
			if (canvas === this.canvas && this.context) {
				this.context.__hidpi__ = false
				return
			}
			this.canvas = canvas
			this.context = canvas.getContext?.('2d') || null
			if (this.context) this.context.__hidpi__ = false
			this.dpr = Math.max(1, Math.min(2, Number(globalThis.devicePixelRatio) || 1))

			this.observer?.disconnect?.()
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
		},
		configureCanvas() {
			if (!isNativeCanvasCandidate(this.canvas) || !this.context) return null
			// 先完成所有尺寸读取，再统一写入 Canvas，避免动画帧内读写交错触发同步布局。
			const width = measuredWidth(this.root, this.canvasHost, this.canvas)
			if (!(width > 0)) return null
			const metrics = resolveVoiceWaveformCanvasMetrics(
				width,
				globalThis.devicePixelRatio || this.dpr)
			this.dpr = metrics.dpr
			if (this.canvas.width !== metrics.pixelWidth) this.canvas.width = metrics.pixelWidth
			if (this.canvas.height !== metrics.pixelHeight) this.canvas.height = metrics.pixelHeight
			if (this.canvas.style) {
				this.canvas.style.width = `${metrics.cssWidth}px`
				this.canvas.style.height = `${metrics.cssHeight}px`
				this.canvas.style.display = 'block'
			}
			if (this.canvasHost?.style) {
				this.canvasHost.style.height = `${metrics.cssHeight}px`
				this.canvasHost.style.minHeight = `${metrics.cssHeight}px`
			}
			this.context = this.canvas.getContext?.('2d') || this.context
			if (!this.context) return null
			this.context.__hidpi__ = false
			setHistoryCapacity(this.renderState, metrics.visibleBars)
			return metrics
		},
		draw(nowMillis = currentTimeMillis()) {
			try {
				if (!this.config) return
				if (!isNativeCanvasCandidate(this.canvas) || !this.context
					|| this.canvas?.isConnected === false) this.connectCanvas()
				const metrics = this.configureCanvas()
				if (!metrics || !this.context) return
				advanceVoiceWaveformState(
					this.renderState,
					this.config.state,
					nowMillis,
					Boolean(this.config.reduced))
				const finalizingScale = this.config.state === 'FINALIZING'
					? resolveVoiceWaveformFinalizingScale(this.finalizingStartedAt, nowMillis)
					: 1
				this.context.setTransform(1, 0, 0, 1, 0, 0)
				this.context.clearRect(0, 0, metrics.pixelWidth, metrics.pixelHeight)
				this.context.setTransform(metrics.dpr, 0, 0, metrics.dpr, 0, 0)
				drawVoiceWaveformFrame(this.context, {
					width: metrics.cssWidth,
					height: metrics.cssHeight,
					levels: voiceWaveformHistory(this.renderState),
					finalizingScale
				})
			} catch (_) {
				// Canvas 只是反馈层，绘制失败时保留录音、传输和转写主流程。
				this.stop()
			}
		},
		restart() {
			this.stop()
			if (!this.config || this.hidden || !this.visible) return
			this.draw(currentTimeMillis())
			if (!['RECORDING', 'FINALIZING'].includes(this.config.state)) return
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
				if (this.config?.state === 'FINALIZING'
					&& resolveVoiceWaveformFinalizingScale(
						this.finalizingStartedAt,
						timestamp) === 0) {
					this.stop()
					return
				}
				this.raf = requestAnimationFrame(loop)
			}
			this.raf = requestAnimationFrame(loop)
		},
		scheduleReduced() {
			if (this.reducedTimer || this.hidden || !this.visible) return
			this.reducedTimer = setTimeout(() => {
				this.reducedTimer = 0
				const now = currentTimeMillis()
				this.draw(now)
				if (this.config?.state === 'FINALIZING'
					&& resolveVoiceWaveformFinalizingScale(
						this.finalizingStartedAt,
						now) === 0) return
				if (['RECORDING', 'FINALIZING'].includes(this.config?.state)) {
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
			this.stop()
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

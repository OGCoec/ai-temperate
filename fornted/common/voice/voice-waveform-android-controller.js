import {
	VOICE_WAVEFORM_INTERVAL_MS,
	VOICE_WAVEFORM_MAX_CAPACITY,
	createVoiceWaveformTimeline
} from './voice-waveform-timeline.js'
import {
	presentVoiceWaveformBar,
	resolveVoiceWaveformCapacity
} from './voice-waveform-presentation.js'

function defaultNow() {
	return typeof performance !== 'undefined' && typeof performance.now === 'function'
		? performance.now() : Date.now()
}

function safeTime(now) {
	try {
		const value = Number(now())
		return Number.isFinite(value) ? value : 0
	} catch (_) {
		return 0
	}
}

function safeEpoch(value) {
	try {
		const numeric = Number(value)
		return Number.isFinite(numeric) ? numeric : -1
	} catch (_) {
		return -1
	}
}

export function resolveAndroidVoiceWaveformCapacity(width) {
	return resolveVoiceWaveformCapacity(width)
}

export function presentAndroidVoiceWaveformBar(bar) {
	return presentVoiceWaveformBar(bar)
}

export function createAndroidVoiceWaveformController({
	capacity = VOICE_WAVEFORM_MAX_CAPACITY,
	now = defaultNow,
	schedule = (callback, delay) => setTimeout(callback, delay),
	cancel = timer => clearTimeout(timer),
	onSnapshot = () => {},
	report = phase => {
		if (typeof console === 'undefined') return
		const output = phase === 'ANDROID_WAVEFORM_FAILED' ? console.warn : console.log
		output?.call?.(console, `event=voice_android_waveform phase=${phase}`)
	}
} = {}) {
	const timeline = createVoiceWaveformTimeline({ capacity, now })
	let sessionEpoch = -1
	let nextTickAt = null
	let timer = 0
	let active = false
	let disposed = false
	let reportedPhases = new Set()

	function clearScheduledTick() {
		if (!timer) return
		try { cancel(timer) } catch (_) {}
		timer = 0
	}

	function reportOnce(phase) {
		if (!phase || reportedPhases.has(phase)) return
		reportedPhases.add(phase)
		try { report(phase) } catch (_) {}
	}

	function failOpen() {
		active = false
		nextTickAt = null
		clearScheduledTick()
		timeline.stop()
		reportOnce('ANDROID_WAVEFORM_FAILED')
	}

	function publishSnapshot(nowMillis = safeTime(now)) {
		try {
			onSnapshot(timeline.snapshot(nowMillis))
			return true
		} catch (_) {
			failOpen()
			return false
		}
	}

	function scheduleNext(delay = VOICE_WAVEFORM_INTERVAL_MS) {
		if (!active || disposed || timer) return
		try {
			timer = schedule(tick, Math.max(0, Number(delay) || 0))
		} catch (_) {
			failOpen()
		}
	}

	function tick() {
		timer = 0
		if (!active || disposed) return
		try {
			const currentTime = safeTime(now)
			if (nextTickAt == null) {
				nextTickAt = currentTime + VOICE_WAVEFORM_INTERVAL_MS
				scheduleNext(VOICE_WAVEFORM_INTERVAL_MS)
				return
			}
			if (currentTime < nextTickAt) {
				scheduleNext(nextTickAt - currentTime)
				return
			}

			const advanced = timeline.advance(currentTime)
			// 迟到时只结算当前一根，并从当前时间建立下一周期，不追赶遗漏边界。
			nextTickAt = currentTime + VOICE_WAVEFORM_INTERVAL_MS
			if (!publishSnapshot(currentTime)) return
			if (advanced) reportOnce('ANDROID_WAVEFORM_FIRST_BAR_RENDERED')
			scheduleNext(VOICE_WAVEFORM_INTERVAL_MS)
		} catch (_) {
			failOpen()
		}
	}

	return {
		setCapacity(value) {
			try {
				const resolved = timeline.setCapacity(value)
				if (active) publishSnapshot()
				return resolved
			} catch (_) {
				failOpen()
				return timeline.snapshot().capacity
			}
		},

		start(epoch) {
			if (disposed) return false
			try {
				clearScheduledTick()
				reportedPhases = new Set()
				sessionEpoch = safeEpoch(epoch)
				active = timeline.start(sessionEpoch) === true
				if (!active) {
					failOpen()
					return false
				}
				const currentTime = safeTime(now)
				nextTickAt = currentTime + VOICE_WAVEFORM_INTERVAL_MS
				if (!publishSnapshot(currentTime)) return false
				reportOnce('ANDROID_WAVEFORM_STARTED')
				scheduleNext(VOICE_WAVEFORM_INTERVAL_MS)
				return active
			} catch (_) {
				failOpen()
				return false
			}
		},

		accept(packet) {
			if (!active || disposed) return false
			try {
				const accepted = timeline.accept(packet)
				if (accepted) reportOnce('ANDROID_WAVEFORM_PACKET_ACCEPTED')
				return accepted
			} catch (_) {
				failOpen()
				return false
			}
		},

		stop() {
			const shouldReport = active || Boolean(timer)
			active = false
			nextTickAt = null
			clearScheduledTick()
			timeline.stop()
			publishSnapshot()
			if (shouldReport) reportOnce('ANDROID_WAVEFORM_STOPPED')
		},

		reset(epoch) {
			try {
				active = false
				nextTickAt = null
				clearScheduledTick()
				sessionEpoch = safeEpoch(epoch)
				timeline.reset(sessionEpoch)
				publishSnapshot()
			} catch (_) {
				failOpen()
			}
		},

		dispose() {
			if (disposed) return
			try {
				active = false
				nextTickAt = null
				clearScheduledTick()
				timeline.dispose()
				publishSnapshot()
				disposed = true
			} catch (_) {
				disposed = true
				failOpen()
			}
		},

		snapshot() {
			return timeline.snapshot(safeTime(now))
		}
	}
}

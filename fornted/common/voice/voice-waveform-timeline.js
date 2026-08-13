export const VOICE_WAVEFORM_INTERVAL_MS = 300
export const VOICE_WAVEFORM_QUEUE_LIMIT = 15
export const VOICE_WAVEFORM_MAX_CAPACITY = 192

function clamp01(value) {
	try {
		return Math.max(0, Math.min(1, Number(value) || 0))
	} catch (_) {
		return 0
	}
}

function normalizeCapacity(value) {
	try {
		return Math.max(1, Math.min(
			VOICE_WAVEFORM_MAX_CAPACITY,
			Math.floor(Number(value) || 1)))
	} catch (_) {
		return 1
	}
}

function normalizeTime(value) {
	try {
		const numeric = Number(value)
		return Number.isFinite(numeric) ? numeric : 0
	} catch (_) {
		return 0
	}
}

function normalizeEpoch(value) {
	try {
		const numeric = Number(value)
		return Number.isFinite(numeric) ? numeric : -1
	} catch (_) {
		return -1
	}
}

function defaultNow() {
	return typeof performance !== 'undefined' && typeof performance.now === 'function'
		? performance.now() : Date.now()
}

export function aggregateVoiceWaveformLevels(levels) {
	if (!Array.isArray(levels) || levels.length === 0) return 0
	try {
		let squareSum = 0
		for (const level of levels) {
			const value = clamp01(level)
			squareSum += value * value
		}
		return clamp01(Math.sqrt(squareSum / levels.length))
	} catch (_) {
		return 0
	}
}

export function createVoiceWaveformTimeline({
	capacity = 1,
	now = defaultNow
} = {}) {
	let visibleCapacity = normalizeCapacity(capacity)
	let epoch = -1
	let cycle = 0
	let packetSequence = -1
	let queuedLevels = []
	let settledBars = []
	let pendingBar = null
	let phaseStartedAt = null
	let nextAdvanceAt = null
	let nextBarId = 1
	let active = false
	let disposed = false

	function createBaselineBar() {
		return {
			id: nextBarId++,
			level: 0,
			recorded: false
		}
	}

	function clear(epochValue) {
		epoch = normalizeEpoch(epochValue)
		cycle = 0
		packetSequence = -1
		queuedLevels = []
		settledBars = []
		pendingBar = null
		phaseStartedAt = null
		nextAdvanceAt = null
		active = false
	}

	function safeNow(value) {
		if (value != null) return normalizeTime(value)
		try {
			return normalizeTime(now())
		} catch (_) {
			return 0
		}
	}

	function copyBar(bar) {
		return {
			id: bar.id,
			level: bar.level,
			recorded: bar.recorded
		}
	}

	return {
		start(epochValue) {
			if (disposed) return false
			try {
				clear(epochValue)
				settledBars = Array.from(
					{ length: visibleCapacity },
					() => createBaselineBar())
				pendingBar = createBaselineBar()
				const currentTime = safeNow()
				phaseStartedAt = currentTime
				nextAdvanceAt = currentTime + VOICE_WAVEFORM_INTERVAL_MS
				active = true
				return true
			} catch (_) {
				clear(epochValue)
				return false
			}
		},

		accept(packet) {
			try {
				if (!active || disposed || !packet
					|| normalizeEpoch(packet.epoch) !== epoch
					|| !Array.isArray(packet.levels)) return false
				const sequence = Number(packet.sequence)
				if (!Number.isSafeInteger(sequence) || sequence <= packetSequence) return false
				const levels = packet.levels.slice(0, 5).map(clamp01)
				if (levels.length === 0) return false
				packetSequence = sequence
				queuedLevels.push(...levels)
				if (queuedLevels.length > VOICE_WAVEFORM_QUEUE_LIMIT) {
					queuedLevels.splice(
						0,
						queuedLevels.length - VOICE_WAVEFORM_QUEUE_LIMIT)
				}
				return true
			} catch (_) {
				return false
			}
		},

		advance(nowMillis) {
			if (!active || disposed || !pendingBar) return false
			try {
				const currentTime = safeNow(nowMillis)
				if (nextAdvanceAt == null) {
					phaseStartedAt = currentTime
					nextAdvanceAt = currentTime + VOICE_WAVEFORM_INTERVAL_MS
					return false
				}
				if (currentTime < nextAdvanceAt) return false

				const committedBar = {
					id: pendingBar.id,
					level: aggregateVoiceWaveformLevels(queuedLevels),
					recorded: true
				}
				settledBars = [
					...settledBars.slice(1),
					committedBar
				]
				pendingBar = createBaselineBar()
				queuedLevels = []
				cycle += 1
				phaseStartedAt = currentTime
				// 卡顿后从当前时刻重建边界，避免补画多根已经失去实时意义的历史柱。
				nextAdvanceAt = currentTime + VOICE_WAVEFORM_INTERVAL_MS
				return true
			} catch (_) {
				active = false
				queuedLevels = []
				return false
			}
		},

		setCapacity(value) {
			try {
				const nextCapacity = normalizeCapacity(value)
				if (nextCapacity === visibleCapacity) return visibleCapacity
				if (active) {
					if (settledBars.length > nextCapacity) {
						settledBars = settledBars.slice(-nextCapacity)
					} else if (settledBars.length < nextCapacity) {
						const padding = Array.from(
							{ length: nextCapacity - settledBars.length },
							() => createBaselineBar())
						settledBars = [...padding, ...settledBars]
					}
				}
				visibleCapacity = nextCapacity
				return visibleCapacity
			} catch (_) {
				return visibleCapacity
			}
		},

		snapshot(nowMillis) {
			try {
				let progress = 0
				if (active && phaseStartedAt != null) {
					progress = clamp01(
						(safeNow(nowMillis) - phaseStartedAt)
						/ VOICE_WAVEFORM_INTERVAL_MS)
				}
				const settled = settledBars.map(copyBar)
				return {
					epoch,
					cycle,
					capacity: visibleCapacity,
					settledBars: settled,
					movingBars: pendingBar
						? [...settled, copyBar(pendingBar)] : [],
					progress
				}
			} catch (_) {
				return {
					epoch,
					cycle,
					capacity: visibleCapacity,
					settledBars: [],
					movingBars: [],
					progress: 0
				}
			}
		},

		reset(epochValue) {
			try {
				clear(epochValue)
				return true
			} catch (_) {
				return false
			}
		},

		stop() {
			try {
				clear(epoch)
				return true
			} catch (_) {
				return false
			}
		},

		dispose() {
			if (disposed) return true
			try {
				clear(epoch)
				disposed = true
				return true
			} catch (_) {
				disposed = true
				return false
			}
		}
	}
}

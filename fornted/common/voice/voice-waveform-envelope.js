const SAMPLES_PER_WINDOW = 320
const MINIMUM_DECIBELS = -52
const MAXIMUM_DECIBELS = -12
const RMS_WEIGHT = 0.8
const PEAK_WEIGHT = 0.2
const VISUAL_EXPONENT = 0.7
const ATTACK = 0.65
const RELEASE = 0.20
const PCM16_SCALE = 32768

function clamp01(value) {
	return Math.max(0, Math.min(1, Number(value) || 0))
}

function targetLevel(rms, peak) {
	const mixed = RMS_WEIGHT * rms + PEAK_WEIGHT * peak
	if (!(mixed > 0)) return 0
	const decibels = 20 * Math.log10(mixed)
	const normalized = clamp01(
		(decibels - MINIMUM_DECIBELS) /
		(MAXIMUM_DECIBELS - MINIMUM_DECIBELS))
	return Math.pow(normalized, VISUAL_EXPONENT)
}

/**
 * 创建跨 Android 与 H5 共用的 PCM16 音量分析器，只提取有界的视觉包络，不保存或修改原始音频。
 */
export function createVoiceWaveformAnalyzer() {
	let smoothedLevel = 0

	return Object.freeze({
		analyze(frame) {
			if (!(frame instanceof ArrayBuffer)
				|| frame.byteLength === 0
				|| frame.byteLength % 2 !== 0) return []

			try {
				const view = new DataView(frame)
				const sampleCount = frame.byteLength / 2
				const levels = []

				for (let windowStart = 0; windowStart < sampleCount;
					windowStart += SAMPLES_PER_WINDOW) {
					const windowEnd = Math.min(sampleCount, windowStart + SAMPLES_PER_WINDOW)
					let squareSum = 0
					let peak = 0

					for (let index = windowStart; index < windowEnd; index += 1) {
						const sample = view.getInt16(index * 2, true) / PCM16_SCALE
						const magnitude = Math.abs(sample)
						squareSum += sample * sample
						if (magnitude > peak) peak = magnitude
					}

					const rms = Math.sqrt(squareSum / (windowEnd - windowStart))
					const target = targetLevel(rms, peak)
					const smoothing = target > smoothedLevel ? ATTACK : RELEASE
					smoothedLevel = clamp01(
						smoothedLevel + (target - smoothedLevel) * smoothing)
					levels.push(smoothedLevel)
				}

				return levels
			} catch (_) {
				// 可视化必须 Fail Open，任何解析异常都不能中断语音发送主路径。
				return []
			}
		},
		reset() {
			smoothedLevel = 0
		}
	})
}

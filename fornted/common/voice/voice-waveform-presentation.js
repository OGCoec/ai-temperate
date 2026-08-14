import { VOICE_WAVEFORM_MAX_CAPACITY } from './voice-waveform-timeline.js'

export const VOICE_WAVEFORM_HEIGHT = 24
export const VOICE_WAVEFORM_BAR_WIDTH = 2.5
export const VOICE_WAVEFORM_BAR_GAP = 3
export const VOICE_WAVEFORM_BAR_PITCH = 5.5
export const VOICE_WAVEFORM_MINIMUM_HEIGHT = 2
export const VOICE_WAVEFORM_MAXIMUM_HEIGHT = 20
export const VOICE_WAVEFORM_BASELINE_COLOR = 'rgba(174,185,179,0.24)'

function clamp01(value) {
	try {
		return Math.max(0, Math.min(1, Number(value) || 0))
	} catch (_) {
		return 0
	}
}

export function resolveVoiceWaveformCapacity(
	width,
	maxCapacity = VOICE_WAVEFORM_MAX_CAPACITY
) {
	try {
		const limit = Math.max(1, Math.floor(Number(maxCapacity)
			|| VOICE_WAVEFORM_MAX_CAPACITY))
		return Math.max(1, Math.min(
			limit,
			Math.floor(Math.max(0, Number(width) || 0) / VOICE_WAVEFORM_BAR_PITCH)))
	} catch (_) {
		return 1
	}
}

export function presentVoiceWaveformBar(barOrLevel) {
	const source = typeof barOrLevel === 'object' && barOrLevel !== null
		? barOrLevel : { level: barOrLevel }
	const level = clamp01(source.level)
	const alpha = Number((0.55 + level * 0.33).toFixed(3))
	return {
		...(source.id == null ? {} : { id: source.id }),
		level,
		recorded: Boolean(source.recorded),
		height: VOICE_WAVEFORM_MINIMUM_HEIGHT
			+ (VOICE_WAVEFORM_MAXIMUM_HEIGHT - VOICE_WAVEFORM_MINIMUM_HEIGHT) * level,
		color: level > 0
			? `rgba(205,211,208,${alpha})`
			: VOICE_WAVEFORM_BASELINE_COLOR
	}
}

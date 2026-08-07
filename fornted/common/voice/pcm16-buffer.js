export const VOICE_SAMPLE_RATE = 16000
export const VOICE_CHANNELS = 1
export const VOICE_FRAME_DURATION_MS = 100
export const VOICE_SAMPLES_PER_FRAME = 1600
export const VOICE_BYTES_PER_FRAME = 3200

function finiteSample(value) {
	const number = Number(value)
	if (!Number.isFinite(number)) return 0
	return Math.max(-1, Math.min(1, number))
}

export function float32ToPcm16(samples) {
	const input = samples instanceof Float32Array ? samples : Float32Array.from(samples || [])
	const output = new ArrayBuffer(input.length * 2)
	const view = new DataView(output)
	for (let index = 0; index < input.length; index += 1) {
		const sample = finiteSample(input[index])
		const value = sample < 0
			? Math.round(sample * 0x8000)
			: Math.round(sample * 0x7fff)
		view.setInt16(index * 2, value, true)
	}
	return output
}

export function resampleLinearMono(samples, sourceRate, targetRate = VOICE_SAMPLE_RATE) {
	const input = samples instanceof Float32Array ? samples : Float32Array.from(samples || [])
	const source = Number(sourceRate)
	const target = Number(targetRate)
	if (!Number.isFinite(source) || source <= 0 || !Number.isFinite(target) || target <= 0) {
		throw new TypeError('采样率必须是正数。')
	}
	if (!input.length || source === target) return input.slice()
	const outputLength = Math.max(1, Math.floor(input.length * target / source))
	const output = new Float32Array(outputLength)
	const ratio = source / target
	for (let index = 0; index < outputLength; index += 1) {
		const position = Math.min(input.length - 1, index * ratio)
		const left = Math.floor(position)
		const right = Math.min(input.length - 1, left + 1)
		const weight = position - left
		output[index] = input[left] + (input[right] - input[left]) * weight
	}
	return output
}

export class Pcm16FrameBuffer {
	constructor(samplesPerFrame = VOICE_SAMPLES_PER_FRAME) {
		if (!Number.isInteger(samplesPerFrame) || samplesPerFrame <= 0) {
			throw new TypeError('每帧采样数必须是正整数。')
		}
		this.samplesPerFrame = samplesPerFrame
		this.pending = []
	}

	push(samples) {
		for (const sample of samples || []) this.pending.push(finiteSample(sample))
		const frames = []
		while (this.pending.length >= this.samplesPerFrame) {
			frames.push(float32ToPcm16(this.pending.splice(0, this.samplesPerFrame)))
		}
		return frames
	}

	flush() {
		if (!this.pending.length) return null
		const frame = float32ToPcm16(this.pending)
		this.pending = []
		return frame
	}
}

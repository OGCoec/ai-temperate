class AitPcm16Processor extends AudioWorkletProcessor {
	constructor() {
		super()
		this.targetRate = 16000
		this.samplesPerFrame = 1600
		this.pending = []
		this.previous = null
		this.position = 0
		this.port.onmessage = event => {
			if (event.data?.type !== 'flush') return
			this.emitFrame(true)
			this.port.postMessage({ type: 'flushed' })
		}
	}

	process(inputs) {
		const channels = inputs[0]
		if (!channels?.length || !channels[0]?.length) return true
		const mono = new Float32Array(channels[0].length)
		for (let index = 0; index < mono.length; index += 1) {
			let sum = 0
			for (const channel of channels) sum += Number(channel[index] || 0)
			mono[index] = sum / channels.length
		}
		this.resample(mono)
		this.emitFrame(false)
		return true
	}

	resample(input) {
		if (sampleRate === this.targetRate) {
			for (const value of input) this.pending.push(value)
			return
		}
		const combined = new Float32Array(input.length + (this.previous == null ? 0 : 1))
		let offset = 0
		if (this.previous != null) combined[offset++] = this.previous
		combined.set(input, offset)
		const ratio = sampleRate / this.targetRate
		while (this.position < combined.length - 1) {
			const left = Math.floor(this.position)
			const weight = this.position - left
			this.pending.push(combined[left] + (combined[left + 1] - combined[left]) * weight)
			this.position += ratio
		}
		this.position -= combined.length - 1
		this.previous = combined[combined.length - 1]
	}

	emitFrame(flush) {
		while (this.pending.length >= this.samplesPerFrame || (flush && this.pending.length)) {
			const count = Math.min(this.samplesPerFrame, this.pending.length)
			const buffer = new ArrayBuffer(count * 2)
			const view = new DataView(buffer)
			for (let index = 0; index < count; index += 1) {
				const sample = Math.max(-1, Math.min(1, Number(this.pending[index]) || 0))
				view.setInt16(index * 2, sample < 0
					? Math.round(sample * 0x8000)
					: Math.round(sample * 0x7fff), true)
			}
			this.pending.splice(0, count)
			this.port.postMessage({ type: 'frame', data: buffer }, [buffer])
		}
	}
}

registerProcessor('ait-pcm16-processor', AitPcm16Processor)

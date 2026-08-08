function recorderError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

export class H5VoiceRecorder {
	constructor() {
		this.stream = null
		this.context = null
		this.source = null
		this.node = null
		this.silentGain = null
		this.onFrame = null
		this.flushedResolve = null
		this.permissionGranted = false
	}

	async requestPermission() {
		if (!globalThis.navigator?.mediaDevices?.getUserMedia) {
			throw recorderError('VOICE_RECORDER_UNSUPPORTED', '当前浏览器不支持麦克风实时录音。')
		}
		if (!globalThis.isSecureContext) {
			throw recorderError('VOICE_RECORDER_UNSUPPORTED', '麦克风需要在 HTTPS 或 localhost 安全页面中使用。')
		}
		try {
			const permissionStream = await this._openStream()
			for (const track of permissionStream.getTracks?.() || []) track.stop()
			this.permissionGranted = true
		} catch (_) {
			throw recorderError('VOICE_PERMISSION_DENIED', '没有获得麦克风权限。')
		}
	}

	async start(onFrame) {
		if (!this.permissionGranted) throw recorderError('VOICE_PERMISSION_DENIED', '麦克风权限尚未获得。')
		const AudioContextClass = globalThis.AudioContext || globalThis.webkitAudioContext
		if (!AudioContextClass || !globalThis.AudioWorkletNode) {
			throw recorderError('VOICE_RECORDER_UNSUPPORTED', '当前浏览器不支持 AudioWorklet。')
		}
		try {
			this.stream = await this._openStream()
		} catch (_) {
			throw recorderError('VOICE_PERMISSION_DENIED', '无法重新打开麦克风。')
		}
		this.onFrame = onFrame
		this.context = new AudioContextClass({ latencyHint: 'interactive' })
		await this.context.audioWorklet.addModule('/static/voice/pcm16-worklet.js')
		this.source = this.context.createMediaStreamSource(this.stream)
		this.node = new AudioWorkletNode(this.context, 'ait-pcm16-processor', {
			numberOfInputs: 1,
			numberOfOutputs: 1,
			outputChannelCount: [1]
		})
		this.silentGain = this.context.createGain()
		this.silentGain.gain.value = 0
		this.node.port.onmessage = event => {
			if (event.data?.type === 'frame' && event.data.data instanceof ArrayBuffer) {
				this.onFrame?.(event.data.data)
			} else if (event.data?.type === 'flushed') {
				this.flushedResolve?.()
				this.flushedResolve = null
			}
		}
		this.source.connect(this.node)
		this.node.connect(this.silentGain)
		this.silentGain.connect(this.context.destination)
		if (this.context.state === 'suspended') await this.context.resume()
	}

	_openStream() {
		return globalThis.navigator.mediaDevices.getUserMedia({
			audio: {
				channelCount: 1,
				echoCancellation: true,
				noiseSuppression: true,
				autoGainControl: true
			},
			video: false
		})
	}

	async stop() {
		if (this.node) {
			await Promise.race([
				new Promise(resolve => {
					this.flushedResolve = resolve
					this.node.port.postMessage({ type: 'flush' })
				}),
				new Promise(resolve => setTimeout(resolve, 150))
			])
		}
		await this.destroy()
	}

	async destroy() {
		this.onFrame = null
		this.flushedResolve = null
		try { this.source?.disconnect?.() } catch (_) {}
		try { this.node?.disconnect?.() } catch (_) {}
		try { this.silentGain?.disconnect?.() } catch (_) {}
		for (const track of this.stream?.getTracks?.() || []) track.stop()
		try { await this.context?.close?.() } catch (_) {}
		this.stream = null
		this.context = null
		this.source = null
		this.node = null
		this.silentGain = null
	}
}

export function createH5VoiceRecorder() {
	return new H5VoiceRecorder()
}

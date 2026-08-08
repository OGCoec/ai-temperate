import { createH5VoiceRecorder } from './voice-recorder-h5.js'

// #ifdef APP-PLUS
import {
	requestRecordPermission,
	startRecording
} from '@/uni_modules/ait-voice-recorder'
// #endif

function recorderError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

class AndroidVoiceRecorder {
	constructor() {
		this.nativeSession = null
		this.permissionGranted = false
		this.stoppedPromise = null
		this.stoppedResolve = null
	}

	requestPermission() {
		// #ifdef APP-PLUS
		return new Promise((resolve, reject) => {
			requestRecordPermission({
				onGranted: () => {
					this.permissionGranted = true
					resolve()
				},
				onDenied: permanent => reject(recorderError(
					'VOICE_PERMISSION_DENIED',
					permanent ? '麦克风权限已被永久拒绝，请到系统设置中开启。' : '没有获得麦克风权限。'))
			})
		})
		// #endif
		// #ifndef APP-PLUS
		return Promise.reject(recorderError('VOICE_RECORDER_UNSUPPORTED', '当前平台不支持 Android 录音器。'))
		// #endif
	}

	start(onFrame, onRuntimeError = () => {}) {
		if (!this.permissionGranted) return Promise.reject(recorderError(
			'VOICE_PERMISSION_DENIED', '麦克风权限尚未获得。'))
		// #ifdef APP-PLUS
		return new Promise((resolve, reject) => {
			try {
				this.stoppedPromise = new Promise(stoppedResolve => {
					this.stoppedResolve = stoppedResolve
				})
				this.nativeSession = startRecording({
					onFrame,
					onStarted: resolve,
					onStopped: () => {
						this.stoppedResolve?.()
						this.stoppedResolve = null
					},
					onError: failure => {
						const error = recorderError(
							failure?.code || 'VOICE_RECORDER_FAILED',
							failure?.message || 'Android 录音失败。')
						if (this.nativeSession) onRuntimeError(error)
						else reject(error)
					}
				})
			} catch (_) {
				reject(recorderError('VOICE_RECORDER_FAILED', 'Android 录音启动失败。'))
			}
		})
		// #endif
		// #ifndef APP-PLUS
		return Promise.reject(recorderError('VOICE_RECORDER_UNSUPPORTED', '当前平台不支持 Android 录音器。'))
		// #endif
	}

	async stop() {
		this.nativeSession?.stop?.()
		if (this.stoppedPromise) {
			await Promise.race([
				this.stoppedPromise,
				new Promise(resolve => setTimeout(resolve, 250))
			])
		}
		this.nativeSession = null
		this.stoppedPromise = null
		this.stoppedResolve = null
	}

	async destroy() {
		await this.stop()
	}
}

export function createVoiceRecorder() {
	// #ifdef APP-PLUS
	return new AndroidVoiceRecorder()
	// #endif
	// #ifndef APP-PLUS
	return createH5VoiceRecorder()
	// #endif
}

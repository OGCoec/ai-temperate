import { createH5VoiceRecorder } from './voice-recorder-h5.js'

// #ifdef APP-PLUS
import {
	requestRecordPermission,
	startRecording,
	renewRecordingLease,
	stopRecording
} from '@/uni_modules/ait-voice-recorder'
// #endif

function recorderError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

const MAX_ANDROID_PCM16_FRAME_BYTES = 3200
const ANDROID_RECORDING_LEASE_RENEWAL_MS = 500
const STANDARD_BASE64_PATTERN = /^[A-Za-z0-9+/]*={0,2}$/

function jsExceptionType(error) {
	const name = String(error?.name || '')
	return ['TypeError', 'ReferenceError', 'RangeError', 'Error'].includes(name)
		? name
		: 'UNKNOWN'
}

function diagnosticInteger(value) {
	const number = Number(value)
	return Number.isSafeInteger(number) ? number : -1
}

function diagnosticCode(value) {
	const code = String(value || '')
	return /^[A-Z][A-Z0-9_]{0,63}$/.test(code) ? code : 'UNKNOWN'
}

function isValidNativeRecordingId(value) {
	return Number.isSafeInteger(value) && value > 0
}

function androidPcmBridgeError(reason, declaredByteLength, decodedByteLength = -1) {
	const error = recorderError(
		'VOICE_AUDIO_BRIDGE_INVALID',
		'Android 音频通道转换失败，请重新启动录音。')
	error.frameReason = reason
	error.declaredByteLength = declaredByteLength
	error.decodedByteLength = decodedByteLength
	return error
}

function logAndroidPcmBridgeError(error) {
	if (error?.code !== 'VOICE_AUDIO_BRIDGE_INVALID' || !error?.frameReason) return
	console.warn(
		`event=voice_android_pcm_bridge phase=REJECTED reason=${error.frameReason}`
		+ ` declaredBytes=${diagnosticInteger(error.declaredByteLength)}`
		+ ` decodedBytes=${diagnosticInteger(error.decodedByteLength)}`)
}

function decodeAndroidPcm16Frame(payloadBase64, declaredByteLength) {
	if (typeof payloadBase64 !== 'string' || payloadBase64.length === 0) {
		throw androidPcmBridgeError('FRAME_PAYLOAD_INVALID', Number(declaredByteLength), -1)
	}
	if (!Number.isSafeInteger(declaredByteLength) || declaredByteLength < 0) {
		throw androidPcmBridgeError('FRAME_DECLARED_LENGTH_INVALID', declaredByteLength, -1)
	}
	if (declaredByteLength === 0) {
		throw androidPcmBridgeError('FRAME_EMPTY', declaredByteLength, 0)
	}
	if (declaredByteLength > MAX_ANDROID_PCM16_FRAME_BYTES) {
		throw androidPcmBridgeError('FRAME_TOO_LARGE', declaredByteLength, -1)
	}
	if (declaredByteLength % 2 !== 0) {
		throw androidPcmBridgeError('FRAME_ODD_LENGTH', declaredByteLength, -1)
	}

	const expectedBase64Characters = 4 * Math.ceil(declaredByteLength / 3)
	if (payloadBase64.length !== expectedBase64Characters
		|| payloadBase64.length % 4 !== 0
		|| !STANDARD_BASE64_PATTERN.test(payloadBase64)) {
		throw androidPcmBridgeError('FRAME_BASE64_SHAPE_INVALID', declaredByteLength, -1)
	}

	let decoded
	try {
		// App-Plus 对原生 ArrayBuffer 的对象桥接不稳定，因此只跨桥传递 Base64，再由 JS Service 创建标准 ArrayBuffer。
		decoded = uni.base64ToArrayBuffer(payloadBase64)
	} catch (_) {
		throw androidPcmBridgeError('FRAME_DECODE_FAILED', declaredByteLength, -1)
	}

	const decodedByteLength = Number(decoded?.byteLength)
	if (!Number.isSafeInteger(decodedByteLength) || decodedByteLength < 0) {
		throw androidPcmBridgeError('FRAME_DECODE_FAILED', declaredByteLength, -1)
	}
	if (decodedByteLength === 0) {
		throw androidPcmBridgeError('FRAME_EMPTY', declaredByteLength, decodedByteLength)
	}
	if (decodedByteLength > MAX_ANDROID_PCM16_FRAME_BYTES) {
		throw androidPcmBridgeError('FRAME_TOO_LARGE', declaredByteLength, decodedByteLength)
	}
	if (decodedByteLength % 2 !== 0) {
		throw androidPcmBridgeError('FRAME_ODD_LENGTH', declaredByteLength, decodedByteLength)
	}
	if (decodedByteLength !== declaredByteLength) {
		throw androidPcmBridgeError('FRAME_LENGTH_MISMATCH', declaredByteLength, decodedByteLength)
	}
	return decoded
}

class AndroidVoiceRecorder {
	constructor() {
		this.nativeRecordingId = null
		this.permissionGranted = false
		this.stoppedPromise = null
		this.stoppedResolve = null
		this.leaseTimer = null
		this.recordingEpoch = 0
		this.firstDecodedFrameLogged = false
		this.diagnosticRunId = ''
	}

	_clearLeaseTimer() {
		if (this.leaseTimer != null) clearInterval(this.leaseTimer)
		this.leaseTimer = null
	}

	_invokeNativeStop(recordingId, diagnosticRunId) {
		if (!isValidNativeRecordingId(recordingId)) return false
		console.log(
			`event=voice_android_bridge phase=STOP_INVOKE_ATTEMPT diagnosticRunId=${diagnosticRunId}`
			+ ` recordingId=${recordingId}`)
		try {
			const controlMatched = stopRecording(recordingId) === true
			console.log(
				`event=voice_android_bridge phase=STOP_INVOKE_RETURNED diagnosticRunId=${diagnosticRunId}`
				+ ` recordingId=${recordingId} controlMatched=${controlMatched}`)
			return controlMatched
		} catch (error) {
			console.warn(
				`event=voice_android_bridge phase=STOP_INVOKE_FAILED diagnosticRunId=${diagnosticRunId}`
				+ ` recordingId=${recordingId} exceptionType=${jsExceptionType(error)}`)
			return false
		}
	}

	_startLeaseRenewal(recordingId, recordingEpoch, diagnosticRunId, reportFailure) {
		this._clearLeaseTimer()
		let renewalCount = 0
		const renew = () => {
			if (this.nativeRecordingId !== recordingId
				|| this.recordingEpoch !== recordingEpoch) return false
			renewalCount += 1
			if (renewalCount === 1) {
				console.log(
					`event=voice_android_bridge phase=LEASE_RENEW_ATTEMPT diagnosticRunId=${diagnosticRunId}`
					+ ` recordingId=${recordingId} renewalCount=${renewalCount}`)
			}
			try {
				const renewed = renewRecordingLease(recordingId) === true
				if (!renewed) {
					console.warn(
						`event=voice_android_bridge phase=LEASE_RENEW_REJECTED diagnosticRunId=${diagnosticRunId}`
						+ ` recordingId=${recordingId} renewalCount=${renewalCount}`)
					reportFailure(recorderError(
						'VOICE_AUDIO_BRIDGE_INVALID',
						'Android 音频通道续约失败，请重新启动录音。'), 'LEASE_RENEW')
					return false
				}
				if (renewalCount === 1 || renewalCount % 10 === 0) {
					console.log(
						`event=voice_android_bridge phase=LEASE_RENEW_SUCCEEDED diagnosticRunId=${diagnosticRunId}`
						+ ` recordingId=${recordingId} renewalCount=${renewalCount}`)
				}
				return true
			} catch (error) {
				console.warn(
					`event=voice_android_bridge phase=LEASE_RENEW_FAILED diagnosticRunId=${diagnosticRunId}`
					+ ` recordingId=${recordingId} exceptionType=${jsExceptionType(error)}`
					+ ` renewalCount=${renewalCount}`)
				reportFailure(recorderError(
					'VOICE_AUDIO_BRIDGE_INVALID',
					'Android 音频通道续约失败，请重新启动录音。'), 'LEASE_RENEW')
				return false
			}
		}
		console.log(
			`event=voice_android_bridge phase=LEASE_TIMER_STARTED diagnosticRunId=${diagnosticRunId}`
			+ ` intervalMs=${ANDROID_RECORDING_LEASE_RENEWAL_MS}`)
		if (renew()) {
			this.leaseTimer = setInterval(renew, ANDROID_RECORDING_LEASE_RENEWAL_MS)
		}
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
		this.recordingEpoch += 1
		const recordingEpoch = this.recordingEpoch
		const diagnosticRunId = `v${Date.now().toString(36)}-${recordingEpoch}`
		this._clearLeaseTimer()
		const previousRecordingId = this.nativeRecordingId
		this.nativeRecordingId = null
		this._invokeNativeStop(previousRecordingId, this.diagnosticRunId || 'ABSENT')
		this.firstDecodedFrameLogged = false
		this.diagnosticRunId = diagnosticRunId
		console.log(
			`event=voice_android_bridge phase=START_CALL diagnosticRunId=${diagnosticRunId}`
			+ ` recordingEpoch=${recordingEpoch}`)
		return new Promise((resolve, reject) => {
			let started = false
			let nativeStarted = false
			let startCallReturned = false
			let startSettled = false
			let runtimeFailureReported = false
			let framesReceived = 0
			let framesDecoded = 0
			const ignoredReasonsLogged = new Set()
			let recordingId = null
			let stopRequested = false
			let resolveStoppedSession = () => {}
			const sessionStoppedPromise = new Promise(stoppedResolve => {
				resolveStoppedSession = stoppedResolve
			})
			const stopNative = () => {
				this._clearLeaseTimer()
				if (!isValidNativeRecordingId(recordingId)) {
					stopRequested = true
					return
				}
				if (this.nativeRecordingId === recordingId) this.nativeRecordingId = null
				this._invokeNativeStop(recordingId, diagnosticRunId)
			}
			const reportFailure = (error, failureSource = 'UNKNOWN') => {
				if (runtimeFailureReported) return
				runtimeFailureReported = true
				console.warn(
					`event=voice_android_bridge phase=FAILURE_REPORTED diagnosticRunId=${diagnosticRunId}`
					+ ` failureSource=${failureSource} errorCode=${diagnosticCode(error?.code)}`
					+ ` started=${started} startSettled=${startSettled}`)
				logAndroidPcmBridgeError(error)
				stopNative()
				if (!started && !startSettled) {
					startSettled = true
					reject(error)
					return
				}
				onRuntimeError(error)
			}
			const completeStartIfReady = () => {
				if (startSettled || runtimeFailureReported || !nativeStarted
					|| !startCallReturned || !isValidNativeRecordingId(recordingId)
					|| this.recordingEpoch !== recordingEpoch) return
				started = true
				startSettled = true
				this._startLeaseRenewal(
					recordingId,
					recordingEpoch,
					diagnosticRunId,
					reportFailure)
				resolve()
			}
			try {
				this.stoppedPromise = sessionStoppedPromise
				this.stoppedResolve = resolveStoppedSession
				recordingId = startRecording({
					diagnosticRunId,
					onFrame: (payloadBase64, declaredByteLength, frameSequence) => {
						framesReceived += 1
						const epochMatched = this.recordingEpoch === recordingEpoch
						if (framesReceived === 1 || runtimeFailureReported || !epochMatched) {
							console.log(
								`event=voice_android_bridge phase=FRAME_CALLBACK_ENTERED diagnosticRunId=${diagnosticRunId}`
								+ ` frameSequence=${diagnosticInteger(frameSequence)}`
								+ ` payloadType=${typeof payloadBase64}`
								+ ` base64Characters=${typeof payloadBase64 === 'string' ? payloadBase64.length : -1}`
								+ ` declaredBytes=${Number(declaredByteLength)}`
								+ ` runtimeFailureReported=${runtimeFailureReported} epochMatched=${epochMatched}`)
						}
						if (runtimeFailureReported || !epochMatched) {
							const reason = runtimeFailureReported ? 'RUNTIME_FAILURE' : 'EPOCH_MISMATCH'
							if (!ignoredReasonsLogged.has(reason)) {
								ignoredReasonsLogged.add(reason)
								console.warn(
									`event=voice_android_bridge phase=FRAME_CALLBACK_IGNORED diagnosticRunId=${diagnosticRunId}`
									+ ` frameSequence=${diagnosticInteger(frameSequence)} reason=${reason}`)
							}
							return
						}
						let decodedFrame
						try {
							decodedFrame = decodeAndroidPcm16Frame(
								payloadBase64,
								declaredByteLength)
						} catch (error) {
							reportFailure(error, 'FRAME_DECODE')
							return
						}
						framesDecoded += 1
						if (!this.firstDecodedFrameLogged) {
							this.firstDecodedFrameLogged = true
							console.log(
								`event=voice_android_bridge phase=JS_FRAME_DECODED diagnosticRunId=${diagnosticRunId}`
								+ ` frameSequence=${diagnosticInteger(frameSequence)}`
								+ ` declaredBytes=${diagnosticInteger(declaredByteLength)}`
								+ ` decodedBytes=${decodedFrame.byteLength}`)
						} else if (framesReceived % 50 === 0) {
							console.log(
								`event=voice_android_bridge phase=JS_FRAME_SUMMARY diagnosticRunId=${diagnosticRunId}`
								+ ` framesReceived=${framesReceived} framesDecoded=${framesDecoded}`
								+ ` lastFrameBytes=${decodedFrame.byteLength}`)
						}
						onFrame(decodedFrame)
					},
					onStarted: () => {
						const epochMatched = this.recordingEpoch === recordingEpoch
						console.log(
							`event=voice_android_bridge phase=START_CALLBACK_ENTERED diagnosticRunId=${diagnosticRunId}`
							+ ` startSettled=${startSettled} runtimeFailureReported=${runtimeFailureReported}`
							+ ` startCallReturned=${startCallReturned} epochMatched=${epochMatched}`)
						if (startSettled || runtimeFailureReported || !epochMatched) return
						nativeStarted = true
						completeStartIfReady()
					},
					onStopped: () => {
						console.log(
							`event=voice_android_bridge phase=STOP_CALLBACK_ENTERED diagnosticRunId=${diagnosticRunId}`
							+ ` epochMatched=${this.recordingEpoch === recordingEpoch}`)
						if (this.recordingEpoch === recordingEpoch) {
							this._clearLeaseTimer()
							if (this.nativeRecordingId === recordingId) {
								this.nativeRecordingId = null
							}
						}
						resolveStoppedSession()
						if (this.stoppedPromise === sessionStoppedPromise) {
							this.stoppedResolve = null
						}
					},
					onError: failure => {
						console.warn(
							`event=voice_android_bridge phase=ERROR_CALLBACK_ENTERED diagnosticRunId=${diagnosticRunId}`
							+ ` errorCode=${diagnosticCode(failure?.code)}`)
						const error = recorderError(
							failure?.code || 'VOICE_RECORDER_FAILED',
							failure?.message || 'Android 录音失败。')
						reportFailure(error, 'NATIVE_ERROR')
					}
				})
				startCallReturned = true
				const recordingIdValid = isValidNativeRecordingId(recordingId)
				console.log(
					`event=voice_android_bridge phase=START_RETURNED diagnosticRunId=${diagnosticRunId}`
					+ ` recordingId=${diagnosticInteger(recordingId)}`
					+ ` recordingIdValid=${recordingIdValid}`)
				if (!recordingIdValid) {
					reportFailure(
						recorderError(
							'VOICE_AUDIO_BRIDGE_INVALID',
							'Android 音频通道控制编号无效，请重新启动录音。'),
						'START_RETURN')
					return
				}
				if (stopRequested || runtimeFailureReported
					|| this.recordingEpoch !== recordingEpoch) {
					stopNative()
				} else {
					this.nativeRecordingId = recordingId
					completeStartIfReady()
				}
			} catch (error) {
				console.warn(
					`event=voice_android_bridge phase=START_CALL_FAILED diagnosticRunId=${diagnosticRunId}`
					+ ` exceptionType=${jsExceptionType(error)}`)
				reportFailure(
					recorderError('VOICE_RECORDER_FAILED', 'Android 录音启动失败。'),
					'START_CALL')
			}
		})
		// #endif
		// #ifndef APP-PLUS
		return Promise.reject(recorderError('VOICE_RECORDER_UNSUPPORTED', '当前平台不支持 Android 录音器。'))
		// #endif
	}

	async stop() {
		this.recordingEpoch += 1
		this._clearLeaseTimer()
		const recordingId = this.nativeRecordingId
		const diagnosticRunId = this.diagnosticRunId || 'ABSENT'
		const stoppedPromise = this.stoppedPromise
		this.nativeRecordingId = null
		this._invokeNativeStop(recordingId, diagnosticRunId)
		if (stoppedPromise) {
			await Promise.race([
				stoppedPromise,
				new Promise(resolve => setTimeout(resolve, 250))
			])
		}
		if (this.stoppedPromise === stoppedPromise) {
			this.stoppedPromise = null
			this.stoppedResolve = null
		}
		if (this.diagnosticRunId === diagnosticRunId) this.diagnosticRunId = ''
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

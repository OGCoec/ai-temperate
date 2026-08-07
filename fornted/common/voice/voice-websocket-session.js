import { voiceWebSocketUrl } from './voice-ticket-api.js'

export const VOICE_SESSION_STATES = Object.freeze({
	IDLE: 'IDLE',
	CONNECTING: 'CONNECTING',
	QUEUED: 'QUEUED',
	RECORDING: 'RECORDING',
	FINALIZING: 'FINALIZING',
	COMPLETED: 'COMPLETED',
	ERROR: 'ERROR',
	CLOSED: 'CLOSED'
})

const MAX_PENDING_AUDIO_BYTES = 1024 * 1024
const MAX_AUDIO_FRAME_BYTES = 128 * 1024
const MAX_EVENT_CHARACTERS = 64 * 1024
const MAX_TRANSCRIPT_CHARACTERS = 48 * 1024
const QUEUE_TIMEOUT_GRACE_MS = 5000

function voiceError(code, message, retryable = false) {
	const error = new Error(message)
	error.code = code
	error.retryable = retryable
	return error
}

function parseServerEvent(raw) {
	if (typeof raw !== 'string' || raw.length > MAX_EVENT_CHARACTERS) {
		throw voiceError('VOICE_PROTOCOL_INVALID', '语音服务返回了无效事件。')
	}
	let event
	try { event = JSON.parse(raw) } catch (_) {
		throw voiceError('VOICE_PROTOCOL_INVALID', '语音服务返回了无法解析的事件。')
	}
	if (!event || typeof event.type !== 'string') {
		throw voiceError('VOICE_PROTOCOL_INVALID', '语音服务事件缺少类型。')
	}
	if (event.text != null && (typeof event.text !== 'string'
		|| event.text.length > MAX_TRANSCRIPT_CHARACTERS)) {
		throw voiceError('VOICE_PROTOCOL_INVALID', '语音转写文本超过客户端安全边界。')
	}
	return event
}

export function voiceErrorMessage(error) {
	const messages = {
		VOICE_TICKET_INVALID: '语音连接凭证已失效，请重新点击麦克风。',
		VOICE_TICKET_RATE_LIMITED: '语音连接尝试过于频繁，请稍后再试。',
		VOICE_BUSY: '本地显卡正在处理另一段语音，请稍后再试。',
		VOICE_QUEUE_FULL: '本地语音识别等待队列已满，请稍后再试。',
		VOICE_QUEUE_TIMEOUT: '等待本地语音识别超时，请重新尝试。',
		VOICE_BACKPRESSURE: '音频发送速度超过服务处理能力，请重新录音。',
		VOICE_FRAME_TOO_LARGE: '麦克风产生的音频帧超过安全大小，请重新录音。',
		VOICE_UPSTREAM_UNAVAILABLE: '本地语音识别服务暂时不可用。',
		VOICE_TRANSCRIPTION_FAILED: '语音识别失败，请重新录音。',
		VOICE_AUDIO_FORMAT_INVALID: '录音格式不受支持。',
		VOICE_PROTOCOL_INVALID: '语音连接协议异常，请重新连接。',
		VOICE_PERMISSION_DENIED: '没有麦克风权限，请在系统设置中允许录音。'
	}
	return messages[error?.code] || error?.message || '语音输入暂时不可用。'
}

export class VoiceWebSocketSession {
	constructor(options = {}) {
		this.url = options.url || voiceWebSocketUrl()
		this.language = options.language || 'auto'
		this.onEvent = typeof options.onEvent === 'function' ? options.onEvent : () => {}
		this.onError = typeof options.onError === 'function' ? options.onError : () => {}
		this.state = VOICE_SESSION_STATES.IDLE
		this.task = null
		this.pendingAudioBytes = 0
		this.sentAudioBytes = 0
		this.maximumAudioBytes = 300000 * 32
		this.lastSequence = -1
		this.sendChain = Promise.resolve()
		this.readyResolve = null
		this.readyReject = null
		this.readyTimer = null
	}

	connect(ticketIssue) {
		if (this.state !== VOICE_SESSION_STATES.IDLE) {
			return Promise.reject(voiceError('VOICE_PROTOCOL_INVALID', '语音会话不能重复连接。'))
		}
		this.state = VOICE_SESSION_STATES.CONNECTING
		this.maximumAudioBytes = Number(ticketIssue?.maxDurationMs || 300000) * 32
		return new Promise((resolve, reject) => {
			this.readyResolve = resolve
			this.readyReject = reject
			this._scheduleReadyTimeout(10000, '语音连接超时。')
			try {
				this.task = uni.connectSocket({
					url: this.url,
					// UniApp 未传回调时会返回 Promise；显式提供 complete 才能取得下方需要的 SocketTask。
					complete: () => {}
				})
				this.task.onOpen(() => this._sendJson({
					type: 'session.start',
					protocolVersion: Number(ticketIssue?.protocolVersion),
					ticket: String(ticketIssue?.ticket || ''),
					language: this.language,
					format: 'pcm_s16le',
					sampleRate: 16000,
					channels: 1
				}).catch(error => this._fail(error)))
				this.task.onMessage(message => this._handleMessage(message?.data))
				this.task.onError(() => this._fail(voiceError(
					'VOICE_UPSTREAM_UNAVAILABLE', '语音连接失败。', true)))
				this.task.onClose(() => {
					if (![VOICE_SESSION_STATES.COMPLETED, VOICE_SESSION_STATES.CLOSED].includes(this.state)) {
						this._fail(voiceError('VOICE_UPSTREAM_UNAVAILABLE', '语音连接已断开。', true), false)
					}
				})
			} catch (_) {
				this._fail(voiceError('VOICE_UPSTREAM_UNAVAILABLE', '无法建立语音连接。', true))
			}
		})
	}

	sendAudio(data) {
		if (this.state !== VOICE_SESSION_STATES.RECORDING) return Promise.resolve(false)
		const length = Number(data?.byteLength || 0)
		if (length > MAX_AUDIO_FRAME_BYTES) {
			const error = voiceError('VOICE_FRAME_TOO_LARGE', '音频帧超过允许大小。')
			this._fail(error)
			return Promise.reject(error)
		}
		if (!length || length % 2 !== 0) {
			const error = voiceError('VOICE_AUDIO_FORMAT_INVALID', '音频帧大小无效。')
			this._fail(error)
			return Promise.reject(error)
		}
		if (this.sentAudioBytes + length > this.maximumAudioBytes) return Promise.resolve(false)
		this.sentAudioBytes += length
		return this._enqueue(data, length).then(() => true)
	}

	commit() {
		if (this.state !== VOICE_SESSION_STATES.RECORDING) return Promise.resolve()
		this.state = VOICE_SESSION_STATES.FINALIZING
		return this._sendJson({ type: 'input.commit' })
	}

	async stop() {
		if ([VOICE_SESSION_STATES.CLOSED, VOICE_SESSION_STATES.COMPLETED].includes(this.state)) return
		try { await this._sendJson({ type: 'session.stop' }) } catch (_) {}
		this._resolveReady(null)
		this.state = VOICE_SESSION_STATES.CLOSED
		this._closeTask(1000, 'CLIENT_STOP')
	}

	_handleMessage(raw) {
		try {
			const event = parseServerEvent(raw)
			if (event.type === 'session.queued') {
				if (![VOICE_SESSION_STATES.CONNECTING, VOICE_SESSION_STATES.QUEUED].includes(this.state)
					|| !this._validQueueEvent(event)) throw voiceError(
						'VOICE_PROTOCOL_INVALID', '语音排队事件无效。')
				this.state = VOICE_SESSION_STATES.QUEUED
				this._scheduleReadyTimeout(
					Number(event.maxWaitMs) + QUEUE_TIMEOUT_GRACE_MS,
					'等待本地语音识别超时。',
					'VOICE_QUEUE_TIMEOUT')
			} else if (event.type === 'session.ready') {
				if (![VOICE_SESSION_STATES.CONNECTING, VOICE_SESSION_STATES.QUEUED].includes(this.state)) throw voiceError(
					'VOICE_PROTOCOL_INVALID', '语音就绪事件顺序无效。')
				this.state = VOICE_SESSION_STATES.RECORDING
				this._resolveReady(event)
			} else if (event.type === 'transcript.partial' || event.type === 'transcript.final') {
				const sequence = Number(event.sequence)
				if (!Number.isInteger(sequence) || sequence <= this.lastSequence) throw voiceError(
					'VOICE_PROTOCOL_INVALID', '语音转写序号无效。')
				this.lastSequence = sequence
				if (event.type === 'transcript.final') this.state = VOICE_SESSION_STATES.COMPLETED
			} else if (event.type === 'input.limit_reached') {
				this.state = VOICE_SESSION_STATES.FINALIZING
			} else if (event.type === 'error') {
				throw voiceError(
					String(event.code || 'VOICE_TRANSCRIPTION_FAILED'),
					String(event.message || '语音识别失败。'),
					event.retryable === true)
			} else {
				throw voiceError('VOICE_PROTOCOL_INVALID', '语音服务返回了未知事件。')
			}
			this.onEvent(event)
			if (event.type === 'transcript.final') this._closeTask(1000, 'TRANSCRIPT_FINAL')
		} catch (error) {
			this._fail(error)
		}
	}

	_enqueue(data, audioBytes = 0) {
		if (!this.task) return Promise.reject(voiceError('VOICE_UPSTREAM_UNAVAILABLE', '语音连接尚未建立。'))
		if (this.pendingAudioBytes + audioBytes > MAX_PENDING_AUDIO_BYTES) {
			const error = voiceError('VOICE_BACKPRESSURE', '本地音频发送队列已满。', true)
			this._fail(error)
			return Promise.reject(error)
		}
		this.pendingAudioBytes += audioBytes
		const operation = this.sendChain.then(() => new Promise((resolve, reject) => {
			this.task.send({
				data,
				success: resolve,
				fail: () => reject(voiceError('VOICE_UPSTREAM_UNAVAILABLE', '音频发送失败。', true))
			})
		})).finally(() => { this.pendingAudioBytes -= audioBytes })
		this.sendChain = operation.catch(() => {})
		return operation
	}

	_sendJson(value) {
		return this._enqueue(JSON.stringify(value))
	}

	_resolveReady(event) {
		clearTimeout(this.readyTimer)
		this.readyTimer = null
		this.readyResolve?.(event)
		this.readyResolve = null
		this.readyReject = null
	}

	_scheduleReadyTimeout(delayMs, message, code = 'VOICE_UPSTREAM_UNAVAILABLE') {
		clearTimeout(this.readyTimer)
		this.readyTimer = setTimeout(() => {
			this._fail(voiceError(code, message, true))
		}, delayMs)
	}

	_validQueueEvent(event) {
		const position = Number(event.position)
		const capacity = Number(event.queueCapacity)
		const maxWaitMs = Number(event.maxWaitMs)
		return /^[0-9a-f]{32}$/.test(String(event.sessionId || ''))
			&& Number.isInteger(position) && position >= 1
			&& Number.isInteger(capacity) && capacity >= position && capacity <= 32
			&& Number.isInteger(maxWaitMs) && maxWaitMs >= 1000 && maxWaitMs <= 300000
	}

	_fail(error, close = true) {
		if (this.state === VOICE_SESSION_STATES.ERROR || this.state === VOICE_SESSION_STATES.CLOSED) return
		this.state = VOICE_SESSION_STATES.ERROR
		clearTimeout(this.readyTimer)
		this.readyTimer = null
		this.readyReject?.(error)
		this.readyResolve = null
		this.readyReject = null
		this.onError(error)
		if (close) {
			const retryLater = ['VOICE_BUSY', 'VOICE_QUEUE_FULL', 'VOICE_QUEUE_TIMEOUT'].includes(error?.code)
			const closeCode = error?.code === 'VOICE_PROTOCOL_INVALID' ? 1008 : (retryLater ? 1013 : 1011)
			this._closeTask(closeCode, error?.code || 'VOICE_ERROR')
		}
	}

	_closeTask(code, reason) {
		try { this.task?.close?.({ code, reason: String(reason || '').slice(0, 64) }) } catch (_) {}
	}
}

export function createVoiceWebSocketSession(options) {
	return new VoiceWebSocketSession(options)
}

const RESULT_SCHEME = 'aitwebrtc://result'
const RESULT_URL_PREFIX = `${RESULT_SCHEME}?`
const RESULT_URL_MATCH = '^aitwebrtc://result.*$'
const RESULT_DELIVERY_GRACE_MILLIS = 1000
const MAX_ENCRYPTED_PAYLOAD_LENGTH = 4096
const MAX_PLAINTEXT_LENGTH = 4096
const MAX_RESULT_URL_LENGTH = 4608
const CHANNEL_PATTERN = /^[a-f0-9]{32}$/
const KEY_PATTERN = /^[A-Za-z0-9_-]{43}$/
const IV_PATTERN = /^[A-Za-z0-9_-]{16}$/
const PAYLOAD_PATTERN = /^[A-Za-z0-9_-]+$/
const INVALID_RESULT_URL_CHARACTERS = /[\u0000-\u0020\u007f\\#]/
const ALLOWED_RESULT_PARAMETERS = new Set([
	'channel',
	'iv',
	'payload',
	'error'
])
const ALLOWED_CHILD_ERRORS = new Set([
	'probe_error',
	'crypto_unavailable'
])

let probeSequence = 0

/**
 * 在Android屏幕外本地WebView中执行WebRTC，并通过调用方注入的UTS原生桥解密一次性回传结果。
 */
export async function collectAndroidWebRtcIpsInBackground(options = {}) {
	if (options.signal?.aborted === true) {
		throw webRtcAbortError(options.signal.reason)
	}
	const startedAt = Date.now()
	const diagnosticsEnabled = options.diagnosticsEnabled === true
	const onDiagnostic = typeof options.onDiagnostic === 'function'
		? options.onDiagnostic
		: null
	const timeoutMillis = boundedTimeout(options.timeoutMillis)
	const stunUrls = Array.isArray(options.stunUrls)
		? options.stunUrls.slice(0, 4)
		: []
	const attemptId = safeSegment(options.attemptId, 'discover', 40)
	const diagnosticRole = safeSegment(options.diagnosticRole, 'user', 12)
	probeSequence = probeSequence >= 999999 ? 1 : probeSequence + 1
	const requestedProbeRunId = optionalSafeSegment(options.probeRunId, 64)
	const probeRunId = requestedProbeRunId
		|| `${diagnosticRole}-${attemptId}-${probeSequence}`
	const trace = (stage, fields = {}) => {
		if (!diagnosticsEnabled || !onDiagnostic) return
		try {
			onDiagnostic(stage, {
				...fields,
				probeRunId,
				elapsedMs: Math.max(0, Date.now() - startedAt)
			})
		} catch (_) {
			// 诊断接收器不属于认证控制流，失败时必须完全忽略。
		}
	}
	const traceEmptyFinish = reason => trace('probe_finished', {
		reason,
		candidateCount: 0,
		ipv4Count: 0,
		ipv6Count: 0
	})

	trace('probe_requested', {
		stunCount: stunUrls.length,
		timeoutMillis
	})
	if (typeof plus === 'undefined' || !plus.webview) {
		trace('environment_unavailable', { reason: 'plus_webview_unavailable' })
		traceEmptyFinish('environment_unavailable')
		return []
	}
	const cryptoBridge = validCryptoBridge(options.cryptoBridge)
	if (!cryptoBridge) {
		trace('crypto_bridge_invalid', { reason: 'crypto_bridge_invalid' })
		traceEmptyFinish('crypto_bridge_invalid')
		return []
	}

	let channelId = ''
	let nonce = ''
	let key = ''
	try {
		const channel = cryptoBridge.createChannel()
		if (!validChannel(channel)) {
			trace('crypto_channel_failed', {
				errorCode: safeErrorCode(channel?.errorCode, 'INVALID_CHANNEL')
			})
			traceEmptyFinish('crypto_channel_failed')
			return []
		}
		channelId = channel.channelId
		nonce = channel.nonce
		key = channel.key
		trace('crypto_channel_ready')
	} catch (_) {
		trace('crypto_channel_failed', { errorCode: 'CRYPTO_FAILURE' })
		traceEmptyFinish('crypto_channel_failed')
		return []
	}

	const deadlineAt = startedAt + timeoutMillis
	const webviewBaseId = safeSegment(options.webviewId, 'ait-webrtc', 32)
	const webviewId = `${webviewBaseId}-${probeRunId}`
	let webview = null
	let timer = null
	let settled = false
	let responseAccepted = false
	let probeStarted = false
	let callbackCount = 0

	return new Promise((resolve, reject) => {
		const finish = (value, reason, cancellationError = null) => {
			if (settled) return
			const webRtcIps = Array.isArray(value) ? value.slice(0, 8) : []
			trace('finish_started', {
				reason: safeErrorCode(reason, 'completed').toLowerCase(),
				candidateCount: webRtcIps.length,
				...candidateFamilyCounts(webRtcIps)
			})
			settled = true
			options.signal?.removeEventListener?.('abort', onAbort)
			if (timer) {
				clearTimeout(timer)
				timer = null
			}
			const closingWebView = webview
			webview = null
			let closeState = 'ABSENT'
			try {
				if (typeof closingWebView?.close === 'function') {
					closingWebView.close('none')
					closeState = 'SUCCESS'
				}
			} catch (_) {
				// WebView可能已由系统销毁；重复关闭不得改变探针最终结果。
				closeState = 'FAILED'
			}
			trace('webview_close_completed', {
				state: closeState,
				candidateCount: webRtcIps.length
			})
			trace('probe_finished', {
				reason: safeErrorCode(reason, 'completed').toLowerCase(),
				candidateCount: webRtcIps.length,
				...candidateFamilyCounts(webRtcIps)
			})
			channelId = ''
			nonce = ''
			key = ''
			if (cancellationError) {
				trace('promise_rejecting', { reason: 'aborted' })
				reject(cancellationError)
				return
			}
			trace('promise_resolving', {
				candidateCount: webRtcIps.length,
				...candidateFamilyCounts(webRtcIps)
			})
			resolve(webRtcIps)
		}
		const onAbort = () => finish(
			[],
			'aborted',
			webRtcAbortError(options.signal?.reason))
		options.signal?.addEventListener?.('abort', onAbort, { once: true })
		if (options.signal?.aborted === true) {
			onAbort()
			return
		}

		try {
			webview = plus.webview.create(
				String(options.resourcePath || '/hybrid/html/webrtc-probe.html'),
				webviewId,
				{
					left: '-10000px',
					top: '-10000px',
					width: '1px',
					height: '1px',
					opacity: 0,
					plusrequire: 'none',
					render: 'always',
					background: 'transparent'
				}
			)
			trace('webview_created')
			webview.overrideUrlLoading(
				{
					mode: 'reject',
					effect: 'instant',
					match: RESULT_URL_MATCH
				},
				event => {
					callbackCount += 1
					const callbackState = settled
						? 'SETTLED'
						: responseAccepted ? 'DUPLICATE' : 'ACTIVE'
					trace('result_callback_entered', {
						callbackCount,
						state: callbackState,
						timerActive: timer !== null,
						remainingMillis: Math.max(0, deadlineAt - Date.now()),
						urlLength: safeTextLength(event?.url)
					})
					if (settled || responseAccepted) {
						trace('result_callback_ignored', {
							callbackCount,
							state: callbackState,
							reason: settled ? 'settled' : 'duplicate',
							timerActive: timer !== null,
							remainingMillis: Math.max(0, deadlineAt - Date.now())
						})
						return
					}
					responseAccepted = true
					if (timer) {
						clearTimeout(timer)
						timer = null
					}
					trace('parent_timer_cleared', {
						callbackCount,
						timerActive: timer !== null,
						remainingMillis: Math.max(0, deadlineAt - Date.now())
					})
					trace('result_intercepted', {
						callbackCount,
						urlLength: safeTextLength(event?.url)
					})
					void decryptResult(
						event?.url,
						cryptoBridge,
						channelId,
						nonce,
						key,
						trace
					).then(result => {
						trace('parent_result_ready', {
							reason: result.reason,
							candidateCount: result.webRtcIps.length,
							...candidateFamilyCounts(result.webRtcIps)
						})
						finish(result.webRtcIps, result.reason)
					}).catch(() => {
						trace('decrypt_exception', { errorCode: 'DECRYPT_EXCEPTION' })
						trace('parent_result_ready', {
							reason: 'decrypt_exception',
							candidateCount: 0,
							ipv4Count: 0,
							ipv6Count: 0
						})
						finish([], 'decrypt_exception')
					})
				}
			)
			trace('interceptor_registered')
			webview.addEventListener('loaded', () => {
				if (settled || probeStarted) return
				probeStarted = true
				const remainingMillis = Math.max(0, deadlineAt - Date.now())
				trace('webview_loaded', { remainingMillis })
				const iceTimeoutMillis = remainingMillis - RESULT_DELIVERY_GRACE_MILLIS
				if (iceTimeoutMillis <= 0) {
					trace('ice_budget_exhausted', { remainingMillis })
					finish([], 'ice_budget_exhausted')
					return
				}
				const configuration = {
					channelId,
					nonce,
					key,
					stunUrls,
					timeoutMillis: iceTimeoutMillis,
					diagnosticsEnabled,
					probeRunId
				}
				try {
					webview?.evalJS?.(
						`window.startWebRtcProbe(${JSON.stringify(configuration)})`)
					trace('ice_probe_started', {
						iceTimeoutMillis,
						stunCount: stunUrls.length
					})
				} catch (_) {
					trace('ice_probe_start_failed', { errorCode: 'EVAL_JS_FAILED' })
					finish([], 'ice_probe_start_failed')
				}
			})
			webview.addEventListener('error', () => {
				if (settled) return
				trace('webview_error', { errorCode: 'WEBVIEW_ERROR' })
				finish([], 'webview_error')
			})
			webview.addEventListener('close', () => {
				if (settled) return
				trace('webview_closed_early', { reason: 'webview_closed_early' })
				finish([], 'webview_closed_early')
			})
			timer = setTimeout(() => {
				if (settled) return
				trace('parent_timeout', { reason: 'parent_timeout' })
				finish([], 'parent_timeout')
			}, Math.max(1, deadlineAt - Date.now()))
			// 屏幕外WebView仍需show，确保系统创建渲染上下文并执行WebRTC。
			webview.show('none', 0)
		} catch (_) {
			trace('webview_setup_failed', { errorCode: 'WEBVIEW_SETUP_FAILED' })
			finish([], 'webview_setup_failed')
		}
	})
}

function webRtcAbortError(reason) {
	const error = new Error('WebRTC attempt was cancelled.')
	error.code = 'WEBRTC_ATTEMPT_ABORTED'
	error.cancelReason = String(reason || 'EPOCH_INVALIDATED')
	return error
}

async function decryptResult(
	rawUrl,
	cryptoBridge,
	expectedChannel,
	expectedNonce,
	key,
	trace
) {
	const urlLength = safeTextLength(rawUrl)
	const parsed = parseAitWebRtcResultUrl(rawUrl)
	if (!parsed.success) {
		trace('result_url_invalid', { reason: parsed.reason, urlLength })
		return failedResult('result_url_invalid')
	}
	const receivedChannel = parsed.channel
	const iv = parsed.iv
	const encryptedValue = parsed.payload
	const childError = parsed.error
	trace('result_url_parsed', {
		urlLength,
		hasChannel: receivedChannel.length > 0,
		hasIv: iv.length > 0,
		hasPayload: encryptedValue.length > 0,
		ivLength: iv.length,
		payloadLength: encryptedValue.length
	})
	if (receivedChannel !== expectedChannel) {
		trace('result_channel_mismatch', {
			reason: 'channel_mismatch',
			hasChannel: receivedChannel.length > 0
		})
		return failedResult('result_channel_mismatch')
	}
	trace('result_channel_validated', { hasChannel: true })
	if (childError) {
		const errorCode = childError === 'crypto_unavailable'
			? 'crypto_unavailable'
			: 'probe_error'
		trace('child_probe_error', { errorCode })
		return failedResult('child_probe_error')
	}
	if (!IV_PATTERN.test(iv)
		|| !PAYLOAD_PATTERN.test(encryptedValue)
		|| encryptedValue.length > MAX_ENCRYPTED_PAYLOAD_LENGTH) {
		trace('encrypted_payload_invalid', {
			reason: 'encrypted_value_invalid',
			hasIv: iv.length > 0,
			hasPayload: encryptedValue.length > 0,
			ivLength: iv.length,
			payloadLength: encryptedValue.length
		})
		return failedResult('encrypted_payload_invalid')
	}
	trace('encrypted_payload_validated', {
		hasIv: true,
		hasPayload: true,
		ivLength: iv.length,
		payloadLength: encryptedValue.length
	})

	let result
	trace('native_decrypt_started')
	try {
		result = cryptoBridge.decryptPayload({
			channelId: expectedChannel,
			nonce: expectedNonce,
			key,
			iv,
			payload: encryptedValue
		})
	} catch (_) {
		trace('native_decrypt_completed', { state: 'THREW' })
		trace('decrypt_exception', { errorCode: 'DECRYPT_EXCEPTION' })
		return failedResult('decrypt_exception')
	}
	trace('native_decrypt_completed', {
		state: result?.success === true ? 'SUCCESS' : 'FAILED'
	})
	if (result?.success !== true) {
		trace('native_decrypt_failed', {
			errorCode: safeErrorCode(result?.errorCode, 'DECRYPT_FAILED')
		})
		return failedResult('native_decrypt_failed')
	}
	if (typeof result.plaintext !== 'string'
		|| result.plaintext.length === 0
		|| result.plaintext.length > MAX_PLAINTEXT_LENGTH) {
		trace('plaintext_invalid', {
			reason: 'plaintext_length_invalid',
			plaintextLength: safeTextLength(result?.plaintext)
		})
		return failedResult('plaintext_invalid')
	}

	let value
	try {
		value = JSON.parse(result.plaintext)
	} catch (_) {
		trace('plaintext_invalid', {
			reason: 'json_invalid',
			plaintextLength: result.plaintext.length
		})
		return failedResult('plaintext_invalid')
	}
	trace('plaintext_parsed', { plaintextLength: result.plaintext.length })
	if (value?.channelId !== expectedChannel || value?.nonce !== expectedNonce) {
		trace('result_identity_mismatch', { reason: 'identity_mismatch' })
		return failedResult('result_identity_mismatch')
	}
	trace('result_identity_validated')
	const webRtcIps = Array.isArray(value.webRtcIps)
		? value.webRtcIps
			.filter(item => typeof item === 'string' && item.length <= 64)
			.slice(0, 8)
		: []
	trace('decrypt_success', {
		candidateCount: webRtcIps.length,
		...candidateFamilyCounts(webRtcIps)
	})
	return { webRtcIps, reason: 'success' }
}

function parseAitWebRtcResultUrl(rawUrl) {
	if (typeof rawUrl !== 'string') {
		return invalidResultUrl('url_type_invalid')
	}
	if (rawUrl.length === 0 || rawUrl.length > MAX_RESULT_URL_LENGTH) {
		return invalidResultUrl('url_length_invalid')
	}
	if (INVALID_RESULT_URL_CHARACTERS.test(rawUrl)) {
		return invalidResultUrl('url_character_invalid')
	}
	if (!rawUrl.startsWith(RESULT_URL_PREFIX)) {
		return invalidResultUrl('scheme_mismatch')
	}

	const query = rawUrl.slice(RESULT_URL_PREFIX.length)
	if (!query || query.includes('?')) {
		return invalidResultUrl('query_invalid')
	}
	const segments = query.split('&')
	if (segments.length === 0 || segments.length > ALLOWED_RESULT_PARAMETERS.size
		|| segments.some(segment => !segment)) {
		return invalidResultUrl('query_invalid')
	}

	const parameters = Object.create(null)
	for (const segment of segments) {
		const separatorIndex = segment.indexOf('=')
		if (separatorIndex <= 0 || separatorIndex === segment.length - 1) {
			return invalidResultUrl('parameter_invalid')
		}
		const name = segment.slice(0, separatorIndex)
		if (!ALLOWED_RESULT_PARAMETERS.has(name)) {
			return invalidResultUrl('parameter_unknown')
		}
		if (Object.prototype.hasOwnProperty.call(parameters, name)) {
			return invalidResultUrl('parameter_duplicate')
		}
		let value
		try {
			value = decodeURIComponent(segment.slice(separatorIndex + 1))
		} catch (_) {
			return invalidResultUrl('parameter_decode_failed')
		}
		if (!value) {
			return invalidResultUrl('parameter_invalid')
		}
		parameters[name] = value
	}

	const parameterNames = Object.keys(parameters)
	const hasChannel = Object.prototype.hasOwnProperty.call(parameters, 'channel')
	const hasIv = Object.prototype.hasOwnProperty.call(parameters, 'iv')
	const hasPayload = Object.prototype.hasOwnProperty.call(parameters, 'payload')
	const hasError = Object.prototype.hasOwnProperty.call(parameters, 'error')
	if (hasError) {
		if (parameterNames.length !== 2 || !hasChannel || hasIv || hasPayload) {
			return invalidResultUrl('result_shape_invalid')
		}
		if (!ALLOWED_CHILD_ERRORS.has(parameters.error)) {
			return invalidResultUrl('child_error_invalid')
		}
		return {
			success: true,
			mode: 'error',
			channel: parameters.channel,
			iv: '',
			payload: '',
			error: parameters.error
		}
	}
	if (parameterNames.length !== 3 || !hasChannel || !hasIv || !hasPayload) {
		return invalidResultUrl('result_shape_invalid')
	}
	return {
		success: true,
		mode: 'success',
		channel: parameters.channel,
		iv: parameters.iv,
		payload: parameters.payload,
		error: ''
	}
}

function invalidResultUrl(reason) {
	return { success: false, reason }
}

function failedResult(reason) {
	return { webRtcIps: [], reason }
}

function candidateFamilyCounts(values) {
	let ipv4Count = 0
	let ipv6Count = 0
	for (const value of values) {
		if (String(value).includes(':')) ipv6Count += 1
		else ipv4Count += 1
	}
	return { ipv4Count, ipv6Count }
}

function validCryptoBridge(value) {
	return value
		&& typeof value.createChannel === 'function'
		&& typeof value.decryptPayload === 'function'
		? value
		: null
}

function validChannel(value) {
	return value?.success === true
		&& CHANNEL_PATTERN.test(String(value.channelId || ''))
		&& CHANNEL_PATTERN.test(String(value.nonce || ''))
		&& KEY_PATTERN.test(String(value.key || ''))
}

function safeSegment(value, fallback, maxLength) {
	const normalized = String(value || fallback)
		.replace(/[^A-Za-z0-9:_-]/g, '')
		.slice(0, maxLength)
	return normalized || fallback
}

function optionalSafeSegment(value, maxLength) {
	const normalized = String(value || '').slice(0, maxLength)
	return /^[A-Za-z0-9:_-]+$/.test(normalized) ? normalized : ''
}

function safeErrorCode(value, fallback) {
	const normalized = String(value || fallback)
		.replace(/[^A-Za-z0-9_-]/g, '')
		.slice(0, 64)
	return normalized || fallback
}

function safeTextLength(value) {
	try {
		return String(value || '').length
	} catch (_) {
		return 0
	}
}

function boundedTimeout(value) {
	const numeric = Number(value)
	return Number.isFinite(numeric) && numeric > 0 ? Math.min(numeric, 15000) : 15000
}

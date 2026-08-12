const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const sourcePath = path.resolve(__dirname, 'android-webrtc-background-probe.js')
const sourceText = fs.readFileSync(sourcePath, 'utf8')
const moduleUrl = `data:text/javascript;base64,${Buffer.from(sourceText).toString('base64')}`

const CHANNEL = '0123456789abcdef0123456789abcdef'
const NONCE = 'abcdef0123456789abcdef0123456789'
const KEY = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
const IV = 'AAAAAAAAAAAAAAAA'
const PAYLOAD = 'BBBBBBBBBBBBBBBBBBBBBB'

function cryptoBridge(overrides = {}) {
	const decryptCalls = []
	return {
		decryptCalls,
		createChannel() {
			return {
				success: true,
				channelId: CHANNEL,
				nonce: NONCE,
				key: KEY,
				errorCode: ''
			}
		},
		decryptPayload(options) {
			decryptCalls.push(options)
			return {
				success: true,
				plaintext: JSON.stringify({
					channelId: CHANNEL,
					nonce: NONCE,
					webRtcIps: ['66.90.98.38']
				}),
				errorCode: ''
			}
		},
		...overrides
	}
}

function diagnosticCollector() {
	const events = []
	return {
		events,
		emit(stage, fields) {
			events.push({ stage, ...fields })
		}
	}
}

function installWebViewHarness() {
	const listeners = new Map()
	const created = []
	const evaluated = []
	let overrideHandler = null
	let overrideOptions = null
	let closeCount = 0

	const webview = {
		overrideUrlLoading(options, handler) {
			overrideOptions = options
			overrideHandler = handler
		},
		addEventListener(name, handler) {
			listeners.set(name, handler)
		},
		evalJS(script) {
			evaluated.push(script)
		},
		show() {},
		close() {
			closeCount += 1
			listeners.get('close')?.()
		}
	}

	global.plus = {
		webview: {
			create(resourcePath, webviewId, styles) {
				created.push({ resourcePath, webviewId, styles })
				return webview
			}
		}
	}

	return {
		created,
		evaluated,
		get closeCount() { return closeCount },
		get overrideOptions() { return overrideOptions },
		trigger(name, event = {}) {
			listeners.get(name)?.(event)
		},
		invokeOverride(url) {
			assert.equal(typeof overrideHandler, 'function')
			overrideHandler({ url })
		},
		navigate(url) {
			if (!overrideHandler || !overrideOptions) return false
			const expression = String(overrideOptions.match || '.*')
			const matched = new RegExp(`^(?:${expression})$`).test(url)
			const intercepted = overrideOptions.mode === 'allow' ? !matched : matched
			if (intercepted) overrideHandler({ url })
			return intercepted
		},
		configuration() {
			assert.equal(evaluated.length, 1)
			const prefix = 'window.startWebRtcProbe('
			const script = evaluated[0]
			assert.ok(script.startsWith(prefix))
			assert.ok(script.endsWith(')'))
			return JSON.parse(script.slice(prefix.length, -1))
		},
		cleanup() {
			delete global.plus
		}
	}
}

async function probeModule() {
	return import(moduleUrl)
}

function installFakeClock() {
	const originalDateNow = Date.now
	const originalSetTimeout = global.setTimeout
	const originalClearTimeout = global.clearTimeout
	const timers = new Map()
	let now = 0
	let nextTimerId = 1

	Date.now = () => now
	global.setTimeout = (callback, delay = 0) => {
		const timerId = nextTimerId++
		const normalizedDelay = Math.max(0, Number(delay) || 0)
		timers.set(timerId, { callback, runAt: now + normalizedDelay })
		return timerId
	}
	global.clearTimeout = timerId => {
		timers.delete(timerId)
	}

	return {
		async advanceBy(milliseconds) {
			const target = now + Math.max(0, Number(milliseconds) || 0)
			while (true) {
				const next = [...timers.entries()]
					.filter(([, timer]) => timer.runAt <= target)
					.sort((left, right) => left[1].runAt - right[1].runAt
						|| left[0] - right[0])[0]
				if (!next) break
				const [timerId, timer] = next
				timers.delete(timerId)
				now = timer.runAt
				timer.callback()
				await Promise.resolve()
			}
			now = target
			await Promise.resolve()
		},
		cleanup() {
			timers.clear()
			Date.now = originalDateNow
			global.setTimeout = originalSetTimeout
			global.clearTimeout = originalClearTimeout
		}
	}
}

test('native crypto channel is passed to the hidden WebView and decrypted once', async () => {
	const clock = installFakeClock()
	const harness = installWebViewHarness()
	try {
		const bridge = cryptoBridge()
		const diagnostics = diagnosticCollector()
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		const resultPromise = collectAndroidWebRtcIpsInBackground({
			attemptId: '3:7',
			webviewId: 'ait-user-webrtc',
			resourcePath: '/hybrid/html/webrtc-probe.html',
			stunUrls: ['stun:stun.cloudflare.com:3478'],
			timeoutMillis: 12000,
			diagnosticsEnabled: true,
			diagnosticRole: 'user',
			onDiagnostic: diagnostics.emit,
			cryptoBridge: bridge
		})

		assert.equal(harness.created.length, 1)
		assert.equal(harness.created[0].styles.plusrequire, 'none')
		assert.deepEqual(harness.overrideOptions, {
			mode: 'reject',
			effect: 'instant',
			match: '^aitwebrtc://result.*$'
		})
		assert.equal(harness.created[0].webviewId.includes(NONCE), false)
		harness.trigger('loaded')
		const configuration = harness.configuration()
		assert.equal(configuration.channelId, CHANNEL)
		assert.equal(configuration.nonce, NONCE)
		assert.equal(configuration.key, KEY)
		assert.deepEqual(configuration.stunUrls, ['stun:stun.cloudflare.com:3478'])
		assert.equal(configuration.timeoutMillis, 11000)
		assert.equal(configuration.diagnosticsEnabled, true)
		assert.match(configuration.probeRunId, /^user-3:7-[1-9][0-9]*$/)

		assert.equal(harness.navigate(
			`aitwebrtc://result?channel=${CHANNEL}&iv=${IV}&payload=${PAYLOAD}`), true)
		assert.deepEqual(await resultPromise, ['66.90.98.38'])
		assert.deepEqual(bridge.decryptCalls, [{
			channelId: CHANNEL,
			nonce: NONCE,
			key: KEY,
			iv: IV,
			payload: PAYLOAD
		}])
		assert.equal(harness.closeCount, 1)
		assert.deepEqual(
			diagnostics.events.map(event => event.stage),
			[
				'probe_requested',
				'crypto_channel_ready',
				'webview_created',
				'interceptor_registered',
				'webview_loaded',
				'ice_probe_started',
				'result_callback_entered',
				'parent_timer_cleared',
				'result_intercepted',
				'result_url_parsed',
				'result_channel_validated',
				'encrypted_payload_validated',
				'native_decrypt_started',
				'native_decrypt_completed',
				'plaintext_parsed',
				'result_identity_validated',
				'decrypt_success',
				'parent_result_ready',
				'finish_started',
				'webview_close_completed',
				'probe_finished',
				'promise_resolving'
			]
		)
		assert.equal(
			diagnostics.events.find(event => event.stage === 'result_callback_entered')?.callbackCount,
			1
		)
		assert.equal(
			diagnostics.events.find(event => event.stage === 'parent_timer_cleared')?.timerActive,
			false
		)
		assert.equal(
			diagnostics.events.find(event => event.stage === 'decrypt_success')?.candidateCount,
			1
		)
		assert.deepEqual(
			diagnostics.events
				.filter(event => ['parent_result_ready', 'finish_started', 'probe_finished', 'promise_resolving']
					.includes(event.stage))
				.map(event => event.candidateCount),
			[1, 1, 1, 1]
		)
		const serializedDiagnostics = JSON.stringify(diagnostics.events)
		assert.doesNotMatch(serializedDiagnostics, new RegExp(CHANNEL))
		assert.doesNotMatch(serializedDiagnostics, new RegExp(NONCE))
		assert.doesNotMatch(serializedDiagnostics, new RegExp(KEY))
		assert.doesNotMatch(serializedDiagnostics, /66\.90\.98\.38|aitwebrtc:\/\/|candidate:/)
	} finally {
		harness.cleanup()
		clock.cleanup()
	}
})

test('custom result navigation requires the complete native match expression', async () => {
	const harness = installWebViewHarness()
	try {
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		const resultPromise = collectAndroidWebRtcIpsInBackground({
			cryptoBridge: cryptoBridge(),
			probeRunId: 'admin-4:2-9',
			timeoutMillis: 12000
		})

		assert.match(harness.created[0].webviewId, /admin-4:2-9$/)
		assert.equal(harness.navigate('https://example.test/aitwebrtc://result'), false)
		assert.equal(harness.navigate(
			`aitwebrtc://result?channel=${CHANNEL}&iv=${IV}&payload=${PAYLOAD}`), true)
		assert.deepEqual(await resultPromise, ['66.90.98.38'])
	} finally {
		harness.cleanup()
	}
})

test('strict result parsing succeeds without the browser URL constructor', async () => {
	const harness = installWebViewHarness()
	const originalUrl = global.URL
	try {
		global.URL = undefined
		const bridge = cryptoBridge()
		const diagnostics = diagnosticCollector()
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		const resultPromise = collectAndroidWebRtcIpsInBackground({
			cryptoBridge: bridge,
			diagnosticsEnabled: true,
			onDiagnostic: diagnostics.emit,
			timeoutMillis: 12000
		})

		harness.navigate(
			`aitwebrtc://result?payload=${PAYLOAD}&channel=${CHANNEL}&iv=${IV}`)

		assert.deepEqual(await resultPromise, ['66.90.98.38'])
		assert.equal(bridge.decryptCalls.length, 1)
		assert.deepEqual(
			diagnostics.events
				.filter(event => [
					'result_url_parsed',
					'result_channel_validated',
					'native_decrypt_completed',
					'decrypt_success'
				].includes(event.stage))
				.map(event => event.stage),
			[
				'result_url_parsed',
				'result_channel_validated',
				'native_decrypt_completed',
				'decrypt_success'
			]
		)
	} finally {
		global.URL = originalUrl
		harness.cleanup()
	}
})

test('strict result parsing accepts only the two documented child errors', async () => {
	for (const errorCode of ['probe_error', 'crypto_unavailable']) {
		const harness = installWebViewHarness()
		try {
			const bridge = cryptoBridge()
			const diagnostics = diagnosticCollector()
			const { collectAndroidWebRtcIpsInBackground } = await probeModule()
			const resultPromise = collectAndroidWebRtcIpsInBackground({
				cryptoBridge: bridge,
				diagnosticsEnabled: true,
				onDiagnostic: diagnostics.emit,
				timeoutMillis: 12000
			})

			harness.navigate(
				`aitwebrtc://result?error=${errorCode}&channel=${CHANNEL}`)

			assert.deepEqual(await resultPromise, [])
			assert.equal(bridge.decryptCalls.length, 0)
			assert.equal(
				diagnostics.events.find(event => event.stage === 'child_probe_error')?.errorCode,
				errorCode
			)
		} finally {
			harness.cleanup()
		}
	}
})

test('strict result parsing rejects malformed and ambiguous custom URLs before decryption', async () => {
	const malformedCases = [
		{
			name: 'non-string URL',
			url: undefined,
			reason: 'url_type_invalid'
		},
		{
			name: 'wrong scheme casing',
			url: `AITWEBRTC://result?channel=${CHANNEL}&iv=${IV}&payload=${PAYLOAD}`,
			reason: 'scheme_mismatch'
		},
		{
			name: 'duplicate parameter',
			url: `aitwebrtc://result?channel=${CHANNEL}&channel=${CHANNEL}&iv=${IV}&payload=${PAYLOAD}`,
			reason: 'parameter_duplicate'
		},
		{
			name: 'unknown parameter',
			url: `aitwebrtc://result?channel=${CHANNEL}&iv=${IV}&payload=${PAYLOAD}&extra=secret_marker`,
			reason: 'parameter_unknown'
		},
		{
			name: 'invalid percent encoding',
			url: `aitwebrtc://result?channel=%ZZ&iv=${IV}&payload=${PAYLOAD}`,
			reason: 'parameter_decode_failed'
		},
		{
			name: 'missing payload',
			url: `aitwebrtc://result?channel=${CHANNEL}&iv=${IV}`,
			reason: 'result_shape_invalid'
		},
		{
			name: 'mixed error and encrypted result',
			url: `aitwebrtc://result?channel=${CHANNEL}&iv=${IV}&payload=${PAYLOAD}&error=probe_error`,
			reason: 'result_shape_invalid'
		},
		{
			name: 'unknown child error',
			url: `aitwebrtc://result?channel=${CHANNEL}&error=unexpected`,
			reason: 'child_error_invalid'
		},
		{
			name: 'empty parameter segment',
			url: `aitwebrtc://result?channel=${CHANNEL}&&iv=${IV}&payload=${PAYLOAD}`,
			reason: 'query_invalid'
		},
		{
			name: 'missing equals sign',
			url: `aitwebrtc://result?channel=${CHANNEL}&iv=${IV}&payload`,
			reason: 'parameter_invalid'
		},
		{
			name: 'fragment',
			url: `aitwebrtc://result?channel=${CHANNEL}&iv=${IV}&payload=${PAYLOAD}#fragment`,
			reason: 'url_character_invalid'
		},
		{
			name: 'control character',
			url: `aitwebrtc://result?channel=${CHANNEL}&iv=${IV}&payload=${PAYLOAD}\n`,
			reason: 'url_character_invalid'
		},
		{
			name: 'oversized URL',
			url: `aitwebrtc://result?channel=${CHANNEL}&iv=${IV}&payload=${'B'.repeat(4600)}`,
			reason: 'url_length_invalid'
		}
	]

	for (const malformed of malformedCases) {
		const harness = installWebViewHarness()
		try {
			const bridge = cryptoBridge()
			const diagnostics = diagnosticCollector()
			const { collectAndroidWebRtcIpsInBackground } = await probeModule()
			const resultPromise = collectAndroidWebRtcIpsInBackground({
				cryptoBridge: bridge,
				diagnosticsEnabled: true,
				onDiagnostic: diagnostics.emit,
				timeoutMillis: 12000
			})

			harness.invokeOverride(malformed.url)

			assert.deepEqual(await resultPromise, [], malformed.name)
			assert.equal(bridge.decryptCalls.length, 0, malformed.name)
			assert.equal(
				diagnostics.events.find(event => event.stage === 'result_url_invalid')?.reason,
				malformed.reason,
				malformed.name
			)
			const serializedDiagnostics = JSON.stringify(diagnostics.events)
			assert.doesNotMatch(
				serializedDiagnostics,
				/secret_marker|aitwebrtc:\/\/|0123456789abcdef|BBBBBBBB/,
				malformed.name
			)
		} finally {
			harness.cleanup()
		}
	}
})

test('late encrypted result is preserved inside the total probe budget', async () => {
	const clock = installFakeClock()
	const harness = installWebViewHarness()
	try {
		const bridge = cryptoBridge()
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		const resultPromise = collectAndroidWebRtcIpsInBackground({
			cryptoBridge: bridge,
			stunUrls: ['stun:stun.cloudflare.com:3478'],
			timeoutMillis: 12000
		})

		await clock.advanceBy(80)
		harness.trigger('loaded')
		const configuration = harness.configuration()
		assert.equal(configuration.timeoutMillis, 10920)

		// ICE 用满内部预算后，仍为加密和自定义协议分发保留足够时间。
		await clock.advanceBy(configuration.timeoutMillis + 190)
		harness.navigate(
			`aitwebrtc://result?channel=${CHANNEL}&iv=${IV}&payload=${PAYLOAD}`)

		assert.deepEqual(await resultPromise, ['66.90.98.38'])
		await clock.advanceBy(2000)
		assert.equal(bridge.decryptCalls.length, 1)
		assert.equal(harness.closeCount, 1)
	} finally {
		harness.cleanup()
		clock.cleanup()
	}
})

test('duplicate loaded events start the hidden probe only once', async () => {
	const harness = installWebViewHarness()
	try {
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		const resultPromise = collectAndroidWebRtcIpsInBackground({
			cryptoBridge: cryptoBridge(),
			timeoutMillis: 12000
		})

		harness.trigger('loaded')
		harness.trigger('loaded')
		assert.equal(harness.evaluated.length, 1)
		harness.trigger('error')
		assert.deepEqual(await resultPromise, [])
	} finally {
		harness.cleanup()
	}
})

test('page load that consumes the delivery reserve does not start ICE', async () => {
	const clock = installFakeClock()
	const harness = installWebViewHarness()
	try {
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		const resultPromise = collectAndroidWebRtcIpsInBackground({
			cryptoBridge: cryptoBridge(),
			timeoutMillis: 12000
		})

		await clock.advanceBy(11001)
		harness.trigger('loaded')

		assert.deepEqual(await resultPromise, [])
		assert.equal(harness.evaluated.length, 0)
		assert.equal(harness.closeCount, 1)
	} finally {
		harness.cleanup()
		clock.cleanup()
	}
})

test('wrong result channel is rejected before native decryption', async () => {
	const harness = installWebViewHarness()
	try {
		const bridge = cryptoBridge()
		const diagnostics = diagnosticCollector()
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		const resultPromise = collectAndroidWebRtcIpsInBackground({
			cryptoBridge: bridge,
			diagnosticsEnabled: true,
			onDiagnostic: diagnostics.emit,
			timeoutMillis: 12000
		})

		harness.navigate(
			`aitwebrtc://result?channel=ffffffffffffffffffffffffffffffff&iv=${IV}&payload=${PAYLOAD}`)
		assert.deepEqual(await resultPromise, [])
		assert.equal(bridge.decryptCalls.length, 0)
		assert.deepEqual(
			diagnostics.events
				.filter(event => event.stage.startsWith('result_'))
				.map(event => event.stage),
			[
				'result_callback_entered',
				'result_intercepted',
				'result_url_parsed',
				'result_channel_mismatch'
			]
		)
	} finally {
		harness.cleanup()
	}
})

test('invalid encrypted values are identified before native decryption', async () => {
	const harness = installWebViewHarness()
	try {
		const bridge = cryptoBridge()
		const diagnostics = diagnosticCollector()
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		const resultPromise = collectAndroidWebRtcIpsInBackground({
			cryptoBridge: bridge,
			diagnosticsEnabled: true,
			onDiagnostic: diagnostics.emit,
			timeoutMillis: 12000
		})

		harness.navigate(
			`aitwebrtc://result?channel=${CHANNEL}&iv=short&payload=${PAYLOAD}`)
		assert.deepEqual(await resultPromise, [])
		assert.equal(bridge.decryptCalls.length, 0)
		assert.deepEqual(
			diagnostics.events
				.filter(event => [
					'result_callback_entered',
					'result_url_parsed',
					'result_channel_validated',
					'encrypted_payload_invalid'
				].includes(event.stage))
				.map(event => event.stage),
			[
				'result_callback_entered',
				'result_url_parsed',
				'result_channel_validated',
				'encrypted_payload_invalid'
			]
		)
		const invalidEvent = diagnostics.events.find(
			event => event.stage === 'encrypted_payload_invalid')
		assert.equal(invalidEvent?.ivLength, 5)
		assert.equal(invalidEvent?.payloadLength, PAYLOAD.length)
	} finally {
		harness.cleanup()
	}
})

test('native authentication failure returns no candidates', async () => {
	const harness = installWebViewHarness()
	try {
		const bridge = cryptoBridge({
			decryptPayload(options) {
				this.decryptCalls.push(options)
				return {
					success: false,
					plaintext: '',
					errorCode: 'AUTHENTICATION_FAILED'
				}
			}
		})
		const diagnostics = diagnosticCollector()
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		const resultPromise = collectAndroidWebRtcIpsInBackground({
			cryptoBridge: bridge,
			diagnosticsEnabled: true,
			onDiagnostic: diagnostics.emit,
			timeoutMillis: 12000
		})

		harness.navigate(
			`aitwebrtc://result?channel=${CHANNEL}&iv=${IV}&payload=${PAYLOAD}`)
		assert.deepEqual(await resultPromise, [])
		assert.equal(bridge.decryptCalls.length, 1)
		assert.equal(
			diagnostics.events.find(event => event.stage === 'native_decrypt_failed')?.errorCode,
			'AUTHENTICATION_FAILED'
		)
		assert.deepEqual(
			diagnostics.events
				.filter(event => [
					'native_decrypt_started',
					'native_decrypt_completed',
					'native_decrypt_failed'
				].includes(event.stage))
				.map(event => [event.stage, event.state]),
			[
				['native_decrypt_started', undefined],
				['native_decrypt_completed', 'FAILED'],
				['native_decrypt_failed', undefined]
			]
		)
	} finally {
		harness.cleanup()
	}
})

test('plaintext nonce mismatch fails closed', async () => {
	const harness = installWebViewHarness()
	try {
		const bridge = cryptoBridge({
			decryptPayload(options) {
				this.decryptCalls.push(options)
				return {
					success: true,
					plaintext: JSON.stringify({
						channelId: CHANNEL,
						nonce: '00000000000000000000000000000000',
						webRtcIps: ['66.90.98.38']
					}),
					errorCode: ''
				}
			}
		})
		const diagnostics = diagnosticCollector()
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		const resultPromise = collectAndroidWebRtcIpsInBackground({
			cryptoBridge: bridge,
			diagnosticsEnabled: true,
			onDiagnostic: diagnostics.emit,
			timeoutMillis: 12000
		})

		harness.navigate(
			`aitwebrtc://result?channel=${CHANNEL}&iv=${IV}&payload=${PAYLOAD}`)
		assert.deepEqual(await resultPromise, [])
		assert.deepEqual(
			diagnostics.events
				.filter(event => [
					'native_decrypt_completed',
					'plaintext_parsed',
					'result_identity_mismatch'
				].includes(event.stage))
				.map(event => event.stage),
			[
				'native_decrypt_completed',
				'plaintext_parsed',
				'result_identity_mismatch'
			]
		)
	} finally {
		harness.cleanup()
	}
})

test('hidden WebView error closes the probe and returns no candidates', async () => {
	const harness = installWebViewHarness()
	try {
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		const resultPromise = collectAndroidWebRtcIpsInBackground({
			cryptoBridge: cryptoBridge(),
			timeoutMillis: 12000
		})

		harness.trigger('error')
		assert.deepEqual(await resultPromise, [])
		assert.equal(harness.closeCount, 1)
	} finally {
		harness.cleanup()
	}
})

test('candidate output is bounded and duplicate callbacks cannot complete twice', async () => {
	const harness = installWebViewHarness()
	try {
		const candidates = Array.from({ length: 10 }, (_value, index) => `66.90.98.${index + 1}`)
		const bridge = cryptoBridge({
			decryptPayload(options) {
				this.decryptCalls.push(options)
				return {
					success: true,
					plaintext: JSON.stringify({
						channelId: CHANNEL,
						nonce: NONCE,
						webRtcIps: [...candidates, 'x'.repeat(65), 123]
					}),
					errorCode: ''
				}
			}
		})
		const diagnostics = diagnosticCollector()
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		const resultPromise = collectAndroidWebRtcIpsInBackground({
			cryptoBridge: bridge,
			diagnosticsEnabled: true,
			onDiagnostic: diagnostics.emit,
			timeoutMillis: 12000
		})
		const url = `aitwebrtc://result?channel=${CHANNEL}&iv=${IV}&payload=${PAYLOAD}`

		harness.navigate(url)
		harness.navigate(url)
		assert.deepEqual(await resultPromise, candidates.slice(0, 8))
		assert.equal(bridge.decryptCalls.length, 1)
		assert.equal(harness.closeCount, 1)
		assert.deepEqual(
			diagnostics.events
				.filter(event => event.stage === 'result_callback_entered')
				.map(event => event.state),
			['ACTIVE', 'DUPLICATE']
		)
		assert.equal(
			diagnostics.events.filter(event => event.stage === 'result_callback_ignored').length,
			1
		)
	} finally {
		harness.cleanup()
	}
})

test('probe timeout closes the hidden WebView and returns no candidates', async () => {
	const clock = installFakeClock()
	const harness = installWebViewHarness()
	try {
		const diagnostics = diagnosticCollector()
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		let result
		void collectAndroidWebRtcIpsInBackground({
			cryptoBridge: cryptoBridge(),
			diagnosticsEnabled: true,
			onDiagnostic: diagnostics.emit,
			timeoutMillis: 12000
		}).then(value => { result = value })

		await clock.advanceBy(11999)
		assert.equal(result, undefined)
		await clock.advanceBy(1)
		assert.deepEqual(result, [])
		assert.equal(harness.closeCount, 1)
		assert.equal(
			diagnostics.events.filter(event => event.stage === 'parent_timeout').length,
			1
		)
		assert.equal(
			diagnostics.events.find(event => event.stage === 'probe_finished')?.reason,
			'parent_timeout'
		)
		assert.equal(
			diagnostics.events.some(event => event.stage === 'result_callback_entered'),
			false
		)
	} finally {
		harness.cleanup()
		clock.cleanup()
	}
})

test('diagnostic callback failures never change a successful probe result', async () => {
	const harness = installWebViewHarness()
	try {
		const { collectAndroidWebRtcIpsInBackground } = await probeModule()
		const resultPromise = collectAndroidWebRtcIpsInBackground({
			cryptoBridge: cryptoBridge(),
			diagnosticsEnabled: true,
			onDiagnostic() {
				throw new Error('diagnostic sink failed')
			},
			timeoutMillis: 12000
		})

		harness.navigate(
			`aitwebrtc://result?channel=${CHANNEL}&iv=${IV}&payload=${PAYLOAD}`)
		assert.deepEqual(await resultPromise, ['66.90.98.38'])
		assert.equal(harness.closeCount, 1)
	} finally {
		harness.cleanup()
	}
})

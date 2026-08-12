const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const RECORDER_SOURCE_PATH = path.resolve(__dirname, 'voice-recorder.js')
const NATIVE_BRIDGE_KEY = '__aitVoiceRecorderNativeTestBridge'
const BASE64_DECODER_KEY = '__aitVoiceRecorderBase64Decoder'

function moduleSourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadVoiceRecorderModule() {
	let source = fs.readFileSync(RECORDER_SOURCE_PATH, 'utf8')
	source = source.replace(
		"import { createH5VoiceRecorder } from './voice-recorder-h5.js'",
		"const createH5VoiceRecorder = () => ({ platform: 'H5' })"
	)
	source = source.replace(
		/import \{\s*requestRecordPermission,\s*startRecording,\s*renewRecordingLease,\s*stopRecording\s*\} from '@\/uni_modules\/ait-voice-recorder'/,
		`const requestRecordPermission = options =>
			globalThis.${NATIVE_BRIDGE_KEY}.requestRecordPermission(options)
		const startRecording = options =>
			globalThis.${NATIVE_BRIDGE_KEY}.startRecording(options)
		const renewRecordingLease = recordingId =>
			globalThis.${NATIVE_BRIDGE_KEY}.renewRecordingLease(recordingId)
		const stopRecording = recordingId =>
			globalThis.${NATIVE_BRIDGE_KEY}.stopRecording(recordingId)`
	)
	source = source.replace(
		'uni.base64ToArrayBuffer(payloadBase64)',
		`globalThis.${BASE64_DECODER_KEY}(payloadBase64)`
	)

	return import(`${moduleSourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function base64Frame(bytes) {
	return Buffer.from(bytes).toString('base64')
}

function defaultBase64Decoder(payload) {
	const decoded = Buffer.from(payload, 'base64')
	return Uint8Array.from(decoded).buffer
}

function installBridge(harness, decoder = defaultBase64Decoder) {
	globalThis[NATIVE_BRIDGE_KEY] = harness.api
	globalThis[BASE64_DECODER_KEY] = decoder
}

function uninstallBridge() {
	delete globalThis[NATIVE_BRIDGE_KEY]
	delete globalThis[BASE64_DECODER_KEY]
}

function createNativeHarness(onStartRecording = () => {}, behavior = {}) {
	let recordingOptions = null
	const recordingOptionsHistory = []
	const recordingIds = []
	const renewalIds = []
	const stopIds = []
	let nextRecordingId = 1
	let activeRecordingId = 0
	let stopCalls = 0
	let renewLeaseCalls = 0

	const defaultRenewLease = recordingId => {
		if (recordingId !== activeRecordingId) return false
		renewLeaseCalls += 1
		return true
	}
	const defaultStop = recordingId => {
		if (recordingId !== activeRecordingId) return false
		stopCalls += 1
		activeRecordingId = 0
		const index = recordingIds.indexOf(recordingId)
		recordingOptionsHistory[index]?.onStopped()
		return true
	}

	return {
		api: {
			requestRecordPermission(options) {
				options.onGranted()
			},
			startRecording(options) {
				recordingOptions = options
				recordingOptionsHistory.push(options)
				const proposedRecordingId = nextRecordingId
				nextRecordingId += 1
				const recordingId = typeof behavior.startResult === 'function'
					? behavior.startResult(options, proposedRecordingId)
					: proposedRecordingId
				recordingIds.push(recordingId)
				if (Number.isSafeInteger(recordingId) && recordingId > 0) {
					activeRecordingId = recordingId
				}
				onStartRecording(options, recordingId)
				return recordingId
			},
			renewRecordingLease(recordingId) {
				renewalIds.push(recordingId)
				return typeof behavior.renewLease === 'function'
					? behavior.renewLease(recordingId, defaultRenewLease)
					: defaultRenewLease(recordingId)
			},
			stopRecording(recordingId) {
				stopIds.push(recordingId)
				return typeof behavior.stop === 'function'
					? behavior.stop(recordingId, defaultStop)
					: defaultStop(recordingId)
			}
		},
		get recordingOptions() {
			return recordingOptions
		},
		get recordingOptionsHistory() {
			return recordingOptionsHistory
		},
		get stopCalls() {
			return stopCalls
		},
		get renewLeaseCalls() {
			return renewLeaseCalls
		},
		get recordingIds() {
			return recordingIds
		},
		get renewalIds() {
			return renewalIds
		},
		get stopIds() {
			return stopIds
		},
		get activeRecordingId() {
			return activeRecordingId
		}
	}
}

function captureConsole() {
	const logs = []
	const warnings = []
	const previousLog = console.log
	const previousWarn = console.warn
	console.log = message => logs.push(String(message))
	console.warn = message => warnings.push(String(message))
	return {
		logs,
		warnings,
		restore() {
			console.log = previousLog
			console.warn = previousWarn
		}
	}
}

async function createStartedRecorder(harness, onFrame, onRuntimeError = () => {}) {
	installBridge(harness)
	const { createVoiceRecorder } = await loadVoiceRecorderModule()
	const recorder = createVoiceRecorder()
	await recorder.requestPermission()
	const started = recorder.start(onFrame, onRuntimeError)
	harness.recordingOptions.onStarted()
	await started
	return recorder
}

test('Android recorder decodes a 3200-byte Base64 PCM16 frame into JS-owned memory', async () => {
	const harness = createNativeHarness()
	const delivered = []
	const original = new Uint8Array(3200)
	for (let index = 0; index < original.length; index += 1) {
		original[index] = index % 251
	}
	const expected = Uint8Array.from(original)

	try {
		const recorder = await createStartedRecorder(harness, frame => delivered.push(frame))
		harness.recordingOptions.onFrame(base64Frame(original), original.byteLength, 1)

		assert.equal(delivered.length, 1)
		assert.ok(delivered[0] instanceof ArrayBuffer)
		assert.equal(delivered[0].byteLength, 3200)
		assert.deepEqual(new Uint8Array(delivered[0]), expected)

		original.fill(0)
		assert.deepEqual(new Uint8Array(delivered[0]), expected)
		assert.equal(harness.renewLeaseCalls >= 1, true)
		assert.equal(harness.renewalIds[0], harness.recordingIds[0])
		await recorder.destroy()
	} finally {
		uninstallBridge()
	}
})

for (const bytes of [
	Uint8Array.from([0x34, 0x12]),
	Uint8Array.from([0x34, 0x12, 0x78, 0x56])
]) {
	test(`Android recorder accepts a complete ${bytes.byteLength}-byte PCM16 frame`, async () => {
		const harness = createNativeHarness()
		const delivered = []

		try {
			const recorder = await createStartedRecorder(harness, frame => delivered.push(frame))
			harness.recordingOptions.onFrame(base64Frame(bytes), bytes.byteLength, 1)

			assert.equal(delivered.length, 1)
			assert.deepEqual(Array.from(new Uint8Array(delivered[0])), Array.from(bytes))
			await recorder.destroy()
		} finally {
			uninstallBridge()
		}
	})
}

const oversizedBytes = new Uint8Array(3202)
const invalidCases = [
	{ name: 'empty payload', payload: '', declared: 2, reason: 'FRAME_PAYLOAD_INVALID' },
	{ name: 'invalid alphabet', payload: '!!!!', declared: 2, reason: 'FRAME_BASE64_SHAPE_INVALID' },
	{ name: 'payload with a newline', payload: 'NBI=\n', declared: 2, reason: 'FRAME_BASE64_SHAPE_INVALID' },
	{ name: 'URL-safe payload', payload: 'NB_-', declared: 2, reason: 'FRAME_BASE64_SHAPE_INVALID' },
	{ name: 'zero declared length', payload: 'NBI=', declared: 0, reason: 'FRAME_EMPTY' },
	{ name: 'odd declared length', payload: 'AAAA', declared: 3, reason: 'FRAME_ODD_LENGTH' },
	{
		name: 'oversized declared length',
		payload: base64Frame(oversizedBytes),
		declared: oversizedBytes.byteLength,
		reason: 'FRAME_TOO_LARGE'
	},
	{ name: 'negative declared length', payload: 'NBI=', declared: -1, reason: 'FRAME_DECLARED_LENGTH_INVALID' },
	{ name: 'fractional declared length', payload: 'NBI=', declared: 2.5, reason: 'FRAME_DECLARED_LENGTH_INVALID' },
	{
		name: 'overlong Base64 payload',
		payload: `${base64Frame(new Uint8Array(3200))}AAAA`,
		declared: 3200,
		reason: 'FRAME_BASE64_SHAPE_INVALID'
	},
	{
		name: 'decoded length mismatch',
		payload: base64Frame(new Uint8Array(6)),
		declared: 4,
		reason: 'FRAME_LENGTH_MISMATCH'
	}
]

for (const invalidCase of invalidCases) {
	test(`Android recorder rejects ${invalidCase.name} once and stops native capture`, async () => {
		const harness = createNativeHarness()
		const delivered = []
		const failures = []
		const warnings = []
		const previousWarn = console.warn
		console.warn = message => warnings.push(String(message))

		try {
			const recorder = await createStartedRecorder(
				harness,
				frame => delivered.push(frame),
				error => failures.push(error)
			)
			harness.recordingOptions.onFrame(invalidCase.payload, invalidCase.declared, 1)
			harness.recordingOptions.onFrame(invalidCase.payload, invalidCase.declared, 2)

			assert.equal(delivered.length, 0)
			assert.equal(failures.length, 1)
			assert.equal(failures[0].code, 'VOICE_AUDIO_BRIDGE_INVALID')
			const rejectionLogs = warnings.filter(value =>
				value.includes('event=voice_android_pcm_bridge phase=REJECTED'))
			assert.equal(rejectionLogs.length, 1)
			assert.match(rejectionLogs[0], new RegExp(`reason=${invalidCase.reason}`))
			assert.match(rejectionLogs[0], /declaredBytes=-?\d+(?:\.\d+)? decodedBytes=-?\d+/)
			assert.equal(harness.stopCalls, 1)
			await recorder.destroy()
			assert.equal(harness.stopCalls, 1)
		} finally {
			console.warn = previousWarn
			uninstallBridge()
		}
	})
}

test('Android recorder reports Base64 decoder failures without forwarding audio', async () => {
	const harness = createNativeHarness()
	const delivered = []
	const failures = []
	const warnings = []
	const previousWarn = console.warn
	console.warn = message => warnings.push(String(message))
	installBridge(harness, () => { throw new TypeError('decoder unavailable') })

	try {
		const { createVoiceRecorder } = await loadVoiceRecorderModule()
		const recorder = createVoiceRecorder()
		await recorder.requestPermission()
		const started = recorder.start(
			frame => delivered.push(frame),
			error => failures.push(error)
		)
		harness.recordingOptions.onStarted()
		await started
		harness.recordingOptions.onFrame('NBI=', 2, 1)

		assert.equal(delivered.length, 0)
		assert.equal(failures.length, 1)
		assert.equal(failures[0].frameReason, 'FRAME_DECODE_FAILED')
		assert.equal(harness.stopCalls, 1)
		await recorder.destroy()
	} finally {
		console.warn = previousWarn
		uninstallBridge()
	}
})

test('Android recorder rejects start when an invalid frame arrives before startup completes', async () => {
	const harness = createNativeHarness(options => {
		options.onFrame('', 2, 1)
	})
	const delivered = []
	const runtimeFailures = []
	const warnings = []
	const previousWarn = console.warn
	console.warn = message => warnings.push(String(message))
	installBridge(harness)

	try {
		const { createVoiceRecorder } = await loadVoiceRecorderModule()
		const recorder = createVoiceRecorder()
		await recorder.requestPermission()

		await assert.rejects(
			recorder.start(
				frame => delivered.push(frame),
				error => runtimeFailures.push(error)
			),
			error => error.code === 'VOICE_AUDIO_BRIDGE_INVALID'
		)
		assert.equal(delivered.length, 0)
		assert.equal(runtimeFailures.length, 0)
		const rejectionLogs = warnings.filter(value =>
			value.includes('event=voice_android_pcm_bridge phase=REJECTED'))
		assert.equal(rejectionLogs.length, 1)
		assert.match(rejectionLogs[0], /reason=FRAME_PAYLOAD_INVALID/)
		assert.equal(harness.stopCalls, 1)
		await recorder.destroy()
		assert.equal(harness.stopCalls, 1)
		assert.deepEqual(harness.stopIds, [harness.recordingIds[0]])
	} finally {
		console.warn = previousWarn
		uninstallBridge()
	}
})

test('Android recorder stop and destroy preserve one native stop lifecycle', async () => {
	const harness = createNativeHarness()

	try {
		const recorder = await createStartedRecorder(harness, () => {})
		await recorder.stop()
		await recorder.destroy()
		assert.equal(harness.stopCalls, 1)
		assert.deepEqual(harness.stopIds, [harness.recordingIds[0]])
	} finally {
		uninstallBridge()
	}
})

test('Android recorder stops an old session and ignores its delayed frames', async () => {
	const harness = createNativeHarness()
	const delivered = []
	installBridge(harness)

	try {
		const { createVoiceRecorder } = await loadVoiceRecorderModule()
		const recorder = createVoiceRecorder()
		await recorder.requestPermission()

		const firstStart = recorder.start(frame => delivered.push(frame))
		const firstOptions = harness.recordingOptions
		firstOptions.onStarted()
		await firstStart

		const secondStart = recorder.start(frame => delivered.push(frame))
		const secondOptions = harness.recordingOptions
		secondOptions.onStarted()
		await secondStart

		assert.equal(harness.stopCalls, 1)
		const firstRecordingId = harness.recordingIds[0]
		const secondRecordingId = harness.recordingIds[1]
		assert.deepEqual(harness.stopIds, [firstRecordingId])
		assert.equal(harness.api.renewRecordingLease(firstRecordingId), false)
		assert.equal(harness.api.stopRecording(firstRecordingId), false)
		assert.equal(harness.activeRecordingId, secondRecordingId)
		firstOptions.onFrame('NBI=', 2, 1)
		assert.equal(delivered.length, 0)
		secondOptions.onFrame('NBI=', 2, 1)
		assert.equal(delivered.length, 1)

		await recorder.destroy()
		assert.equal(harness.stopCalls, 2)
	} finally {
		uninstallBridge()
	}
})

test('Android recorder logs the healthy native bridge boundary without audio content', async () => {
	const harness = createNativeHarness()
	const captured = captureConsole()
	let recorder = null
	const bytes = new Uint8Array(3200)
	bytes.fill(0x41)
	const payload = base64Frame(bytes)

	try {
		recorder = await createStartedRecorder(harness, () => {})
		harness.recordingOptions.onFrame(payload, bytes.byteLength, 1)
		const output = [...captured.logs, ...captured.warnings].join('\n')

		for (const phase of [
			'START_CALL',
			'START_RETURNED',
			'START_CALLBACK_ENTERED',
			'LEASE_RENEW_ATTEMPT',
			'LEASE_RENEW_SUCCEEDED',
			'FRAME_CALLBACK_ENTERED',
			'JS_FRAME_DECODED'
		]) assert.match(output, new RegExp(`phase=${phase}`))
		assert.match(output, /phase=START_RETURNED[^\n]*recordingId=1 recordingIdValid=true/)
		assert.match(output, /frameSequence=1/)
		assert.match(harness.recordingOptions.diagnosticRunId, /^v[a-z0-9]+-1$/)
		assert.match(output, new RegExp(`diagnosticRunId=${harness.recordingOptions.diagnosticRunId}`))
		assert.doesNotMatch(output, new RegExp(payload))
		await recorder.destroy()
		recorder = null
	} finally {
		await recorder?.destroy?.()
		captured.restore()
		uninstallBridge()
	}
})

test('Android recorder reports a rejected top-level lease and ignores later frames after failure', async () => {
	const harness = createNativeHarness(
		() => {},
		{ renewLease: () => false })
	const captured = captureConsole()
	const failures = []
	let recorder = null

	try {
		recorder = await createStartedRecorder(
			harness,
			() => assert.fail('a frame must not pass after the lease failure'),
			error => failures.push(error))
		harness.recordingOptions.onFrame('NBI=', 2, 1)
		const output = [...captured.logs, ...captured.warnings].join('\n')

		assert.equal(failures.length, 1)
		assert.equal(failures[0].code, 'VOICE_AUDIO_BRIDGE_INVALID')
		assert.match(output, /phase=START_RETURNED[^\n]*recordingId=1 recordingIdValid=true/)
		assert.match(output, /phase=LEASE_RENEW_REJECTED[^\n]*recordingId=1/)
		assert.match(output, /phase=FRAME_CALLBACK_ENTERED/)
		assert.match(output, /phase=FRAME_CALLBACK_IGNORED[^\n]*reason=RUNTIME_FAILURE/)
		assert.doesNotMatch(output, /decoder unavailable|native lease exploded/)
		await recorder.destroy()
		recorder = null
	} finally {
		await recorder?.destroy?.()
		captured.restore()
		uninstallBridge()
	}
})

test('Android recorder reports a thrown top-level lease without exposing its message', async () => {
	const harness = createNativeHarness(
		() => {},
		{ renewLease: () => { throw new TypeError('sensitive lease failure') } })
	const captured = captureConsole()
	const failures = []
	let recorder = null

	try {
		recorder = await createStartedRecorder(
			harness,
			() => assert.fail('audio must not pass after lease invocation fails'),
			error => failures.push(error))
		const output = [...captured.logs, ...captured.warnings].join('\n')

		assert.equal(failures.length, 1)
		assert.equal(failures[0].code, 'VOICE_AUDIO_BRIDGE_INVALID')
		assert.match(output, /phase=LEASE_RENEW_FAILED[^\n]*recordingId=1 exceptionType=TypeError/)
		assert.doesNotMatch(output, /sensitive lease failure/)
	} finally {
		await recorder?.destroy?.()
		captured.restore()
		uninstallBridge()
	}
})

for (const invalidRecordingId of [
	null,
	0,
	-1,
	1.5,
	Number.MAX_SAFE_INTEGER + 1
]) {
	test(`Android recorder rejects invalid native recording id ${String(invalidRecordingId)}`, async () => {
		const harness = createNativeHarness(
			() => {},
			{ startResult: () => invalidRecordingId })
		const captured = captureConsole()
		let recorder = null

		try {
			installBridge(harness)
			const { createVoiceRecorder } = await loadVoiceRecorderModule()
			recorder = createVoiceRecorder()
			await recorder.requestPermission()
			await assert.rejects(
				recorder.start(() => {}),
				error => error.code === 'VOICE_AUDIO_BRIDGE_INVALID')
			const output = [...captured.logs, ...captured.warnings].join('\n')

			assert.match(output, /phase=START_RETURNED[^\n]*recordingIdValid=false/)
			assert.match(output, /phase=FAILURE_REPORTED[^\n]*failureSource=START_RETURN/)
			assert.equal(harness.renewLeaseCalls, 0)
			assert.equal(harness.stopCalls, 0)
		} finally {
			await recorder?.destroy?.()
			captured.restore()
			uninstallBridge()
		}
	})
}

test('Android recorder waits for a valid recording id when onStarted fires synchronously', async () => {
	const harness = createNativeHarness(options => options.onStarted())
	installBridge(harness)

	try {
		const { createVoiceRecorder } = await loadVoiceRecorderModule()
		const recorder = createVoiceRecorder()
		await recorder.requestPermission()
		await recorder.start(() => {})

		assert.equal(harness.renewLeaseCalls, 1)
		assert.deepEqual(harness.renewalIds, [harness.recordingIds[0]])
		await recorder.destroy()
	} finally {
		uninstallBridge()
	}
})

test('Android recorder logs stale callback rejection before decoding it', async () => {
	const harness = createNativeHarness()
	const captured = captureConsole()
	const delivered = []
	let recorder = null
	installBridge(harness)

	try {
		const { createVoiceRecorder } = await loadVoiceRecorderModule()
		recorder = createVoiceRecorder()
		await recorder.requestPermission()
		const firstStart = recorder.start(frame => delivered.push(frame))
		const firstOptions = harness.recordingOptions
		firstOptions.onStarted()
		await firstStart
		const secondStart = recorder.start(frame => delivered.push(frame))
		harness.recordingOptions.onStarted()
		await secondStart
		firstOptions.onFrame('NBI=', 2, 1)

		const output = [...captured.logs, ...captured.warnings].join('\n')
		assert.equal(delivered.length, 0)
		assert.match(output, /phase=FRAME_CALLBACK_IGNORED[^\n]*reason=EPOCH_MISMATCH/)
		await recorder.destroy()
		recorder = null
	} finally {
		await recorder?.destroy?.()
		captured.restore()
		uninstallBridge()
	}
})

test('Android recorder samples normal frame diagnostics and reports top-level stop failures safely', async () => {
	const harness = createNativeHarness(
		() => {},
		{ stop: () => { throw new TypeError('sensitive stop failure') } })
	const captured = captureConsole()

	try {
		const recorder = await createStartedRecorder(harness, () => {})
		for (let sequence = 1; sequence <= 51; sequence += 1) {
			harness.recordingOptions.onFrame('NBI=', 2, sequence)
		}
		await recorder.destroy()
		const output = [...captured.logs, ...captured.warnings].join('\n')
		const callbackEntries = captured.logs.filter(value => value.includes('phase=FRAME_CALLBACK_ENTERED'))

		assert.equal(callbackEntries.length, 1)
		assert.match(output, /phase=JS_FRAME_SUMMARY[^\n]*framesReceived=50/)
		assert.match(output, /phase=STOP_INVOKE_FAILED[^\n]*exceptionType=TypeError/)
		assert.doesNotMatch(output, /sensitive stop failure|NBI=/)
	} finally {
		captured.restore()
		uninstallBridge()
	}
})

test('Android recorder treats an already released top-level stop as idempotent', async () => {
	const harness = createNativeHarness(
		() => {},
		{ stop: () => false })
	const captured = captureConsole()
	const failures = []

	try {
		const recorder = await createStartedRecorder(
			harness,
			() => {},
			error => failures.push(error))
		await recorder.destroy()
		const output = [...captured.logs, ...captured.warnings].join('\n')

		assert.equal(failures.length, 0)
		assert.match(output, /phase=STOP_INVOKE_RETURNED[^\n]*recordingId=1 controlMatched=false/)
	} finally {
		captured.restore()
		uninstallBridge()
	}
})

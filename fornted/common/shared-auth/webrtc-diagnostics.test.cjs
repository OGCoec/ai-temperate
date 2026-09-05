const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const sourcePath = path.resolve(__dirname, 'webrtc-diagnostics.js')
const sourceText = fs.readFileSync(sourcePath, 'utf8')
const moduleUrl = `data:text/javascript;base64,${Buffer.from(sourceText).toString('base64')}`

async function diagnosticsModule() {
	return import(moduleUrl)
}

function captureConsole() {
	const original = {
		info: console.info,
		warn: console.warn,
		error: console.error
	}
	const entries = []
	console.info = (...args) => entries.push({ level: 'info', args })
	console.warn = (...args) => entries.push({ level: 'warn', args })
	console.error = (...args) => entries.push({ level: 'error', args })
	return {
		entries,
		cleanup() {
			console.info = original.info
			console.warn = original.warn
			console.error = original.error
		}
	}
}

test('explicitly enabled diagnostics emit one complete console argument without reading process', async () => {
	const capture = captureConsole()
	try {
		const { createWebRtcDiagnosticLogger } = await diagnosticsModule()
		const trace = createWebRtcDiagnosticLogger('user-android-parent', true)
		trace('webview_loaded', {
			probeRunId: 'user-0:1-1',
			elapsedMs: 63,
			remainingMillis: 11937,
			callbackCount: 1,
			urlLength: 192,
			ivLength: 16,
			payloadLength: 128,
			plaintextLength: 96,
			timerActive: true,
			hasChannel: true,
			hasIv: true,
			hasPayload: true,
			hostCount: 2,
			srflxCount: 2,
			acceptedHostCount: 1,
			acceptedSrflxCount: 1,
			ignoredRelayCount: 1,
			rejectedNonPublicCount: 2,
			ipv4Count: 1,
			ipv6Count: 1,
			unknownField: 'must-not-appear'
		})

		assert.equal(capture.entries.length, 1)
		assert.equal(capture.entries[0].level, 'info')
		assert.equal(capture.entries[0].args.length, 1)
		assert.match(capture.entries[0].args[0], /^\[ait-webrtc\] \{/)
		assert.deepEqual(JSON.parse(capture.entries[0].args[0].slice('[ait-webrtc] '.length)), {
			scope: 'user-android-parent',
			stage: 'webview_loaded',
			probeRunId: 'user-0:1-1',
			elapsedMs: 63,
			remainingMillis: 11937,
			callbackCount: 1,
			urlLength: 192,
			ivLength: 16,
			payloadLength: 128,
			plaintextLength: 96,
			timerActive: true,
			hasChannel: true,
			hasIv: true,
			hasPayload: true,
			hostCount: 2,
			srflxCount: 2,
			acceptedHostCount: 1,
			acceptedSrflxCount: 1,
			ignoredRelayCount: 1,
			rejectedNonPublicCount: 2,
			ipv4Count: 1,
			ipv6Count: 1
		})
		assert.doesNotMatch(sourceText, /typeof process|process\.env/)
	} finally {
		capture.cleanup()
	}
})

test('diagnostics discard sensitive and malformed values', async () => {
	const capture = captureConsole()
	try {
		const { createWebRtcDiagnosticLogger } = await diagnosticsModule()
		const trace = createWebRtcDiagnosticLogger('user-flow', true)
		trace('report_failed', {
			errorCode: 'https://example.test/203.10.97.121',
			reason: '203.10.97.121',
			sourceIndexes: [1, 3, 9, '2'],
			candidateCount: Number.NaN,
			webRtcStatus: false,
			url: 'aitwebrtc://result?payload=secret',
			payload: 'secret',
			candidate: 'candidate:raw'
		})

		const serialized = capture.entries[0].args[0]
		const event = JSON.parse(serialized.slice('[ait-webrtc] '.length))
		assert.deepEqual(event, {
			scope: 'user-flow',
			stage: 'report_failed',
			sourceIndexes: [1, 3],
			webRtcStatus: false
		})
		assert.doesNotMatch(serialized, /203\.10\.97\.121|aitwebrtc|payload|candidate:raw|secret/)
	} finally {
		capture.cleanup()
	}
})

test('disabled diagnostics do not access the console', async () => {
	const capture = captureConsole()
	try {
		const { createWebRtcDiagnosticLogger } = await diagnosticsModule()
		const trace = createWebRtcDiagnosticLogger('admin-flow', false)
		trace('verification_succeeded', { candidateCount: 1 })
		assert.deepEqual(capture.entries, [])
	} finally {
		capture.cleanup()
	}
})

test('controlled failures use warning level and unexpected failures use error level', async () => {
	const capture = captureConsole()
	try {
		const { createWebRtcDiagnosticLogger } = await diagnosticsModule()
		const trace = createWebRtcDiagnosticLogger('admin-android-parent', true)
		trace('parent_timeout', { reason: 'parent_timeout' })
		trace('webview_setup_failed', { errorCode: 'WEBVIEW_SETUP_FAILED' })
		assert.deepEqual(capture.entries.map(entry => entry.level), ['warn', 'error'])
	} finally {
		capture.cleanup()
	}
})

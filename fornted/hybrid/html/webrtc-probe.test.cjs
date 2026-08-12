const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const sourcePath = path.resolve(__dirname, 'webrtc-probe.js')
const sourceText = fs.readFileSync(sourcePath, 'utf8')
const adminSourcePath = path.resolve(
	__dirname,
	'../../../myuniappadmin/hybrid/html/webrtc-probe.js')

const CHANNEL = '0123456789abcdef0123456789abcdef'
const NONCE = 'abcdef0123456789abcdef0123456789'
const KEY = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
const STUN_URLS = [
	'stun:stun.cloudflare.com:3478',
	'stun:global.stun.twilio.com:3478'
]

function base64Encode(value) {
	return Buffer.from(value, 'binary').toString('base64')
}

function base64Decode(value) {
	return Buffer.from(value, 'base64').toString('binary')
}

function createHarness(options = {}) {
	const entries = []
	const timers = new Map()
	let nextTimerId = 1
	let connection = null

	class FakePeerConnection {
		constructor(configuration) {
			this.configuration = configuration
			this.listeners = new Map()
			this.iceGatheringState = 'gathering'
			connection = this
		}

		addEventListener(name, handler) {
			this.listeners.set(name, handler)
		}

		removeEventListener(name) {
			this.listeners.delete(name)
		}

		createDataChannel() {}

		createOffer() {
			if (options.offerFailure) return Promise.reject(new Error('offer failed'))
			return Promise.resolve({ type: 'offer', sdp: '' })
		}

		setLocalDescription() {
			for (const candidate of options.candidates || []) {
				this.listeners.get('icecandidate')?.({ candidate })
			}
			if (options.finishWithNullCandidate !== false) {
				this.listeners.get('icecandidate')?.({ candidate: null })
			}
			return Promise.resolve()
		}

		close() {}
	}

	const crypto = options.cryptoAvailable === false ? undefined : {
		getRandomValues(bytes) {
			bytes.fill(7)
			return bytes
		},
		subtle: {
			importKey() {
				return Promise.resolve({})
			},
			encrypt() {
				return Promise.resolve(new Uint8Array(32).buffer)
			}
		}
	}

	const window = {
		crypto,
		location: { href: '' }
	}
	if (options.peerConnectionAvailable !== false) {
		window.RTCPeerConnection = FakePeerConnection
	}

	const context = {
		window,
		console: {
			info(...args) { entries.push({ level: 'info', args }) },
			warn(...args) { entries.push({ level: 'warn', args }) },
			error(...args) { entries.push({ level: 'error', args }) }
		},
		TextEncoder,
		Uint8Array,
		Promise,
		Number,
		Object,
		Array,
		String,
		RegExp,
		JSON,
		Math,
		Date,
		btoa: base64Encode,
		atob: base64Decode,
		setTimeout(callback) {
			const timerId = nextTimerId++
			timers.set(timerId, callback)
			return timerId
		},
		clearTimeout(timerId) {
			timers.delete(timerId)
		}
	}
	vm.runInNewContext(sourceText, context, { filename: sourcePath })

	return {
		entries,
		window,
		get connection() { return connection },
		start() {
			window.startWebRtcProbe({
				channelId: CHANNEL,
				nonce: NONCE,
				key: KEY,
				stunUrls: STUN_URLS,
				timeoutMillis: 11000,
				diagnosticsEnabled: true,
				probeRunId: 'user-0:1-1'
			})
		},
		fireFirstTimer() {
			const first = timers.entries().next().value
			if (!first) return false
			timers.delete(first[0])
			first[1]()
			return true
		},
		events() {
			return entries.map(entry => ({
				level: entry.level,
				prefix: entry.args[0],
				...JSON.parse(entry.args[1])
			}))
		}
	}
}

async function flushMicrotasks(count = 12) {
	for (let index = 0; index < count; index++) await Promise.resolve()
}

test('hidden probe aggregates candidate metadata without logging candidate addresses', async () => {
	const harness = createHarness({
		candidates: [
			{
				type: 'host',
				address: '192.168.1.20',
				candidate: 'candidate:host typ host'
			},
			{
				type: 'srflx',
				address: '203.10.97.121',
				url: STUN_URLS[0],
				candidate: 'candidate:public typ srflx'
			},
			{
				type: 'srflx',
				address: '203.10.97.121',
				url: STUN_URLS[0],
				candidate: 'candidate:duplicate typ srflx'
			},
			{
				type: 'srflx',
				address: '192.168.1.20',
				candidate: 'candidate:private typ srflx'
			}
		]
	})

	harness.start()
	await flushMicrotasks()

	const finished = harness.events().find(event => event.stage === 'ice_finished')
	assert.deepEqual({
		hostCount: finished.hostCount,
		srflxCount: finished.srflxCount,
		acceptedCount: finished.acceptedCount,
		rejectedCount: finished.rejectedCount,
		ipv4Count: finished.ipv4Count,
		sourceIndexes: finished.sourceIndexes,
		reason: finished.reason
	}, {
		hostCount: 1,
		srflxCount: 3,
		acceptedCount: 1,
		rejectedCount: 1,
		ipv4Count: 1,
		sourceIndexes: [1],
		reason: 'null_candidate'
	})
	assert.equal(
		harness.events().find(event => event.stage === 'result_dispatched')?.candidateCount,
		1
	)
	assert.match(harness.window.location.href, /^aitwebrtc:\/\/result\?/)
	assert.doesNotMatch(
		harness.entries.map(entry => entry.args.join(' ')).join('\n'),
		/203\.10\.97\.121|192\.168\.1\.20|candidate:public|stun\.cloudflare/
	)
})

test('hidden probe reports a real ICE timeout separately from an empty completed result', async () => {
	const harness = createHarness({ finishWithNullCandidate: false })
	harness.start()
	await flushMicrotasks()
	assert.equal(harness.fireFirstTimer(), true)
	await flushMicrotasks()

	const timeout = harness.events().find(event => event.stage === 'ice_timeout')
	assert.equal(timeout?.reason, 'timeout')
	assert.equal(timeout?.acceptedCount, 0)
	assert.equal(
		harness.events().find(event => event.stage === 'result_dispatched')?.candidateCount,
		0
	)
})

test('hidden probe reports unavailable peer connection and unavailable crypto safely', async () => {
	const noPeerConnection = createHarness({ peerConnectionAvailable: false })
	noPeerConnection.start()
	await flushMicrotasks()
	assert.equal(
		noPeerConnection.events().some(event => event.stage === 'peer_connection_unavailable'),
		true
	)

	const noCrypto = createHarness({ cryptoAvailable: false })
	noCrypto.start()
	await flushMicrotasks()
	assert.equal(
		noCrypto.events().some(event => event.stage === 'encryption_failed'),
		true
	)
	assert.match(noCrypto.window.location.href, /[?&]error=crypto_unavailable$/)
})

test('ordinary and administrator hidden probes remain byte-identical', () => {
	assert.equal(fs.readFileSync(adminSourcePath, 'utf8'), sourceText)
})

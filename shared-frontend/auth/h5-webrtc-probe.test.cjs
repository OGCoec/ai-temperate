const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const sourcePath = path.resolve(__dirname, 'h5-webrtc-probe.js')
const sourceText = fs.readFileSync(sourcePath, 'utf8')
	.replace(
		"import { WEBRTC_DEFAULT_TIMEOUT_MILLIS } from './webrtc-verification-core.js'",
		'const WEBRTC_DEFAULT_TIMEOUT_MILLIS = 12000')
	.replace('export function collectH5WebRtcIps', 'function collectH5WebRtcIps')
	.concat('\nglobalThis.__collectH5WebRtcIps = collectH5WebRtcIps\n')
const ordinaryAndroidProbePath = path.resolve(
	__dirname,
	'../../fornted/hybrid/html/webrtc-probe.js')
const adminAndroidProbePath = path.resolve(
	__dirname,
	'../../myuniappadmin/hybrid/html/webrtc-probe.js')

function createCollector(candidates) {
	class FakePeerConnection {
		constructor() {
			this.listeners = new Map()
			this.iceGatheringState = 'gathering'
		}

		addEventListener(name, handler) { this.listeners.set(name, handler) }
		removeEventListener(name) { this.listeners.delete(name) }
		createDataChannel() {}
		createOffer() { return Promise.resolve({ type: 'offer', sdp: '' }) }
		setLocalDescription() {
			for (const candidate of candidates) {
				this.listeners.get('icecandidate')?.({ candidate })
			}
			this.listeners.get('icecandidate')?.({ candidate: null })
			return Promise.resolve()
		}
		close() {}
	}

	const context = {
		RTCPeerConnection: FakePeerConnection,
		Promise,
		Set,
		Number,
		String,
		Array,
		Math,
		setTimeout,
		clearTimeout
	}
	vm.runInNewContext(sourceText, context, { filename: sourcePath })
	return context.__collectH5WebRtcIps
}

test('H5 probe keeps public IPv4 and IPv6 host/srflx candidates and reports safe counters', async () => {
	const diagnostics = []
	const collect = createCollector([
		{ type: 'srflx', address: '203.10.97.121', candidate: 'candidate:1 1 udp 1 203.10.97.121 3478 typ srflx' },
		{ type: 'host', address: '[240e:37a:3cf1:bd00:6433:70b6:cbcb:c2eb]', candidate: 'candidate:2 1 udp 1 240e:37a:3cf1:bd00:6433:70b6:cbcb:c2eb 3478 typ host' },
		{ type: 'srflx', address: '::ffff:203.10.97.121', candidate: 'candidate:3 1 udp 1 ::ffff:203.10.97.121 3478 typ srflx' },
		{ type: 'host', address: '10.0.0.2', candidate: 'candidate:4 1 udp 1 10.0.0.2 3478 typ host' },
		{ type: 'host', address: 'fe80::1', candidate: 'candidate:5 1 udp 1 fe80::1 3478 typ host' },
		{ type: 'host', address: 'device.local', candidate: 'candidate:6 1 udp 1 device.local 3478 typ host' },
		{ type: 'relay', address: '198.51.100.30', candidate: 'candidate:7 1 udp 1 198.51.100.30 3478 typ relay' },
		{ type: 'prflx', address: '198.51.100.31', candidate: 'candidate:8 1 udp 1 198.51.100.31 3478 typ prflx' }
	])

	const result = await collect([], 1000, (stage, fields) => diagnostics.push({ stage, ...fields }))

	assert.deepEqual(Array.from(result), [
		'203.10.97.121',
		'240e:37a:3cf1:bd00:6433:70b6:cbcb:c2eb'
	])
	assert.deepEqual(diagnostics.find(event => event.stage === 'ice_finished'), {
		stage: 'ice_finished',
		reason: 'null_candidate',
		hostCount: 4,
		srflxCount: 2,
		acceptedCount: 2,
		acceptedHostCount: 1,
		acceptedSrflxCount: 1,
		ignoredRelayCount: 1,
		rejectedNonPublicCount: 3,
		ipv4Count: 1,
		ipv6Count: 1
	})
})

test('H5 probe parses the SDP address field, normalizes IPv6, and rejects unsafe syntax', async () => {
	const collect = createCollector([
		{ type: 'host', candidate: 'candidate:1 1 udp 1 240e:37a:3cf1:bd00:0:0:0:1 3478 typ host' },
		{ type: 'host', address: '240e:37a:3cf1:bd00::1', candidate: '' },
		{ type: 'host', address: '[240e:37a:3cf1:bd00::2]', candidate: '' },
		{ type: 'srflx', address: '[::ffff:198.51.101.1]', candidate: '' },
		{ type: 'host', address: '[240e:37a:3cf1:bd00::3]:443', candidate: '' },
		{ type: 'host', address: '240e:37a:3cf1:bd00::4%wlan0', candidate: '' },
		{ type: 'host', address: ' 240e:37a:3cf1:bd00::5', candidate: '' },
		{ type: 'host', address: '::203.10.97.121', candidate: '' },
		...Array.from({ length: 5 }, (_, index) => ({
			type: 'srflx',
			address: `198.51.101.${index + 1}`,
			candidate: ''
		}))
	])

	const result = await collect([], 1000)

	assert.equal(result.length, 7)
	assert.equal(result.filter(value => value === '240e:37a:3cf1:bd00::1').length, 1)
	assert.equal(result.includes('240e:37a:3cf1:bd00::2'), true)
	assert.equal(result.some(value => value.includes('%') || value.includes('[')), false)
})

test('H5 probe reports at most eight stable unique addresses', async () => {
	const collect = createCollector(Array.from({ length: 10 }, (_, index) => ({
		type: 'srflx',
		address: `198.51.101.${index + 1}`,
		candidate: ''
	})))

	const result = await collect([], 1000)

	assert.equal(result.length, 8)
	assert.deepEqual(Array.from(result), [
		'198.51.101.1',
		'198.51.101.10',
		'198.51.101.2',
		'198.51.101.3',
		'198.51.101.4',
		'198.51.101.5',
		'198.51.101.6',
		'198.51.101.7'
	])
})

test('ordinary and administrator Android hidden probes stay byte-identical', () => {
	assert.equal(
		fs.readFileSync(ordinaryAndroidProbePath, 'utf8'),
		fs.readFileSync(adminAndroidProbePath, 'utf8'))
})

test('H5 abort closes RTCPeerConnection and rejects instead of returning zero candidates', async () => {
	const connections = []
	class PendingPeerConnection {
		constructor() {
			this.listeners = new Map()
			this.closed = false
			connections.push(this)
		}

		addEventListener(name, handler) { this.listeners.set(name, handler) }
		removeEventListener(name) { this.listeners.delete(name) }
		createDataChannel() {}
		createOffer() { return Promise.resolve({ type: 'offer', sdp: '' }) }
		setLocalDescription() { return Promise.resolve() }
		close() { this.closed = true }
	}
	const context = {
		RTCPeerConnection: PendingPeerConnection,
		Promise,
		Set,
		Number,
		String,
		Array,
		Math,
		Error,
		setTimeout,
		clearTimeout
	}
	vm.runInNewContext(sourceText, context, { filename: sourcePath })
	const controller = new AbortController()
	const resultPromise = context.__collectH5WebRtcIps(
		[],
		1000,
		undefined,
		controller.signal)

	controller.abort('DOCUMENT_UNLOADED')

	await assert.rejects(resultPromise, error => {
		assert.equal(error.code, 'WEBRTC_ATTEMPT_ABORTED')
		assert.equal(error.cancelReason, 'DOCUMENT_UNLOADED')
		return true
	})
	assert.equal(connections.length, 1)
	assert.equal(connections[0].closed, true)
})

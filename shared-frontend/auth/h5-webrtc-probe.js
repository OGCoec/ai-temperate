import { WEBRTC_DEFAULT_TIMEOUT_MILLIS } from './webrtc-verification-core.js'

/**
 * 只在 H5 浏览器中通过 RTCPeerConnection 收集公网服务器反射候选地址。
 */
export function collectH5WebRtcIps(
	stunUrls,
	timeoutMillis = WEBRTC_DEFAULT_TIMEOUT_MILLIS,
	diagnosticTrace
) {
	return new Promise(resolve => {
		const PeerConnection = typeof globalThis !== 'undefined'
			? globalThis.RTCPeerConnection
				|| globalThis.webkitRTCPeerConnection
			: null
		if (typeof PeerConnection !== 'function') {
			trace(diagnosticTrace, 'ice_finished', {
				reason: 'peer_connection_unavailable',
				hostCount: 0,
				srflxCount: 0,
				acceptedCount: 0,
				acceptedHostCount: 0,
				acceptedSrflxCount: 0,
				ignoredRelayCount: 0,
				rejectedNonPublicCount: 0,
				ipv4Count: 0,
				ipv6Count: 0
			})
			resolve([])
			return
		}

		const addresses = new Set()
		const acceptedTypes = new Map()
		const stats = createCandidateStats()
		let connection
		let timer
		let settled = false

		const finish = (reason = 'completed') => {
			if (settled) return
			settled = true
			if (timer) clearTimeout(timer)
			if (connection) {
				connection.removeEventListener('icecandidate', onCandidate)
				connection.removeEventListener('icegatheringstatechange', onGatheringState)
				connection.close()
			}
			const values = stableIpOrder([...addresses]).slice(0, 8)
			const families = candidateFamilyCounts(values)
			trace(diagnosticTrace, 'ice_finished', {
				reason,
				hostCount: stats.hostCount,
				srflxCount: stats.srflxCount,
				acceptedCount: values.length,
				acceptedHostCount: countAcceptedTypes(values, acceptedTypes, 'host'),
				acceptedSrflxCount: countAcceptedTypes(values, acceptedTypes, 'srflx'),
				ignoredRelayCount: stats.ignoredRelayCount,
				rejectedNonPublicCount: stats.rejectedNonPublicCount,
				ipv4Count: families.ipv4Count,
				ipv6Count: families.ipv6Count
			})
			resolve(values)
		}

		const onCandidate = event => {
			const candidate = event?.candidate
			if (!candidate) {
				finish('null_candidate')
				return
			}
			const candidateType = String(
				candidate.type || candidateTypeFromLine(candidate.candidate)
			).toLowerCase()
			incrementCandidateType(stats, candidateType)
			if (candidateType === 'relay') {
				stats.ignoredRelayCount += 1
				return
			}
			if (candidateType !== 'host' && candidateType !== 'srflx') return
			const address = candidate.address || candidateAddressFromLine(candidate.candidate)
			const normalized = normalizePublicCandidate(address)
			if (!normalized) {
				stats.rejectedNonPublicCount += 1
				return
			}
			if (!addresses.has(normalized)) {
				addresses.add(normalized)
				acceptedTypes.set(normalized, candidateType)
			}
		}

		const onGatheringState = () => {
			if (connection?.iceGatheringState === 'complete') finish('gathering_complete')
		}

		try {
			connection = new PeerConnection({
				iceServers: (Array.isArray(stunUrls) ? stunUrls : [])
					.map(url => ({ urls: String(url) }))
			})
			connection.addEventListener('icecandidate', onCandidate)
			connection.addEventListener('icegatheringstatechange', onGatheringState)
			connection.createDataChannel('ait-webrtc-probe')
			timer = setTimeout(() => finish('timeout'), boundedTimeout(timeoutMillis))
			Promise.resolve(connection.createOffer())
				.then(offer => connection.setLocalDescription(offer))
				.catch(() => finish('offer_failed'))
		} catch (_) {
			finish('constructor_failed')
		}
	})
}

function boundedTimeout(value) {
	const numeric = Number(value)
	return Number.isFinite(numeric) && numeric > 0
		? Math.min(numeric, WEBRTC_DEFAULT_TIMEOUT_MILLIS)
		: WEBRTC_DEFAULT_TIMEOUT_MILLIS
}

function candidateTypeFromLine(line) {
	const parts = String(line || '').trim().split(/\s+/)
	const index = parts.indexOf('typ')
	return index >= 0 ? parts[index + 1] || '' : ''
}

function candidateAddressFromLine(line) {
	const parts = String(line || '').trim().split(/\s+/)
	return parts.length > 4 ? parts[4] : ''
}

function normalizePublicCandidate(value) {
	if (typeof value !== 'string') return ''
	if (value !== value.trim() || /[%/\s]/.test(value)) return ''
	let candidate = value.toLowerCase()
	if (/^\[[0-9a-f:.]+\]$/.test(candidate)) candidate = candidate.slice(1, -1)
	if (!candidate || candidate.length > 64 || /[\[\]]/.test(candidate)) return ''
	if (candidate.endsWith('.local') || candidate.includes('.local.')) return ''
	if (candidate.includes(':')) {
		return normalizePublicIpv6(candidate)
	}
	return normalizePublicIpv4(candidate)
}

function normalizePublicIpv4(value) {
	const parts = String(value).split('.')
	if (parts.length !== 4) return ''
	const octets = parts.map(part => {
		if (!/^\d{1,3}$/.test(part) || (part.length > 1 && part.startsWith('0'))) return NaN
		return Number(part)
	})
	if (octets.some(value => !Number.isInteger(value) || value < 0 || value > 255)) return ''
	const [first, second, third, fourth] = octets
	if (first === 0 || first === 10 || first === 127 || first >= 224) return ''
	if (first === 100 && second >= 64 && second <= 127) return ''
	if (first === 169 && second === 254) return ''
	if (first === 172 && second >= 16 && second <= 31) return ''
	if (first === 192 && second === 168) return ''
	if (first === 192 && second === 0 && third === 0
		&& fourth !== 9 && fourth !== 10) return ''
	if (first === 192 && second === 0 && third === 2) return ''
	if (first === 192 && second === 88 && third === 99) return ''
	if (first === 198 && (second === 18 || second === 19)) return ''
	if (first === 198 && second === 51 && third === 100) return ''
	if (first === 203 && second === 0 && third === 113) return ''
	return octets.join('.')
}

function normalizePublicIpv6(value) {
	let candidate = value
	if (candidate.includes('.')) {
		const separator = candidate.lastIndexOf(':')
		if (separator < 0) return ''
		const ipv4 = normalizeIpv4Syntax(candidate.slice(separator + 1))
		if (!ipv4) return ''
		const octets = ipv4.split('.').map(Number)
		candidate = `${candidate.slice(0, separator)}:${((octets[0] << 8) | octets[1]).toString(16)}:${((octets[2] << 8) | octets[3]).toString(16)}`
	}
	const groups = expandIpv6(candidate)
	if (!groups) return ''
	const mapped = groups.slice(0, 5).every(group => group === 0)
		&& groups[5] === 0xffff
	if (mapped) {
		return normalizePublicIpv4([
			groups[6] >> 8,
			groups[6] & 0xff,
			groups[7] >> 8,
			groups[7] & 0xff
		].join('.'))
	}
	if (groups.slice(0, 6).every(group => group === 0)) return ''
	if (groups.every(group => group === 0)) return ''
	if (groups.slice(0, 7).every(group => group === 0) && groups[7] === 1) return ''
	if ((groups[0] & 0xfe00) === 0xfc00) return ''
	if ((groups[0] & 0xffc0) === 0xfe80) return ''
	if ((groups[0] & 0xff00) === 0xff00) return ''
	if (groups[0] === 0x2001 && groups[1] === 0x0db8) return ''
	return compressIpv6(groups)
}

function normalizeIpv4Syntax(value) {
	const parts = String(value).split('.')
	if (parts.length !== 4) return ''
	const octets = parts.map(part => /^\d{1,3}$/.test(part)
		&& (part.length === 1 || !part.startsWith('0')) ? Number(part) : NaN)
	return octets.every(octet => Number.isInteger(octet) && octet >= 0 && octet <= 255)
		? octets.join('.')
		: ''
}

function expandIpv6(value) {
	if (!/^[0-9a-f:]+$/.test(value) || value.indexOf('::') !== value.lastIndexOf('::')) return null
	const compressed = value.includes('::')
	const halves = compressed ? value.split('::') : [value]
	const left = halves[0] ? halves[0].split(':') : []
	const right = compressed && halves[1] ? halves[1].split(':') : []
	if ([...left, ...right].some(group => !/^[0-9a-f]{1,4}$/.test(group))) return null
	const missing = 8 - left.length - right.length
	if ((!compressed && missing !== 0) || (compressed && missing < 1)) return null
	return [
		...left.map(group => Number.parseInt(group, 16)),
		...Array(missing).fill(0),
		...right.map(group => Number.parseInt(group, 16))
	]
}

function compressIpv6(groups) {
	let bestStart = -1
	let bestLength = 0
	for (let index = 0; index < groups.length;) {
		if (groups[index] !== 0) {
			index += 1
			continue
		}
		let end = index
		while (end < groups.length && groups[end] === 0) end += 1
		if (end - index > bestLength && end - index >= 2) {
			bestStart = index
			bestLength = end - index
		}
		index = end
	}
	const values = groups.map(group => group.toString(16))
	if (bestStart < 0) return values.join(':')
	const left = values.slice(0, bestStart).join(':')
	const right = values.slice(bestStart + bestLength).join(':')
	return `${left}::${right}`
}

function createCandidateStats() {
	return {
		hostCount: 0,
		srflxCount: 0,
		ignoredRelayCount: 0,
		rejectedNonPublicCount: 0
	}
}

function incrementCandidateType(stats, type) {
	if (type === 'host') stats.hostCount += 1
	if (type === 'srflx') stats.srflxCount += 1
}

function countAcceptedTypes(values, acceptedTypes, type) {
	return values.filter(value => acceptedTypes.get(value) === type).length
}

function candidateFamilyCounts(values) {
	return values.reduce((counts, value) => {
		if (value.includes(':')) counts.ipv6Count += 1
		else counts.ipv4Count += 1
		return counts
	}, { ipv4Count: 0, ipv6Count: 0 })
}

function trace(diagnosticTrace, stage, fields) {
	if (typeof diagnosticTrace === 'function') diagnosticTrace(stage, fields)
}

function stableIpOrder(addresses) {
	return [...new Set(addresses)].sort((left, right) => {
		const family = Number(left.includes(':')) - Number(right.includes(':'))
		if (family) return family
		return left === right ? 0 : left < right ? -1 : 1
	})
}

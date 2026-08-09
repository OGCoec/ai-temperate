import { WEBRTC_DEFAULT_TIMEOUT_MILLIS } from './webrtc-verification-core.js'

/**
 * 只在 H5 浏览器中通过 RTCPeerConnection 收集公网服务器反射候选地址。
 */
export function collectH5WebRtcIps(
	stunUrls,
	timeoutMillis = WEBRTC_DEFAULT_TIMEOUT_MILLIS
) {
	return new Promise(resolve => {
		const PeerConnection = typeof globalThis !== 'undefined'
			? globalThis.RTCPeerConnection
				|| globalThis.webkitRTCPeerConnection
			: null
		if (typeof PeerConnection !== 'function') {
			resolve([])
			return
		}

		const addresses = new Set()
		let connection
		let timer
		let settled = false

		const finish = () => {
			if (settled) return
			settled = true
			if (timer) clearTimeout(timer)
			if (connection) {
				connection.removeEventListener('icecandidate', onCandidate)
				connection.removeEventListener('icegatheringstatechange', onGatheringState)
				connection.close()
			}
			resolve(stableIpOrder([...addresses]))
		}

		const onCandidate = event => {
			const candidate = event?.candidate
			if (!candidate) {
				finish()
				return
			}
			const candidateType = candidate.type || candidateTypeFromLine(candidate.candidate)
			if (candidateType !== 'srflx') return
			const address = candidate.address || candidateAddressFromLine(candidate.candidate)
			const normalized = normalizePublicCandidate(address)
			if (normalized) addresses.add(normalized)
		}

		const onGatheringState = () => {
			if (connection?.iceGatheringState === 'complete') finish()
		}

		try {
			connection = new PeerConnection({
				iceServers: (Array.isArray(stunUrls) ? stunUrls : [])
					.map(url => ({ urls: String(url) }))
			})
			connection.addEventListener('icecandidate', onCandidate)
			connection.addEventListener('icegatheringstatechange', onGatheringState)
			connection.createDataChannel('ait-webrtc-probe')
			timer = setTimeout(finish, boundedTimeout(timeoutMillis))
			Promise.resolve(connection.createOffer())
				.then(offer => connection.setLocalDescription(offer))
				.catch(finish)
		} catch (_) {
			finish()
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
	const candidate = value.trim().toLowerCase()
	if (!candidate || candidate.length > 64 || /[%/\s\[\]]/.test(candidate)) return ''
	if (candidate.endsWith('.local') || candidate.includes('.local.')) return ''
	if (candidate.includes(':')) {
		const mapped = candidate.match(/^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/)
		if (mapped) return normalizePublicIpv4(mapped[1])
		if (!/^[0-9a-f:]+$/.test(candidate)) return ''
		if (candidate === '::' || candidate === '::1') return ''
		const firstGroup = candidate.split(':').find(Boolean) || '0'
		const first = Number.parseInt(firstGroup.padEnd(4, '0').slice(0, 2), 16)
		if ((first & 0xfe) === 0xfc || (first & 0xff) === 0xfe || first === 0xff) return ''
		return candidate
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
	const [first, second] = octets
	if (first === 0 || first === 10 || first === 127 || first >= 224) return ''
	if (first === 100 && second >= 64 && second <= 127) return ''
	if (first === 169 && second === 254) return ''
	if (first === 172 && second >= 16 && second <= 31) return ''
	if (first === 192 && second === 168) return ''
	if (first === 198 && (second === 18 || second === 19)) return ''
	return octets.join('.')
}

function stableIpOrder(addresses) {
	return [...new Set(addresses)].sort((left, right) => {
		const family = Number(left.includes(':')) - Number(right.includes(':'))
		return family || left.localeCompare(right)
	})
}

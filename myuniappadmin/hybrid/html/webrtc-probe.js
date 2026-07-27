(function () {
	'use strict'

	var activeNonce = ''
	var completed = false

	window.startWebRtcProbe = function (input) {
		var config = normalizeConfig(input)
		if (!config || (activeNonce && activeNonce === config.nonce)) return
		activeNonce = config.nonce
		completed = false
		collect(config).then(function (webRtcIps) {
			if (completed) return
			completed = true
			postToApplication({
				type: 'result',
				nonce: config.nonce,
				webRtcIps: webRtcIps
			})
		})
	}

	function collect(config) {
		return new Promise(function (resolve) {
			var PeerConnection = window.RTCPeerConnection || window.webkitRTCPeerConnection
			if (typeof PeerConnection !== 'function') {
				resolve([])
				return
			}
			var connection
			var timer
			var settled = false
			var addresses = Object.create(null)

			function finish() {
				if (settled) return
				settled = true
				if (timer) clearTimeout(timer)
				if (connection) {
					connection.removeEventListener('icecandidate', onCandidate)
					connection.removeEventListener('icegatheringstatechange', onGatheringState)
					connection.close()
				}
				resolve(Object.keys(addresses).sort(stableOrder))
			}

			function onCandidate(event) {
				var candidate = event && event.candidate
				if (!candidate) {
					finish()
					return
				}
				var type = candidate.type || candidateType(candidate.candidate)
				if (type !== 'srflx') return
				var value = normalizePublicIp(candidate.address || candidateAddress(candidate.candidate))
				if (value) addresses[value] = true
			}

			function onGatheringState() {
				if (connection && connection.iceGatheringState === 'complete') finish()
			}

			try {
				connection = new PeerConnection({
					iceServers: config.stunUrls.map(function (url) {
						return { urls: url }
					})
				})
				connection.addEventListener('icecandidate', onCandidate)
				connection.addEventListener('icegatheringstatechange', onGatheringState)
				connection.createDataChannel('ait-webrtc-probe')
				timer = setTimeout(finish, config.timeoutMillis)
				Promise.resolve(connection.createOffer())
					.then(function (offer) { return connection.setLocalDescription(offer) })
					.catch(finish)
			} catch (_) {
				finish()
			}
		})
	}

	function postToApplication(payload) {
		if (window.uni && typeof window.uni.postMessage === 'function') {
			window.uni.postMessage({ data: payload })
			return
		}
		function deliver() {
			if (!window.plus || !plus.webview) return
			var opener = plus.webview.currentWebview().opener()
			if (!opener) return
			var serialized = JSON.stringify(payload)
			opener.evalJS(
				'window.__AIT_WEBRTC_POST_MESSAGE__(' + JSON.stringify(serialized) + ')'
			)
		}
		if (window.plus) deliver()
		else document.addEventListener('plusready', deliver, { once: true })
	}

	function normalizeConfig(input) {
		if (!input || !/^[a-f0-9]{32}$/.test(String(input.nonce || ''))) return null
		var stunUrls = Array.isArray(input.stunUrls)
			? input.stunUrls.filter(function (value) {
				return typeof value === 'string' && /^stun:[a-z0-9.-]+:\d{1,5}$/i.test(value)
			}).slice(0, 4)
			: []
		var timeout = Number(input.timeoutMillis)
		return {
			nonce: String(input.nonce),
			stunUrls: stunUrls,
			timeoutMillis: Number.isFinite(timeout) && timeout > 0
				? Math.min(timeout, 15000)
				: 15000
		}
	}

	function candidateType(line) {
		var parts = String(line || '').trim().split(/\s+/)
		var index = parts.indexOf('typ')
		return index >= 0 ? parts[index + 1] || '' : ''
	}

	function candidateAddress(line) {
		var parts = String(line || '').trim().split(/\s+/)
		return parts.length > 4 ? parts[4] : ''
	}

	function normalizePublicIp(value) {
		if (typeof value !== 'string') return ''
		var candidate = value.trim().toLowerCase()
		if (!candidate || candidate.length > 64 || /[%/\s\[\]]/.test(candidate)) return ''
		if (candidate.endsWith('.local') || candidate.indexOf('.local.') >= 0) return ''
		if (candidate.indexOf(':') >= 0) {
			var mapped = candidate.match(/^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/)
			if (mapped) return normalizePublicIpv4(mapped[1])
			if (!/^[0-9a-f:]+$/.test(candidate) || candidate === '::' || candidate === '::1') return ''
			var firstGroup = candidate.split(':').filter(Boolean)[0] || '0'
			var first = parseInt((firstGroup + '0000').slice(0, 2), 16)
			if ((first & 0xfe) === 0xfc || first === 0xfe || first === 0xff) return ''
			return candidate
		}
		return normalizePublicIpv4(candidate)
	}

	function normalizePublicIpv4(value) {
		var parts = String(value).split('.')
		if (parts.length !== 4) return ''
		var octets = parts.map(function (part) {
			if (!/^\d{1,3}$/.test(part) || (part.length > 1 && part.charAt(0) === '0')) return NaN
			return Number(part)
		})
		if (octets.some(function (value) {
			return !Number.isInteger(value) || value < 0 || value > 255
		})) return ''
		var first = octets[0]
		var second = octets[1]
		if (first === 0 || first === 10 || first === 127 || first >= 224) return ''
		if (first === 100 && second >= 64 && second <= 127) return ''
		if (first === 169 && second === 254) return ''
		if (first === 172 && second >= 16 && second <= 31) return ''
		if (first === 192 && second === 168) return ''
		if (first === 198 && (second === 18 || second === 19)) return ''
		return octets.join('.')
	}

	function stableOrder(left, right) {
		var family = Number(left.indexOf(':') >= 0) - Number(right.indexOf(':') >= 0)
		return family || left.localeCompare(right)
	}

	function ready() {
		postToApplication({ type: 'ready' })
	}
	if (document.readyState === 'loading') {
		document.addEventListener('DOMContentLoaded', ready, { once: true })
	} else {
		ready()
	}
})()

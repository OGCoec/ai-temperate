;(function () {
	'use strict'

	var activeNonce = ''
	var completed = false

	window.startWebRtcProbe = function (input) {
		var config = normalizeConfig(input)
		if (!config || (activeNonce && activeNonce === config.nonce)) return
		activeNonce = config.nonce
		completed = false
		var trace = createDiagnosticTrace(config)
		trace('hidden_probe_received', {
			stunCount: config.stunUrls.length,
			timeoutMillis: config.timeoutMillis
		})
		collect(config, trace).then(function (webRtcIps) {
			if (completed) return
			completed = true
			return encryptAndPost(config, webRtcIps, trace)
		}).catch(function () {
			trace('peer_connection_failed', { errorCode: 'PROBE_ERROR' })
			postError(config, 'probe_error', trace, 0)
		})
	}

	function collect(config, trace) {
		return new Promise(function (resolve) {
			var PeerConnection = window.RTCPeerConnection || window.webkitRTCPeerConnection
			if (typeof PeerConnection !== 'function') {
				trace('peer_connection_unavailable', {
					reason: 'peer_connection_unavailable'
				})
				resolve([])
				return
			}
			var connection
			var timer
			var settled = false
			var addresses = Object.create(null)
			var stats = {
				hostCount: 0,
				srflxCount: 0,
				relayCount: 0,
				prflxCount: 0,
				unknownCount: 0,
				rejectedCount: 0,
				sourceIndexes: Object.create(null)
			}

			function finish(reason) {
				if (settled) return
				settled = true
				if (timer) clearTimeout(timer)
				if (connection) {
					connection.removeEventListener('icecandidate', onCandidate)
					connection.removeEventListener('icegatheringstatechange', onGatheringState)
					connection.close()
				}
				var values = Object.keys(addresses).sort(stableOrder)
				var families = candidateFamilyCounts(values)
				var fields = {
					reason: reason,
					hostCount: stats.hostCount,
					srflxCount: stats.srflxCount,
					relayCount: stats.relayCount,
					prflxCount: stats.prflxCount,
					unknownCount: stats.unknownCount,
					acceptedCount: values.length,
					rejectedCount: stats.rejectedCount,
					ipv4Count: families.ipv4Count,
					ipv6Count: families.ipv6Count,
					sourceIndexes: Object.keys(stats.sourceIndexes)
						.map(Number).sort(function (left, right) { return left - right })
				}
				trace(reason === 'timeout' ? 'ice_timeout' : 'ice_finished', fields)
				resolve(values)
			}

			function onCandidate(event) {
				var candidate = event && event.candidate
				if (!candidate) {
					finish('null_candidate')
					return
				}
				var type = candidate.type || candidateType(candidate.candidate)
				incrementCandidateType(stats, type)
				if (type !== 'srflx') return
				var value = normalizePublicIp(candidate.address || candidateAddress(candidate.candidate))
				if (!value) {
					stats.rejectedCount += 1
					return
				}
				addresses[value] = true
				var sourceIndex = matchingSourceIndex(config.stunUrls, candidate.url)
				if (sourceIndex > 0) stats.sourceIndexes[sourceIndex] = true
			}

			function onGatheringState() {
				if (connection && connection.iceGatheringState === 'complete') {
					finish('gathering_complete')
				}
			}

			try {
				connection = new PeerConnection({
					iceServers: config.stunUrls.map(function (url) {
						return { urls: url }
					})
				})
				trace('peer_connection_created', { stunCount: config.stunUrls.length })
				connection.addEventListener('icecandidate', onCandidate)
				connection.addEventListener('icegatheringstatechange', onGatheringState)
				connection.createDataChannel('ait-webrtc-probe')
				timer = setTimeout(function () { finish('timeout') }, config.timeoutMillis)
				Promise.resolve(connection.createOffer())
					.then(function (offer) {
						trace('offer_created')
						return connection.setLocalDescription(offer)
					})
					.then(function () { trace('local_description_set') })
					.catch(function () {
						trace('offer_failed', { errorCode: 'OFFER_FAILED' })
						finish('offer_failed')
					})
			} catch (_) {
				trace('peer_connection_failed', { errorCode: 'CONSTRUCTOR_FAILED' })
				finish('constructor_failed')
			}
		})
	}

	function encryptAndPost(config, webRtcIps, trace) {
		trace('encryption_started', {
			candidateCount: Array.isArray(webRtcIps) ? webRtcIps.length : 0
		})
		if (!window.crypto || !window.crypto.subtle || typeof TextEncoder !== 'function') {
			trace('encryption_failed', { errorCode: 'CRYPTO_UNAVAILABLE' })
			postError(
				config,
				'crypto_unavailable',
				trace,
				Array.isArray(webRtcIps) ? webRtcIps.length : 0)
			return Promise.resolve()
		}
		var iv = new Uint8Array(12)
		window.crypto.getRandomValues(iv)
		var additionalData = new TextEncoder().encode(config.channelId + '|' + config.nonce)
		var plaintext = new TextEncoder().encode(JSON.stringify({
			channelId: config.channelId,
			nonce: config.nonce,
			webRtcIps: Array.isArray(webRtcIps) ? webRtcIps.slice(0, 8) : []
		}))
		return window.crypto.subtle.importKey(
			'raw',
			fromBase64Url(config.key),
			{ name: 'AES-GCM' },
			false,
			['encrypt']
		).then(function (key) {
			return window.crypto.subtle.encrypt(
				{ name: 'AES-GCM', iv: iv, additionalData: additionalData },
				key,
				plaintext
			)
		}).then(function (encrypted) {
			trace('encryption_succeeded', { candidateCount: webRtcIps.length })
			trace('result_dispatched', { candidateCount: webRtcIps.length })
			window.location.href = 'aitwebrtc://result?channel='
				+ encodeURIComponent(config.channelId)
				+ '&iv=' + encodeURIComponent(toBase64Url(new Uint8Array(iv)))
				+ '&payload=' + encodeURIComponent(toBase64Url(new Uint8Array(encrypted)))
		}).catch(function () {
			trace('encryption_failed', { errorCode: 'ENCRYPTION_FAILED' })
			postError(config, 'probe_error', trace, webRtcIps.length)
		})
	}

	function postError(config, code, trace, candidateCount) {
		trace('result_dispatched', { candidateCount: candidateCount || 0 })
		window.location.href = 'aitwebrtc://result?channel='
			+ encodeURIComponent(config.channelId)
			+ '&error=' + encodeURIComponent(code)
	}

	function normalizeConfig(input) {
		if (!input
				|| !/^[a-f0-9]{32}$/.test(String(input.nonce || ''))
				|| !/^[a-f0-9]{32}$/.test(String(input.channelId || ''))
				|| !/^[A-Za-z0-9_-]{43}$/.test(String(input.key || ''))) return null
		var stunUrls = Array.isArray(input.stunUrls)
			? input.stunUrls.filter(function (value) {
				return typeof value === 'string' && /^stun:[a-z0-9.-]+:\d{1,5}$/i.test(value)
			}).slice(0, 4)
			: []
		var timeout = Number(input.timeoutMillis)
		return {
			channelId: String(input.channelId),
			nonce: String(input.nonce),
			key: String(input.key),
			stunUrls: stunUrls,
			timeoutMillis: Number.isFinite(timeout) && timeout > 0
				? Math.min(timeout, 15000)
				: 15000,
			diagnosticsEnabled: input.diagnosticsEnabled === true
				&& /^[A-Za-z0-9:_-]{1,64}$/.test(String(input.probeRunId || '')),
			probeRunId: /^[A-Za-z0-9:_-]{1,64}$/.test(String(input.probeRunId || ''))
				? String(input.probeRunId)
				: ''
		}
	}

	function createDiagnosticTrace(config) {
		var startedAt = Date.now()
		var scope = config.probeRunId.indexOf('admin-') === 0
			? 'admin-hidden-webview'
			: 'user-hidden-webview'
		return function (stage, fields) {
			if (!config.diagnosticsEnabled
				|| typeof console === 'undefined'
				|| !/^[A-Za-z0-9:_-]{1,64}$/.test(String(stage || ''))) return
			var event = {
				scope: scope,
				stage: String(stage),
				probeRunId: config.probeRunId,
				elapsedMs: Math.max(0, Date.now() - startedAt)
			}
			copyDiagnosticFields(event, fields)
			var level = diagnosticLevel(event.stage)
			try {
				if (typeof console[level] === 'function') {
					console[level]('[ait-webrtc]', JSON.stringify(event))
				}
			} catch (_) {
				// 隐藏页日志失败不能影响ICE、加密或结果回传。
			}
		}
	}

	function copyDiagnosticFields(target, fields) {
		if (!fields || typeof fields !== 'object') return
		var numberFields = [
			'stunCount', 'timeoutMillis', 'candidateCount',
			'hostCount', 'srflxCount', 'relayCount', 'prflxCount', 'unknownCount',
			'acceptedCount', 'rejectedCount', 'ipv4Count', 'ipv6Count'
		]
		numberFields.forEach(function (field) {
			var value = fields[field]
			if (Number.isFinite(value) && value >= 0 && value <= 86400000) {
				target[field] = Math.floor(value)
			}
		})
		;['reason', 'errorCode'].forEach(function (field) {
			var value = String(fields[field] || '')
			if (/^[A-Za-z0-9_-]{1,64}$/.test(value)) target[field] = value
		})
		if (Array.isArray(fields.sourceIndexes)) {
			var indexes = fields.sourceIndexes.filter(function (value) {
				return Number.isInteger(value) && value >= 1 && value <= 4
			})
			if (indexes.length) target.sourceIndexes = Array.from(new Set(indexes)).sort()
		}
	}

	function diagnosticLevel(stage) {
		if (stage === 'peer_connection_failed' || stage === 'encryption_failed') return 'error'
		return /(failed|timeout|unavailable|invalid|mismatch|error)$/.test(stage)
			? 'warn'
			: 'info'
	}

	function incrementCandidateType(stats, type) {
		if (type === 'host') stats.hostCount += 1
		else if (type === 'srflx') stats.srflxCount += 1
		else if (type === 'relay') stats.relayCount += 1
		else if (type === 'prflx') stats.prflxCount += 1
		else stats.unknownCount += 1
	}

	function matchingSourceIndex(stunUrls, candidateUrl) {
		if (typeof candidateUrl !== 'string' || !candidateUrl) return 0
		for (var index = 0; index < stunUrls.length; index++) {
			if (candidateUrl === stunUrls[index]) return index + 1
		}
		return 0
	}

	function candidateFamilyCounts(values) {
		var ipv4Count = 0
		var ipv6Count = 0
		values.forEach(function (value) {
			if (value.indexOf(':') >= 0) ipv6Count += 1
			else ipv4Count += 1
		})
		return { ipv4Count: ipv4Count, ipv6Count: ipv6Count }
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

	function toBase64Url(bytes) {
		var binary = ''
		bytes.forEach(function (value) { binary += String.fromCharCode(value) })
		return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
	}

	function fromBase64Url(value) {
		var normalized = String(value).replace(/-/g, '+').replace(/_/g, '/')
		var padded = normalized + '='.repeat((4 - normalized.length % 4) % 4)
		var binary = atob(padded)
		return Uint8Array.from(binary, function (character) {
			return character.charCodeAt(0)
		})
	}
})()

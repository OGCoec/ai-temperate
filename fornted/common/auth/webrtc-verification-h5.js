import { collectH5WebRtcIps } from '@shared-auth/h5-webrtc-probe.js'

/**
 * H5 仅使用浏览器原生 RTCPeerConnection，不依赖 Android WebView。
 */
export function collectH5VerificationIps(options = {}) {
	return collectH5WebRtcIps(
		options.stunUrls,
		options.timeoutMillis,
		options.diagnosticTrace)
}

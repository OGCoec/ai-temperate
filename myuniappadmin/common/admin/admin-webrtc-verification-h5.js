import { collectH5WebRtcIps } from '@shared-auth/h5-webrtc-probe.js'

/**
 * 管理员 H5 仅通过浏览器原生 RTCPeerConnection 采集 WebRTC 公网 IP。
 */
export function collectAdminH5VerificationIps(options = {}) {
	return collectH5WebRtcIps(
		options.stunUrls,
		options.timeoutMillis,
		options.diagnosticTrace
	)
}

import {
	collectAndroidWebRtcIpsInBackground
} from '@shared-auth/android-webrtc-background-probe.js'
import {
	createWebRtcDiagnosticLogger
} from '@shared-auth/webrtc-diagnostics.js'
import {
	createWebRtcProbeChannel,
	decryptWebRtcProbePayload
} from '@/uni_modules/ait-webrtc-crypto'

const WEBRTC_DIAGNOSTICS_ENABLED = process.env.NODE_ENV === 'development'
const androidDiagnostics = createWebRtcDiagnosticLogger(
	'user-android-parent',
	WEBRTC_DIAGNOSTICS_ENABLED)

/**
 * Android 仅使用屏幕外本地 WebView 探测 WebRTC，并由 UTS 原生加密桥保护回传结果。
 */
export async function collectAndroidVerificationIps(options = {}) {
	if (typeof plus === 'undefined' || !plus.webview) {
		androidDiagnostics('environment_unavailable', {
			reason: 'plus_webview_unavailable'
		})
		androidDiagnostics('probe_finished', {
			reason: 'environment_unavailable',
			candidateCount: 0,
			ipv4Count: 0,
			ipv6Count: 0
		})
		const error = new Error('Android WebRTC 后台探测环境不可用。')
		error.code = 'WEBRTC_VERIFICATION_FAILED'
		error.retryable = false
		throw error
	}
	return collectAndroidWebRtcIpsInBackground({
		attemptId: options.attemptId,
		probeRunId: options.probeRunId,
		webviewId: 'ait-user-webrtc',
		resourcePath: '/hybrid/html/webrtc-probe.html',
		stunUrls: options.stunUrls,
		timeoutMillis: options.timeoutMillis,
		diagnosticRole: 'user',
		diagnosticsEnabled: WEBRTC_DIAGNOSTICS_ENABLED,
		onDiagnostic: androidDiagnostics,
		cryptoBridge: {
			createChannel: createWebRtcProbeChannel,
			decryptPayload: decryptWebRtcProbePayload
		}
	})
}

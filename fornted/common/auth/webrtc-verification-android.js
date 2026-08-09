import {
	collectAndroidWebRtcIpsInBackground
} from '@shared-auth/android-webrtc-background-probe.js'

/**
 * Android 仅使用屏幕外本地 WebView，不允许降级到 H5 浏览器探测。
 */
export async function collectAndroidVerificationIps(options = {}) {
	if (typeof plus === 'undefined'
		|| !plus.webview
		|| !globalThis.crypto?.subtle
		|| !globalThis.crypto?.getRandomValues) {
		const error = new Error('Android WebRTC 后台探测环境不可用。')
		error.code = 'WEBRTC_VERIFICATION_FAILED'
		error.retryable = false
		throw error
	}
	return collectAndroidWebRtcIpsInBackground({
		attemptId: options.attemptId,
		webviewId: 'ait-user-webrtc',
		resourcePath: '/hybrid/html/webrtc-probe.html',
		stunUrls: options.stunUrls,
		timeoutMillis: options.timeoutMillis
	})
}

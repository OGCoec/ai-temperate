import { AUTH_API_BASE_URL, clientPlatform } from './config.js'
import {
	AndroidEdgeChallengeError,
	edgeChallengeError,
	executeWithAndroidEdgeChallengeRecovery,
	extractAndroidClearanceCookie
} from './android-edge-challenge-policy.js'

const PRIMARY_ORIGIN = 'https://niko000o.site'
const CLEARANCE_PATH = '/__edge/android-clearance'
const CLEARANCE_STATUS_PATH = '/__edge/android-clearance/status'
const VERIFIED_SCHEME = 'ait-edge://verified'
const WEBVIEW_ID = 'ait-cloudflare-managed-challenge'
const CHALLENGE_TIMEOUT_MILLIS = 120000

let clearanceInFlight = null

export function getAndroidClearanceCookieHeader() {
	if (clientPlatform() !== 'ANDROID') return ''
	try {
		const CookieManager = plus.android.importClass('android.webkit.CookieManager')
		const manager = plus.android.invoke(CookieManager, 'getInstance')
		const cookieHeader = plus.android.invoke(manager, 'getCookie', PRIMARY_ORIGIN)
		return extractAndroidClearanceCookie(String(cookieHeader || ''))
	} catch (_) {
		return ''
	}
}

export function androidEdgeRequestHeaders(headers = {}) {
	const normalized = { ...(headers || {}) }
	if (clientPlatform() !== 'ANDROID') return normalized
	for (const name of Object.keys(normalized)) {
		if (name.toLowerCase() === 'cookie') delete normalized[name]
	}
	const clearance = getAndroidClearanceCookieHeader()
	if (clearance) normalized.Cookie = clearance
	return normalized
}

export async function runAndroidRequestWithEdgeRecovery(executeRequest) {
	if (clientPlatform() !== 'ANDROID') return executeRequest()
	return executeWithAndroidEdgeChallengeRecovery(
		executeRequest,
		ensureAndroidEdgeClearance)
}

export function ensureAndroidEdgeClearance() {
	if (clientPlatform() !== 'ANDROID') return Promise.resolve()
	if (!clearanceInFlight) {
		clearanceInFlight = openManagedChallenge()
			.finally(() => { clearanceInFlight = null })
	}
	return clearanceInFlight
}

function openManagedChallenge() {
	return new Promise((resolve, reject) => {
		let webview = null
		let settled = false
		let verifying = false
		let timeoutHandle = null

		const closeWebview = () => {
			if (!webview) return
			const active = webview
			webview = null
			try { active.close('slide-out-bottom', 180) } catch (_) {}
		}
		const settle = (error = null) => {
			if (settled) return
			settled = true
			if (timeoutHandle) clearTimeout(timeoutHandle)
			closeWebview()
			if (error) reject(error)
			else resolve()
		}
		const verifySharedClearance = async () => {
			if (verifying || settled) return
			verifying = true
			try {
				flushAndroidCookies()
				await probeClearanceStatus()
				settle()
			} catch (error) {
				settle(error)
			}
		}

		try {
			const url = `${AUTH_API_BASE_URL}${CLEARANCE_PATH}`
			webview = plus.webview.create(url, WEBVIEW_ID, {
				top: '0px',
				bottom: '0px',
				left: '0px',
				right: '0px',
				background: '#0b0d0c'
			})
			webview.overrideUrlLoading(
				{ mode: 'reject', match: 'ait-edge://*' },
				event => {
					if (String(event?.url || '') !== VERIFIED_SCHEME) return
					void verifySharedClearance()
				})
			webview.addEventListener('close', () => {
				webview = null
				if (!settled && !verifying) {
					settle(edgeChallengeError(
						AndroidEdgeChallengeError.CANCELLED,
						'已取消 Cloudflare 安全验证。'))
				}
			})
			webview.addEventListener('error', () => {
				settle(edgeChallengeError(
					AndroidEdgeChallengeError.NOT_SHARED,
					'Cloudflare 安全验证页面加载失败，请稍后重试。'))
			})
			timeoutHandle = setTimeout(() => {
				settle(edgeChallengeError(
					AndroidEdgeChallengeError.TIMEOUT,
					'Cloudflare 安全验证等待超时，请重试。'))
			}, CHALLENGE_TIMEOUT_MILLIS)
			webview.show('slide-in-bottom', 180)
		} catch (_) {
			settle(edgeChallengeError(
				AndroidEdgeChallengeError.NOT_SHARED,
				'无法打开 Cloudflare 安全验证，请稍后重试。'))
		}
	})
}

function flushAndroidCookies() {
	try {
		const CookieManager = plus.android.importClass('android.webkit.CookieManager')
		const manager = plus.android.invoke(CookieManager, 'getInstance')
		plus.android.invoke(manager, 'flush')
	} catch (_) {
		// 状态探测仍是最终事实来源；flush 不可用时不能假定 Cookie 已共享。
	}
}

function probeClearanceStatus() {
	const clearance = getAndroidClearanceCookieHeader()
	if (!clearance) {
		return Promise.reject(edgeChallengeError(
			AndroidEdgeChallengeError.NOT_SHARED,
			'Cloudflare 验证 Cookie 尚未共享到应用请求。'))
	}
	return new Promise((resolve, reject) => {
		uni.request({
			url: `${AUTH_API_BASE_URL}${CLEARANCE_STATUS_PATH}`,
			method: 'GET',
			header: {
				'X-Client-Platform': 'ANDROID',
				Cookie: clearance
			},
			withCredentials: false,
			timeout: 10000,
			success(response) {
				if (response.statusCode === 204) {
					resolve()
					return
				}
				reject(edgeChallengeError(
					AndroidEdgeChallengeError.NOT_SHARED,
					'Cloudflare 验证状态未同步，请重新验证。'))
			},
			fail() {
				reject(edgeChallengeError(
					AndroidEdgeChallengeError.NOT_SHARED,
					'无法确认 Cloudflare 验证状态，请稍后重试。'))
			}
		})
	})
}

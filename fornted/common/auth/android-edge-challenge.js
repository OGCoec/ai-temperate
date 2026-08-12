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
const CLEARANCE_STATUS_TIMEOUT_MILLIS = 2000
const CLEARANCE_CONFIRMATION_DELAYS = Object.freeze([0, 150, 400, 800, 1500])
const ClearanceProbeStatus = Object.freeze({
	VERIFIED: 'VERIFIED',
	PENDING: 'PENDING',
	RETRYABLE: 'RETRYABLE'
})
const EDGE_CHALLENGE_FAILURE_MESSAGES = Object.freeze({
	[AndroidEdgeChallengeError.TIMEOUT]: '安全验证等待超时，请重新验证。',
	[AndroidEdgeChallengeError.NOT_SHARED]: '安全验证状态未同步，请重新验证。',
	[AndroidEdgeChallengeError.REPEATED]: '安全验证后请求仍被拦截，请稍后重试。'
})

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

export function presentAndroidEdgeChallengeFailure(error) {
	const code = String(error?.code || '')
	if (code === AndroidEdgeChallengeError.CANCELLED) return true
	const message = EDGE_CHALLENGE_FAILURE_MESSAGES[code]
	if (!message) return false
	try {
		uni.showToast({
			title: message,
			icon: 'none',
			duration: 4000
		})
	} catch (_) {}
	return true
}

function openManagedChallenge() {
	return new Promise((resolve, reject) => {
		let webview = null
		let settled = false
		let confirmationInFlight = false
		let authoritativeCompletionSeen = false
		let timeoutHandle = null
		const retryWaits = new Map()
		const activeRequests = new Set()

		const closeWebview = () => {
			if (!webview) return
			const active = webview
			webview = null
			try { active.close('slide-out-bottom', 180) } catch (_) {}
		}
		const clearRetryWaits = () => {
			for (const [handle, completeWait] of retryWaits) {
				clearTimeout(handle)
				completeWait()
			}
			retryWaits.clear()
		}
		const abortActiveRequests = () => {
			for (const request of activeRequests) {
				try { request.abort?.() } catch (_) {}
			}
			activeRequests.clear()
		}
		const settle = (error = null) => {
			if (settled) return
			settled = true
			if (timeoutHandle) clearTimeout(timeoutHandle)
			clearRetryWaits()
			abortActiveRequests()
			closeWebview()
			if (error) reject(error)
			else resolve()
		}
		const waitForRetry = delay => new Promise(completeWait => {
			if (settled) {
				completeWait()
				return
			}
			let handle = null
			handle = setTimeout(() => {
				retryWaits.delete(handle)
				completeWait()
			}, delay)
			retryWaits.set(handle, completeWait)
		})
		const registerRequest = request => {
			if (request && typeof request.abort === 'function') {
				activeRequests.add(request)
			}
		}
		const unregisterRequest = request => {
			if (request) activeRequests.delete(request)
		}
		const confirmSharedClearance = async authoritative => {
			if (authoritative) authoritativeCompletionSeen = true
			if (confirmationInFlight || settled) return
			confirmationInFlight = true
			try {
				for (const delay of CLEARANCE_CONFIRMATION_DELAYS) {
					if (delay > 0) await waitForRetry(delay)
					if (settled) return
					flushAndroidCookies()
					const status = await probeClearanceStatus(
						registerRequest,
						unregisterRequest)
					if (settled) return
					if (status === ClearanceProbeStatus.VERIFIED) {
						settle()
						return
					}
				}
				if (authoritativeCompletionSeen) {
					settle(edgeChallengeError(
						AndroidEdgeChallengeError.NOT_SHARED,
						'Cloudflare 验证状态未同步，请重新验证。'))
				}
			} catch (error) {
				settle(error)
			} finally {
				confirmationInFlight = false
			}
		}

		try {
			const url = `${AUTH_API_BASE_URL}${CLEARANCE_PATH}`
			webview = plus.webview.create('', WEBVIEW_ID, {
				top: '0px',
				bottom: '0px',
				left: '0px',
				right: '0px',
				background: '#0b0d0c'
			})
			webview.overrideUrlLoading(
				{
					mode: 'reject',
					match: '^ait-edge://verified$',
					effect: 'instant',
					exclude: 'none'
				},
				event => {
					if (String(event?.url || '') !== VERIFIED_SCHEME) return
					void confirmSharedClearance(true)
				})
			webview.addEventListener('loaded', () => {
				void confirmSharedClearance(false)
			})
			webview.addEventListener('close', () => {
				webview = null
				if (!settled) {
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
					'Cloudflare 安全验证等待超时，请重新验证。'))
			}, CHALLENGE_TIMEOUT_MILLIS)
			webview.loadURL(url)
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

function probeClearanceStatus(registerRequest, unregisterRequest) {
	const clearance = getAndroidClearanceCookieHeader()
	if (!clearance) {
		return Promise.resolve(ClearanceProbeStatus.PENDING)
	}
	return new Promise((resolve, reject) => {
		let request = null
		let completed = false
		request = uni.request({
			url: `${AUTH_API_BASE_URL}${CLEARANCE_STATUS_PATH}`,
			method: 'GET',
			header: {
				'X-Client-Platform': 'ANDROID',
				Cookie: clearance
			},
			withCredentials: false,
			timeout: CLEARANCE_STATUS_TIMEOUT_MILLIS,
			success(response) {
				if (response.statusCode === 204) {
					resolve(ClearanceProbeStatus.VERIFIED)
					return
				}
				if (response.statusCode === 428) {
					resolve(ClearanceProbeStatus.PENDING)
					return
				}
				reject(edgeChallengeError(
					AndroidEdgeChallengeError.NOT_SHARED,
					'Cloudflare 验证状态未同步，请重新验证。'))
			},
			fail() {
				resolve(ClearanceProbeStatus.RETRYABLE)
			},
			complete() {
				completed = true
				unregisterRequest(request)
			}
		})
		if (!completed) registerRequest(request)
	})
}

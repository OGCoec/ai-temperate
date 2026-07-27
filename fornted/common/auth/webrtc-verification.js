import {
	WEBRTC_DEFAULT_TIMEOUT_MILLIS,
	collectBrowserWebRtcIps,
	isWebRtcFailureCode,
	webRtcErrorFromResponse
} from '@shared-auth/webrtc-verification-core.js'
import { AUTH_API_BASE_URL, clientPlatform } from './config.js'
import { getDeviceInstallationId } from './device-installation.js'
import { currentPreAuthToken } from './pre-auth.js'

const START_PATH = '/api/_edge/webrtc/start'
const FAILURE_PAGE = '/pages/risk/webrtc-failed'
const PROBE_PAGE = '/pages/risk/webrtc-probe'

let verifiedInMemory = false
let verificationInFlight = null
let latestFailure = null
let failureNavigationInFlight = false
let androidProbe = null

export function invalidateWebRtcVerification() {
	verifiedInMemory = false
}

export function currentWebRtcFailure() {
	return latestFailure ? { ...latestFailure, webRtcIps: [...latestFailure.webRtcIps] } : null
}

export async function ensureWebRtcVerified(options = {}) {
	const force = options.force === true
	if (verifiedInMemory && !force) return { webRtcStatus: true }
	if (!verificationInFlight) {
		verificationInFlight = verify(force)
			.finally(() => { verificationInFlight = null })
	}
	return verificationInFlight
}

export async function refreshWebRtcFailure() {
	const start = await requestEdge(START_PATH, 'GET')
	if (start?.webRtcStatus === true || start?.mode === 'DISABLED') {
		verifiedInMemory = true
		latestFailure = null
		return null
	}
	latestFailure = failureFromPayload(start)
	return currentWebRtcFailure()
}

export function presentWebRtcFailure(error) {
	if (!isWebRtcFailureCode(error?.code)) return false
	verifiedInMemory = false
	latestFailure = failureFromPayload(error)
	if (failureNavigationInFlight || currentRoute() === FAILURE_PAGE.slice(1)) return true
	failureNavigationInFlight = true
	uni.reLaunch({
		url: FAILURE_PAGE,
		complete() {
			failureNavigationInFlight = false
		}
	})
	return true
}

export function takeAndroidWebRtcProbeConfiguration() {
	if (!androidProbe) return null
	return {
		nonce: androidProbe.nonce,
		stunUrls: [...androidProbe.stunUrls],
		timeoutMillis: androidProbe.timeoutMillis
	}
}

export function completeAndroidWebRtcProbe(nonce, webRtcIps) {
	if (!androidProbe || androidProbe.completed || nonce !== androidProbe.nonce) return false
	androidProbe.completed = true
	clearTimeout(androidProbe.safetyTimer)
	const resolve = androidProbe.resolve
	androidProbe = null
	resolve(Array.isArray(webRtcIps) ? [...webRtcIps] : [])
	return true
}

export function cancelAndroidWebRtcProbe(nonce) {
	return completeAndroidWebRtcProbe(nonce, [])
}

async function verify(force) {
	const start = await requestEdge(START_PATH, 'GET')
	if (start?.mode === 'DISABLED') {
		verifiedInMemory = true
		latestFailure = null
		return start
	}
	if (start?.webRtcStatus === true) {
		verifiedInMemory = true
		latestFailure = null
		return start
	}
	if (start?.mode === 'OBSERVE' && start?.webRtcStatus === false) {
		verifiedInMemory = true
		return start
	}
	if (start?.webRtcStatus === false && !force) {
		throw failureError(start)
	}

	const timeoutMillis = boundedTimeout(start?.timeoutMillis)
	const webRtcIps = clientPlatform() === 'ANDROID'
		? await collectAndroidWebRtcIps(start?.stunUrls, timeoutMillis)
		: await collectBrowserWebRtcIps(start?.stunUrls, timeoutMillis)
	const report = await requestEdge(start?.reportPath || '/api/_edge/webrtc/report', 'POST', {
		webRtcIps
	})
	if (start?.mode === 'OBSERVE' || report?.webRtcStatus === true) {
		verifiedInMemory = true
		latestFailure = null
		return report
	}
	throw failureError(report)
}

function collectAndroidWebRtcIps(stunUrls, timeoutMillis) {
	if (androidProbe) return androidProbe.promise
	const nonce = randomNonce()
	let resolveProbe
	const promise = new Promise(resolve => { resolveProbe = resolve })
	androidProbe = {
		nonce,
		stunUrls: Array.isArray(stunUrls) ? [...stunUrls] : [],
		timeoutMillis,
		completed: false,
		resolve: resolveProbe,
		promise,
		safetyTimer: setTimeout(() => {
			if (completeAndroidWebRtcProbe(nonce, [])) uni.navigateBack()
		}, timeoutMillis)
	}
	uni.navigateTo({
		url: PROBE_PAGE,
		fail() {
			completeAndroidWebRtcProbe(nonce, [])
		}
	})
	return promise
}

function requestEdge(path, method, data) {
	return new Promise((resolve, reject) => {
		const platform = clientPlatform()
		const headers = {
			'Accept': 'application/json',
			'Content-Type': 'application/json',
			'X-Client-Platform': platform,
			'X-Device-Installation-Id': getDeviceInstallationId()
		}
		const preAuthToken = currentPreAuthToken()
		if (platform === 'ANDROID' && preAuthToken) headers['X-AIT-PreAuth'] = preAuthToken
		uni.request({
			url: `${AUTH_API_BASE_URL}${path}`,
			method,
			data,
			header: headers,
			withCredentials: true,
			timeout: 10000,
			success(response) {
				if (response.statusCode >= 200 && response.statusCode < 300) {
					resolve(response.data)
					return
				}
				reject(webRtcErrorFromResponse(response, 'WebRTC 网络校验失败。'))
			},
			fail(cause) {
				const error = new Error('网络连接失败，请稍后重试。')
				error.code = 'NETWORK_ERROR'
				error.cause = cause
				reject(error)
			}
		})
	})
}

function failureError(payload) {
	const error = new Error(payload?.message || 'WebRTC 网络校验失败。')
	error.code = payload?.code || (Array.isArray(payload?.webRtcIps) && payload.webRtcIps.length
		? 'WEBRTC_IP_MISMATCH'
		: 'WEBRTC_VERIFICATION_FAILED')
	error.webRtcStatus = payload?.webRtcStatus
	error.httpIp = payload?.httpIp || ''
	error.webRtcIps = Array.isArray(payload?.webRtcIps) ? [...payload.webRtcIps] : []
	error.retryable = payload?.retryable === true
	return error
}

function failureFromPayload(payload) {
	const error = payload instanceof Error ? payload : failureError(payload)
	return {
		code: error.code || 'WEBRTC_VERIFICATION_FAILED',
		message: error.message || 'WebRTC 网络校验失败。',
		httpIp: error.httpIp || '',
		webRtcIps: Array.isArray(error.webRtcIps) ? [...error.webRtcIps] : [],
		retryable: error.retryable !== false
	}
}

function boundedTimeout(value) {
	const numeric = Number(value)
	return Number.isFinite(numeric) && numeric > 0
		? Math.min(numeric, WEBRTC_DEFAULT_TIMEOUT_MILLIS)
		: WEBRTC_DEFAULT_TIMEOUT_MILLIS
}

function randomNonce() {
	const bytes = new Uint8Array(16)
	if (typeof globalThis !== 'undefined' && globalThis.crypto?.getRandomValues) {
		globalThis.crypto.getRandomValues(bytes)
		return [...bytes].map(value => value.toString(16).padStart(2, '0')).join('')
	}
	return Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join('')
}

function currentRoute() {
	const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
	return pages.length ? pages[pages.length - 1].route || '' : ''
}

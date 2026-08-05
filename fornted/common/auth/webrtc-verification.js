import {
	WEBRTC_DEFAULT_TIMEOUT_MILLIS,
	collectBrowserWebRtcIps,
	isWebRtcFailureCode,
	isWebRtcRetryCode,
	webRtcErrorFromResponse,
	webRtcTriggerFromHeaders
} from '@shared-auth/webrtc-verification-core.js'
import {
	collectAndroidWebRtcIpsInBackground
} from '@shared-auth/android-webrtc-background-probe.js'
import { AUTH_API_BASE_URL, clientPlatform } from './config.js'
import { getDeviceInstallationId } from './device-installation.js'
import { currentPreAuthToken } from './pre-auth.js'

const START_PATH = '/api/_edge/webrtc/start'
const FAILURE_PAGE = '/pages/risk/webrtc-failed'

let preAuthEpoch = 0
let latestGeneration = ''
let verifiedInMemory = false
const verificationTasks = new Map()
let latestFailure = null
let failureNavigationInFlight = false

export function invalidateWebRtcVerification() {
	preAuthEpoch += 1
	latestGeneration = ''
	verifiedInMemory = false
	verificationTasks.clear()
	latestFailure = null
}

export function observeWebRtcVerificationHeaders(headers = {}) {
	const trigger = webRtcTriggerFromHeaders(headers)
	if (!trigger) return false
	const generation = trigger.generation
	if (compareGeneration(generation, latestGeneration) < 0) return true
	latestGeneration = generation
	if (trigger.state === 'VERIFIED') {
		verifiedInMemory = true
		latestFailure = null
		return true
	}
	verifiedInMemory = false
	if (trigger.state === 'REQUIRED' || trigger.state === 'PENDING') {
		setTimeout(() => {
			void startWebRtcVerificationInBackground(trigger.generation)
				.catch(error => {
					if (isWebRtcFailureCode(error?.code)) presentWebRtcFailure(error)
				})
		}, 0)
	}
	return true
}

export function currentWebRtcFailure() {
	return latestFailure ? { ...latestFailure, webRtcIps: [...latestFailure.webRtcIps] } : null
}

export function startWebRtcVerificationInBackground(expectedGeneration = '') {
	if (verifiedInMemory && !expectedGeneration) {
		return Promise.resolve({ verificationState: 'VERIFIED', webRtcStatus: true })
	}
	const normalized = /^[1-9][0-9]{0,18}$/.test(String(expectedGeneration || ''))
		? String(expectedGeneration)
		: ''
	if (normalized && compareGeneration(normalized, latestGeneration) > 0) {
		latestGeneration = normalized
	}
	const epoch = preAuthEpoch
	// 同一个 PreAuth epoch 同时只运行一个探测；若期间出现新 generation，finally 会接续最新一代。
	const activeEntry = [...verificationTasks.entries()]
		.find(([taskKey]) => taskKey.startsWith(`${epoch}:`))
	if (activeEntry) {
		return activeEntry[1]
	}
	const key = `${epoch}:${normalized || 'discover'}`
	if (!verificationTasks.has(key)) {
		const attempt = { epoch, expectedGeneration: normalized, resolvedGeneration: '' }
		const task = verify(attempt, true)
			.finally(() => {
				if (verificationTasks.get(key) === task) verificationTasks.delete(key)
				const completedGeneration = attempt.resolvedGeneration
					|| attempt.expectedGeneration
				if (epoch === preAuthEpoch
					&& completedGeneration
					&& latestGeneration
					&& compareGeneration(completedGeneration, latestGeneration) !== 0) {
					setTimeout(() => {
						void startWebRtcVerificationInBackground(latestGeneration)
							.catch(() => {})
					}, 0)
				}
			})
		verificationTasks.set(key, task)
	}
	return verificationTasks.get(key)
}

export function ensureWebRtcVerified() {
	return startWebRtcVerificationInBackground(
		latestGeneration)
}

export async function refreshWebRtcFailure() {
	const start = await requestEdge(START_PATH, 'GET')
	const state = verificationState(start)
	if (start?.mode === 'OBSERVE' || start?.mode === 'DISABLED' || state === 'VERIFIED') {
		verifiedInMemory = true
		latestFailure = null
		return null
	}
	if (state === 'PENDING') {
		latestFailure = null
		await startWebRtcVerificationInBackground(String(start.probeGeneration || ''))
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
		complete() { failureNavigationInFlight = false }
	})
	return true
}

async function verify(attempt, allowGenerationRefresh) {
	try {
		const start = await requestEdge(START_PATH, 'GET')
		const state = verificationState(start)
		const generation = String(start?.probeGeneration || '')
		if (start?.mode === 'DISABLED' || state === 'VERIFIED') {
			if (isInvocationCurrent(attempt)) {
				verifiedInMemory = true
				latestFailure = null
			}
			return start
		}
		if (start?.mode === 'OBSERVE' && state === 'FAILED') return start
		if (state === 'FAILED') throw failureError(start)
		if (state !== 'PENDING' || !/^[1-9][0-9]{0,18}$/.test(generation)) {
			throw failureError(start)
		}
		attempt.resolvedGeneration = generation
		const activeAttempt = { epoch: attempt.epoch, generation }
		if (compareGeneration(generation, latestGeneration) > 0) {
			latestGeneration = generation
		}
		if (!isAttemptActive(activeAttempt)) return ignoredResult()

		const remainingMillis = nonNegativeNumber(
			start?.pendingRemainingMillis,
			Number(start?.timeoutMillis || 0) + Number(start?.reportGraceMillis || 0))
		const reportGraceMillis = positiveNumber(start?.reportGraceMillis, 3000)
		const probeMillis = Math.min(
			boundedTimeout(start?.timeoutMillis),
			Math.max(1, remainingMillis - reportGraceMillis))
		const deadlineAt = Date.now() + remainingMillis
		const webRtcIps = clientPlatform() === 'ANDROID'
			? await collectAndroidWebRtcIpsInBackground({
				attemptId: `${activeAttempt.epoch}:${generation}`,
				webviewId: 'ait-user-webrtc',
				resourcePath: '/hybrid/html/webrtc-probe.html',
				stunUrls: start?.stunUrls,
				timeoutMillis: probeMillis
			})
			: await collectBrowserWebRtcIps(start?.stunUrls, probeMillis)
		if (!isAttemptActive(activeAttempt)) return ignoredResult()
		const report = await submitReport(
			start?.reportPath || '/api/_edge/webrtc/report',
			{ probeGeneration: generation, webRtcIps },
			deadlineAt)
		if (!isAttemptActive(activeAttempt)) return ignoredResult()
		if (start?.mode === 'OBSERVE' || report?.webRtcStatus === true) {
			verifiedInMemory = true
			latestFailure = null
			return report
		}
		throw failureError(report)
	} catch (error) {
		if (!isInvocationCurrent(attempt)) return ignoredResult()
		if (allowGenerationRefresh && isWebRtcRetryCode(error?.code)) {
			verifiedInMemory = false
			attempt.expectedGeneration = ''
			attempt.resolvedGeneration = ''
			return verify(attempt, false)
		}
		throw error
	}
}

async function submitReport(path, data, deadlineAt) {
	try {
		return await requestEdge(path, 'POST', data, reportRequestTimeout(deadlineAt))
	} catch (error) {
		if (error?.code !== 'NETWORK_ERROR') throw error
		// Report 可能已在服务端成功落地但响应丢失；幂等 GET start 读取终态后再决定是否重试。
		const status = await requestEdge(START_PATH, 'GET')
		const state = verificationState(status)
		if (state === 'VERIFIED' || status?.mode === 'OBSERVE') return status
		if (state === 'FAILED') throw failureError(status)
		if (String(status?.probeGeneration || '') !== String(data.probeGeneration)
			|| deadlineAt - Date.now() < 1000) throw error
		return requestEdge(path, 'POST', data, reportRequestTimeout(deadlineAt))
	}
}

function requestEdge(path, method, data, timeout = 10000) {
	return new Promise((resolve, reject) => {
		const platform = clientPlatform()
		const headers = {
			Accept: 'application/json',
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
			timeout,
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

function isEpochActive(attempt) {
	return attempt.epoch === preAuthEpoch
}

function isInvocationCurrent(attempt) {
	return isEpochActive(attempt)
		&& (!attempt.expectedGeneration
			|| compareGeneration(attempt.expectedGeneration, latestGeneration) >= 0)
}

function isAttemptActive(attempt) {
	return isEpochActive(attempt) && attempt.generation === latestGeneration
}

function compareGeneration(left, right) {
	const normalizedLeft = String(left || '').replace(/^0+/, '')
	const normalizedRight = String(right || '').replace(/^0+/, '')
	if (normalizedLeft.length !== normalizedRight.length) {
		return normalizedLeft.length - normalizedRight.length
	}
	return normalizedLeft === normalizedRight
		? 0
		: normalizedLeft > normalizedRight ? 1 : -1
}

function ignoredResult() {
	return { verificationState: 'IGNORED', ignored: true }
}

function verificationState(payload) {
	if (typeof payload?.verificationState === 'string') return payload.verificationState
	if (payload?.webRtcStatus === true) return 'VERIFIED'
	if (payload?.webRtcStatus === false) return 'FAILED'
	return payload?.probeRequired === true ? 'PENDING' : 'REQUIRED'
}

function failureError(payload) {
	const error = new Error(payload?.message || 'WebRTC 网络校验失败。')
	error.code = payload?.code || (Array.isArray(payload?.webRtcIps) && payload.webRtcIps.length
		? 'WEBRTC_IP_MISMATCH' : 'WEBRTC_VERIFICATION_FAILED')
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
		retryable: false
	}
}

function boundedTimeout(value) {
	const numeric = Number(value)
	return Number.isFinite(numeric) && numeric > 0
		? Math.min(numeric, WEBRTC_DEFAULT_TIMEOUT_MILLIS)
		: WEBRTC_DEFAULT_TIMEOUT_MILLIS
}

function positiveNumber(value, fallback) {
	const numeric = Number(value)
	return Number.isFinite(numeric) && numeric > 0 ? numeric : fallback
}

function nonNegativeNumber(value, fallback) {
	const numeric = Number(value)
	return Number.isFinite(numeric) && numeric >= 0 ? numeric : fallback
}

function reportRequestTimeout(deadlineAt) {
	return Math.max(1, Math.min(3000, deadlineAt - Date.now()))
}

function currentRoute() {
	const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
	return pages.length ? pages[pages.length - 1].route || '' : ''
}

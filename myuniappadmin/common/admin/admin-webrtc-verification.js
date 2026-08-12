import {
	WEBRTC_DEFAULT_TIMEOUT_MILLIS,
	isWebRtcFailureCode,
	isWebRtcRetryCode,
	webRtcErrorFromResponse,
	webRtcTriggerFromHeaders
} from '@shared-auth/webrtc-verification-core.js'
import {
	createWebRtcDiagnosticLogger
} from '@shared-auth/webrtc-diagnostics.js'
// #ifdef H5
import {
	collectAdminH5VerificationIps
} from './admin-webrtc-verification-h5.js'
// #endif
// #ifdef APP-PLUS
import {
	collectAdminAndroidVerificationIps
} from './admin-webrtc-verification-android.js'
// #endif
import { ADMIN_API_BASE_URL, adminClientPlatform } from './admin-config.js'
import { adminDeviceInstallationId } from './admin-device.js'
import { currentAdminPreAuthToken } from './admin-pre-auth.js'

const START_PATH = '/api/admin/_edge/webrtc/start'
const FAILURE_PAGE = '/pages/risk/webrtc-failed'
const WEBRTC_DIAGNOSTICS_ENABLED = process.env.NODE_ENV === 'development'
const webRtcDiagnostics = createWebRtcDiagnosticLogger(
	'admin-flow',
	WEBRTC_DIAGNOSTICS_ENABLED)

let preAuthEpoch = 0
let latestGeneration = ''
let verifiedInMemory = false
const verificationTasks = new Map()
let latestFailure = null
let failureNavigationInFlight = false
let diagnosticProbeSequence = 0

export function invalidateAdminWebRtcVerification() {
	preAuthEpoch += 1
	latestGeneration = ''
	verifiedInMemory = false
	verificationTasks.clear()
	latestFailure = null
}

export function observeAdminWebRtcVerificationHeaders(headers = {}) {
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
			void startAdminWebRtcVerificationInBackground(trigger.generation)
				.catch(error => {
					if (isWebRtcFailureCode(error?.code)) presentAdminWebRtcFailure(error)
				})
		}, 0)
	}
	return true
}

export function currentAdminWebRtcFailure() {
	return latestFailure ? { ...latestFailure, webRtcIps: [...latestFailure.webRtcIps] } : null
}

export function startAdminWebRtcVerificationInBackground(expectedGeneration = '') {
	const requestProbeRunId = nextProbeRunId(
		'admin',
		`${preAuthEpoch}:${expectedGeneration || 'discover'}`)
	traceAndroidVerification('verification_requested', {
		reason: expectedGeneration ? 'expected_generation' : 'discover',
		probeRunId: requestProbeRunId
	})
	if (verifiedInMemory && !expectedGeneration) {
		traceAndroidVerification('verification_short_circuited', {
			reason: 'verified_memory',
			state: 'VERIFIED',
			probeRunId: requestProbeRunId
		})
		return Promise.resolve({ verificationState: 'VERIFIED', webRtcStatus: true })
	}
	const normalized = /^[1-9][0-9]{0,18}$/.test(String(expectedGeneration || ''))
		? String(expectedGeneration)
		: ''
	if (normalized && compareGeneration(normalized, latestGeneration) > 0) {
		latestGeneration = normalized
	}
	const epoch = preAuthEpoch
	// 管理员端与普通端共享同一串行规则，新 generation 在当前任务完成后自动接续。
	const activeEntry = [...verificationTasks.entries()]
		.find(([taskKey]) => taskKey.startsWith(`${epoch}:`))
	if (activeEntry) {
		return activeEntry[1]
	}
	const key = `${epoch}:${normalized || 'discover'}`
	if (!verificationTasks.has(key)) {
		const attempt = {
			epoch,
			expectedGeneration: normalized,
			resolvedGeneration: '',
			probeRunId: requestProbeRunId
		}
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
						void startAdminWebRtcVerificationInBackground(latestGeneration)
							.catch(() => {})
					}, 0)
				}
			})
		verificationTasks.set(key, task)
	}
	return verificationTasks.get(key)
}

export function ensureAdminWebRtcVerified() {
	return startAdminWebRtcVerificationInBackground(
		latestGeneration)
}

export async function refreshAdminWebRtcFailure() {
	const start = await requestEdge(START_PATH, 'GET')
	const state = verificationState(start)
	if (start?.mode === 'OBSERVE' || start?.mode === 'DISABLED' || state === 'VERIFIED') {
		verifiedInMemory = true
		latestFailure = null
		return null
	}
	if (state === 'PENDING') {
		latestFailure = null
		await startAdminWebRtcVerificationInBackground(String(start.probeGeneration || ''))
		return null
	}
	latestFailure = failureFromPayload(start)
	return currentAdminWebRtcFailure()
}

export function presentAdminWebRtcFailure(error) {
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
	let phase = 'start'
	const trace = (stage, fields = {}) => traceAndroidVerification(stage, {
		...fields,
		probeRunId: attempt.probeRunId
	})
	try {
		trace('start_request_started')
		const start = await requestEdge(START_PATH, 'GET')
		const state = verificationState(start)
		const generation = String(start?.probeGeneration || '')
		trace('start_response_received', {
			mode: diagnosticCode(start?.mode, 'UNKNOWN'),
			state: diagnosticCode(state, 'UNKNOWN'),
			stunCount: Array.isArray(start?.stunUrls) ? start.stunUrls.length : 0,
			timeoutMillis: nonNegativeNumber(start?.timeoutMillis, 0),
			remainingMillis: nonNegativeNumber(start?.pendingRemainingMillis, 0)
		})
		if (start?.mode === 'DISABLED' || state === 'VERIFIED') {
			trace('verification_short_circuited', {
				reason: start?.mode === 'DISABLED' ? 'disabled' : 'verified',
				mode: diagnosticCode(start?.mode, 'UNKNOWN'),
				state: diagnosticCode(state, 'UNKNOWN')
			})
			if (isInvocationCurrent(attempt)) {
				verifiedInMemory = true
				latestFailure = null
			}
			return start
		}
		if (start?.mode === 'OBSERVE' && state === 'FAILED') {
			trace('verification_short_circuited', {
				reason: 'observe_failed',
				mode: 'OBSERVE',
				state: 'FAILED'
			})
			return start
		}
		if (state === 'FAILED') {
			trace('verification_short_circuited', {
				reason: 'failed',
				state: 'FAILED'
			})
			throw failureError(start)
		}
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
		phase = 'probe'
		trace('platform_probe_started', {
			timeoutMillis: probeMillis,
			stunCount: Array.isArray(start?.stunUrls) ? start.stunUrls.length : 0
		})
		const webRtcIps = await collectPlatformVerificationIps({
			attemptId: `${activeAttempt.epoch}:${generation}`,
			probeRunId: attempt.probeRunId,
			stunUrls: start?.stunUrls,
			timeoutMillis: probeMillis
		})
		trace('platform_probe_completed', {
			candidateCount: webRtcIps.length
		})
		if (!isAttemptActive(activeAttempt)) return ignoredResult()
		phase = 'report'
		const reportPayload = { probeGeneration: generation, webRtcIps }
		trace('report_payload_prepared', {
			candidateCount: reportPayload.webRtcIps.length
		})
		trace('report_started', {
			candidateCount: reportPayload.webRtcIps.length
		})
		const report = await submitReport(
			start?.reportPath || '/api/admin/_edge/webrtc/report',
			reportPayload,
			deadlineAt)
		trace('report_completed', {
			candidateCount: webRtcIps.length,
			webRtcStatus: report?.webRtcStatus === true
		})
		if (!isAttemptActive(activeAttempt)) return ignoredResult()
		if (start?.mode === 'OBSERVE' || report?.webRtcStatus === true) {
			verifiedInMemory = true
			latestFailure = null
			trace('verification_succeeded', {
				candidateCount: webRtcIps.length,
				webRtcStatus: report?.webRtcStatus === true
			})
			return report
		}
		throw failureError(report)
	} catch (error) {
		if (!isInvocationCurrent(attempt)) return ignoredResult()
		if (phase === 'report') {
			trace('report_failed', {
				errorCode: diagnosticCode(error?.code, 'REPORT_FAILED'),
				retryable: error?.retryable === true,
				webRtcStatus: error?.webRtcStatus === true
			})
		}
		trace('verification_failed', {
			errorCode: diagnosticCode(error?.code, 'VERIFICATION_FAILED'),
			retryable: error?.retryable === true
		})
		if (allowGenerationRefresh && isWebRtcRetryCode(error?.code)) {
			verifiedInMemory = false
			attempt.expectedGeneration = ''
			attempt.resolvedGeneration = ''
			attempt.probeRunId = nextProbeRunId('admin', `${attempt.epoch}:refresh`)
			return verify(attempt, false)
		}
		throw error
	}
}

function traceAndroidVerification(stage, fields = {}) {
	if (adminClientPlatform() !== 'ANDROID') return
	webRtcDiagnostics(stage, fields)
}

function diagnosticCode(value, fallback) {
	const normalized = String(value || fallback)
		.replace(/[^A-Za-z0-9_-]/g, '')
		.slice(0, 64)
	return normalized || fallback
}

function nextProbeRunId(role, attemptId) {
	diagnosticProbeSequence = diagnosticProbeSequence >= 999999
		? 1
		: diagnosticProbeSequence + 1
	const safeAttemptId = String(attemptId || 'discover')
		.replace(/[^A-Za-z0-9:_-]/g, '')
		.slice(0, 40) || 'discover'
	return `${role}-${safeAttemptId}-${diagnosticProbeSequence}`
}

async function collectPlatformVerificationIps(options) {
	// #ifdef H5
	return collectAdminH5VerificationIps(options)
	// #endif

	// #ifdef APP-PLUS
	if (adminClientPlatform() === 'ANDROID') {
		return collectAdminAndroidVerificationIps(options)
	}
	// #endif
	return []
}

async function submitReport(path, data, deadlineAt) {
	try {
		return await requestEdge(path, 'POST', data, reportRequestTimeout(deadlineAt))
	} catch (error) {
		if (error?.code !== 'NETWORK_ERROR') throw error
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
		const platform = adminClientPlatform()
		const headers = {
			Accept: 'application/json',
			'Content-Type': 'application/json',
			'X-Client-Platform': platform,
			'X-Device-Installation-Id': adminDeviceInstallationId()
		}
		const preAuthToken = currentAdminPreAuthToken()
		if (platform === 'ANDROID' && preAuthToken) headers['X-AIT-PreAuth'] = preAuthToken
		uni.request({
			url: `${ADMIN_API_BASE_URL}${path}`,
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
				const error = webRtcErrorFromResponse(
					response,
					'管理员 WebRTC 网络校验失败。')
				error.challengeRef = response.data?.challengeRef || ''
				error.challengePath = response.data?.challengePath || ''
				error.expiresAt = response.data?.expiresAt || ''
				error.reauthenticationRequired = response.data?.reauthenticationRequired === true
				reject(error)
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
	const error = new Error(payload?.message || '管理员 WebRTC 网络校验失败。')
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
		message: error.message || '管理员 WebRTC 网络校验失败。',
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

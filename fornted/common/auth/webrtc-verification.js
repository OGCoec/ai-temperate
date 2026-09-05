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
	collectH5VerificationIps
} from './webrtc-verification-h5.js'
// #endif
// #ifdef APP-PLUS
import {
	collectAndroidVerificationIps
} from './webrtc-verification-android.js'
// #endif
import { AUTH_API_BASE_URL, clientPlatform, usesExplicitTokenTransport } from './config.js'
import { isBlockingWebRtc as isAndroidOAuthBlockingWebRtc } from './android-oauth-coordinator.js'
import { getDeviceInstallationId } from './device-installation.js'
import { ownsH5WebRtcScheduling } from './h5-oauth-webrtc-gate.js'
import { currentPreAuthToken, isPreAuthReady } from './pre-auth.js'
import { presentRiskBlock } from './risk-block-navigation.js'
import {
	authDiagnosticRequestHeaders,
	createAuthDiagnosticId,
	createAuthRequestDiagnostic,
	flushAuthDiagnostics,
	recordAuthDiagnosticEvent,
	recordAuthDiagnosticFailure,
	recordAuthDiagnosticResponse,
	setCurrentAuthDiagnosticWebRtcProbeRunId
} from './auth-diagnostics.js'

const START_PATH = '/api/_edge/webrtc/start'
const VERDICT_STATUS_PATH = '/api/_edge/webrtc/verdict-status'
const FAILURE_PAGE = '/pages/risk/webrtc-failed'
const DEFAULT_CANCEL_REASON = 'EPOCH_INVALIDATED'
const WEBRTC_ATTEMPT_ABORTED = 'WEBRTC_ATTEMPT_ABORTED'
const WEBRTC_DIAGNOSTICS_ENABLED = process.env.NODE_ENV === 'development'
const webRtcDiagnostics = createWebRtcDiagnosticLogger(
	'user-flow',
	WEBRTC_DIAGNOSTICS_ENABLED)

let preAuthEpoch = 0
let latestGeneration = ''
let verifiedInMemory = false
const verificationTasks = new Map()
const oauthAttemptTasks = new Map()
let latestFailure = null
let failureNavigationInFlight = false
let activeDiagnosticAttempt = null
let oauthPrepareTask = null
let suspendedOAuthAttempt = null

function createStartHandshake() {
	let resolvePromise
	let rejectPromise
	const handshake = {
		settled: false,
		promise: new Promise((resolve, reject) => {
			resolvePromise = resolve
			rejectPromise = reject
		}),
		resolve(value) {
			if (handshake.settled) return
			handshake.settled = true
			resolvePromise(value)
		},
		reject(error) {
			if (handshake.settled) return
			handshake.settled = true
			rejectPromise(error)
		}
	}
	// 普通后台探测可能无人等待 start 握手；预挂拒绝处理避免取消时产生未处理 Promise。
	void handshake.promise.catch(() => {})
	return handshake
}

export function invalidateWebRtcVerification(reason = DEFAULT_CANCEL_REASON) {
	const attempt = activeDiagnosticAttempt
	const cancelReason = diagnosticCode(reason, DEFAULT_CANCEL_REASON)
	// 先提升 epoch 再终止资源，确保 abort 回调即使同步触发也只能观察到旧上下文。
	preAuthEpoch += 1
	if (attempt) cancelWebRtcAttempt(attempt, cancelReason)
	latestGeneration = ''
	verifiedInMemory = false
	verificationTasks.clear()
	latestFailure = null
	activeDiagnosticAttempt = null
	suspendedOAuthAttempt = null
	setCurrentAuthDiagnosticWebRtcProbeRunId('')
	recordAuthDiagnosticEvent('WEBRTC_INVALIDATED', {
		source: 'invalidate_webrtc_verification',
		preAuthEpoch,
		cancelReason,
		outcome: 'invalidated'
	})
}

export function currentWebRtcVerificationEpoch() {
	return preAuthEpoch
}

export function cancelActiveWebRtcVerification(reason = DEFAULT_CANCEL_REASON) {
	if (!activeDiagnosticAttempt) return false
	invalidateWebRtcVerification(reason)
	return true
}

/**
 * 收敛 H5 文档离开时仍在运行的探测，且不尝试在卸载期间补发网络请求。
 */
export function installH5WebRtcDiagnosticLifecycle() {
	// #ifdef H5
	const target = globalThis.window
	if (typeof target?.addEventListener !== 'function'
		|| target.__aitWebRtcDiagnosticLifecycleInstalled === true) return false
	target.__aitWebRtcDiagnosticLifecycleInstalled = true
	target.addEventListener('pagehide', event => {
		if (!activeDiagnosticAttempt) return
		recordAuthDiagnosticEvent('WEBRTC_PAGEHIDE_WITH_ACTIVE_ATTEMPT', {
			...activeAttemptDiagnosticFields(activeDiagnosticAttempt),
			persisted: event?.persisted === true,
			source: 'window_pagehide'
		})
		if (suspendedOAuthAttempt) {
			cancelWebRtcAttempt(activeDiagnosticAttempt, 'OAUTH_SUSPENDED', true)
			activeDiagnosticAttempt = null
		} else {
			invalidateWebRtcVerification('DOCUMENT_UNLOADED')
		}
		flushAuthDiagnostics()
	})
	target.addEventListener('pageshow', event => {
		if (event?.persisted !== true
			|| !isPreAuthReady()
			|| ownsH5WebRtcScheduling()) return
		scheduleH5WebRtcVerification({
			path: currentRoute() || START_PATH,
			source: 'bfcache_pageshow'
		})
	})
	globalThis.document?.addEventListener?.('visibilitychange', () => {
		if (!activeDiagnosticAttempt) return
		recordAuthDiagnosticEvent('WEBRTC_VISIBILITY_CHANGED_WITH_ACTIVE_ATTEMPT', {
			...activeAttemptDiagnosticFields(activeDiagnosticAttempt),
			source: 'document_visibility'
		})
		flushAuthDiagnostics()
	})
	return true
	// #endif
	// #ifndef H5
	return false
	// #endif
}

export function observeWebRtcVerificationHeaders(headers = {}, context = {}) {
	const trigger = webRtcTriggerFromHeaders(headers)
	if (!trigger) return false
	const generation = trigger.generation
	const requestEpoch = normalizedEpoch(context?.requestEpoch, preAuthEpoch)
	const diagnosticFields = responseHeaderDiagnosticFields(
		context,
		requestEpoch,
		generation)
	if (context?.responseAccepted !== true) {
		recordAuthDiagnosticEvent('WEBRTC_RESPONSE_HEADER_IGNORED', {
			...diagnosticFields,
			outcome: 'non_success_response'
		})
		return true
	}
	if (requestEpoch !== preAuthEpoch) {
		recordAuthDiagnosticEvent('WEBRTC_RESPONSE_HEADER_IGNORED', {
			...diagnosticFields,
			outcome: 'stale_epoch'
		})
		return true
	}
	if (compareGeneration(generation, latestGeneration) < 0) {
		recordAuthDiagnosticEvent('WEBRTC_RESPONSE_HEADER_IGNORED', {
			...diagnosticFields,
			outcome: 'stale_generation'
		})
		return true
	}
	recordAuthDiagnosticEvent('WEBRTC_RESPONSE_HEADER_ACCEPTED', {
		...diagnosticFields,
		outcome: diagnosticCode(trigger.state, 'accepted')
	})
	latestGeneration = generation
	if (trigger.state === 'VERIFIED') {
		verifiedInMemory = true
		latestFailure = null
		return true
	}
	verifiedInMemory = false
	if (trigger.state === 'REQUIRED' || trigger.state === 'PENDING') {
		latestFailure = null
		if (clientPlatform() === 'H5') {
			scheduleH5WebRtcVerification({
				clientRequestId: context?.clientRequestId,
				triggerClientRequestId: context?.clientRequestId,
				expectedGeneration: generation,
				path: context?.path || START_PATH,
				requestEpoch,
				source: context?.source || 'response_headers'
			})
		} else if (clientPlatform() === 'ANDROID') {
			setTimeout(() => {
				void startPlatformWebRtcVerification(generation, {
					clientRequestId: context?.clientRequestId,
					triggerClientRequestId: context?.clientRequestId,
					path: context?.path || START_PATH,
					requestEpoch,
					source: context?.source || 'response_headers'
				})
					.catch(error => {
						if (presentRiskBlock(error)) return
						if (isWebRtcFailureCode(error?.code)) presentWebRtcFailure(error)
					})
			}, 0)
		}
	}
	return true
}

/**
 * 调度 H5 WebRTC 后台探测且不向调用方暴露 Promise，防止普通请求重新把探测串行化。
 */
export function scheduleH5WebRtcVerification(context = {}) {
	// #ifdef H5
	const epoch = preAuthEpoch
	const requestEpoch = normalizedEpoch(context?.requestEpoch, epoch)
	const expectedGeneration = normalizedGeneration(context?.expectedGeneration)
	const diagnosticFields = backgroundDiagnosticFields(
		context,
		expectedGeneration,
		requestEpoch)
	if (ownsH5WebRtcScheduling()) {
		recordAuthDiagnosticEvent('WEBRTC_BACKGROUND_SKIPPED', {
			...diagnosticFields,
			outcome: 'oauth_attempt_owned'
		})
		return
	}
	if (requestEpoch !== epoch) {
		recordAuthDiagnosticEvent('WEBRTC_BACKGROUND_SKIPPED', {
			...diagnosticFields,
			outcome: 'stale_epoch'
		})
		return
	}
	if (!isPreAuthReady()) {
		recordAuthDiagnosticEvent('WEBRTC_BACKGROUND_SKIPPED', {
			...diagnosticFields,
			outcome: 'preauth_not_ready'
		})
		return
	}
	recordAuthDiagnosticEvent('WEBRTC_BACKGROUND_SCHEDULED', {
		...diagnosticFields,
		outcome: 'scheduled'
	})
	void startPlatformWebRtcVerification(expectedGeneration, {
		...context,
		requestEpoch
	})
		.then(result => {
			// 跨页或跨域返回后旧 epoch 的完成结果只结束旧任务，不得污染当前诊断和安全状态。
			if (epoch !== preAuthEpoch) return
			recordAuthDiagnosticEvent('WEBRTC_BACKGROUND_COMPLETED', {
				...diagnosticFields,
				outcome: diagnosticCode(result?.verificationState, 'completed')
			})
		})
		.catch(error => {
			if (epoch !== preAuthEpoch) return
			recordAuthDiagnosticEvent('WEBRTC_BACKGROUND_FAILED', {
				...diagnosticFields,
				errorCode: diagnosticCode(error?.code, 'VERIFICATION_FAILED'),
				outcome: 'failed'
			})
			if (presentRiskBlock(error)) return
			if (isWebRtcFailureCode(error?.code)) presentWebRtcFailure(error)
		})
	// #endif
}

export function currentWebRtcFailure() {
	return latestFailure ? { ...latestFailure, webRtcIps: [...latestFailure.webRtcIps] } : null
}

export function ensureH5WebRtcVerified(context = {}) {
	// #ifdef H5
	return startPlatformWebRtcVerification('', context)
	// #endif
	// #ifndef H5
	return Promise.resolve(ignoredResult())
	// #endif
}

export function startAndroidWebRtcVerificationInBackground(expectedGeneration = '') {
	// #ifdef APP-PLUS
	if (clientPlatform() === 'ANDROID' && isAndroidOAuthBlockingWebRtc()) {
		recordAuthDiagnosticEvent('ANDROID_WEBRTC_SKIPPED_DURING_OAUTH', {
			phase: 'oauth_active',
			outcome: 'suppressed'
		})
		return Promise.resolve({ verificationState: 'PENDING', webRtcStatus: null })
	}
	return startPlatformWebRtcVerification(expectedGeneration)
	// #endif
	// #ifndef APP-PLUS
	return Promise.resolve(ignoredResult())
	// #endif
}

function startPlatformWebRtcVerification(expectedGeneration = '', context = {}) {
	if (clientPlatform() === 'H5' && ownsH5WebRtcScheduling()) {
		recordAuthDiagnosticEvent('WEBRTC_BACKGROUND_SKIPPED', {
			...singleFlightDiagnosticFields(context, expectedGeneration),
			preAuthEpoch,
			outcome: 'oauth_attempt_owned'
		})
		return Promise.resolve({
			verificationState: 'PENDING',
			webRtcStatus: null,
			oauthAttemptOwned: true
		})
	}
	if (verifiedInMemory && !expectedGeneration) {
		recordAuthDiagnosticEvent('WEBRTC_SINGLE_FLIGHT', {
			...singleFlightDiagnosticFields(context, expectedGeneration),
			preAuthEpoch,
			outcome: 'verified_memory',
			owner: false,
			waiter: false
		})
		traceAndroidVerification('verification_short_circuited', {
			reason: 'verified_memory',
			state: 'VERIFIED'
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
	// 同一个 PreAuth epoch 同时只运行一个探测；若期间出现新 generation，finally 会接续最新一代。
	const activeEntry = [...verificationTasks.entries()]
		.find(([taskKey]) => taskKey.startsWith(`${epoch}:`))
	if (activeEntry) {
		recordAuthDiagnosticEvent('WEBRTC_SINGLE_FLIGHT', {
			...singleFlightDiagnosticFields({
				...context,
				probeRunId: activeDiagnosticAttempt?.probeRunId
			}, expectedGeneration),
			preAuthEpoch,
			webRtcGeneration: expectedGeneration || 'discover',
			outcome: 'joined',
			owner: false,
			waiter: true
		})
		return activeEntry[1]
	}
	const key = `${epoch}:${normalized || 'discover'}`
	if (!verificationTasks.has(key)) {
		const requestProbeRunId = normalizedDiagnosticId(context?.probeRunId)
			|| nextProbeRunId()
		setCurrentAuthDiagnosticWebRtcProbeRunId(requestProbeRunId)
		const diagnosticContext = { ...context, probeRunId: requestProbeRunId }
		traceAndroidVerification('verification_requested', {
			reason: expectedGeneration ? 'expected_generation' : 'discover',
			probeRunId: requestProbeRunId
		})
		recordAuthDiagnosticEvent('WEBRTC_SINGLE_FLIGHT', {
			...singleFlightDiagnosticFields(diagnosticContext, normalized),
			preAuthEpoch,
			webRtcGeneration: normalized || 'discover',
			outcome: 'created',
			owner: true,
			waiter: false
		})
		const attempt = {
			epoch,
			expectedGeneration: normalized,
			resolvedGeneration: '',
			probeRunId: requestProbeRunId,
			phase: 'STARTED',
			deadlineAt: 0,
			startedAt: diagnosticNow(),
			abortController: createAttemptAbortController(),
			requestTasks: new Set(),
			cancelled: false,
			cancelReason: '',
			settled: false,
			triggerContext: {
				triggerClientRequestId: context?.triggerClientRequestId
					|| context?.clientRequestId,
				path: context?.path,
				requestEpoch: normalizedEpoch(context?.requestEpoch, epoch),
				source: context?.source
			},
			startHandshake: createStartHandshake()
		}
		activeDiagnosticAttempt = attempt
		recordAuthDiagnosticEvent('WEBRTC_ATTEMPT_CREATED', {
			...activeAttemptDiagnosticFields(attempt),
			outcome: 'created'
		})
		const task = verify(attempt, true)
			.finally(() => {
				attempt.settled = true
				if (verificationTasks.get(key) === task) verificationTasks.delete(key)
				if (activeDiagnosticAttempt === attempt) activeDiagnosticAttempt = null
				const completedGeneration = attempt.resolvedGeneration
					|| attempt.expectedGeneration
				if (epoch === preAuthEpoch
					&& completedGeneration
					&& latestGeneration
					&& compareGeneration(completedGeneration, latestGeneration) !== 0) {
					setTimeout(() => {
						void startPlatformWebRtcVerification(
							latestGeneration,
							attempt.triggerContext)
							.catch(() => {})
					}, 0)
				}
			})
		verificationTasks.set(key, task)
	}
	return verificationTasks.get(key)
}

export async function refreshWebRtcFailure() {
	if (clientPlatform() === 'WECHAT_MINI_PROGRAM') {
		return null
	}
	if (clientPlatform() === 'H5' && ownsH5WebRtcScheduling()) {
		recordAuthDiagnosticEvent('WEBRTC_BACKGROUND_SKIPPED', {
			source: 'refresh_webrtc_failure',
			outcome: 'oauth_attempt_owned'
		})
		return null
	}
	const start = await requestEdge(START_PATH, 'GET')
	const state = verificationState(start)
	if (start?.mode === 'OBSERVE' || start?.mode === 'DISABLED' || state === 'VERIFIED') {
		verifiedInMemory = true
		latestFailure = null
		return null
	}
	if (state === 'PENDING') {
		latestFailure = null
		await startPlatformWebRtcVerification(String(start.probeGeneration || ''))
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
	let phase = 'start'
	const trace = (stage, fields = {}) => traceAndroidVerification(stage, {
		...fields,
		probeRunId: attempt.probeRunId
	})
	try {
		attempt.phase = 'STARTED'
		assertAttemptActive(attempt)
		trace('start_request_started')
		const start = await requestEdge(
			START_PATH,
			'GET',
			undefined,
			10000,
			{
				...attempt.triggerContext,
				probeRunId: attempt.probeRunId,
				attempt
			})
		assertAttemptActive(attempt)
		const state = verificationState(start)
		const generation = String(start?.probeGeneration || '')
		const timeoutMillis = nonNegativeNumber(start?.timeoutMillis, 0)
		const remainingMillis = nonNegativeNumber(
			start?.pendingRemainingMillis,
			Number(start?.timeoutMillis || 0) + Number(start?.reportGraceMillis || 0))
		const reportGraceMillis = positiveNumber(start?.reportGraceMillis, 3000)
		const probeMillis = state === 'PENDING'
			? Math.min(
				boundedTimeout(start?.timeoutMillis),
				Math.max(1, remainingMillis - reportGraceMillis))
			: 0
		attempt.deadlineAt = state === 'PENDING' ? Date.now() + remainingMillis : 0
		trace('start_response_received', {
			mode: diagnosticCode(start?.mode, 'UNKNOWN'),
			state: diagnosticCode(state, 'UNKNOWN'),
			stunCount: Array.isArray(start?.stunUrls) ? start.stunUrls.length : 0,
			timeoutMillis,
			remainingMillis
		})
		recordAuthDiagnosticEvent('WEBRTC_START_RESOLVED', {
			...activeAttemptDiagnosticFields(attempt),
			mode: diagnosticCode(start?.mode, 'UNKNOWN'),
			verificationState: diagnosticCode(state, 'UNKNOWN'),
			generation: generation || 'discover',
			webRtcGeneration: generation || 'discover',
			startDisposition: startDisposition(
				state,
				remainingMillis,
				timeoutMillis,
				reportGraceMillis),
			pendingRemainingMs: remainingMillis,
			timeoutMs: timeoutMillis,
			reportGraceMs: reportGraceMillis,
			probeBudgetMs: probeMillis,
			stunCount: Array.isArray(start?.stunUrls) ? start.stunUrls.length : 0,
			outcome: 'received'
		})
		if (start?.mode === 'DISABLED' || state === 'VERIFIED') {
			attempt.startHandshake.resolve({
				...start,
				generation,
				probeGeneration: generation,
				probeRunId: attempt.probeRunId,
				phase: state
			})
			trace('verification_short_circuited', {
				reason: start?.mode === 'DISABLED' ? 'disabled' : 'verified',
				mode: diagnosticCode(start?.mode, 'UNKNOWN'),
				state: diagnosticCode(state, 'UNKNOWN')
			})
			if (isInvocationCurrent(attempt)) {
				verifiedInMemory = true
				latestFailure = null
			}
			recordAttemptCompleted(attempt, 'succeeded', {
				verificationState: state,
				webRtcStatus: true
			})
			return start
		}
		if (start?.mode === 'OBSERVE' && state === 'FAILED') {
			// OBSERVE 只保留失败证据，不得让 OAuth 点击因为诊断结果而停止跳转。
			attempt.startHandshake.resolve({
				...start,
				generation,
				probeGeneration: generation,
				probeRunId: attempt.probeRunId,
				phase: state
			})
			trace('verification_short_circuited', {
				reason: 'observe_failed',
				mode: 'OBSERVE',
				state: 'FAILED'
			})
			recordAttemptCompleted(attempt, 'observed_failure', {
				verificationState: state,
				webRtcStatus: false
			})
			return start
		}
		if (state === 'FAILED') {
			attempt.startHandshake.reject(failureError(start))
			trace('verification_short_circuited', {
				reason: 'failed',
				state: 'FAILED'
			})
			throw failureError(start)
		}
		if (state !== 'PENDING' || !/^[1-9][0-9]{0,18}$/.test(generation)) {
			attempt.startHandshake.reject(failureError(start))
			throw failureError(start)
		}
		attempt.resolvedGeneration = generation
		attempt.startHandshake.resolve({
			...start,
			generation,
			probeGeneration: generation,
			probeRunId: attempt.probeRunId,
			phase: state
		})
		const activeAttempt = { epoch: attempt.epoch, generation }
		if (compareGeneration(generation, latestGeneration) > 0) {
			latestGeneration = generation
		}
		if (!isAttemptActive(activeAttempt)) return ignoredResult()

		const deadlineAt = attempt.deadlineAt
		phase = 'probe'
		attempt.phase = 'COLLECTING'
		assertAttemptActive(attempt)
		const probeStartedAt = diagnosticNow()
		trace('platform_probe_started', {
			timeoutMillis: probeMillis,
			stunCount: Array.isArray(start?.stunUrls) ? start.stunUrls.length : 0
		})
		recordAuthDiagnosticEvent('WEBRTC_PROBE_STARTED', {
			...activeAttemptDiagnosticFields(attempt),
			webRtcGeneration: generation,
			probeBudgetMs: probeMillis,
			stunCount: Array.isArray(start?.stunUrls) ? start.stunUrls.length : 0,
			outcome: 'started'
		})
		let probeFinish = {}
		const diagnosticTrace = (stage, fields = {}) => {
			webRtcDiagnostics(stage, {
				...fields,
				probeRunId: attempt.probeRunId
			})
			if (stage === 'ice_finished' || stage === 'ice_timeout') {
				probeFinish = { ...fields, stage }
			}
		}
		const webRtcIps = await collectPlatformVerificationIps({
			attemptId: `${activeAttempt.epoch}:${generation}`,
			probeRunId: attempt.probeRunId,
			stunUrls: start?.stunUrls,
			timeoutMillis: probeMillis,
			diagnosticTrace,
			signal: attempt.abortController.signal
		})
		assertAttemptActive(attempt)
		const candidateFamilies = webRtcCandidateFamilyCounts(webRtcIps)
		trace('platform_probe_completed', {
			candidateCount: webRtcIps.length
		})
		recordAuthDiagnosticEvent('WEBRTC_PROBE_FINISHED', {
			...activeAttemptDiagnosticFields(attempt),
			webRtcGeneration: generation,
			probeDurationMs: diagnosticNow() - probeStartedAt,
			candidateCount: webRtcIps.length,
			hostCount: probeFinish.hostCount,
			srflxCount: probeFinish.srflxCount,
			relayCount: probeFinish.ignoredRelayCount,
			acceptedCount: probeFinish.acceptedCount ?? webRtcIps.length,
			acceptedHostCount: probeFinish.acceptedHostCount,
			acceptedSrflxCount: probeFinish.acceptedSrflxCount,
			ignoredRelayCount: probeFinish.ignoredRelayCount,
			rejectedNonPublicCount: probeFinish.rejectedNonPublicCount,
			ipv4Count: probeFinish.ipv4Count ?? candidateFamilies.ipv4Count,
			ipv6Count: probeFinish.ipv6Count ?? candidateFamilies.ipv6Count,
			finishReason: probeFinishReason(probeFinish),
			outcome: 'completed'
		})
		if (!isAttemptActive(activeAttempt)) return ignoredResult()
		phase = 'report'
		attempt.phase = 'REPORTING'
		assertAttemptActive(attempt)
		const reportPayload = { probeGeneration: generation, webRtcIps }
		trace('report_payload_prepared', {
			candidateCount: reportPayload.webRtcIps.length
		})
		trace('report_started', {
			candidateCount: reportPayload.webRtcIps.length
		})
		recordAuthDiagnosticEvent('WEBRTC_REPORT_PREPARED', {
			...activeAttemptDiagnosticFields(attempt),
			webRtcGeneration: generation,
			candidateCount: reportPayload.webRtcIps.length,
			deadlineRemainingMs: Math.max(0, deadlineAt - Date.now()),
			reportDispatched: false,
			outcome: 'prepared'
		})
		recordAuthDiagnosticEvent('WEBRTC_REPORT_DISPATCHED', {
			...activeAttemptDiagnosticFields(attempt),
			webRtcGeneration: generation,
			candidateCount: reportPayload.webRtcIps.length,
			deadlineRemainingMs: Math.max(0, deadlineAt - Date.now()),
			reportDispatched: true,
			outcome: 'dispatched'
		})
		const report = await submitReport(
			start?.reportPath || '/api/_edge/webrtc/report',
			reportPayload,
			deadlineAt,
			{
				...attempt.triggerContext,
				probeRunId: attempt.probeRunId,
				attempt
			})
		assertAttemptActive(attempt)
		trace('report_completed', {
			candidateCount: webRtcIps.length,
			webRtcStatus: report?.webRtcStatus === true
		})
		recordAuthDiagnosticEvent('WEBRTC_REPORT_COMPLETED', {
			...activeAttemptDiagnosticFields(attempt),
			webRtcGeneration: generation,
			candidateCount: webRtcIps.length,
			webRtcStatus: report?.webRtcStatus === true,
			verificationState: verificationState(report),
			outcome: report?.webRtcStatus === true ? 'verified' : 'rejected'
		})
		if (!isAttemptActive(activeAttempt)) return ignoredResult()
		if (start?.mode === 'OBSERVE' || report?.webRtcStatus === true) {
			verifiedInMemory = true
			latestFailure = null
			trace('verification_succeeded', {
				candidateCount: webRtcIps.length,
				webRtcStatus: report?.webRtcStatus === true
			})
			recordAttemptCompleted(attempt, 'succeeded', {
				verificationState: verificationState(report),
				webRtcStatus: report?.webRtcStatus === true
			})
			return report
		}
		throw failureError(report)
	} catch (error) {
		attempt.startHandshake?.reject(error)
		if (isWebRtcAttemptCancellation(error)
			|| attempt.cancelled
			|| attempt.epoch !== preAuthEpoch) {
			recordReportSkipped(
				attempt,
				error?.cancelReason || attempt.cancelReason || 'STALE_EPOCH')
			return ignoredResult()
		}
		if (!isInvocationCurrent(attempt)) return ignoredResult()
		if (phase === 'report') {
			trace('report_failed', {
				errorCode: diagnosticCode(error?.code, 'REPORT_FAILED'),
				retryable: error?.retryable === true,
				webRtcStatus: error?.webRtcStatus === true
			})
			recordAuthDiagnosticEvent('WEBRTC_REPORT_FAILED', {
				...activeAttemptDiagnosticFields(attempt),
				webRtcGeneration: attempt.resolvedGeneration || attempt.expectedGeneration,
				errorCode: diagnosticCode(error?.code, 'REPORT_FAILED'),
				retryable: error?.retryable === true,
				webRtcStatus: error?.webRtcStatus === true,
				outcome: 'failed'
			})
		}
		trace('verification_failed', {
			errorCode: diagnosticCode(error?.code, 'VERIFICATION_FAILED'),
			retryable: error?.retryable === true
		})
		if (allowGenerationRefresh && isWebRtcRetryCode(error?.code)) {
			recordAttemptCompleted(attempt, 'retrying', {
				errorCode: diagnosticCode(error?.code, 'VERIFICATION_FAILED')
			})
			verifiedInMemory = false
			attempt.expectedGeneration = ''
			attempt.resolvedGeneration = ''
			attempt.probeRunId = nextProbeRunId()
			setCurrentAuthDiagnosticWebRtcProbeRunId(attempt.probeRunId)
			attempt.phase = 'STARTED'
			attempt.deadlineAt = 0
			attempt.startedAt = diagnosticNow()
			recordAuthDiagnosticEvent('WEBRTC_ATTEMPT_CREATED', {
				...activeAttemptDiagnosticFields(attempt),
				outcome: 'retry_created'
			})
			return verify(attempt, false)
		}
		recordAttemptCompleted(attempt, 'failed', {
			errorCode: diagnosticCode(error?.code, 'VERIFICATION_FAILED'),
			retryable: error?.retryable === true
		})
		throw error
	}
}

/**
 * 只取得当前 PreAuth 的 start 握手与 generation，不执行候选采集或 report。
 */
export function prepareWebRtcAttempt(context = {}) {
	// #ifdef H5
	if (oauthPrepareTask) return oauthPrepareTask
	if (verifiedInMemory) {
		return Promise.resolve({
			verificationState: 'VERIFIED',
			webRtcStatus: true,
			phase: 'VERIFIED',
			generation: latestGeneration,
			probeGeneration: latestGeneration,
			probeRunId: nextProbeRunId()
		})
	}
	if (activeDiagnosticAttempt?.epoch !== preAuthEpoch) {
		void startPlatformWebRtcVerification('', {
			...context,
			probeRunId: normalizedDiagnosticId(context?.probeRunId) || nextProbeRunId(),
			source: context?.source || 'oauth_prepare'
		}).catch(() => {})
	}
	const owner = activeDiagnosticAttempt?.epoch === preAuthEpoch
		? activeDiagnosticAttempt : null
	if (!owner?.startHandshake?.promise) {
		return Promise.reject(failureError({ code: 'WEBRTC_VERIFICATION_FAILED' }))
	}
	// OAuth 点击加入页面启动时已经存在的 start 握手，不再发送第二个 start 请求。
	oauthPrepareTask = owner.startHandshake.promise
		.finally(() => { oauthPrepareTask = null })
	return oauthPrepareTask
	// #endif
	// #ifndef H5
	return Promise.resolve(ignoredResult())
	// #endif
}

/**
 * OAuth 外跳只停止当前文档的本地资源，不提升 PreAuth epoch，也不废弃服务端 generation。
 */
export function suspendH5WebRtcForOAuth(prepared) {
	// #ifdef H5
	const generation = normalizedGeneration(
		prepared?.generation || prepared?.probeGeneration || latestGeneration)
	suspendedOAuthAttempt = {
		generation,
		probeRunId: normalizedDiagnosticId(prepared?.probeRunId),
		phase: 'OAUTH_SUSPENDED'
	}
	if (activeDiagnosticAttempt) {
		cancelWebRtcAttempt(activeDiagnosticAttempt, 'OAUTH_SUSPENDED', true)
		activeDiagnosticAttempt = null
	}
	verificationTasks.clear()
	return { ...suspendedOAuthAttempt }
	// #endif
	// #ifndef H5
	return null
	// #endif
}

/** 恢复回调页返回的同一 attempt 配置，不触发第二次 start。 */
export function resumeOAuthWebRtcAttempt(resumed) {
	const generation = normalizedGeneration(
		resumed?.probeGeneration || resumed?.generation)
	if (!generation) throw failureError(resumed)
	latestGeneration = generation
	suspendedOAuthAttempt = null
	return { ...resumed, generation, probeGeneration: generation }
}

/**
 * 在首页后台为已恢复的 OAuth attempt 重新创建 RTCPeerConnection，并且只上报一次原 generation。
 */
export function collectAndReportAttempt(configuration = {}) {
	const generation = normalizedGeneration(
		configuration?.generation || configuration?.probeGeneration)
	const attemptId = String(configuration?.attemptId || '')
	if (!generation || !h5AttemptId(attemptId)) {
		return Promise.reject(failureError(configuration))
	}
	const taskKey = `${attemptId}:${generation}`
	const existing = oauthAttemptTasks.get(taskKey)
	if (existing) return existing
	const task = runCollectAndReportAttempt({ ...configuration, attemptId, generation })
		.finally(() => {
			if (oauthAttemptTasks.get(taskKey) === task) oauthAttemptTasks.delete(taskKey)
		})
	oauthAttemptTasks.set(taskKey, task)
	return task
}

async function runCollectAndReportAttempt(configuration) {
	const generation = configuration.generation
	const attemptId = configuration.attemptId
	const probeRunId = normalizedDiagnosticId(configuration?.probeRunId) || nextProbeRunId()
	latestGeneration = generation
	const explicitDeadlineAt = configuration?.verdictDeadlineAt
		? Date.parse(configuration.verdictDeadlineAt)
		: Number.NaN
	const deadlineAt = Number.isFinite(explicitDeadlineAt)
		? explicitDeadlineAt
		: Date.now() + boundedTimeout(configuration?.timeoutMillis)
	// 回调页和页面刷新都只能继承服务端截止时间，绝不能在客户端重新获得十五秒窗口。
	if (deadlineAt <= Date.now()) {
		throw failureError({
			code: 'WEBRTC_VERIFICATION_TIMEOUT',
			message: 'WebRTC 异步裁决窗口已结束，请重新登录。'
		})
	}
	const attempt = {
		epoch: preAuthEpoch,
		expectedGeneration: generation,
		resolvedGeneration: generation,
		probeRunId,
		phase: 'COLLECTING',
		deadlineAt,
		startedAt: diagnosticNow(),
		abortController: createAttemptAbortController(),
		requestTasks: new Set(),
		cancelled: false,
		cancelReason: '',
		settled: false,
		triggerContext: { source: 'oauth_async_verdict', probeRunId }
	}
	activeDiagnosticAttempt = attempt
	try {
		const webRtcIps = await collectPlatformVerificationIps({
			attemptId: `${attemptId}:${generation}`,
			probeRunId,
			stunUrls: configuration?.stunUrls,
			timeoutMillis: Math.max(1, Math.min(
				boundedTimeout(configuration?.timeoutMillis),
				deadlineAt - Date.now())),
			signal: attempt.abortController.signal
		})
		assertAttemptActive(attempt)
		attempt.phase = 'REPORTING'
		const payload = { attemptId, probeGeneration: generation, webRtcIps }
		try {
			const report = await requestEdge(
				configuration?.reportPath || '/api/_edge/webrtc/report',
				'POST',
				payload,
				reportRequestTimeout(deadlineAt),
				{ attempt, probeRunId, source: 'oauth_async_verdict' })
			if (report?.webRtcStatus !== true) throw failureError(report)
			verifiedInMemory = true
			return report
		} catch (error) {
			if (error?.code !== 'NETWORK_ERROR' && !isWebRtcRetryCode(error?.code)) throw error
			// 响应丢失时只能轮询只读终态，禁止重发 report、延长截止时间或调用 start。
			return queryOAuthVerdictUntilFinal({
				attempt,
				attemptId,
				generation,
				probeRunId,
				deadlineAt
			})
		}
	} finally {
		attempt.settled = true
		if (activeDiagnosticAttempt === attempt) activeDiagnosticAttempt = null
	}
}

async function queryOAuthVerdictUntilFinal({
	attempt,
	attemptId,
	generation,
	probeRunId,
	deadlineAt
}) {
	let lastNetworkError = null
	while (Date.now() < deadlineAt) {
		assertAttemptActive(attempt)
		try {
			const verdict = await requestEdge(
				VERDICT_STATUS_PATH,
				'POST',
				{ attemptId, probeGeneration: generation },
				Math.max(1, Math.min(3000, deadlineAt - Date.now())),
				{ attempt, probeRunId, source: 'oauth_verdict_status' })
			if (verdict?.state === 'VERIFIED') return verdict
			if (verdict?.state === 'FAILED' || verdict?.state === 'EXPIRED') {
				throw failureError({
					...verdict,
					code: verdict.state === 'EXPIRED'
						? 'WEBRTC_VERIFICATION_TIMEOUT'
						: 'WEBRTC_VERIFICATION_FAILED'
				})
			}
		} catch (error) {
			if (error?.code !== 'NETWORK_ERROR') throw error
			lastNetworkError = error
		}
		const remaining = deadlineAt - Date.now()
		if (remaining <= 0) break
		await new Promise(resolve => setTimeout(resolve, Math.min(250, remaining)))
	}
	const timeout = failureError({
		code: 'WEBRTC_VERIFICATION_TIMEOUT',
		message: 'WebRTC 异步裁决未在安全窗口内完成。'
	})
	if (lastNetworkError) timeout.cause = lastNetworkError
	throw timeout
}

function cancelWebRtcAttempt(
	attempt,
	reason = DEFAULT_CANCEL_REASON,
	preserveServerAttempt = false
) {
	if (!attempt || attempt.cancelled || attempt.settled) return false
	attempt.cancelled = true
	attempt.cancelReason = diagnosticCode(reason, DEFAULT_CANCEL_REASON)
	recordAuthDiagnosticEvent(
		preserveServerAttempt
			? 'OAUTH_WEBRTC_LOCAL_RESOURCES_RELEASED'
			: 'WEBRTC_ATTEMPT_CANCEL_REQUESTED', {
		...activeAttemptDiagnosticFields(attempt),
		cancelReason: attempt.cancelReason,
		outcome: preserveServerAttempt ? 'suspended' : 'cancel_requested'
	})
	if (attempt.phase === 'COLLECTING') {
		recordAuthDiagnosticEvent('WEBRTC_PROBE_ABORTED', {
			...activeAttemptDiagnosticFields(attempt),
			cancelReason: attempt.cancelReason,
			outcome: 'aborted'
		})
	}
	try {
		attempt.abortController?.abort?.(attempt.cancelReason)
	} catch (_) {
		// 取消属于尽力释放资源；epoch 围栏仍负责阻止旧结果提交。
	}
	for (const entry of [...attempt.requestTasks]) {
		recordAuthDiagnosticEvent('WEBRTC_REQUEST_ABORTED', {
			...activeAttemptDiagnosticFields(attempt),
			clientRequestId: entry.clientRequestId,
			path: entry.path,
			cancelReason: attempt.cancelReason,
			outcome: 'aborted'
		})
		try {
			entry.task?.abort?.()
		} catch (_) {
			// 请求可能已由运行时结算；重复 abort 不得改变认证状态。
		}
	}
	attempt.requestTasks.clear()
	if (!preserveServerAttempt) {
		recordReportSkipped(attempt, attempt.cancelReason)
		recordAuthDiagnosticEvent('WEBRTC_ATTEMPT_ABANDONED', {
			...activeAttemptDiagnosticFields(attempt),
			reason: attempt.cancelReason,
			outcome: 'abandoned'
		})
	}
	return true
}

function recordReportSkipped(attempt, reason) {
	if (!attempt || attempt.reportSkipRecorded === true) return
	attempt.reportSkipRecorded = true
	recordAuthDiagnosticEvent('WEBRTC_REPORT_SKIPPED', {
		...activeAttemptDiagnosticFields(attempt),
		cancelReason: diagnosticCode(reason, DEFAULT_CANCEL_REASON),
		outcome: 'skipped'
	})
}

function createAttemptAbortController() {
	if (typeof globalThis.AbortController === 'function') {
		return new globalThis.AbortController()
	}
	let aborted = false
	let reason
	const listeners = new Set()
	const signal = {
		get aborted() { return aborted },
		get reason() { return reason },
		addEventListener(name, listener) {
			if (name === 'abort' && typeof listener === 'function') listeners.add(listener)
		},
		removeEventListener(name, listener) {
			if (name === 'abort') listeners.delete(listener)
		}
	}
	return {
		signal,
		abort(cancelReason) {
			if (aborted) return
			aborted = true
			reason = cancelReason
			for (const listener of [...listeners]) listener.call(signal, { type: 'abort' })
			listeners.clear()
		}
	}
}

function webRtcAttemptCancellation(attempt, reason = '') {
	const error = new Error('WebRTC attempt was cancelled.')
	error.code = WEBRTC_ATTEMPT_ABORTED
	error.cancelReason = diagnosticCode(
		reason || attempt?.cancelReason,
		DEFAULT_CANCEL_REASON)
	return error
}

function isWebRtcAttemptCancellation(error) {
	return error?.code === WEBRTC_ATTEMPT_ABORTED
		|| error?.code === 'PREAUTH_ATTEMPT_STALE'
}

function assertAttemptActive(attempt) {
	if (!attempt
		|| attempt.cancelled
		|| attempt.abortController?.signal?.aborted
		|| attempt.epoch !== preAuthEpoch) {
		throw webRtcAttemptCancellation(attempt, attempt?.cancelReason || 'STALE_EPOCH')
	}
}

function traceAndroidVerification(stage, fields = {}) {
	if (clientPlatform() !== 'ANDROID') return
	webRtcDiagnostics(stage, fields)
}

function activeAttemptDiagnosticFields(attempt) {
	const generation = attempt?.resolvedGeneration
		|| attempt?.expectedGeneration
		|| latestGeneration
		|| 'discover'
	return {
		probeRunId: attempt?.probeRunId,
		preAuthEpoch: normalizedEpoch(attempt?.epoch, preAuthEpoch),
		phase: diagnosticCode(attempt?.phase, 'UNKNOWN'),
		source: diagnosticCode(attempt?.triggerContext?.source, 'webrtc_verification'),
		generation,
		webRtcGeneration: generation,
		deadlineRemainingMs: attempt?.deadlineAt > 0
			? Math.max(0, attempt.deadlineAt - Date.now())
			: 0,
		documentVisibility: diagnosticCode(
			globalThis.document?.visibilityState,
			'unknown'),
		activeTaskCount: verificationTasks.size
	}
}

function recordAttemptCompleted(attempt, outcome, fields = {}) {
	recordAuthDiagnosticEvent('WEBRTC_ATTEMPT_COMPLETED', {
		...activeAttemptDiagnosticFields(attempt),
		...fields,
		durationMs: diagnosticNow() - Number(attempt?.startedAt || diagnosticNow()),
		outcome
	})
}

function startDisposition(state, remainingMillis, timeoutMillis, reportGraceMillis) {
	if (state !== 'PENDING') return diagnosticCode(state, 'UNKNOWN')
	const fullWindow = Math.max(0, timeoutMillis + reportGraceMillis)
	return fullWindow > 0 && remainingMillis + 1000 < fullWindow
		? 'PENDING_PRESERVED'
		: 'STARTED_OR_RECENT'
}

function webRtcCandidateFamilyCounts(values) {
	return (Array.isArray(values) ? values : []).reduce((counts, value) => {
		if (String(value).includes(':')) counts.ipv6Count += 1
		else counts.ipv4Count += 1
		return counts
	}, { ipv4Count: 0, ipv6Count: 0 })
}

function probeFinishReason(probeFinish = {}) {
	const stage = diagnosticCode(probeFinish?.stage, '')
	const reason = diagnosticCode(probeFinish?.reason, '')
	if (stage === 'ice_timeout' || reason === 'timeout') return 'TIMEOUT'
	if (reason === 'document_unloaded') return 'DOCUMENT_UNLOADED'
	if (reason === 'page_hidden') return 'PAGE_HIDDEN'
	if (reason === 'offer_failed'
		|| reason === 'constructor_failed'
		|| reason === 'peer_connection_unavailable') return 'ERROR'
	return 'ICE_COMPLETE'
}

function diagnosticNow() {
	return typeof globalThis.performance?.now === 'function'
		? globalThis.performance.now()
		: Date.now()
}

function diagnosticCode(value, fallback) {
	const normalized = String(value || fallback)
		.replace(/[^A-Za-z0-9_-]/g, '')
		.slice(0, 64)
	return normalized || fallback
}

function normalizedGeneration(value) {
	return /^[1-9][0-9]{0,18}$/.test(String(value || ''))
		? String(value)
		: ''
}

function normalizedDiagnosticId(value) {
	const normalized = String(value || '').trim().toLowerCase()
	return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(normalized)
		? normalized : ''
}

function h5AttemptId(value) {
	return /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
		String(value || '').trim().toLowerCase())
}

function normalizedEpoch(value, fallback) {
	const numeric = Number(value)
	return Number.isSafeInteger(numeric) && numeric >= 0 ? numeric : fallback
}

function responseHeaderDiagnosticFields(context, requestEpoch, generation) {
	return {
		clientRequestId: context?.clientRequestId,
		path: String(context?.path || START_PATH).split(/[?#]/, 1)[0],
		preAuthEpoch,
		requestEpoch,
		source: diagnosticCode(context?.source, 'response_headers'),
		status: nonNegativeNumber(context?.status, 0),
		errorCode: context?.errorCode,
		generation: generation || 'discover',
		webRtcGeneration: generation || 'discover'
	}
}

function backgroundDiagnosticFields(context, expectedGeneration, requestEpoch) {
	const generation = expectedGeneration || latestGeneration || 'discover'
	return {
		clientRequestId: context?.clientRequestId,
		triggerClientRequestId: context?.triggerClientRequestId
			|| context?.clientRequestId,
		path: String(context?.path || START_PATH).split(/[?#]/, 1)[0],
		source: diagnosticCode(context?.source, 'h5_background'),
		preAuthEpoch,
		requestEpoch,
		preAuthReady: isPreAuthReady(),
		activeTaskCount: verificationTasks.size,
		generation,
		webRtcGeneration: generation
	}
}

function singleFlightDiagnosticFields(context, generation) {
	const resolvedGeneration = generation || 'discover'
	return {
		clientRequestId: context?.clientRequestId,
		triggerClientRequestId: context?.triggerClientRequestId
			|| context?.clientRequestId,
		path: String(context?.path || START_PATH).split(/[?#]/, 1)[0],
		source: diagnosticCode(context?.source, 'ensure_webrtc_verified'),
		requestEpoch: normalizedEpoch(context?.requestEpoch, preAuthEpoch),
		preAuthReady: isPreAuthReady(),
		activeTaskCount: verificationTasks.size,
		probeRunId: context?.probeRunId,
		generation: resolvedGeneration,
		webRtcGeneration: resolvedGeneration
	}
}

function nextProbeRunId() {
	return createAuthDiagnosticId()
}

function collectPlatformVerificationIps(options) {
	// #ifdef H5
	return collectH5VerificationIps(options)
	// #endif
	// #ifdef APP-PLUS
	return collectAndroidVerificationIps(options)
	// #endif
	return Promise.resolve([])
}

async function submitReport(path, data, deadlineAt, context = {}) {
	try {
		return await requestEdge(
			path,
			'POST',
			data,
			reportRequestTimeout(deadlineAt),
			context)
	} catch (error) {
		if (error?.code !== 'NETWORK_ERROR') throw error
		// Report 可能已在服务端成功落地但响应丢失；幂等 GET start 读取终态后再决定是否重试。
		const status = await requestEdge(START_PATH, 'GET', undefined, 10000, context)
		const state = verificationState(status)
		if (state === 'VERIFIED' || status?.mode === 'OBSERVE') return status
		if (state === 'FAILED') throw failureError(status)
		if (String(status?.probeGeneration || '') !== String(data.probeGeneration)
			|| deadlineAt - Date.now() < 1000) throw error
		return requestEdge(
			path,
			'POST',
			data,
			reportRequestTimeout(deadlineAt),
			context)
	}
}

function requestEdge(path, method, data, timeout = 10000, context = {}) {
	const platform = clientPlatform()
	const attempt = context?.attempt || null
	if (attempt) {
		try {
			assertAttemptActive(attempt)
		} catch (error) {
			return Promise.reject(error)
		}
	}
	if (path === START_PATH && platform === 'H5' && !isPreAuthReady()) {
		recordAuthDiagnosticEvent('WEBRTC_BACKGROUND_SKIPPED', {
			triggerClientRequestId: context?.triggerClientRequestId,
			probeRunId: context?.probeRunId,
			path: context?.path || START_PATH,
			source: context?.source || 'webrtc_verification',
			requestEpoch: normalizedEpoch(context?.requestEpoch, preAuthEpoch),
			preAuthEpoch,
			preAuthReady: false,
			activeTaskCount: verificationTasks.size,
			outcome: 'preauth_not_ready'
		})
		const error = new Error('预登录安全状态已经失效。')
		error.code = 'PREAUTH_ATTEMPT_STALE'
		return Promise.reject(error)
	}
	const authDiagnostic = createAuthRequestDiagnostic(path, 'webrtc_verification')
	authDiagnostic.requestEpoch = normalizedEpoch(context?.requestEpoch, preAuthEpoch)
	return new Promise((resolve, reject) => {
		let settled = false
		let requestEntry = null
		const cleanup = () => {
			attempt?.abortController?.signal?.removeEventListener?.('abort', onAbort)
			if (requestEntry) attempt?.requestTasks?.delete(requestEntry)
		}
		const settleResolve = value => {
			if (settled) return
			settled = true
			cleanup()
			resolve(value)
		}
		const settleReject = error => {
			if (settled) return
			settled = true
			cleanup()
			reject(error)
		}
		const onAbort = () => {
			if (settled) return
			if (requestEntry) {
				recordAuthDiagnosticEvent('WEBRTC_REQUEST_ABORTED', {
					...activeAttemptDiagnosticFields(attempt),
					clientRequestId: requestEntry.clientRequestId,
					path: requestEntry.path,
					cancelReason: attempt?.cancelReason || DEFAULT_CANCEL_REASON,
					outcome: 'aborted'
				})
				try {
					requestEntry.task?.abort?.()
				} catch (_) {
					// 运行时可能已经完成请求；epoch 围栏仍会忽略该响应。
				}
			}
			settleReject(webRtcAttemptCancellation(attempt))
		}
		const headers = {
			Accept: 'application/json',
			'Content-Type': 'application/json',
			'X-Client-Platform': platform,
			'X-Device-Installation-Id': getDeviceInstallationId()
		}
		const preAuthToken = currentPreAuthToken()
		if (usesExplicitTokenTransport(platform) && preAuthToken) headers['X-AIT-PreAuth'] = preAuthToken
		Object.assign(headers, authDiagnosticRequestHeaders(authDiagnostic, {
			probeRunId: context?.probeRunId
		}))
		if (path === START_PATH) {
			recordAuthDiagnosticEvent('WEBRTC_START_DISPATCHED', {
				clientRequestId: authDiagnostic.clientRequestId,
				triggerClientRequestId: context?.triggerClientRequestId,
				probeRunId: context?.probeRunId,
				path: START_PATH,
				source: context?.source || 'webrtc_verification',
				requestEpoch: normalizedEpoch(context?.requestEpoch, preAuthEpoch),
				preAuthEpoch,
				activeTaskCount: verificationTasks.size,
				outcome: 'dispatched'
			})
			recordAuthDiagnosticEvent('WEBRTC_START_REQUEST_DISPATCHED', {
				clientRequestId: authDiagnostic.clientRequestId,
				triggerClientRequestId: context?.triggerClientRequestId,
				probeRunId: context?.probeRunId,
				path: context?.path || START_PATH,
				source: context?.source || 'webrtc_verification',
				requestEpoch: normalizedEpoch(context?.requestEpoch, preAuthEpoch),
				preAuthEpoch,
				preAuthReady: isPreAuthReady(),
				activeTaskCount: verificationTasks.size
			})
		}
		attempt?.abortController?.signal?.addEventListener?.('abort', onAbort, { once: true })
		try {
			if (attempt) assertAttemptActive(attempt)
		} catch (error) {
			settleReject(error)
			return
		}
		// 这里是网络创建前的最后一道 epoch 围栏；上层检查不能替代这一检查。
		let requestTask
		try {
			requestTask = uni.request({
				url: `${AUTH_API_BASE_URL}${path}`,
				method,
				data,
				header: headers,
				withCredentials: true,
				timeout,
				success(response) {
					try {
						if (attempt) assertAttemptActive(attempt)
					} catch (error) {
						settleReject(error)
						return
					}
					recordAuthDiagnosticResponse(authDiagnostic, response)
					if (response.statusCode >= 200 && response.statusCode < 300) {
						settleResolve(response.data)
						return
					}
					settleReject(webRtcErrorFromResponse(response, 'WebRTC 网络校验失败。'))
				},
				fail(cause) {
					if (attempt?.cancelled
						|| attempt?.abortController?.signal?.aborted
						|| attempt?.epoch !== preAuthEpoch) {
						settleReject(webRtcAttemptCancellation(attempt))
						return
					}
					const error = new Error('网络连接失败，请稍后重试。')
					error.code = 'NETWORK_ERROR'
					error.cause = cause
					recordAuthDiagnosticFailure(authDiagnostic, error)
					settleReject(error)
				}
			})
		} catch (cause) {
			if (attempt?.cancelled
				|| attempt?.abortController?.signal?.aborted
				|| attempt?.epoch !== preAuthEpoch) {
				settleReject(webRtcAttemptCancellation(attempt))
				return
			}
			const error = new Error('网络连接失败，请稍后重试。')
			error.code = 'NETWORK_ERROR'
			error.cause = cause
			recordAuthDiagnosticFailure(authDiagnostic, error)
			settleReject(error)
			return
		}
		requestEntry = {
			task: requestTask,
			path,
			clientRequestId: authDiagnostic.clientRequestId
		}
		if (!settled && attempt) attempt.requestTasks.add(requestEntry)
		if (attempt && !isInvocationCurrent(attempt)) {
			try {
				requestTask.abort()
			} catch (_) {
				// 部分平台在请求同步结算后不再允许 abort。
			}
			if (!settled) settleReject(webRtcAttemptCancellation(attempt, 'STALE_EPOCH'))
		}
	})
}

function isEpochActive(attempt) {
	return attempt.epoch === preAuthEpoch
}

function isInvocationCurrent(attempt) {
	return !attempt.cancelled
		&& !attempt.abortController?.signal?.aborted
		&& isEpochActive(attempt)
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

import {
	applyBrowserCsrfHeader,
	browserCsrfToken,
	requiresCsrf
} from './browser-cookies.js'
import { AUTH_API_BASE_URL, AUTH_ROUTES, clientPlatform, usesExplicitTokenTransport } from './config.js'
import {
	androidEdgeRequestHeaders,
	ensureAndroidEdgeClearance,
	runAndroidRequestWithEdgeRecovery
} from './android-edge-challenge.js'
import {
	ensureCookieScopeMigration,
	invalidateCookieScopeMigration
} from './cookie-scope-migration.js'
import { ensureDeviceInstallationId, getDeviceInstallationId } from './device-installation.js'
import { currentPhase as currentAndroidOAuthPhase,
	isBlockingWebRtc as isAndroidOAuthBlockingWebRtc } from './android-oauth-coordinator.js'
import { clearAndroidOAuthFlow } from './android-flow-keystore.js'
import {
	clearH5OAuthWebRtcGate,
	ownsH5WebRtcScheduling
} from './h5-oauth-webrtc-gate.js'
import { captureEtagPayload } from './http-response-metadata.js'
import {
	authDiagnosticRequestHeaders,
	createAuthRequestDiagnostic,
	recordAuthDiagnosticEvent,
	recordAuthDiagnosticFailure,
	recordAuthDiagnosticResponse,
	runAuthDiagnosticStage
} from './auth-diagnostics.js'
import {
	acceptAndroidRiskChallenge,
	currentPreAuthToken,
	ensurePreAuth,
	invalidatePreAuth,
	isPreAuthReady,
	recheckPreAuthAfterRiskChallenge
} from './pre-auth.js'
import { repeatedAndroidRiskChallengeError } from './android-risk-challenge.js'
import { presentRiskBlock } from './risk-block-navigation.js'
import { beginRiskChallenge } from './risk-challenge-navigation.js'
import { hasCompleteSessionCredentials } from './session-credentials.js'
import {
	beginRuntimeTerminalSessionTransition,
	claimRuntimeTerminalSessionRedirect,
	isRuntimeTerminalSessionActive,
	releaseRuntimeTerminalSessionRedirect,
	runtimeSessionRequestGeneration
} from './authenticated-session-state.js'
import {
	SessionRenewalMode,
	SessionRequestPurpose,
	isTerminalSessionError,
	sessionRenewalMode
} from './session-retry-policy.js'
import { clearSession, currentSession, saveSession } from './session-vault.js'
import {
	applyDiagnosticsToError,
	inspectAuthResponse,
	networkFailureDiagnostics
} from './turnstile-response-diagnostics.js'
import {
	isWebRtcFailureCode,
	isWebRtcRetryCode
} from '@shared-auth/webrtc-verification-core.js'
import {
	currentWebRtcVerificationEpoch,
	ensureH5WebRtcVerified,
	invalidateWebRtcVerification,
	observeWebRtcVerificationHeaders,
	presentWebRtcFailure,
	scheduleH5WebRtcVerification
} from './webrtc-verification.js'
// #ifdef APP-PLUS
import {
	startAndroidWebRtcVerificationInBackground
} from './webrtc-verification.js'
// #endif

const CSRF_PATH = '/api/auth/csrf'
const BOOTSTRAP_PATH = '/api/auth/session/bootstrap'
export const WebRtcSchedulingPolicy = Object.freeze({
	NORMAL: 'NORMAL',
	SUPPRESS: 'SUPPRESS'
})
export const PreAuthBootstrapPolicy = Object.freeze({
	ENSURE: 'ENSURE',
	REQUIRE_EXISTING: 'REQUIRE_EXISTING'
})
const terminalRedirectAttempts = new WeakMap()
const SESSION_TERMINATED_CODE = 'SESSION_TERMINATED'
const SESSION_GENERATION_STALE_CODE = 'SESSION_GENERATION_STALE'

let bootstrapInFlight = null
let bootstrapInFlightGeneration = null
let csrfInFlight = null

function normalizedSessionGeneration(value) {
	return Number.isSafeInteger(value) && value >= 0
		? value
		: runtimeSessionRequestGeneration()
}

function localSessionCancellation(error) {
	return error?.code === SESSION_TERMINATED_CODE
		|| error?.code === SESSION_GENERATION_STALE_CODE
}

/**
 * 流式调用方在进入通用重连前使用该判断，终止性认证错误和旧会话本地取消都必须停止重连。
 */
export function isAuthorizedSessionTermination(error) {
	return localSessionCancellation(error)
		|| isTerminalSessionError(error, SessionRequestPurpose.PROTECTED)
}

export function assertAuthorizedSessionCurrent(expectedGeneration, authDiagnostic = null, allowTerminal = false) {
	const generation = normalizedSessionGeneration(expectedGeneration)
	const currentGeneration = runtimeSessionRequestGeneration()
	const stale = generation !== currentGeneration
	if (!stale && (allowTerminal || !isRuntimeTerminalSessionActive())) return generation
	const error = new Error(stale
		? '旧会话请求已过期。'
		: '当前登录会话已经结束。')
	error.code = stale ? SESSION_GENERATION_STALE_CODE : SESSION_TERMINATED_CODE
	error.sessionGeneration = generation
	recordAuthDiagnosticEvent('AUTHORIZED_SESSION_GUARD_REJECTED', {
		clientRequestId: authDiagnostic?.clientRequestId,
		path: authDiagnostic?.path,
		source: authDiagnostic?.source,
		sessionGeneration: generation,
		currentSessionGeneration: currentGeneration,
		outcome: stale ? 'stale_generation' : 'terminal_session'
	})
	throw error
}
function rawRequestTask(options) {
	return new Promise((resolve, reject) => {
		if (options.protectedSession === true) {
			assertAuthorizedSessionCurrent(options.sessionGeneration, options.authDiagnostic)
		}
		const requestEpoch = currentWebRtcVerificationEpoch()
		if (options.authDiagnostic) options.authDiagnostic.requestEpoch = requestEpoch
		const diagnosticHeaders = authDiagnosticRequestHeaders(options.authDiagnostic)
		uni.request({
			url: `${AUTH_API_BASE_URL}${options.path}`,
			method: options.method || 'POST',
			data: options.data,
			header: androidEdgeRequestHeaders({
				...(options.headers || {}),
				...diagnosticHeaders
			}),
			timeout: options.timeout,
			withCredentials: true,
			success(response) {
				recordAuthDiagnosticResponse(options.authDiagnostic, response)
				const diagnostics = inspectAuthResponse(response)
				const terminalResponse = isTerminalSessionError({
					code: response.data?.code,
					statusCode: response.statusCode,
					responseClassification: diagnostics.classification
				})
				if (options.protectedSession === true) {
					try {
						assertAuthorizedSessionCurrent(
							options.sessionGeneration,
							options.authDiagnostic,
							terminalResponse)
					} catch (sessionError) {
						reject(sessionError)
						return
					}
				}
				try {
					// Android 必须在解释业务状态前保存同请求续签的 AT；H5 由浏览器接收 HttpOnly Cookie。
					if (!terminalResponse) applySessionRenewalHeaders(
						response.header || {},
						options.sessionGeneration)
				} catch (renewalError) {
					reject(renewalError)
					return
				}
				notifyResponseObserver(options.onResponse, diagnostics)
				// 认证完成边界只消费业务响应；新 Session 提交后再显式建立 WebRTC epoch。
				if (effectiveWebRtcSchedulingPolicy(options) === WebRtcSchedulingPolicy.SUPPRESS) {
					recordWebRtcSchedulingSuppressed(
						options.authDiagnostic,
						'response_headers')
				} else if (!terminalResponse) {
					try {
						observeWebRtcVerificationHeaders(
						response.header || response.headers || {},
						{
							clientRequestId: options.authDiagnostic?.clientRequestId,
							errorCode: diagnostics.classification === 'EDGE_CHALLENGE'
								? 'EDGE_CHALLENGE'
								: response.data?.code,
							path: options.path,
							requestEpoch,
							responseAccepted: diagnostics.classification !== 'EDGE_CHALLENGE'
								&& response.statusCode >= 200
								&& response.statusCode < 300,
							source: 'response_headers',
							status: response.statusCode
						})
					} catch (_) {
						// 后台观察器的格式异常不能吞掉已经到达的业务响应。
					}
				}
				if (diagnostics.classification === 'EDGE_CHALLENGE') {
					const edgeError = new Error('Cloudflare 安全检查尚未完成，请重新完成人机验证。')
					edgeError.code = 'EDGE_CHALLENGE'
					edgeError.statusCode = response.statusCode
					reject(applyDiagnosticsToError(edgeError, diagnostics))
					return
				}
				if (response.statusCode >= 200 && response.statusCode < 300) {
					resolve(options.captureEtag === true
						? captureEtagPayload(
							response.data,
							response.header || response.headers || {})
						: response.data)
					return
				}
				const hasStableClientMessage = typeof response.data?.code === 'string' &&
					typeof response.data?.message === 'string' &&
					response.data.message.trim().length > 0
				const error = new Error(hasStableClientMessage
					? response.data.message
					: '请求未完成，请稍后重试。')
				error.code = response.data?.code || `HTTP_${response.statusCode}`
				error.statusCode = response.statusCode
				error.sessionGeneration = options.sessionGeneration
				error.challengeRef = response.data?.challengeRef || ''
				error.challengePath = response.data?.challengePath || ''
				error.expiresAt = response.data?.expiresAt || ''
				error.preAuthToken = response.data?.preAuthToken || ''
				error.webRtcStatus = response.data?.webRtcStatus
				error.httpIp = response.data?.httpIp || ''
				error.webRtcIps = Array.isArray(response.data?.webRtcIps)
					? [...response.data.webRtcIps]
					: []
				error.retryable = response.data?.retryable === true
				reject(applyDiagnosticsToError(error, diagnostics))
			},
			fail(cause) {
				if (options.protectedSession === true) {
					try {
						assertAuthorizedSessionCurrent(
							options.sessionGeneration,
							options.authDiagnostic)
					} catch (sessionError) {
						reject(sessionError)
						return
					}
				}
				const diagnostics = networkFailureDiagnostics(cause, {
					path: options.path,
					timeoutMs: options.timeout,
					phase: currentAndroidOAuthPhase(),
					preAuthReady: Boolean(currentPreAuthToken())
				})
				notifyResponseObserver(options.onResponse, diagnostics)
				const networkError = new Error('网络连接失败，请检查后重试。')
				networkError.code = 'NETWORK_ERROR'
				networkError.errno = diagnostics.errno
				networkError.errMsg = diagnostics.errMsg
				networkError.timeoutMs = diagnostics.timeoutMs
				networkError.oauthPhase = diagnostics.phase
				networkError.preAuthReady = diagnostics.preAuthReady
				recordAuthDiagnosticFailure(options.authDiagnostic, networkError)
				reject(applyDiagnosticsToError(networkError, diagnostics))
			}
		})
	})
}

async function requestTask(options) {
	await runAuthDiagnosticStage(
		options.authDiagnostic,
		'COOKIE_MIGRATION',
		() => ensureCookieScopeMigration({
			triggerClientRequestId: options.authDiagnostic?.triggerClientRequestId
		}))
	return runAndroidRequestWithEdgeRecovery(() => rawRequestTask(options))
}

function notifyResponseObserver(observer, diagnostics) {
	if (typeof observer !== 'function') return
	try {
		observer(diagnostics)
	} catch (_) {
		// 诊断观察器异常不能覆盖原始网络响应或认证结果。
	}
}

function clientContextHeaders() {
	const headers = { 'Content-Type': 'application/json' }
	headers['X-Device-Installation-Id'] = getDeviceInstallationId()
	headers['X-Client-Platform'] = clientPlatform()
	const preAuthToken = currentPreAuthToken()
	if (usesExplicitTokenTransport() && preAuthToken) {
		headers['X-AIT-PreAuth'] = preAuthToken
	}

	return headers
}

function scheduleH5WebRtcForRequest(authDiagnostic, source = 'request_ready') {
	if (clientPlatform() !== 'H5') return
	scheduleH5WebRtcVerification({
		clientRequestId: authDiagnostic?.clientRequestId,
		path: authDiagnostic?.path,
		source
	})
}

function effectiveWebRtcSchedulingPolicy(options = {}) {
	if (clientPlatform() === 'H5' && ownsH5WebRtcScheduling()) {
		return WebRtcSchedulingPolicy.SUPPRESS
	}
	return options.webRtcSchedulingPolicy === WebRtcSchedulingPolicy.SUPPRESS
		? WebRtcSchedulingPolicy.SUPPRESS
		: WebRtcSchedulingPolicy.NORMAL
}

function effectivePreAuthBootstrapPolicy(options = {}) {
	if (clientPlatform() === 'H5' && ownsH5WebRtcScheduling()) {
		return PreAuthBootstrapPolicy.REQUIRE_EXISTING
	}
	return options.preAuthBootstrapPolicy === PreAuthBootstrapPolicy.REQUIRE_EXISTING
		? PreAuthBootstrapPolicy.REQUIRE_EXISTING
		: PreAuthBootstrapPolicy.ENSURE
}

function recordWebRtcSchedulingSuppressed(authDiagnostic, phase) {
	recordAuthDiagnosticEvent('WEBRTC_SCHEDULING_SUPPRESSED', {
		clientRequestId: authDiagnostic?.clientRequestId,
		path: authDiagnostic?.path,
		source: authDiagnostic?.source,
		phase,
		outcome: 'suppressed'
	})
}

export async function initializeBrowserCsrf(
	migrationRetried = false,
	preAuthRetried = false,
	webRtcRetried = false,
	schedulingPolicy = WebRtcSchedulingPolicy.NORMAL,
	triggerClientRequestId = ''
) {
	if (clientPlatform() !== 'H5') return ''
	const effectiveScheduling = effectiveWebRtcSchedulingPolicy({
		webRtcSchedulingPolicy: schedulingPolicy
	})
	const bootstrapPolicy = effectivePreAuthBootstrapPolicy()
	const authDiagnostic = createAuthRequestDiagnostic(
		CSRF_PATH,
		'initialize_browser_csrf',
		{ triggerClientRequestId })
	// 必须先清理旧父域 Cookie；OAuth attempt 已拥有调度权时只能复用现有 HttpOnly PreAuth。
	await runAuthDiagnosticStage(
		authDiagnostic,
		'COOKIE_MIGRATION',
		() => ensureCookieScopeMigration({ triggerClientRequestId }))
	if (bootstrapPolicy === PreAuthBootstrapPolicy.ENSURE) {
		await runAuthDiagnosticStage(
			authDiagnostic,
			'PREAUTH',
			() => ensurePreAuth())
	} else {
		recordAuthDiagnosticEvent('PREAUTH_EXISTING_REQUIRED', {
			clientRequestId: authDiagnostic.clientRequestId,
			path: CSRF_PATH,
			preAuthReady: isPreAuthReady(),
			outcome: 'oauth_attempt_owned'
		})
	}
	if (effectiveScheduling === WebRtcSchedulingPolicy.SUPPRESS) {
		recordWebRtcSchedulingSuppressed(authDiagnostic, 'csrf_ready')
	} else {
		scheduleH5WebRtcForRequest(authDiagnostic, 'csrf_ready')
	}
	try {
		const existing = browserCsrfToken()
		if (existing) return existing
		if (!csrfInFlight) {
			csrfInFlight = requestTask({
				path: CSRF_PATH,
				method: 'GET',
				headers: clientContextHeaders(),
				webRtcSchedulingPolicy: effectiveScheduling,
				authDiagnostic
			}).finally(() => { csrfInFlight = null })
		}
		await csrfInFlight
	} catch (error) {
		recordAuthDiagnosticFailure(authDiagnostic, error)
		if (presentRiskBlock(error)) throw error
		if (isWebRtcFailureCode(error.code)) presentWebRtcFailure(error)
		if (effectiveScheduling !== WebRtcSchedulingPolicy.SUPPRESS
			&& !webRtcRetried
			&& isWebRtcRetryCode(error.code)) {
			await recoverH5WebRtc()
			return initializeBrowserCsrf(
				migrationRetried,
				preAuthRetried,
				true,
				effectiveScheduling,
				triggerClientRequestId)
		}
		if (error.code === 'RISK_CHALLENGE_REQUIRED') {
			beginRiskChallenge(error)
		}
		if (error.code === 'PREAUTH_REQUIRED') {
			if (terminateAuthenticatedAndroidSession(error, authDiagnostic)) throw error
			if (bootstrapPolicy === PreAuthBootstrapPolicy.REQUIRE_EXISTING) throw error
			invalidatePreAuth()
			invalidateWebRtcVerification()
			if (!preAuthRetried) {
				await ensurePreAuth()
				if (effectiveScheduling !== WebRtcSchedulingPolicy.SUPPRESS) {
					scheduleH5WebRtcForRequest(authDiagnostic, 'preauth_recovered')
				}
				return initializeBrowserCsrf(
					migrationRetried,
					true,
					webRtcRetried,
					effectiveScheduling,
					triggerClientRequestId)
			}
		}
		if (migrationRetried
			|| error.code !== 'EDGE_COOKIE_SCOPE_RESET_REQUIRED') {
			throw error
		}
		const recoveryTriggerClientRequestId = authDiagnostic.clientRequestId
		await recoverCookieScopeAfter428(authDiagnostic)
		return initializeBrowserCsrf(
			true,
			preAuthRetried,
			webRtcRetried,
			effectiveScheduling,
			recoveryTriggerClientRequestId)
	}
	return browserCsrfToken()
}

export async function publicRequest(
	path,
	options = {},
	migrationRetried = false,
	preAuthRetried = false,
	webRtcRetried = false,
	riskChallengeRetried = false,
	triggerClientRequestId = ''
) {
	const guard = (allowTerminal = false) => {
		if (options.protectedSession === true) {
			assertAuthorizedSessionCurrent(options.sessionGeneration, null, allowTerminal)
		}
	}
	guard()
	const automaticReplayAllowed = options.disableAutomaticReplay !== true
	const bootstrapPolicy = effectivePreAuthBootstrapPolicy(options)
	const authDiagnostic = createAuthRequestDiagnostic(
		path,
		options.diagnosticSource || 'public_request',
		{ triggerClientRequestId })
	await ensureDeviceInstallationId()
	guard()
	await runAuthDiagnosticStage(
		authDiagnostic,
		'COOKIE_MIGRATION',
		() => ensureCookieScopeMigration({ triggerClientRequestId }))
	guard()
	if (bootstrapPolicy === PreAuthBootstrapPolicy.ENSURE) {
		await runAuthDiagnosticStage(
			authDiagnostic,
			'PREAUTH',
			() => ensurePreAuth())
	} else {
		recordAuthDiagnosticEvent('PREAUTH_EXISTING_REQUIRED', {
			clientRequestId: authDiagnostic.clientRequestId,
			path,
			preAuthReady: isPreAuthReady(),
			outcome: 'bootstrap_suppressed'
		})
	}
	guard()
	const schedulingPolicy = effectiveWebRtcSchedulingPolicy(options)
	if (schedulingPolicy === WebRtcSchedulingPolicy.SUPPRESS) {
		recordWebRtcSchedulingSuppressed(authDiagnostic, 'request_ready')
	} else {
		scheduleH5WebRtcForRequest(authDiagnostic)
	}
	try {
		const method = String(options.method || 'POST').toUpperCase()
		const headers = clientContextHeaders()
		Object.assign(headers, options.headers || {})
		if (clientPlatform() === 'H5' && requiresCsrf(method) && path !== BOOTSTRAP_PATH) {
			const csrfToken = browserCsrfToken() || (bootstrapPolicy === PreAuthBootstrapPolicy.ENSURE
				? await initializeBrowserCsrf(
				false,
				false,
				false,
				schedulingPolicy,
				triggerClientRequestId)
				: '')
			if (!csrfToken) {
				const error = new Error('CSRF token is unavailable.')
				error.code = 'CSRF_INVALID'
				throw error
			}
			applyBrowserCsrfHeader(headers, method, csrfToken)
		}
		return await requestTask({
			path,
			method,
			data: options.data,
			headers,
			authDiagnostic,
			webRtcSchedulingPolicy: schedulingPolicy,
			protectedSession: options.protectedSession === true,
			sessionGeneration: options.sessionGeneration,
			timeout: options.timeout,
			onResponse: options.onResponse
		})
	} catch (error) {
		guard(isTerminalSessionError(error))
		if (options.protectedSession === true && isTerminalSessionError(error)) throw error
		recordAuthDiagnosticFailure(authDiagnostic, error)
		if (presentRiskBlock(error)) throw error
		if (isWebRtcFailureCode(error.code)) presentWebRtcFailure(error)
		if (schedulingPolicy !== WebRtcSchedulingPolicy.SUPPRESS
			&& !webRtcRetried
			&& isWebRtcRetryCode(error.code)) {
			// #ifdef H5
			await recoverH5WebRtc()
			// #endif
			// #ifdef APP-PLUS
			recoverAndroidWebRtc()
			// #endif
			return publicRequest(
				path,
				options,
				migrationRetried,
				preAuthRetried,
				true,
				riskChallengeRetried,
				triggerClientRequestId)
		}
		if (error.code === 'RISK_CHALLENGE_REQUIRED') {
			if (clientPlatform() === 'H5') beginRiskChallenge(error)
			if (!automaticReplayAllowed) throw error
			if (riskChallengeRetried) {
				throw repeatedAndroidRiskChallengeError(error)
			}
			await acceptAndroidRiskChallenge(error)
			guard()
			await recheckPreAuthAfterRiskChallenge()
			return publicRequest(
				path,
				options,
				migrationRetried,
				preAuthRetried,
				webRtcRetried,
				true,
				triggerClientRequestId)
		}
		if (error.code === 'PREAUTH_REQUIRED') {
			if (terminateAuthenticatedAndroidSession(error, authDiagnostic)) throw error
			if (bootstrapPolicy === PreAuthBootstrapPolicy.REQUIRE_EXISTING) throw error
			invalidatePreAuth()
			invalidateWebRtcVerification()
			if (!automaticReplayAllowed) throw error
			if (!preAuthRetried) {
				await ensurePreAuth()
				guard()
				if (schedulingPolicy !== WebRtcSchedulingPolicy.SUPPRESS) {
					scheduleH5WebRtcForRequest(authDiagnostic, 'preauth_recovered')
				}
				// #ifdef APP-PLUS
				if (schedulingPolicy !== WebRtcSchedulingPolicy.SUPPRESS) {
					void startAndroidWebRtcVerificationInBackground().catch(() => {})
				}
				// #endif
				return publicRequest(
					path,
					options,
					migrationRetried,
					true,
					webRtcRetried,
					riskChallengeRetried,
					triggerClientRequestId)
			}
		}
		if (!automaticReplayAllowed
			|| clientPlatform() !== 'H5'
			|| migrationRetried
			|| error.code !== 'EDGE_COOKIE_SCOPE_RESET_REQUIRED') {
			throw error
		}
		const recoveryTriggerClientRequestId = authDiagnostic.clientRequestId
		await recoverCookieScopeAfter428(authDiagnostic)
		return publicRequest(
			path,
			options,
			true,
			preAuthRetried,
			webRtcRetried,
			riskChallengeRetried,
			recoveryTriggerClientRequestId)
	}
}

async function recoverCookieScopeAfter428(authDiagnostic) {
	const triggerClientRequestId = authDiagnostic?.clientRequestId || ''
	recordAuthDiagnosticEvent('COOKIE_SCOPE_428_RECOVERY_STARTED', {
		clientRequestId: triggerClientRequestId,
		pageInstanceId: authDiagnostic?.pageInstanceId,
		path: authDiagnostic?.path,
		source: authDiagnostic?.source,
		triggerClientRequestId,
		outcome: 'started'
	})
	invalidateCookieScopeMigration()
	invalidatePreAuth()
	invalidateWebRtcVerification()
	try {
		const migration = await ensureCookieScopeMigration({
			triggerClientRequestId
		})
		recordAuthDiagnosticEvent('COOKIE_SCOPE_428_RECOVERY_COMPLETED', {
			clientRequestId: triggerClientRequestId,
			migrationClientRequestId: migration.clientRequestId,
			pageInstanceId: authDiagnostic?.pageInstanceId,
			path: authDiagnostic?.path,
			source: authDiagnostic?.source,
			triggerClientRequestId,
			cookieScopeReset: migration.reset,
			cookieScopeState: migration.cookieScopeState,
			edgeOutcome: migration.edgeOutcome,
			outcome: 'succeeded'
		})
	} catch (error) {
		recordAuthDiagnosticEvent('COOKIE_SCOPE_428_RECOVERY_FAILED', {
			clientRequestId: triggerClientRequestId,
			pageInstanceId: authDiagnostic?.pageInstanceId,
			path: authDiagnostic?.path,
			source: authDiagnostic?.source,
			triggerClientRequestId,
			errorCode: error?.code || 'NETWORK_ERROR',
			outcome: 'failed'
		})
		throw error
	}
}

async function bootstrapBrowserSession(
	authDiagnostic = null,
	expectedGeneration = runtimeSessionRequestGeneration()
) {
	const sessionGeneration = normalizedSessionGeneration(expectedGeneration)
	try {
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
		// 恢复已登录会话时必须要求客户端已持有有效绑定 PreAuth，禁止在刷新时重新拉取匿名 PreAuth 强行续命；
		// 且禁止在会话恢复请求上自动重放，确保凭据缺失时立即抛出 401 并交由外层终止会话清理状态。
		const response = await publicRequest(BOOTSTRAP_PATH, {
			diagnosticSource: 'session_bootstrap',
			protectedSession: true,
			sessionGeneration,
			preAuthBootstrapPolicy: PreAuthBootstrapPolicy.REQUIRE_EXISTING,
			disableAutomaticReplay: true
		})
		// bootstrap 返回期间可能已经重新登录或结束会话；旧结果不得覆盖后来建立的会话。
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
		saveSession(response)
		return response
	} catch (error) {
		// 这里只负责及时废弃旧会话；清理去重不能决定外层是否仍需执行登录页跳转。
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic, true)
		clearTerminalSessionState(
			error,
			authDiagnostic,
			sessionGeneration,
			SessionRequestPurpose.SESSION_RECOVERY)
		throw error
	}
}

export function restoreBrowserSession(
	authDiagnostic = null,
	expectedGeneration = runtimeSessionRequestGeneration()
) {
	if (clientPlatform() !== 'H5') return Promise.resolve(null)
	const sessionGeneration = normalizedSessionGeneration(expectedGeneration)
	try {
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
	} catch (error) {
		return Promise.reject(error)
	}
	const owner = !bootstrapInFlight
		|| bootstrapInFlightGeneration !== sessionGeneration
	recordAuthDiagnosticEvent('SESSION_BOOTSTRAP_SINGLE_FLIGHT', {
		clientRequestId: authDiagnostic?.clientRequestId,
		path: authDiagnostic?.path || BOOTSTRAP_PATH,
		source: authDiagnostic?.source || 'restore_browser_session',
		sessionGeneration,
		owner,
		waiter: !owner
	})
	if (owner) {
		const task = bootstrapBrowserSession(authDiagnostic, sessionGeneration)
		let trackedTask = null
		trackedTask = task.finally(() => {
			// 旧代次 Promise 晚结束时只能释放自己，禁止清空新登录后创建的恢复任务。
			if (bootstrapInFlight === trackedTask) {
				bootstrapInFlight = null
				bootstrapInFlightGeneration = null
			}
		})
		bootstrapInFlight = trackedTask
		bootstrapInFlightGeneration = sessionGeneration
	}
	return bootstrapInFlight
}
export async function restorePersistedSession(authDiagnostic = null, expectedGeneration = runtimeSessionRequestGeneration()) {
	assertAuthorizedSessionCurrent(expectedGeneration, authDiagnostic)
	if (clientPlatform() === 'H5') return restoreBrowserSession(authDiagnostic, expectedGeneration)
	const credentials = currentSession()
	return Promise.resolve(hasCompleteSessionCredentials(credentials)
		? { restored: true }
		: null)
}

export async function authorizedRequest(path, options = {}, retryState = {}) {
	const sessionGeneration = normalizedSessionGeneration(retryState.sessionGeneration ?? options.sessionGeneration)
	const authDiagnostic = createAuthRequestDiagnostic(path, options.diagnosticSource || 'authorized_request')
	try {
		return await authorizedRequestAttempt(path, options, { ...retryState, sessionGeneration }, authDiagnostic)
	} catch (error) {
		// 恢复步骤内部抛出的错误也必须经过同一个退出入口。
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic, true)
		handleTerminalSessionError(error, authDiagnostic, sessionGeneration)
		throw error
	}
}

async function authorizedRequestAttempt(path, options, retryState, authDiagnostic) {
	const bootstrapPolicy = effectivePreAuthBootstrapPolicy(options)
	const schedulingPolicy = effectiveWebRtcSchedulingPolicy(options)
	const sessionGeneration = normalizedSessionGeneration(retryState.sessionGeneration)
	const nextRetryState = update => ({
		...retryState,
		sessionGeneration,
		...update
	})
	try {
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
		await ensureDeviceInstallationId()
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
		// 两端普通请求只等待 Cookie/PreAuth；H5 RTCPeerConnection 和 Android WebView 都在后台完成。
		await runAuthDiagnosticStage(
			authDiagnostic,
			'COOKIE_MIGRATION',
			() => ensureCookieScopeMigration())
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
		if (bootstrapPolicy === PreAuthBootstrapPolicy.ENSURE) {
			await runAuthDiagnosticStage(
				authDiagnostic,
				'PREAUTH',
				() => ensurePreAuth())
			assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
		} else {
			recordAuthDiagnosticEvent('PREAUTH_EXISTING_REQUIRED', {
				clientRequestId: authDiagnostic.clientRequestId,
				path,
				preAuthReady: isPreAuthReady(),
				outcome: 'oauth_attempt_owned'
			})
		}
		if (schedulingPolicy === WebRtcSchedulingPolicy.SUPPRESS) {
			recordWebRtcSchedulingSuppressed(authDiagnostic, 'authorized_request_ready')
		} else {
			scheduleH5WebRtcForRequest(authDiagnostic)
		}
		const headers = await protectedCredentialHeaders(
			options.headers,
			authDiagnostic,
			sessionGeneration)
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
		return await requestTask({
			path,
			method: options.method || 'POST',
			data: options.data,
			headers,
			authDiagnostic,
			protectedSession: true,
			sessionGeneration,
			webRtcSchedulingPolicy: schedulingPolicy,
			captureEtag: options.captureEtag === true,
			timeout: options.timeout,
			onResponse: options.onResponse
		})
	} catch (error) {
		recordAuthDiagnosticFailure(authDiagnostic, error)
		// 本地门禁错误只负责停止旧工作，不得再触发风险恢复、PreAuth 或会话清理。
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic, true)
		if (isAuthorizedSessionTermination(error)) throw error
		handleAuthorizedSecurityFailure(error)
		if (error?.code === 'RISK_CHALLENGE_REQUIRED') {
			if (clientPlatform() === 'H5') beginRiskChallenge(error)
			if (retryState.riskChallenge) {
				throw repeatedAndroidRiskChallengeError(error)
			}
			assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
			await acceptAndroidRiskChallenge(error)
			assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
			await recheckPreAuthAfterRiskChallenge()
			return authorizedRequest(
				path,
				options,
				nextRetryState({ riskChallenge: true }))
		}
		if (schedulingPolicy !== WebRtcSchedulingPolicy.SUPPRESS
			&& !retryState.webRtc
			&& isWebRtcRetryCode(error?.code)) {
			assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
			// #ifdef H5
			await recoverH5WebRtc()
			return authorizedRequest(
				path,
				options,
				nextRetryState({ webRtc: true }))
			// #endif
			// #ifdef APP-PLUS
			recoverAndroidWebRtc()
			// #endif
		}
		if (error?.code === 'PREAUTH_REQUIRED') {
			if (terminateAuthenticatedAndroidSession(error, authDiagnostic)) throw error
			if (bootstrapPolicy === PreAuthBootstrapPolicy.REQUIRE_EXISTING) {
				terminateOwnedH5OAuthSession(error, authDiagnostic)
				throw error
			}
			assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
			invalidatePreAuth()
			invalidateWebRtcVerification()
			if (!retryState.preAuth) {
				await ensurePreAuth()
				assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
				scheduleH5WebRtcForRequest(authDiagnostic, 'preauth_recovered')
				// #ifdef APP-PLUS
				void startAndroidWebRtcVerificationInBackground().catch(() => {})
				// #endif
				return authorizedRequest(
					path,
					options,
					nextRetryState({ preAuth: true }))
			}
		}
		throw error
	}
}
function handleAuthorizedSecurityFailure(error) {
	if (presentRiskBlock(error)) return
	if (isWebRtcFailureCode(error?.code)) {
		presentWebRtcFailure(error)
		invalidateWebRtcVerification()
	}
	if (error?.code === 'EDGE_COOKIE_SCOPE_RESET_REQUIRED') {
		invalidateCookieScopeMigration()
		invalidatePreAuth()
		invalidateWebRtcVerification()
	}
}

/**
 * 为无法通过 uni.request 消费的流式请求准备与普通认证请求完全相同的地址和安全请求头。
 * 调用方只能把结果用于一次受保护的 SSE 请求，不得写入本地存储或日志。
 */
export async function prepareAuthorizedStreamingRequest(path, options = {}) {
	const bootstrapPolicy = effectivePreAuthBootstrapPolicy(options)
	const schedulingPolicy = effectiveWebRtcSchedulingPolicy(options)
	const sessionGeneration = normalizedSessionGeneration(options.sessionGeneration)
	const authDiagnostic = createAuthRequestDiagnostic(
		path,
		options.diagnosticSource || 'authorized_streaming_request')
	try {
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
		await runAuthDiagnosticStage(
			authDiagnostic,
			'COOKIE_MIGRATION',
			() => ensureCookieScopeMigration())
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
		if (bootstrapPolicy === PreAuthBootstrapPolicy.ENSURE) {
			await runAuthDiagnosticStage(
				authDiagnostic,
				'PREAUTH',
				() => ensurePreAuth())
			assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
		} else {
			recordAuthDiagnosticEvent('PREAUTH_EXISTING_REQUIRED', {
				clientRequestId: authDiagnostic.clientRequestId,
				path,
				preAuthReady: isPreAuthReady(),
				outcome: 'oauth_attempt_owned'
			})
		}
		if (schedulingPolicy === WebRtcSchedulingPolicy.SUPPRESS) {
			recordWebRtcSchedulingSuppressed(authDiagnostic, 'streaming_ready')
		} else {
			scheduleH5WebRtcForRequest(authDiagnostic, 'streaming_ready')
		}
		const method = String(options.method || 'POST').toUpperCase()
		const headers = await protectedCredentialHeaders(
			options.headers,
			authDiagnostic,
			sessionGeneration)
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
		return Object.freeze({
			url: `${AUTH_API_BASE_URL}${path}`,
			method,
			headers: Object.freeze({ ...headers }),
			sessionGeneration,
			allowCsrfRecovery: options.allowCsrfRecovery === true
		})
	} catch (error) {
		recordAuthDiagnosticFailure(authDiagnostic, error)
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic, true)
		handleTerminalSessionError(error, authDiagnostic, sessionGeneration)
		throw error
	}
}

/**
 * 仅在流式请求尚未收到 accepted 时执行一次既有会话恢复；accepted 之后禁止自动重放生成 POST。
 * 返回 true 只表示恢复确实成功；终止性错误会完成退出处理并返回 false，恢复过程失败则保留原错误抛出。
 */
export async function recoverAuthorizedStreamingSession(error, options = {}) {
	const sessionGeneration = normalizedSessionGeneration(options.sessionGeneration ?? error?.sessionGeneration)
	const alreadyRetried = options.alreadyRetried === true
	const authDiagnostic = options.authDiagnostic || null
	assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic, true)
	if (localSessionCancellation(error) || isRuntimeTerminalSessionActive()) {
		handleTerminalSessionError(error, authDiagnostic, sessionGeneration)
		return false
	}
	if (!alreadyRetried && clientPlatform() === 'ANDROID' && error?.code === 'EDGE_CHALLENGE') {
		await ensureAndroidEdgeClearance()
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
		return true
	}
	const mode = sessionRenewalMode(
		clientPlatform(),
		error?.code,
		alreadyRetried)
	if (mode === SessionRenewalMode.NONE) {
		handleTerminalSessionError(
			error,
			authDiagnostic,
			sessionGeneration,
			SessionRequestPurpose.SESSION_RECOVERY)
		return false
	}
	try {
		await restoreBrowserSession(authDiagnostic, sessionGeneration)
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
		return true
	} catch (recoveryError) {
		assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic, true)
		if (!localSessionCancellation(recoveryError)) {
			handleTerminalSessionError(
				recoveryError,
				authDiagnostic,
				sessionGeneration,
				SessionRequestPurpose.SESSION_RECOVERY)
		}
		throw recoveryError
	}
}
async function protectedCredentialHeaders(additionalHeaders = {}, authDiagnostic = null, sessionGeneration = runtimeSessionRequestGeneration()) {
	assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
	const headers = clientContextHeaders()
	Object.assign(headers, additionalHeaders || {})
	const session = currentSession()
	if (usesExplicitTokenTransport()) {
		if (session.accessToken) headers.Authorization = `Bearer ${session.accessToken}`
		if (session.refreshToken) headers['X-Refresh-Token'] = session.refreshToken
		if (session.csrfToken) headers['X-CSRF-Token'] = session.csrfToken
		return headers
	}

	if (!browserCsrfToken()) {
		// 受保护会话丢失 CSRF 时必须通过 bootstrap 同步轮换 Redis 绑定，不能只领取未绑定的新 Cookie。
		await runAuthDiagnosticStage(
			authDiagnostic,
			'SESSION_BOOTSTRAP',
			() => restoreBrowserSession(authDiagnostic, sessionGeneration))
	}
	assertAuthorizedSessionCurrent(sessionGeneration, authDiagnostic)
	const csrfToken = browserCsrfToken()
	if (!csrfToken) {
		const error = new Error('CSRF token is unavailable.')
		error.code = 'CSRF_INVALID'
		throw error
	}
	// RT-first 要求安全读取请求也提交 CSRF，因此这里不再按 HTTP 方法过滤。
	headers['X-CSRF-Token'] = csrfToken
	return headers
}

export function applySessionRenewalHeaders(headers = {}, sessionGeneration = runtimeSessionRequestGeneration()) {
	const renewed = responseHeader(headers, 'X-Session-Renewed')
	if (String(renewed).toLowerCase() !== 'true') return false
	assertAuthorizedSessionCurrent(sessionGeneration)
	if (clientPlatform() === 'H5') return true
	const newAccessToken = responseHeader(headers, 'X-New-Access-Token')
	if (!newAccessToken) {
		const error = new Error('Session renewal response is incomplete.')
		error.code = 'SESSION_RESPONSE_INVALID'
		throw error
	}
	saveSession({ accessToken: String(newAccessToken) })
	return true
}

function responseHeader(headers, expectedName) {
	const entry = Object.entries(headers || {})
		.find(([name]) => name.toLowerCase() === expectedName.toLowerCase())
	return entry ? entry[1] : ''
}

export function clearTerminalSessionState(error, authDiagnostic = null, sessionGeneration = error?.sessionGeneration ?? runtimeSessionRequestGeneration(), purpose = SessionRequestPurpose.PROTECTED) {
	if (sessionGeneration !== runtimeSessionRequestGeneration() || !isTerminalSessionError(error, purpose)) return false
	if (!beginRuntimeTerminalSessionTransition()) {
		recordAuthDiagnosticEvent('SESSION_CLEAR_COALESCED', {
			clientRequestId: authDiagnostic?.clientRequestId,
			path: authDiagnostic?.path,
			source: authDiagnostic?.source,
			errorCode: error.code,
			outcome: 'joined'
		})
		return false
	}
	recordAuthDiagnosticEvent('SESSION_CLEAR_TRIGGERED', {
		clientRequestId: authDiagnostic?.clientRequestId,
		path: authDiagnostic?.path,
		source: authDiagnostic?.source,
		errorCode: error.code
	})
	// 终止性 401 必须按固定顺序废弃会话、PreAuth 和 WebRTC epoch，旧异步回调随后只能被忽略。
	clearSession()
	clearH5OAuthWebRtcGate()
	invalidatePreAuth()
	invalidateWebRtcVerification()
	return true
}

export function handleTerminalSessionError(error, authDiagnostic = null, sessionGeneration = error?.sessionGeneration ?? runtimeSessionRequestGeneration(), purpose = SessionRequestPurpose.PROTECTED) {
	if (sessionGeneration !== runtimeSessionRequestGeneration()) return false
	if (localSessionCancellation(error)) {
		redirectTerminalSessionToLogin(error, authDiagnostic, sessionGeneration)
		return true
	}
	if (!isTerminalSessionError(error, purpose)) return false
	// bootstrap 可以先清理；即使本次清理被合并，外层仍必须独立尝试领取跳转。
	clearTerminalSessionState(error, authDiagnostic, sessionGeneration, purpose)
	redirectTerminalSessionToLogin(error, authDiagnostic, sessionGeneration)
	return true
}

export function redirectTerminalSessionToLogin(error, authDiagnostic = null, sessionGeneration = error?.sessionGeneration ?? runtimeSessionRequestGeneration(), onFailure = null) {
	// 同一个错误沿调用栈传播时不能在导航失败后立即重试；后续页面动作仍可重新领取。
	if (error && terminalRedirectAttempts.get(error) === sessionGeneration) return false
	if (sessionGeneration !== runtimeSessionRequestGeneration() || !claimRuntimeTerminalSessionRedirect()) return false
	if (error) terminalRedirectAttempts.set(error, sessionGeneration)
	const fields = {
		clientRequestId: authDiagnostic?.clientRequestId,
		path: authDiagnostic?.path,
		source: authDiagnostic?.source,
		errorCode: error?.code,
		sessionGeneration,
		route: AUTH_ROUTES.login
	}
	recordAuthDiagnosticEvent('LOGIN_REDIRECT_TRIGGERED', {
		...fields
	})
	const failed = () => {
		if (!releaseRuntimeTerminalSessionRedirect(sessionGeneration)) return
		recordAuthDiagnosticEvent('LOGIN_REDIRECT_FAILED', { ...fields, outcome: 'failed' })
		try { onFailure?.() } catch (_) { /* 导航回调不得覆盖认证错误。 */ }
	}
	try {
		uni.reLaunch({
			url: AUTH_ROUTES.login,
			success: () => {
				if (sessionGeneration === runtimeSessionRequestGeneration()) {
					recordAuthDiagnosticEvent('LOGIN_REDIRECT_COMPLETED', { ...fields, outcome: 'succeeded' })
				}
			},
			fail: failed
		})
	} catch (_) {
		failed()
	}
	return true
}

/** 流式传输在通用重连之前统一处理 HTTP 认证失败，保留错误码并隔离旧会话结果。 */
export function handleAuthorizedStreamingFailure(error, request = {}) {
	const sessionGeneration = normalizedSessionGeneration(request.sessionGeneration ?? error?.sessionGeneration)
	try {
		assertAuthorizedSessionCurrent(sessionGeneration, null, true)
	} catch (cancelled) {
		return cancelled
	}
	error.sessionGeneration = sessionGeneration
	if (clientPlatform() === 'H5' && request.allowCsrfRecovery === true
		&& error?.code === 'CSRF_INVALID' && !isRuntimeTerminalSessionActive()) return error
	if (!handleTerminalSessionError(error, null, sessionGeneration)) {
		try { assertAuthorizedSessionCurrent(sessionGeneration) } catch (cancelled) { return cancelled }
	}
	return error
}

function terminateAuthenticatedAndroidSession(error, authDiagnostic = null) {
	if (clientPlatform() !== 'ANDROID'
		|| !hasCompleteSessionCredentials(currentSession())) return false
	if (!beginRuntimeTerminalSessionTransition()) return true
	clearSession()
	clearAndroidOAuthFlow()
	invalidatePreAuth()
	invalidateWebRtcVerification('ANDROID_PREAUTH_MISMATCH')
	recordAuthDiagnosticEvent('ANDROID_SESSION_CLEARED_PREAUTH_MISMATCH', {
		clientRequestId: authDiagnostic?.clientRequestId,
		path: authDiagnostic?.path,
		source: authDiagnostic?.source,
		errorCode: error?.code || 'PREAUTH_REQUIRED',
		outcome: 'cleared'
	})
	redirectTerminalSessionToLogin(error, authDiagnostic)
	return true
}

async function recoverH5WebRtc() {
	if (ownsH5WebRtcScheduling()) {
		recordAuthDiagnosticEvent('WEBRTC_BACKGROUND_SKIPPED', {
			source: 'recover_h5_webrtc',
			outcome: 'oauth_attempt_owned'
		})
		return null
	}
	invalidateWebRtcVerification()
	try {
		return await ensureH5WebRtcVerified()
	} catch (verificationError) {
		if (presentRiskBlock(verificationError)) return
		if (isWebRtcFailureCode(verificationError.code)) {
			presentWebRtcFailure(verificationError)
		}
		throw verificationError
	}
}

function terminateOwnedH5OAuthSession(error, authDiagnostic) {
	if (!ownsH5WebRtcScheduling()) return false
	clearSession()
	clearH5OAuthWebRtcGate()
	invalidateWebRtcVerification('OAUTH_PREAUTH_REQUIRED')
	recordAuthDiagnosticEvent('OAUTH_WEBRTC_VERDICT_REJECTED', {
		clientRequestId: authDiagnostic?.clientRequestId,
		path: authDiagnostic?.path,
		source: authDiagnostic?.source,
		errorCode: error?.code || 'PREAUTH_REQUIRED',
		outcome: 'preauth_missing'
	})
	return true
}

function recoverAndroidWebRtc() {
	if (isAndroidOAuthBlockingWebRtc()) return
	invalidateWebRtcVerification()
	void startAndroidWebRtcVerificationInBackground().catch(verificationError => {
		if (presentRiskBlock(verificationError)) return
		if (isWebRtcFailureCode(verificationError.code)) {
			presentWebRtcFailure(verificationError)
		}
	})
}

export async function logoutSession() {
	const sessionGeneration = runtimeSessionRequestGeneration()
	const platform = clientPlatform()
	const session = currentSession()
	const headers = {}
	let data
	if (platform === 'ANDROID') {
		if (session.accessToken) headers.Authorization = `Bearer ${session.accessToken}`
		if (session.csrfToken) headers['X-CSRF-Token'] = session.csrfToken
		data = { refreshToken: session.refreshToken || undefined }
	}
	try {
		if (platform === 'H5' && !browserCsrfToken()) {
			await bootstrapBrowserSession(null, sessionGeneration)
		}
		try {
			await publicRequest('/api/auth/session/logout', { headers, data, protectedSession: true, sessionGeneration })
		} catch (error) {
			if (platform !== 'H5' || error.code !== 'CSRF_INVALID') throw error
			await bootstrapBrowserSession(null, sessionGeneration)
			await publicRequest('/api/auth/session/logout', { headers, data, protectedSession: true, sessionGeneration })
		}
	} finally {
		if (sessionGeneration === runtimeSessionRequestGeneration() && beginRuntimeTerminalSessionTransition()) {
			clearSession()
			clearH5OAuthWebRtcGate()
			invalidatePreAuth()
			invalidateWebRtcVerification()
		}
	}
}

export async function logoutAllSessions() {
	const sessionGeneration = runtimeSessionRequestGeneration()
	// 全设备撤销失败时保留本地凭据，让用户留在当前页面重试。
	await authorizedRequest('/api/auth/session/logout-all', {
		preserveSessionOnFailure: true
	})
	assertAuthorizedSessionCurrent(sessionGeneration)
	beginRuntimeTerminalSessionTransition()
	clearSession()
	clearH5OAuthWebRtcGate()
	invalidatePreAuth()
	invalidateWebRtcVerification()
}

import { authApi } from './auth-api.js'
import { AUTH_ROUTES, clientPlatform } from './config.js'
import {
	createAuthDiagnosticId,
	recordAuthDiagnosticEvent
} from './auth-diagnostics.js'
import {
	collectAndReportAttempt,
	prepareWebRtcAttempt,
	presentWebRtcFailure,
	resumeOAuthWebRtcAttempt,
	suspendH5WebRtcForOAuth
} from './webrtc-verification.js'
import {
	adoptExistingH5PreAuth,
	ensurePreAuth,
	invalidatePreAuth as resetPreAuthState
} from './pre-auth.js'
import {
	clearH5OAuthWebRtcGate,
	hasPendingH5OAuthWebRtcVerdict as gateHasPendingH5OAuthWebRtcVerdict,
	readH5OAuthWebRtcGate,
	writeH5OAuthWebRtcGate
} from './h5-oauth-webrtc-gate.js'
import { clearSession, currentSession } from './session-vault.js'
import {
	loadAndroidOAuthFlow,
	clearAndroidOAuthFlow
} from './android-flow-keystore.js'
import {
	AndroidOAuthFailurePhase,
	AndroidOAuthPhase,
	androidOAuthCoordinator
} from './android-oauth-coordinator.js'
import { hasCompleteAndroidOAuthCredentials } from './session-credentials.js'
import { invalidateWebRtcVerification } from './webrtc-verification.js'
// #ifdef APP-PLUS
import {
	isGoogleSignInAvailable,
	signInWithGoogle
} from '@/uni_modules/ait-google-signin'
import { startAndroidWebRtcVerificationInBackground } from './webrtc-verification.js'
// #endif

const GOOGLE_OAUTH_LOG_PREFIX = '[AIT_GOOGLE_OAUTH]'
const GOOGLE_NATIVE_TIMEOUT_MS = 30000
const GOOGLE_NATIVE_COMPLETE_TIMEOUT_MS = 30000
const GOOGLE_NATIVE_COMPLETE_TIMEOUT_CODE = 'GOOGLE_NATIVE_COMPLETE_TIMEOUT'

const ANDROID_OAUTH_OPERATION_KEY = 'google-native'

function logGoogleOAuth(stage, fields = {}) {
	if (clientPlatform() !== 'ANDROID') return
	const allowed = [
		'provider',
		'mode',
		'code',
		'status',
		'httpStatus',
		'elapsedMs',
		'tokenPresent'
	]
	const details = allowed
		.filter(key => fields[key] !== undefined && fields[key] !== null)
		.map(key => `${key}=${String(fields[key])}`)
		.join(' ')
	console.log(`${GOOGLE_OAUTH_LOG_PREFIX} stage=${stage}${details ? ` ${details}` : ''}`)
}

function withClientTimeout(promise, timeoutMs, code, message) {
	let timeoutHandle = null
	const timeoutPromise = new Promise((_, reject) => {
		timeoutHandle = setTimeout(() => {
			const error = new Error(message)
			error.code = code
			reject(error)
		}, timeoutMs)
	})
	return Promise.race([promise, timeoutPromise])
		.finally(() => {
			if (timeoutHandle) clearTimeout(timeoutHandle)
		})
}

let resumePromise = null
let h5OAuthCompletionPromise = null
let h5OAuthVerdictPromise = null

function beginH5OAuthGate(prepared) {
	if (clientPlatform() !== 'H5') return null
	const preparedGeneration = prepared?.generation || prepared?.probeGeneration
	const generation = preparedGeneration
		|| (prepared?.mode === 'DISABLED' ? '1' : '')
	return writeH5OAuthWebRtcGate({
		flowId: createAuthDiagnosticId(),
		probeRunId: prepared?.probeRunId || createAuthDiagnosticId(),
		attemptId: '',
		generation,
		startedAt: Date.now(),
		fallbackUsed: false,
		verdictDeadlineAt: '',
		phase: 'PREPARED',
		stunUrls: Array.isArray(prepared?.stunUrls) ? [...prepared.stunUrls] : [],
		timeoutMillis: Number(prepared?.timeoutMillis) || 0,
		reportGraceMillis: Number(prepared?.reportGraceMillis) || 0,
		reportPath: prepared?.reportPath || '/api/_edge/webrtc/report'
	})
}

function currentH5OAuthGate() {
	if (clientPlatform() !== 'H5') return null
	return readH5OAuthWebRtcGate() || {
		flowId: createAuthDiagnosticId(),
		probeRunId: createAuthDiagnosticId(),
		attemptId: '',
		generation: '',
		startedAt: Date.now(),
		fallbackUsed: false,
		verdictDeadlineAt: '',
		phase: 'MISSING',
		stunUrls: [],
		timeoutMillis: 0,
		reportGraceMillis: 0,
		reportPath: '/api/_edge/webrtc/report'
	}
}

function h5OAuthWebRtcFailure(result) {
	const error = new Error('WebRTC 网络一致性校验未通过，OAuth 登录已终止。')
	error.code = result?.code || 'WEBRTC_VERIFICATION_FAILED'
	error.statusCode = Number(result?.statusCode) || 428
	error.webRtcStatus = result?.webRtcStatus === true
	error.webRtcIps = Array.isArray(result?.webRtcIps)
		? [...result.webRtcIps]
		: []
	error.retryable = false
	return error
}

async function completeH5OAuthWithWebRtc() {
	if (clientPlatform() !== 'H5') {
		recordAuthDiagnosticEvent('OAUTH_WEBRTC_GATE_SKIPPED', {
			source: 'non_h5_platform',
			outcome: 'not_applicable'
		})
		return authApi.oauthComplete()
	}
	const gate = currentH5OAuthGate()
	if (gate.phase === 'VERIFIED') {
		const result = await authApi.oauthComplete()
		clearH5OAuthWebRtcGate()
		return result
	}
	if (!gate.attemptId || !gate.generation) {
		throw h5OAuthWebRtcFailure({ code: 'WEBRTC_VERIFICATION_FAILED' })
	}
	recordAuthDiagnosticEvent('OAUTH_WEBRTC_ATTEMPT_RESUMED', {
		oauthFlowId: gate.flowId,
		probeRunId: gate.probeRunId,
		source: 'oauth_ready_to_complete',
		outcome: 'started'
	})
	let resumed
	try {
		resumed = await authApi.oauthWebRtcResume(gate)
		Object.assign(gate, resumeOAuthWebRtcAttempt({ ...gate, ...resumed }))
		gate.fallbackUsed = resumed?.fallbackUsed === true
		gate.phase = 'RESUMED'
		writeH5OAuthWebRtcGate(gate)
		if (resumed?.state === 'REPLACED') {
			recordAuthDiagnosticEvent('OAUTH_WEBRTC_FALLBACK_STARTED', {
				oauthFlowId: gate.flowId,
				probeRunId: gate.probeRunId,
				generation: gate.generation,
				outcome: 'started'
			})
		}
	} catch (error) {
		gate.phase = 'FAILED'
		writeH5OAuthWebRtcGate(gate)
		recordAuthDiagnosticEvent('OAUTH_WEBRTC_GATE_BLOCKED', {
			oauthFlowId: gate.flowId,
			probeRunId: gate.probeRunId,
			status: Number(error?.statusCode) || 0,
			errorCode: error?.code || 'WEBRTC_VERIFICATION_FAILED',
			failureReason: error?.code || 'WEBRTC_VERIFICATION_FAILED',
			verificationState: error?.verificationState || 'FAILED',
			webRtcStatus: error?.webRtcStatus === true,
			outcome: 'blocked'
		})
		throw error
	}
	const completingAttemptId = gate.attemptId
	const completingGeneration = gate.generation
	const result = await authApi.oauthComplete(null, gate)
	const persistedGate = readH5OAuthWebRtcGate()
	const pendingVerdictIssued = result?.status === 'AUTHENTICATED'
		&& result?.webRtcVerdict === 'PENDING'
		&& !!result?.webRtcVerdictDeadlineAt
		&& persistedGate?.attemptId === completingAttemptId
		&& persistedGate?.generation === completingGeneration
	if (pendingVerdictIssued) {
		adoptExistingH5PreAuth()
		gate.phase = 'PENDING_VERDICT'
		gate.verdictDeadlineAt = result.webRtcVerdictDeadlineAt || ''
		writeH5OAuthWebRtcGate(gate)
		recordAuthDiagnosticEvent('OAUTH_WEBRTC_ASYNC_SESSION_ISSUED', {
			oauthFlowId: gate.flowId,
			probeRunId: gate.probeRunId,
			generation: gate.generation,
			verificationState: result.webRtcVerdict || 'PENDING',
			outcome: 'issued'
		})
	} else if (result?.status === 'AUTHENTICATED') {
		// 服务端已经签发登录结果却没有返回同一 attempt 的待裁决契约时，必须立即丢弃本地会话；
		// 否则首页会在没有 owner gate 的情况下重新启动普通探测，并让 PENDING Session 最终超时。
		clearSession()
		gate.phase = 'FAILED'
		writeH5OAuthWebRtcGate(gate)
		recordAuthDiagnosticEvent('OAUTH_WEBRTC_GATE_BLOCKED', {
			oauthFlowId: gate.flowId,
			probeRunId: gate.probeRunId,
			generation: gate.generation,
			errorCode: 'WEBRTC_ASYNC_VERDICT_INVALID',
			failureReason: 'pending_contract_missing',
			verificationState: result?.webRtcVerdict || 'MISSING',
			outcome: 'blocked'
		})
		throw h5OAuthWebRtcFailure({
			code: 'WEBRTC_VERIFICATION_FAILED',
			statusCode: 503
		})
	} else {
		clearH5OAuthWebRtcGate()
	}
	return result
}

export function hasPendingH5OAuthWebRtcVerdict() {
	return gateHasPendingH5OAuthWebRtcVerdict()
}

export async function settlePendingH5OAuthWebRtcVerdict() {
	if (h5OAuthVerdictPromise) return h5OAuthVerdictPromise
	const gate = readH5OAuthWebRtcGate()
	if (!gate || gate.phase !== 'PENDING_VERDICT') return null
	h5OAuthVerdictPromise = (async () => {
	try {
		const verdict = await collectAndReportAttempt(gate)
		recordAuthDiagnosticEvent('OAUTH_WEBRTC_VERDICT_VERIFIED', {
			oauthFlowId: gate.flowId,
			probeRunId: gate.probeRunId,
			generation: gate.generation,
			verificationState: verdict?.state || verdict?.verificationState || 'VERIFIED',
			outcome: 'verified'
		})
		clearH5OAuthWebRtcGate()
		return verdict
	} catch (error) {
		clearSession()
		const timeout = error?.code === 'WEBRTC_VERIFICATION_TIMEOUT'
			|| (gate.verdictDeadlineAt && Date.now() > Date.parse(gate.verdictDeadlineAt))
		recordAuthDiagnosticEvent(
			timeout ? 'OAUTH_WEBRTC_VERDICT_TIMEOUT' : 'OAUTH_WEBRTC_VERDICT_REJECTED', {
				oauthFlowId: gate.flowId,
				probeRunId: gate.probeRunId,
				generation: gate.generation,
				errorCode: error?.code || 'WEBRTC_VERIFICATION_FAILED',
				outcome: timeout ? 'timeout' : 'rejected'
			})
		clearH5OAuthWebRtcGate()
		presentWebRtcFailure(error)
		throw error
	}
	})().finally(() => { h5OAuthVerdictPromise = null })
	return h5OAuthVerdictPromise
}

function openBrowser(url) {
	if (!url) throw new Error('第三方登录地址无效。')
	// #ifdef H5
	window.location.assign(url)
	return
	// #endif
	// #ifdef APP-PLUS
	plus.runtime.openURL(url, () => {
		uni.showModal({
			title: '无法打开浏览器',
			content: '请检查系统是否安装并启用了可用浏览器。',
			showCancel: false
		})
	})
	// #endif
}

function nativeGoogleAvailable() {
	if (clientPlatform() !== 'ANDROID') return false
	// #ifdef APP-PLUS
	try { return isGoogleSignInAvailable() }
	catch (error) { return false }
	// #endif
	return false
}

function requestNativeGoogle(serverClientId, nonce) {
	return new Promise((resolve, reject) => {
		let settled = false
		let timeoutHandle = null
		const settle = callback => value => {
			if (settled) return
			settled = true
			if (timeoutHandle) clearTimeout(timeoutHandle)
			callback(value)
		}
		const resolveOnce = settle(resolve)
		const rejectOnce = settle(reject)
		timeoutHandle = setTimeout(() => {
			if (settled) return
			settled = true
			timeoutHandle = null
			const error = new Error('Google 原生登录超时。')
			error.code = 'GOOGLE_NATIVE_TIMEOUT'
			reject(error)
		}, GOOGLE_NATIVE_TIMEOUT_MS)

		if (clientPlatform() !== 'ANDROID') {
			const error = new Error('当前设备不支持 Google 原生登录。')
			error.code = 'GOOGLE_NATIVE_UNAVAILABLE'
			rejectOnce(error)
			return
		}
		// #ifdef APP-PLUS
		try {
			signInWithGoogle(serverClientId, nonce, {
				success: result => {
					const idToken = result?.idToken
					if (!idToken) {
						const error = new Error('Google 原生登录未返回凭据。')
						error.code = 'GOOGLE_NATIVE_EMPTY_TOKEN'
						rejectOnce(error)
						return
					}
					resolveOnce(idToken)
				},
				cancel: () => resolveOnce(''),
				fail: (code, message) => {
					const error = new Error(message || 'Google 原生登录不可用。')
					error.code = code || 'GOOGLE_NATIVE_UNAVAILABLE'
					rejectOnce(error)
				}
			})
		} catch (error) {
			rejectOnce(error)
		}
		return
		// #endif
		const error = new Error('当前设备不支持 Google 原生登录。')
		error.code = 'GOOGLE_NATIVE_UNAVAILABLE'
		rejectOnce(error)
	})
}

function isAndroidOAuthNetworkFailure(error) {
	return error?.code === 'NETWORK_ERROR'
		|| error?.code === GOOGLE_NATIVE_COMPLETE_TIMEOUT_CODE
		|| error?.code === 'GOOGLE_NATIVE_TIMEOUT'
}

function androidOAuthStateError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

function clearAndroidOAuthState(clearAuthenticatedSession = false) {
	clearAndroidOAuthFlow()
	// Flow 过期只废弃 Flow；只有明确的会话/PreAuth 失配才销毁现有认证绑定。
	if (clearAuthenticatedSession) {
		clearSession()
		resetPreAuthState()
	}
	invalidateWebRtcVerification('ANDROID_OAUTH_STATE_RESET')
}

function localAndroidSessionIsComplete() {
	return hasCompleteAndroidOAuthCredentials(currentSession())
}

function requireLocalAndroidSession() {
	if (localAndroidSessionIsComplete()) return true
	clearAndroidOAuthState(true)
	throw androidOAuthStateError(
		'SESSION_RESPONSE_INVALID',
		'登录结果缺少完整会话凭据，请重新登录。')
}

async function confirmAndroidOAuthResult(flow, stage, idToken, retryState = {}) {
	let status
	try {
		status = await authApi.oauthStatus(flow)
	} catch (statusError) {
		if (['FLOW_NOT_FOUND', 'FLOW_EXPIRED', 'FLOW_FORBIDDEN'].includes(statusError?.code)) {
			clearAndroidOAuthState(false)
			throw androidOAuthStateError(
				'FLOW_EXPIRED',
				'登录流程已过期，请重新登录。')
		}
		// status 也失败时不能推断服务器是否已签发会话，更不能清除可能仍有效的本地会话。
		const error = androidOAuthStateError(
			'NETWORK_UNKNOWN',
			'登录结果尚未确认，请检查网络后重试。')
		error.causeCode = statusError?.code || 'NETWORK_ERROR'
		error.oauthStage = stage
		if (androidOAuthCoordinator.isActive()) {
			androidOAuthCoordinator.fail(AndroidOAuthFailurePhase.NETWORK_UNKNOWN, error)
		}
		throw error
	}
	if (status?.state === 'READY_TO_COMPLETE') {
		return { status, retryComplete: stage === 'oauth_complete' && !retryState.complete }
	}
	if (status?.state === 'PROVIDER_PENDING') {
		return {
			status,
			retryNative: stage === 'native_complete'
				&& typeof idToken === 'string'
				&& idToken.length > 0
				&& retryState.native !== true
		}
	}
	if (status?.state === 'AUTHENTICATED') {
		requireLocalAndroidSession()
		if (androidOAuthCoordinator.isActive()) {
			androidOAuthCoordinator.setPhase(AndroidOAuthPhase.CREDENTIALS_COMMITTED)
		}
		return { status, authenticated: true }
	}
	if (status?.state === 'TOTP_REQUIRED') return { status, completed: true }
	if (['FLOW_NOT_FOUND', 'EXPIRED', 'FAILED'].includes(status?.state)) {
		clearAndroidOAuthState(false)
		throw androidOAuthStateError(
			'FLOW_EXPIRED',
			'登录流程已过期，请重新登录。')
	}
	return { status }
}

async function completeAndroidOAuthWithReconciliation(flow = null) {
	let completeRetried = false
	while (true) {
		try {
			const result = await authApi.oauthComplete(flow)
			if (result?.status === 'AUTHENTICATED') requireLocalAndroidSession()
			if (androidOAuthCoordinator.isActive()) {
				androidOAuthCoordinator.setPhase(AndroidOAuthPhase.SESSION_COMPLETE)
				if (result?.status === 'AUTHENTICATED') {
					androidOAuthCoordinator.setPhase(AndroidOAuthPhase.CREDENTIALS_COMMITTED)
					// 凭据已整体提交后才启动后台探测；探测失败不回滚刚完成的会话。
					// #ifdef APP-PLUS
					void startAndroidWebRtcVerificationInBackground().catch(() => {})
					// #endif
				}
			}
			return result
		} catch (error) {
			if (error?.code === 'PREAUTH_REQUIRED') {
				clearAndroidOAuthState(true)
				if (androidOAuthCoordinator.isActive()) {
					androidOAuthCoordinator.fail(AndroidOAuthFailurePhase.PREAUTH_MISMATCH, error)
				}
				throw error
			}
			if (error?.code === 'SESSION_RESPONSE_INVALID') {
				clearAndroidOAuthState(true)
				throw error
			}
			if (error?.code === 'ALREADY_COMPLETED'
				|| error?.code === 'COMPLETION_IN_PROGRESS') {
				const decision = await confirmAndroidOAuthResult(
					flow, 'oauth_complete', null, { complete: true })
				if (decision.authenticated) {
					// 服务器已完成且本地凭据完整时只恢复状态，不重复调用 complete。
					// #ifdef APP-PLUS
					void startAndroidWebRtcVerificationInBackground().catch(() => {})
					// #endif
					return decision.status
				}
				if (decision.completed) return decision.status
			}
			if (!isAndroidOAuthNetworkFailure(error)) throw error
			recordAuthDiagnosticEvent('ANDROID_OAUTH_NETWORK_ERROR_BY_STAGE', {
				stage: 'oauth_complete',
				errorCode: error?.code || 'NETWORK_ERROR',
				outcome: 'reconciling'
			})
			const decision = await confirmAndroidOAuthResult(
				flow,
				'oauth_complete',
				null,
				{ complete: completeRetried })
			if (decision.authenticated) {
				recordAuthDiagnosticEvent('ANDROID_OAUTH_COMPLETION_RECONCILED', {
					stage: 'oauth_complete',
					outcome: 'authenticated'
				})
				// 响应丢失但会话已在本地提交，同样只恢复状态。
				// #ifdef APP-PLUS
				void startAndroidWebRtcVerificationInBackground().catch(() => {})
				// #endif
				return decision.status
			}
			if (decision.completed) return decision.status
			if (!decision.retryComplete || completeRetried) throw error
			completeRetried = true
		}
	}
}

async function continueFromStatus(status, flow = null) {
	if (!status?.state) return null
	if (['PHONE_REQUIRED', 'HUMAN_VERIFICATION_REQUIRED', 'CODE_READY'].includes(status.state)) {
		uni.navigateTo({ url: AUTH_ROUTES.oauthPhone })
		return status
	}
	if (status.state === 'READY_TO_COMPLETE') {
		const result = clientPlatform() === 'ANDROID'
			? await completeAndroidOAuthWithReconciliation(flow)
			: await completeH5OAuthWithWebRtc()
		if (result?.status === 'TOTP_REQUIRED') {
			uni.navigateTo({ url: AUTH_ROUTES.totpLogin })
		} else if (result?.status === 'AUTHENTICATED') {
			uni.reLaunch({ url: AUTH_ROUTES.home })
			// 跳转本身不等待裁决；下一事件循环与 App 生命周期会加入同一个后台任务。
			setTimeout(() => {
				void settlePendingH5OAuthWebRtcVerdict().catch(() => {})
			}, 0)
		}
		return result
	}
	if (status.state === 'TOTP_REQUIRED') {
		uni.navigateTo({ url: AUTH_ROUTES.totpLogin })
		return status
	}
	if (status.state === 'AUTHENTICATED') {
		if (clientPlatform() === 'ANDROID') {
			requireLocalAndroidSession()
			if (androidOAuthCoordinator.isActive()) {
				androidOAuthCoordinator.setPhase(AndroidOAuthPhase.CREDENTIALS_COMMITTED)
			}
			clearAndroidOAuthFlow()
		}
		uni.reLaunch({ url: AUTH_ROUTES.home })
		return status
	}
	if (['FAILED', 'EXPIRED'].includes(status.state)) {
		if (clientPlatform() === 'ANDROID') clearAndroidOAuthFlow()
		const error = new Error('第三方登录未完成，请重新尝试。')
		error.code = `OAUTH_${status.state}`
		throw error
	}
	return status
}

async function startOAuthInternal(provider, coordinatorContext = null) {
	const setAndroidPhase = phase => {
		if (clientPlatform() === 'ANDROID' && coordinatorContext) {
			coordinatorContext.setPhase(phase)
		}
	}
	const platform = clientPlatform()
	const nativeGoogle = platform === 'ANDROID' && provider === 'GOOGLE'
	if (nativeGoogle && !nativeGoogleAvailable()) {
		// 严格原生策略下，标准基座或无 GMS 不能静默改走浏览器，避免用户误以为已启动账号选择器。
		return { nativeUnavailable: true, code: 'GOOGLE_NATIVE_UNAVAILABLE' }
	}
	const oauthStartStartedAt = Date.now()
	logGoogleOAuth('oauth_start_begin', { provider })
	let response
	try {
		if (platform === 'ANDROID') {
			await ensurePreAuth()
			setAndroidPhase(AndroidOAuthPhase.PREAUTH_READY)
		}
		let prepared = null
		let attemptForOAuth = null
		if (platform === 'H5') {
			prepared = await prepareWebRtcAttempt({ source: 'oauth_start' })
			const gate = beginH5OAuthGate(prepared)
			if (!gate) {
				throw h5OAuthWebRtcFailure({ code: 'WEBRTC_VERIFICATION_FAILED' })
			}
			recordAuthDiagnosticEvent('OAUTH_WEBRTC_ATTEMPT_PREPARED', {
				oauthFlowId: gate.flowId,
				probeRunId: gate.probeRunId,
				generation: gate.generation,
				outcome: 'prepared'
			})
			const asyncVerdictRequired = prepared?.mode !== 'OBSERVE'
				&& prepared?.mode !== 'DISABLED'
				&& prepared?.phase !== 'VERIFIED'
				&& prepared?.verificationState !== 'VERIFIED'
			if (!asyncVerdictRequired) {
				// OBSERVE/DISABLED 保持原放行语义，不创建会在回调后撤销 Session 的强制 attempt。
				suspendH5WebRtcForOAuth(prepared)
				gate.phase = 'VERIFIED'
				writeH5OAuthWebRtcGate(gate)
			} else {
				attemptForOAuth = prepared
				suspendH5WebRtcForOAuth(prepared)
				gate.phase = 'OAUTH_SUSPENDED'
				writeH5OAuthWebRtcGate(gate)
				recordAuthDiagnosticEvent('OAUTH_WEBRTC_ATTEMPT_SUSPENDED', {
					oauthFlowId: gate.flowId,
					probeRunId: gate.probeRunId,
					generation: gate.generation,
					outcome: 'suspended'
				})
			}
		}
		response = await authApi.oauthStart(
			provider,
			nativeGoogle ? 'GOOGLE_NATIVE' : 'BROWSER',
			attemptForOAuth)
		setAndroidPhase(AndroidOAuthPhase.FLOW_STARTED)
		if (platform === 'H5') {
			const gate = currentH5OAuthGate()
			if (attemptForOAuth) {
				gate.attemptId = response?.webRtcAttemptId || ''
				gate.generation = response?.probeGeneration || gate.generation
				gate.fallbackUsed = response?.fallbackUsed === true
				gate.phase = response?.webRtcAttemptState === 'VERIFIED'
					? 'VERIFIED' : 'OAUTH_SUSPENDED'
			} else {
				// 已 VERIFIED、OBSERVE 或 DISABLED 不创建服务端 attempt，回调必须保留旧放行语义。
				gate.phase = 'VERIFIED'
			}
			writeH5OAuthWebRtcGate(gate)
		}
		logGoogleOAuth('oauth_start_success', {
			mode: response?.mode,
			elapsedMs: Date.now() - oauthStartStartedAt
		})
	} catch (error) {
		if (platform === 'H5') clearH5OAuthWebRtcGate()
		logGoogleOAuth('oauth_start_fail', {
			provider,
			code: error?.code,
			httpStatus: error?.statusCode,
			elapsedMs: Date.now() - oauthStartStartedAt
		})
		throw error
	}
	if (response?.mode === 'BROWSER_REDIRECT' || response?.mode === 'BROWSER') {
		if (nativeGoogle) {
			const error = new Error('安卓 Google 登录必须使用原生账号选择器。')
			error.code = 'GOOGLE_NATIVE_MODE_REQUIRED'
			throw error
		}
		openBrowser(response.authorizationUrl)
		return { pendingBrowser: true }
	}
	if (nativeGoogle && response?.mode !== 'GOOGLE_NATIVE') {
		const error = new Error('安卓 Google 登录模式无效。')
		error.code = 'GOOGLE_NATIVE_MODE_REQUIRED'
		throw error
	}
	if (!nativeGoogle) {
		throw new Error('第三方登录模式无效。')
	}
	const nativeStartedAt = Date.now()
	logGoogleOAuth('native_begin', { provider })
	setAndroidPhase(AndroidOAuthPhase.NATIVE_PICKER)
	let idToken
	try {
		idToken = await requestNativeGoogle(
			response.googleServerClientId, response.nonce)
	} catch (error) {
		const nativeFields = {
			provider,
			code: error?.code,
			elapsedMs: Date.now() - nativeStartedAt
		}
		logGoogleOAuth(
			error?.code === 'GOOGLE_NATIVE_TIMEOUT' ? 'native_timeout' : 'native_fail',
			nativeFields)
		if (['GOOGLE_NATIVE_UNAVAILABLE', 'GOOGLE_NATIVE_NO_ACCOUNT'].includes(error?.code)) {
			// 原生请求已创建服务端 Flow；失败时显式取消，避免旧 nonce 在本机继续保持待处理状态。
			try { await authApi.oauthCancel() } catch (ignored) { }
			return { nativeUnavailable: true, code: error.code }
		}
		throw error
	}
	if (!idToken) {
		logGoogleOAuth('native_cancel', {
			provider,
			elapsedMs: Date.now() - nativeStartedAt
		})
		try { await authApi.oauthCancel() } catch (ignored) { }
		return { cancelled: true }
	}
	logGoogleOAuth('native_success', {
		provider,
		tokenPresent: true,
		elapsedMs: Date.now() - nativeStartedAt
	})

	// ID Token 只存在于当前局部变量，上传结束后不写入任何持久化存储。
	const completeStartedAt = Date.now()
	logGoogleOAuth('native_complete_begin', { provider })
	setAndroidPhase(AndroidOAuthPhase.NATIVE_COMPLETE)
	let status
	let nativeCompleteRetried = false
	try {
		while (true) {
			try {
				status = await withClientTimeout(
					authApi.oauthNativeGoogleComplete(idToken),
					GOOGLE_NATIVE_COMPLETE_TIMEOUT_MS,
					GOOGLE_NATIVE_COMPLETE_TIMEOUT_CODE,
					'Google 登录完成请求超时。')
				break
			} catch (error) {
				if (!isAndroidOAuthNetworkFailure(error)) throw error
				recordAuthDiagnosticEvent('ANDROID_OAUTH_NETWORK_ERROR_BY_STAGE', {
					stage: 'native_complete',
					errorCode: error?.code || 'NETWORK_ERROR',
					outcome: 'reconciling'
				})
				const decision = await confirmAndroidOAuthResult(
					loadAndroidOAuthFlow(),
					'native_complete',
					idToken,
					{ native: nativeCompleteRetried })
				if (decision.authenticated) {
					status = decision.status
					break
				}
				if (decision.status?.state === 'READY_TO_COMPLETE') {
					status = decision.status
					break
				}
				if (decision.completed) {
					status = decision.status
					break
				}
				if (!decision.retryNative || nativeCompleteRetried) throw error
				nativeCompleteRetried = true
			}
		}
		logGoogleOAuth('native_complete_success', {
			provider,
			status: status?.status,
			elapsedMs: Date.now() - completeStartedAt
		})
	} catch (error) {
		logGoogleOAuth(
			error?.code === GOOGLE_NATIVE_COMPLETE_TIMEOUT_CODE
				? 'native_complete_timeout'
				: 'native_complete_fail',
			{
				provider,
				code: error?.code,
				httpStatus: error?.statusCode,
				elapsedMs: Date.now() - completeStartedAt
			})
		throw error
	} finally {
		// 原生凭据只允许存活到 native complete/结果确认结束，后续 OAuth complete 不再持有它。
		idToken = ''
	}
	setAndroidPhase(AndroidOAuthPhase.SESSION_COMPLETE)
	return await continueFromStatus(status, loadAndroidOAuthFlow())
}

/** Android 原生 Google 登录的唯一入口；H5 仍直接执行原有流程。 */
export function startOAuth(provider) {
	if (clientPlatform() !== 'ANDROID' || provider !== 'GOOGLE') {
		return startOAuthInternal(provider)
	}
	if (androidOAuthCoordinator.isActive()) {
		recordAuthDiagnosticEvent('ANDROID_OAUTH_JOINED_EXISTING_OPERATION', {
			outcome: 'joined'
		})
	}
	return androidOAuthCoordinator.run(
		ANDROID_OAUTH_OPERATION_KEY,
		async context => {
			try {
				return await startOAuthInternal(provider, context)
			} catch (error) {
				if (error?.code === 'PREAUTH_REQUIRED') {
					clearAndroidOAuthState(true)
					context.fail(AndroidOAuthFailurePhase.PREAUTH_MISMATCH, error)
				}
				throw error
			}
		})
}

function resumePendingOAuthInternal(flow, coordinatorContext = null) {
	const setAndroidPhase = phase => coordinatorContext?.setPhase(phase)
	return authApi.oauthStatus(flow)
		.then(status => {
			if (status?.state === 'PROVIDER_PENDING') {
				// 进程恢复后原生 ID Token 已不在内存，不能重放旧 Provider 完成请求。
				clearAndroidOAuthState(false)
				throw androidOAuthStateError(
					'FLOW_EXPIRED',
					'原生登录已中断，请重新登录。')
			}
			setAndroidPhase(AndroidOAuthPhase.FLOW_STARTED)
			return continueFromStatus(status, flow)
		})
		.catch(error => {
			if (['FLOW_NOT_FOUND', 'FLOW_EXPIRED', 'FLOW_FORBIDDEN'].includes(error?.code)) {
				clearAndroidOAuthState(false)
				coordinatorContext?.fail(AndroidOAuthFailurePhase.FLOW_EXPIRED, error)
			}
			throw error
		})
}

export function resumePendingOAuth() {
	if (clientPlatform() !== 'ANDROID') return null
	if (resumePromise) return resumePromise
	const joined = androidOAuthCoordinator.join(ANDROID_OAUTH_OPERATION_KEY)
	if (joined) {
		recordAuthDiagnosticEvent('ANDROID_OAUTH_DUPLICATE_RESUME_SUPPRESSED', {
			outcome: 'joined'
		})
		return joined
	}
	const flow = loadAndroidOAuthFlow()
	if (!flow) return null
	resumePromise = androidOAuthCoordinator.run(
		ANDROID_OAUTH_OPERATION_KEY,
		context => resumePendingOAuthInternal(flow, context))
		.finally(() => { resumePromise = null })
	return resumePromise
}

export function completeH5OAuthReturn() {
	if (h5OAuthCompletionPromise) return h5OAuthCompletionPromise
	h5OAuthCompletionPromise = authApi.oauthStatus()
		.then(status => {
			if (status?.state === 'PROVIDER_PENDING') {
				const error = new Error('第三方登录回调未通过校验，请返回登录页重新尝试。')
				error.code = 'OAUTH_CALLBACK_REJECTED'
				throw error
			}
			return continueFromStatus(status)
		})
		.finally(() => { h5OAuthCompletionPromise = null })
	return h5OAuthCompletionPromise
}

import {
	publicRequest,
	PreAuthBootstrapPolicy,
	WebRtcSchedulingPolicy
} from './http-client.js'
import { commitAndroidOAuthSession, saveSession } from './session-vault.js'
import { markRuntimeSessionAuthenticated } from './authenticated-session-state.js'
import { recordAuthDiagnosticEvent } from './auth-diagnostics.js'
import { clientPlatform } from './config.js'
import { classifyPassword, passwordError } from './password-policy.js'
import {
	loadAndroidPasswordResetFlow,
	loadAndroidRegisterFlow,
	loadAndroidTotpLoginFlow,
	loadAndroidOAuthFlow,
	saveAndroidOAuthFlow,
	updateAndroidOAuthPhoneFlow,
	updateAndroidOAuthFlowExpiry,
	clearAndroidOAuthFlow
} from './android-flow-keystore.js'
import { beginTotpLoginFlow, clearTotpLoginFlow } from './totp-login-flow.js'
import {
	currentWebRtcVerificationEpoch,
	invalidateWebRtcVerification,
	scheduleH5WebRtcVerification
} from './webrtc-verification.js'
// #ifdef APP-PLUS
import {
	startAndroidWebRtcVerificationInBackground
} from './webrtc-verification.js'
// #endif
import {
	beginRegistrationFlow,
	clearRegistrationFlowState,
	handleRegistrationFlowError
} from './registration-flow-guard.js'

function addChallengeHeader(headers, flow) {
	if (flow?.challengeHandle) headers['X-Turnstile-Challenge'] = flow.challengeHandle
}

function flowHeaders(kind, flow = {}) {
	const headers = {}
	const android = clientPlatform() === 'ANDROID'
	if (kind === 'register') {
		const registerFlow = android ? (loadAndroidRegisterFlow() || flow) : flow
		addChallengeHeader(headers, registerFlow)
		if (android) {
			if (registerFlow?.registerToken) headers['X-Register-Token'] = registerFlow.registerToken
			if (registerFlow?.flowCsrf) headers['X-Register-CSRF'] = registerFlow.flowCsrf
		}
	} else if (kind === 'login') {
		addChallengeHeader(headers, flow)
		if (flow?.loginFlowToken) headers['X-Login-Flow-Token'] = flow.loginFlowToken
	} else {
		const resetFlow = android ? (loadAndroidPasswordResetFlow() || flow) : flow
		addChallengeHeader(headers, resetFlow)
		if (android && resetFlow?.resetFlowToken) {
			headers['X-Reset-Flow-Token'] = resetFlow.resetFlowToken
		}
	}
	return headers
}

function oauthHeaders(flow = null, includePhone = false) {
	if (clientPlatform() !== 'ANDROID') return {}
	const current = flow || loadAndroidOAuthFlow() || {}
	const headers = {}
	if (current.oauthFlowToken) headers['X-OAuth-Flow-Token'] = current.oauthFlowToken
	if (includePhone && current.phoneFlowToken) {
		headers['X-OAuth-Phone-Flow-Token'] = current.phoneFlowToken
	}
	if (includePhone && current.turnstileChallenge) {
		headers['X-Turnstile-Challenge'] = current.turnstileChallenge
	}
	return headers
}

function oauthRequestOptions(options = {}) {
	if (clientPlatform() !== 'H5') return options
	return {
		...options,
		// 外部 OAuth 跳转期间禁止普通请求创建后台 WebRTC，避免 pagehide 留下过期 generation。
		webRtcSchedulingPolicy: WebRtcSchedulingPolicy.SUPPRESS
	}
}

function existingH5OAuthRequestOptions(options = {}) {
	return oauthRequestOptions({
		...options,
		disableAutomaticReplay: true,
		preAuthBootstrapPolicy: PreAuthBootstrapPolicy.REQUIRE_EXISTING
	})
}

const H5WebRtcCompletionGate = Object.freeze({
	DEFAULT: 'DEFAULT',
	OAUTH_ASYNC_PENDING: 'OAUTH_ASYNC_PENDING'
})

let androidOAuthCompletePromise = null
let androidOAuthNativeCompletePromise = null

function forgetTokenHeaders(providedForgetToken) {
	if (clientPlatform() !== 'ANDROID') return {}
	const flow = loadAndroidPasswordResetFlow() || {}
	const forgetToken = flow.forgetToken || providedForgetToken
	return forgetToken ? { 'X-Forget-Token': forgetToken } : {}
}

async function registrationFlowRequest(path, requestOptions = {}) {
	try {
		return await publicRequest(path, requestOptions)
	} catch (error) {
		handleRegistrationFlowError(error)
		throw error
	}
}

function rejectClientPassword(message, code) {
	const error = new Error(message)
	error.code = code
	throw error
}

function assertPasswordWriteAllowed(password, passwordConfirmation) {
	const message = passwordError(password, passwordConfirmation)
	if (!message) return
	const code = password === passwordConfirmation
		? 'PASSWORD_STRENGTH_INSUFFICIENT'
		: 'PASSWORD_CONFIRMATION_MISMATCH'
	rejectClientPassword(message, code)
}

function assertPasswordLoginAllowed(password) {
	const assessment = classifyPassword(password)
	if (assessment.acceptable) return
	rejectClientPassword('密码强度不足，请重置密码。', 'PASSWORD_RESET_REQUIRED')
}

function handleLoginResponse(
	response,
	{
		boundaryEstablished = false,
		path = 'authentication_complete',
		scheduleH5AfterAuthentication = true,
		commitAndroidCredentials = false
	} = {}
) {
	if (response?.status === 'TOTP_REQUIRED') {
		beginTotpLoginFlow(response)
		return response
	}
	if (response?.status === 'AUTHENTICATED') {
		clearTotpLoginFlow()
		if (commitAndroidCredentials) commitAndroidOAuthSession(response)
		else saveSession(response)
		markRuntimeSessionAuthenticated()
		// 普通完成请求已在发送前切换 epoch；严格 OAuth 闸门则等 complete 成功后再切换。
		if (!boundaryEstablished) {
			invalidateWebRtcVerification('AUTHENTICATED_EPOCH_ROTATED')
		}
		recordAuthDiagnosticEvent('WEBRTC_AUTH_EPOCH_STARTED', {
			path,
			source: 'handle_login_response',
			preAuthEpoch: currentWebRtcVerificationEpoch(),
			outcome: 'authenticated'
		})
		// #ifdef H5
		if (scheduleH5AfterAuthentication) {
			scheduleH5WebRtcVerification({
				path,
				source: 'authenticated_epoch_started'
			})
		}
		// #endif
		// #ifdef APP-PLUS
		// Android 保持后台 WebView 校验，不阻塞登录成功响应。
		if (!commitAndroidCredentials) {
			void startAndroidWebRtcVerificationInBackground().catch(() => {})
		}
		// #endif
	}
	return response
}

async function authenticationCompletionRequest(
	path,
	options = {},
	{
		cancelReason = 'AUTHENTICATED_EPOCH_ROTATED',
		handleLogin = true,
		h5WebRtcGate = H5WebRtcCompletionGate.DEFAULT,
		commitAndroidCredentials = false,
		preAuthBootstrapPolicy = null
	} = {}
) {
	const pendingH5OAuthVerdict = clientPlatform() === 'H5'
		&& h5WebRtcGate === H5WebRtcCompletionGate.OAUTH_ASYNC_PENDING
	// OAuth pending attempt 必须跨 complete 保留；其他认证完成仍切换到新的认证 epoch。
	if (!pendingH5OAuthVerdict) invalidateWebRtcVerification(cancelReason)
	const response = await publicRequest(path, {
		...options,
		disableAutomaticReplay: true,
		webRtcSchedulingPolicy: WebRtcSchedulingPolicy.SUPPRESS,
		...(pendingH5OAuthVerdict || preAuthBootstrapPolicy
			? { preAuthBootstrapPolicy: PreAuthBootstrapPolicy.REQUIRE_EXISTING }
			: {})
	})
	return handleLogin
		? handleLoginResponse(response, {
			boundaryEstablished: pendingH5OAuthVerdict,
			path,
			scheduleH5AfterAuthentication: !pendingH5OAuthVerdict,
			commitAndroidCredentials
		})
		: response
}

export const authApi = {
	async oauthStart(provider, interactionMode, attempt = null) {
		// 跳转前允许建立缺失的 CSRF，但 prepare 已保证复用当前 PreAuth；严格禁止 bootstrap 从回调页开始。
		const response = await publicRequest('/api/auth/oauth2/start', oauthRequestOptions({
			data: {
				provider,
				interactionMode,
				...(attempt?.probeGeneration
					? { probeGeneration: attempt.probeGeneration } : {}),
				...(attempt?.probeRunId ? { probeRunId: attempt.probeRunId } : {})
			}
		}))
		if (clientPlatform() === 'ANDROID' && response?.oauthFlowToken) {
			saveAndroidOAuthFlow({ ...response, provider })
		}
		return response
	},
	async oauthStatus(flow = null) {
		const response = await publicRequest('/api/auth/oauth2/flow/status',
			(clientPlatform() === 'H5' ? existingH5OAuthRequestOptions : oauthRequestOptions)({
			method: 'GET',
			headers: oauthHeaders(flow)
		}))
		if (clientPlatform() === 'ANDROID') updateAndroidOAuthFlowExpiry(response)
		return response
	},
	oauthWebRtcResume(attempt) {
		return publicRequest('/api/auth/oauth2/webrtc/resume', existingH5OAuthRequestOptions({
			data: {
				attemptId: attempt?.attemptId,
				probeGeneration: attempt?.generation || attempt?.probeGeneration
			}
		}))
	},
	oauthWebRtcVerdictStatus(attempt) {
		return publicRequest('/api/_edge/webrtc/verdict-status', existingH5OAuthRequestOptions({
			data: {
				attemptId: attempt?.attemptId,
				probeGeneration: attempt?.generation || attempt?.probeGeneration
			}
		}))
	},
	oauthNativeGoogleComplete(idToken, flow = null) {
		if (clientPlatform() === 'ANDROID' && androidOAuthNativeCompletePromise) {
			return androidOAuthNativeCompletePromise
		}
		const request = authenticationCompletionRequest('/api/auth/oauth2/google/native/complete', {
			headers: oauthHeaders(flow),
			data: { idToken },
			timeout: 30000
		}, {
			cancelReason: 'OAUTH_COMPLETE_BOUNDARY',
			handleLogin: false,
			preAuthBootstrapPolicy: PreAuthBootstrapPolicy.REQUIRE_EXISTING
		})
		if (clientPlatform() !== 'ANDROID') return request
		androidOAuthNativeCompletePromise = request.finally(() => {
			androidOAuthNativeCompletePromise = null
		})
		return androidOAuthNativeCompletePromise
	},
	async oauthPhoneStart(data, flow = null) {
		const requestOptions = clientPlatform() === 'H5'
			? existingH5OAuthRequestOptions
			: oauthRequestOptions
		const response = await publicRequest('/api/auth/oauth2/phone/start', requestOptions({
			headers: oauthHeaders(flow), data
		}))
		if (clientPlatform() === 'ANDROID') updateAndroidOAuthPhoneFlow(response)
		return response
	},
	oauthPhoneTurnstile(turnstileToken, flow = null) {
		const requestOptions = clientPlatform() === 'H5'
			? existingH5OAuthRequestOptions
			: oauthRequestOptions
		return publicRequest('/api/auth/oauth2/phone/turnstile', requestOptions({
			headers: oauthHeaders(flow, true), data: { turnstileToken }
		}))
	},
	oauthPhoneSend(deliveryMethod, flow = null) {
		const requestOptions = clientPlatform() === 'H5'
			? existingH5OAuthRequestOptions
			: oauthRequestOptions
		return publicRequest('/api/auth/oauth2/phone/send', requestOptions({
			headers: oauthHeaders(flow, true), data: { deliveryMethod }
		}))
	},
	oauthPhoneVerify(code, flow = null) {
		const requestOptions = clientPlatform() === 'H5'
			? existingH5OAuthRequestOptions
			: oauthRequestOptions
		return publicRequest('/api/auth/oauth2/phone/verify', requestOptions({
			headers: oauthHeaders(flow, true), data: { code }
		}))
	},
	oauthComplete(flow = null, attempt = null) {
		if (clientPlatform() === 'ANDROID' && androidOAuthCompletePromise) {
			return androidOAuthCompletePromise
		}
		const execute = async () => {
			const headers = oauthHeaders(flow)
			if (attempt?.attemptId) {
				headers['X-AIT-OAuth-WebRTC-Attempt-Id'] = attempt.attemptId
				headers['X-AIT-WebRTC-Probe-Generation'] =
					attempt.generation || attempt.probeGeneration
			}
			const handled = await authenticationCompletionRequest('/api/auth/oauth2/complete', {
				headers
			}, {
				cancelReason: 'OAUTH_COMPLETE_BOUNDARY',
				commitAndroidCredentials: clientPlatform() === 'ANDROID',
				preAuthBootstrapPolicy: clientPlatform() === 'ANDROID'
					? PreAuthBootstrapPolicy.REQUIRE_EXISTING : null,
				h5WebRtcGate: attempt?.attemptId
					? H5WebRtcCompletionGate.OAUTH_ASYNC_PENDING
					: H5WebRtcCompletionGate.DEFAULT
			})
			if (clientPlatform() === 'ANDROID') clearAndroidOAuthFlow()
			return handled
		}
		if (clientPlatform() !== 'ANDROID') return execute()
		androidOAuthCompletePromise = execute().finally(() => {
			androidOAuthCompletePromise = null
		})
		return androidOAuthCompletePromise
	},
	async oauthCancel(flow = null) {
		try {
			return await publicRequest('/api/auth/oauth2/cancel', oauthRequestOptions({
				headers: oauthHeaders(flow)
			}))
		} finally {
			if (clientPlatform() === 'ANDROID') clearAndroidOAuthFlow()
		}
	},
	turnstileConfig() {
		return publicRequest('/api/auth/turnstile/config', { method: 'GET' })
	},
	phoneCountry() {
		return publicRequest('/api/auth/phone-country', {
			method: 'GET',
			timeout: 8000,
			includeClientContext: false
		})
	},
	async registerStart(data) {
		const response = await publicRequest('/api/auth/register/start', { data })
		beginRegistrationFlow()
		return response
	},
	async registerStatus(flow, options = {}) {
		const headers = flowHeaders('register', flow)
		if (options.attemptId) headers['X-Turnstile-Attempt-Id'] = options.attemptId
		return registrationFlowRequest('/api/auth/register/status', {
			method: 'GET',
			headers,
			onResponse: options.onResponse
		})
	},
	registerTurnstile(flow, turnstileToken, options = {}) {
		const headers = flowHeaders('register', flow)
		if (options.attemptId) headers['X-Turnstile-Attempt-Id'] = options.attemptId
		return registrationFlowRequest('/api/auth/register/turnstile', {
			headers,
			data: { turnstileToken },
			onResponse: options.onResponse
		})
	},
	registerSend(flow, channel, deliveryMethod = 'SMS') {
		if (channel === 'phone') {
			return registrationFlowRequest('/api/auth/register/codes/phone/send', {
				headers: flowHeaders('register', flow),
				data: { deliveryMethod }
			})
		}
		const endpoint = channel === 'sms'
			? '/api/auth/register/codes/sms/send'
			: '/api/auth/register/codes/email/send'
		return registrationFlowRequest(endpoint, {
			headers: flowHeaders('register', flow)
		})
	},
	registerVerify(flow, emailCode, smsCode) {
		return registrationFlowRequest('/api/auth/register/codes/verify', {
			headers: flowHeaders('register', flow), data: { emailCode, smsCode }
		})
	},
	async registerComplete(flow, password, passwordConfirmation) {
		assertPasswordWriteAllowed(password, passwordConfirmation)
		const response = await registrationFlowRequest('/api/auth/register/complete', {
			headers: flowHeaders('register', flow), data: { password, passwordConfirmation }
		})
		if (response?.registered) clearRegistrationFlowState()
		return response
	},
	async passwordLogin(data) {
		assertPasswordLoginAllowed(data?.password)
		return authenticationCompletionRequest('/api/auth/login/password', { data })
	},
	loginCodeStart(data) {
		return publicRequest('/api/auth/login/code/start', { data })
	},
	loginCodeTurnstile(flow, turnstileToken) {
		return publicRequest('/api/auth/login/code/turnstile', {
			headers: flowHeaders('login', flow), data: { turnstileToken }
		})
	},
	loginCodeSend(flow, deliveryMethod) {
		return publicRequest('/api/auth/login/code/send', {
			headers: flowHeaders('login', flow),
			data: deliveryMethod ? { deliveryMethod } : undefined
		})
	},
	async loginCodeVerify(flow, strategyType, code) {
		return authenticationCompletionRequest('/api/auth/login/code/verify', {
			headers: flowHeaders('login', flow), data: { strategyType, code }
		})
	},
	async totpLoginVerify(code) {
		const headers = {}
		if (clientPlatform() === 'ANDROID') {
			const flow = loadAndroidTotpLoginFlow()
			if (!flow?.totpFlowToken) {
				const error = new Error('二次认证流程已过期，请重新登录。')
				error.code = 'TOTP_FLOW_EXPIRED'
				throw error
			}
			headers['X-TOTP-Flow-Token'] = flow.totpFlowToken
		}
		try {
			return await authenticationCompletionRequest('/api/auth/login/totp/verify', {
				headers,
				data: { code }
			})
		} catch (error) {
			if (['TOTP_FLOW_EXPIRED', 'TOTP_ATTEMPTS_EXHAUSTED', 'TOTP_FLOW_FORBIDDEN']
				.includes(error?.code)) clearTotpLoginFlow()
			throw error
		}
	},
	passwordResetStart(data) {
		return publicRequest('/api/auth/password-reset/start', { data })
	},
	passwordResetTurnstile(flow, turnstileToken) {
		return publicRequest('/api/auth/password-reset/turnstile', {
			headers: flowHeaders('reset', flow), data: { turnstileToken }
		})
	},
	passwordResetSend(flow, deliveryMethod) {
		return publicRequest('/api/auth/password-reset/send', {
			headers: flowHeaders('reset', flow),
			data: deliveryMethod ? { deliveryMethod } : undefined
		})
	},
	passwordResetVerify(flow, code) {
		return publicRequest('/api/auth/password-reset/verify', {
			headers: flowHeaders('reset', flow), data: { code }
		})
	},
	passwordResetComplete(first, second, third) {
		const password = third === undefined ? first : second
		const passwordConfirmation = third === undefined ? second : third
		const providedForgetToken = third === undefined ? '' : first
		assertPasswordWriteAllowed(password, passwordConfirmation)
		return publicRequest('/api/auth/password-reset/complete', {
			headers: forgetTokenHeaders(providedForgetToken),
			data: { password, passwordConfirmation }
		})
	}
}

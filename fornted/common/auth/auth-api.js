import { publicRequest } from './http-client.js'
import { saveSession } from './session-vault.js'
import { clientPlatform } from './config.js'
import { classifyPassword, passwordError } from './password-policy.js'
import {
	loadAndroidPasswordResetFlow,
	loadAndroidRegisterFlow,
	loadAndroidTotpLoginFlow
} from './android-flow-keystore.js'
import { beginTotpLoginFlow, clearTotpLoginFlow } from './totp-login-flow.js'
import {
	invalidateWebRtcVerification,
	startWebRtcVerificationInBackground
} from './webrtc-verification.js'
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

function handleLoginResponse(response) {
	if (response?.status === 'TOTP_REQUIRED') {
		beginTotpLoginFlow(response)
		return response
	}
	if (response?.status === 'AUTHENTICATED') {
		clearTotpLoginFlow()
		saveSession(response)
		// 登录轮换 PreAuth 后建立新的任务 epoch；后台校验不得读取旧 Token 或复用旧 Promise。
		invalidateWebRtcVerification()
		void startWebRtcVerificationInBackground().catch(() => {})
	}
	return response
}

export const authApi = {
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
		const response = await publicRequest('/api/auth/login/password', { data })
		return handleLoginResponse(response)
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
		const response = await publicRequest('/api/auth/login/code/verify', {
			headers: flowHeaders('login', flow), data: { strategyType, code }
		})
		return handleLoginResponse(response)
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
			const response = await publicRequest('/api/auth/login/totp/verify', {
				headers,
				data: { code }
			})
			return handleLoginResponse(response)
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

import { adminClientPlatform } from './admin-config.js'
import { adminRequest, hasBrowserAdminFlow } from './admin-http.js'
import { invalidateAdminPreAuth } from './admin-pre-auth.js'
import {
	invalidateAdminWebRtcVerification,
	startAdminWebRtcVerificationInBackground
} from './admin-webrtc-verification.js'
import {
	clearAdminFlow,
	clearAdminSession,
	loadAdminSecureState,
	updateAdminSecureState
} from './admin-secure-vault.js'
import {
	clearAdminMailInspectionSession
} from './admin-mail-inspection-session-store.js'

function rememberFlow(kind, response) {
	if (adminClientPlatform() !== 'ANDROID') return
	if (kind === 'register') {
		updateAdminSecureState({
			registerFlow: {
				registerToken: response.registerToken,
				flowCsrf: response.flowCsrf,
				challengeId: response.challengeId,
				expiresAt: response.expiresAt
			}
		})
	} else {
		updateAdminSecureState({
			loginFlow: {
				loginFlowToken: response.loginFlowToken,
				flowCsrf: response.flowCsrf,
				challengeId: response.challengeId,
				expiresAt: response.expiresAt
			}
		})
	}
}

export const adminApi = {
	state() {
		return adminRequest('/api/admin/auth/state', { method: 'GET' })
	},
	phoneCountry() {
		return adminRequest('/api/admin/auth/phone-country', {
			method: 'GET',
			timeout: 8000,
			includeClientContext: false
		})
	},
	hcaptchaConfig() {
		return adminRequest('/api/admin/auth/hcaptcha/config', { method: 'GET' })
	},
	async registerStart(data) {
		const response = await adminRequest('/api/admin/auth/register/start', { data })
		rememberFlow('register', response)
		return response
	},
	registerStatus() {
		return adminRequest('/api/admin/auth/register/status', { method: 'GET' })
	},
	hasRegistrationFlow() {
		if (adminClientPlatform() === 'ANDROID') {
			return Boolean(loadAdminSecureState().registerFlow)
		}
		return hasBrowserAdminFlow('register')
	},
	registerHcaptcha(hcaptchaToken) {
		return adminRequest('/api/admin/auth/register/hcaptcha', {
			data: { hcaptchaToken }
		})
	},
	registerSendEmail() {
		return adminRequest('/api/admin/auth/register/codes/email/send')
	},
	registerSendPhone(deliveryMethod) {
		return adminRequest('/api/admin/auth/register/codes/phone/send', {
			data: { deliveryMethod }
		})
	},
	registerVerify(emailCode, phoneCode) {
		return adminRequest('/api/admin/auth/register/codes/verify', {
			data: { emailCode, phoneCode }
		})
	},
	async registerComplete(password, passwordConfirmation) {
		const response = await adminRequest('/api/admin/auth/register/complete', {
			data: { password, passwordConfirmation }
		})
		clearAdminFlow('register')
		return response
	},
	async loginStart() {
		const response = await adminRequest('/api/admin/auth/login/start')
		rememberFlow('login', response)
		return response
	},
	async loginComplete(data) {
		const response = await adminRequest('/api/admin/auth/login/complete', { data })
		// 新管理员会话建立前清除上一会话的邮箱原始凭证，防止敏感输入跨会话恢复。
		await clearAdminMailInspectionSession()
		if (adminClientPlatform() === 'ANDROID') {
			updateAdminSecureState({
				adminToken: response.adminToken,
				preAuthToken: response.preAuthToken
			})
		}
		invalidateAdminWebRtcVerification()
		void startAdminWebRtcVerificationInBackground().catch(() => {})
		clearAdminFlow('login')
		return response
	},
	bootstrap() {
		return adminRequest('/api/admin/auth/session/bootstrap')
	},
	me() {
		return adminRequest('/api/admin/me', { method: 'GET' })
	},
	async logout() {
		const response = await adminRequest('/api/admin/auth/logout')
		await clearAdminMailInspectionSession()
		clearAdminSession()
		invalidateAdminPreAuth()
		invalidateAdminWebRtcVerification()
		return response
	},
	async logoutAll() {
		const response = await adminRequest('/api/admin/auth/logout-all')
		await clearAdminMailInspectionSession()
		clearAdminSession()
		invalidateAdminPreAuth()
		invalidateAdminWebRtcVerification()
		return response
	},
	hasAndroidSession() {
		return Boolean(loadAdminSecureState().adminToken)
	}
}

import { authorizedRequest } from './http-client.js'
import { clearSession } from './session-vault.js'

function codeFlowHeaders(flow = {}) {
	const headers = {}
	if (flow.loginFlowToken) headers['X-Login-Flow-Token'] = flow.loginFlowToken
	if (flow.challengeHandle) headers['X-Turnstile-Challenge'] = flow.challengeHandle
	return headers
}

export const totpApi = {
	status() {
		return authorizedRequest('/api/users/me/security/totp', { method: 'GET' })
	},
	reverifyPassword(action, password) {
		return authorizedRequest('/api/users/me/security/totp/reverification/password', {
			data: { action, password }
		})
	},
	reverificationCodeStart(action, strategyType) {
		return authorizedRequest('/api/users/me/security/totp/reverification/code/start', {
			data: { action, strategyType }
		})
	},
	reverificationCodeTurnstile(flow, turnstileToken) {
		return authorizedRequest('/api/users/me/security/totp/reverification/code/turnstile', {
			headers: codeFlowHeaders(flow),
			data: { turnstileToken }
		})
	},
	reverificationCodeSend(flow, deliveryMethod) {
		return authorizedRequest('/api/users/me/security/totp/reverification/code/send', {
			headers: codeFlowHeaders(flow),
			data: deliveryMethod ? { deliveryMethod } : undefined
		})
	},
	reverificationCodeVerify(flow, action, strategyType, code) {
		return authorizedRequest('/api/users/me/security/totp/reverification/code/verify', {
			headers: codeFlowHeaders(flow),
			data: { action, strategyType, code }
		})
	},
	startSetup(action, stepUpToken, currentTotpCode) {
		return authorizedRequest('/api/users/me/security/totp/setup/start', {
			data: { action, stepUpToken, currentTotpCode: currentTotpCode || undefined }
		})
	},
	async confirmSetup(setupToken, code) {
		const response = await authorizedRequest('/api/users/me/security/totp/setup/confirm', {
			data: { setupToken, code },
			preserveSessionOnFailure: true
		})
		if (response?.reauthenticationRequired) clearSession()
		return response
	},
	async disable(stepUpToken, currentTotpCode) {
		const response = await authorizedRequest('/api/users/me/security/totp/disable', {
			data: { stepUpToken, currentTotpCode },
			preserveSessionOnFailure: true
		})
		if (response?.reauthenticationRequired) clearSession()
		return response
	}
}

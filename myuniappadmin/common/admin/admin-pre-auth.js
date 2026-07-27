import { ADMIN_API_BASE_URL, adminClientPlatform } from './admin-config.js'
import { ensureAdminCookieScopeMigration } from './admin-cookie-scope-migration.js'
import { adminDeviceInstallationId } from './admin-device.js'
import {
	clearAdminFlow,
	clearAdminSession,
	loadAdminSecureState,
	updateAdminSecureState
} from './admin-secure-vault.js'
import { presentAdminRiskBlock } from './admin-risk-block-navigation.js'
import {
	beginAdminRiskChallenge,
	claimAdminRiskChallengeRecheck,
	completeAdminRiskChallengeRecheck,
	failAdminRiskChallengeRecheck
} from './admin-risk-challenge-navigation.js'

const PRE_AUTH_PATH = '/api/admin/_edge/pre-auth'
let ready = false
let bootstrapInFlight = null
let resetRequested = false

export function currentAdminPreAuthToken() {
	if (adminClientPlatform() !== 'ANDROID') return ''
	return loadAdminSecureState().preAuthToken || ''
}

export function invalidateAdminPreAuth() {
	ready = false
	resetRequested = true
	if (adminClientPlatform() === 'ANDROID') {
		updateAdminSecureState({ preAuthToken: undefined })
	}
}

export async function ensureAdminPreAuth() {
	if (ready) return currentAdminPreAuthToken()
	if (!bootstrapInFlight) {
		bootstrapInFlight = bootstrap()
			.finally(() => { bootstrapInFlight = null })
	}
	return bootstrapInFlight
}

async function bootstrap() {
	await ensureAdminCookieScopeMigration()
	// Challenge 返回后的同步抢占只允许当前调用方执行一次管理员 PreAuth 复查。
	const challengeRecheck = claimAdminRiskChallengeRecheck()
	const platform = adminClientPlatform()
	const headers = {
		'Content-Type': 'application/json',
		'X-Client-Platform': platform,
		'X-Device-Installation-Id': adminDeviceInstallationId()
	}
	const existing = currentAdminPreAuthToken()
	if (platform === 'ANDROID' && existing) {
		headers['X-AIT-PreAuth'] = existing
	}
	if (resetRequested) headers['X-AIT-PreAuth-Reset'] = '1'
	let response
	try {
		response = await requestBootstrap(headers)
	} catch (error) {
		if (error.reauthenticationRequired) clearLocalAdminAuthentication()
		if (platform === 'ANDROID' && error.preAuthToken) {
			updateAdminSecureState({ preAuthToken: error.preAuthToken })
		}
		if (presentAdminRiskBlock(error)) throw error
		if (error.code === 'RISK_CHALLENGE_REQUIRED') {
			beginAdminRiskChallenge(error)
		}
		if (challengeRecheck) failAdminRiskChallengeRecheck()
		throw error
	}
	if (response?.status === 'DISABLED') {
		if (platform === 'ANDROID') {
			updateAdminSecureState({ preAuthToken: undefined })
		}
		resetRequested = false
		ready = true
		if (challengeRecheck) completeAdminRiskChallengeRecheck()
		return ''
	}
	if (response?.reauthenticationRequired) clearLocalAdminAuthentication()
	if (platform === 'ANDROID') {
		if (!response?.preAuthToken) {
			if (challengeRecheck) failAdminRiskChallengeRecheck()
			throw createError('PREAUTH_BOOTSTRAP_INVALID', '管理员预登录安全凭证初始化失败。')
		}
		updateAdminSecureState({ preAuthToken: response.preAuthToken })
	}
	resetRequested = false
	ready = true
	if (challengeRecheck) completeAdminRiskChallengeRecheck()
	return currentAdminPreAuthToken()
}

function requestBootstrap(headers) {
	return new Promise((resolve, reject) => {
		uni.request({
			url: `${ADMIN_API_BASE_URL}${PRE_AUTH_PATH}`,
			method: 'POST',
			header: headers,
			withCredentials: true,
			timeout: 10000,
			success(response) {
				if (response.statusCode >= 200 && response.statusCode < 300) {
					resolve(response.data)
					return
				}
				const error = createError(
					response.data?.code || `HTTP_${response.statusCode}`,
					response.data?.message || '管理员预登录安全凭证初始化失败。'
				)
				error.statusCode = response.statusCode
				error.challengeRef = response.data?.challengeRef || ''
				error.challengePath = response.data?.challengePath || ''
				error.expiresAt = response.data?.expiresAt || ''
				error.preAuthToken = response.data?.preAuthToken || ''
				error.reauthenticationRequired =
					response.data?.reauthenticationRequired === true
				reject(error)
			},
			fail(cause) {
				const error = createError('NETWORK_ERROR', '网络连接失败，请稍后重试。')
				error.cause = cause
				reject(error)
			}
		})
	})
}

function clearLocalAdminAuthentication() {
	clearAdminSession()
	clearAdminFlow('register')
	clearAdminFlow('login')
}

function createError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

import { AUTH_API_BASE_URL, clientPlatform } from './config.js'
import { ensureCookieScopeMigration } from './cookie-scope-migration.js'
import { getDeviceInstallationId } from './device-installation.js'
import {
	loadAndroidSessionCredentials,
	saveAndroidSessionCredentials
} from './android-keystore.js'
import { presentRiskBlock } from './risk-block-navigation.js'
import {
	beginRiskChallenge,
	claimRiskChallengeRecheck,
	completeRiskChallengeRecheck,
	failRiskChallengeRecheck
} from './risk-challenge-navigation.js'
import { clearSession } from './session-vault.js'

const PRE_AUTH_PATH = '/api/_edge/pre-auth'
let ready = false
let bootstrapInFlight = null
let resetRequested = false

export function currentPreAuthToken() {
	if (clientPlatform() !== 'ANDROID') return ''
	return loadAndroidSessionCredentials().preAuthToken || ''
}

export function invalidatePreAuth() {
	ready = false
	resetRequested = true
	if (clientPlatform() !== 'ANDROID') return
	const current = loadAndroidSessionCredentials()
	saveAndroidSessionCredentials({ ...current, preAuthToken: '' })
}

export async function ensurePreAuth() {
	if (ready) return currentPreAuthToken()
	if (!bootstrapInFlight) {
		bootstrapInFlight = bootstrapPreAuth()
			.finally(() => { bootstrapInFlight = null })
	}
	return bootstrapInFlight
}

async function bootstrapPreAuth() {
	await ensureCookieScopeMigration()
	// Challenge 返回后的同步抢占只允许当前调用方执行一次 PreAuth 复查。
	const challengeRecheck = claimRiskChallengeRecheck()
	const platform = clientPlatform()
	const headers = {
		'Content-Type': 'application/json',
		'X-Client-Platform': platform,
		'X-Device-Installation-Id': getDeviceInstallationId()
	}
	const existing = currentPreAuthToken()
	if (platform === 'ANDROID' && existing) {
		headers['X-AIT-PreAuth'] = existing
	}
	if (resetRequested) headers['X-AIT-PreAuth-Reset'] = '1'
	let response
	try {
		response = await requestBootstrap(headers)
	} catch (error) {
		if (error.reauthenticationRequired) clearSession()
		if (platform === 'ANDROID' && error.preAuthToken) {
			saveAndroidSessionCredentials({
				...loadAndroidSessionCredentials(),
				preAuthToken: error.preAuthToken
			})
		}
		if (presentRiskBlock(error)) throw error
		if (error.code === 'RISK_CHALLENGE_REQUIRED') {
			beginRiskChallenge(error)
		}
		if (challengeRecheck) failRiskChallengeRecheck()
		throw error
	}
	if (response?.status === 'DISABLED') {
		if (platform === 'ANDROID') {
			saveAndroidSessionCredentials({
				...loadAndroidSessionCredentials(),
				preAuthToken: ''
			})
		}
		resetRequested = false
		ready = true
		if (challengeRecheck) completeRiskChallengeRecheck()
		return ''
	}
	if (response?.reauthenticationRequired) clearSession()
	if (platform === 'ANDROID') {
		if (!response?.preAuthToken) {
			if (challengeRecheck) failRiskChallengeRecheck()
			throw preAuthError('PREAUTH_BOOTSTRAP_INVALID', '预登录安全凭证初始化失败。')
		}
		saveAndroidSessionCredentials({
			...loadAndroidSessionCredentials(),
			preAuthToken: response.preAuthToken
		})
	}
	resetRequested = false
	ready = true
	if (challengeRecheck) completeRiskChallengeRecheck()
	return currentPreAuthToken()
}

function requestBootstrap(headers) {
	return new Promise((resolve, reject) => {
		uni.request({
			url: `${AUTH_API_BASE_URL}${PRE_AUTH_PATH}`,
			method: 'POST',
			header: headers,
			withCredentials: true,
			timeout: 10000,
			success(response) {
				if (response.statusCode >= 200 && response.statusCode < 300) {
					resolve(response.data)
					return
				}
				const error = preAuthError(
					response.data?.code || `HTTP_${response.statusCode}`,
					response.data?.message || '预登录安全凭证初始化失败。'
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
				const error = preAuthError('NETWORK_ERROR', '网络连接失败，请稍后重试。')
				error.cause = cause
				reject(error)
			}
		})
	})
}

function preAuthError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

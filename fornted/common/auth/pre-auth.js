import { AUTH_API_BASE_URL, ClientPlatform, clientPlatform, usesExplicitTokenTransport } from './config.js'
import {
	androidEdgeRequestHeaders,
	runAndroidRequestWithEdgeRecovery
} from './android-edge-challenge.js'
import {
	ensureAndroidRiskChallenge,
	repeatedAndroidRiskChallengeError
} from './android-risk-challenge.js'
import { ensureCookieScopeMigration } from './cookie-scope-migration.js'
import { ensureDeviceInstallationId, getDeviceInstallationId } from './device-installation.js'
import {
	authDiagnosticRequestHeaders,
	createAuthRequestDiagnostic,
	recordAuthDiagnosticEvent,
	recordAuthDiagnosticFailure,
	recordAuthDiagnosticResponse
} from './auth-diagnostics.js'
import {
	loadAndroidSessionCredentials,
	saveAndroidSessionCredentials
} from './android-keystore.js'
import {
	clearWechatSessionCredentials,
	loadWechatSessionCredentials,
	saveWechatSessionCredentials
} from './wechat-session-storage.js'
import { presentRiskBlock } from './risk-block-navigation.js'
import {
	beginRiskChallenge,
	claimRiskChallengeRecheck,
	completeRiskChallengeRecheck,
	failRiskChallengeRecheck
} from './risk-challenge-navigation.js'
import { clearSession } from './session-vault.js'
import {
	applyDiagnosticsToError,
	inspectAuthResponse
} from './turnstile-response-diagnostics.js'

const PRE_AUTH_PATH = '/api/_edge/pre-auth'
let ready = false
let bootstrapInFlight = null
let resetRequested = false
let preAuthLifecycleEpoch = 0

export function currentPreAuthToken() {
	const platform = clientPlatform()
	if (platform === ClientPlatform.ANDROID) {
		return loadAndroidSessionCredentials().preAuthToken || ''
	}
	if (platform === ClientPlatform.WECHAT_MINI_PROGRAM) {
		return loadWechatSessionCredentials().preAuthToken || ''
	}
	return ''
}

export function isPreAuthReady() {
	return ready === true
}

/**
 * OAuth complete 已由服务端轮换并写回 HttpOnly PreAuth；这里只采纳该事实，禁止再次 bootstrap。
 */
export function adoptExistingH5PreAuth() {
	if (clientPlatform() !== 'H5') return false
	preAuthLifecycleEpoch += 1
	ready = true
	resetRequested = false
	bootstrapInFlight = null
	recordAuthDiagnosticEvent('PREAUTH_EXISTING_ADOPTED', {
		source: 'oauth_async_complete',
		outcome: 'adopted'
	})
	return true
}

export function invalidatePreAuth() {
	preAuthLifecycleEpoch += 1
	ready = false
	resetRequested = true
	// 旧请求可以自然结束，但不能再被新 epoch 加入，也不能在 finally 中清掉新任务。
	bootstrapInFlight = null
	recordAuthDiagnosticEvent('PREAUTH_INVALIDATED', {
		source: 'invalidate_pre_auth',
		outcome: 'invalidated'
	})
	const platform = clientPlatform()
	if (platform === ClientPlatform.ANDROID) {
		const current = loadAndroidSessionCredentials()
		saveAndroidSessionCredentials({ ...current, preAuthToken: '' })
	} else if (platform === ClientPlatform.WECHAT_MINI_PROGRAM) {
		const current = loadWechatSessionCredentials()
		saveWechatSessionCredentials({ ...current, preAuthToken: '' })
	}
}

export async function ensurePreAuth() {
	if (ready) {
		recordAuthDiagnosticEvent('PREAUTH_SINGLE_FLIGHT', {
			source: 'ensure_pre_auth',
			outcome: 'ready_memory',
			owner: false,
			waiter: false
		})
		return currentPreAuthToken()
	}
	const owner = !bootstrapInFlight
		|| bootstrapInFlight.epoch !== preAuthLifecycleEpoch
	recordAuthDiagnosticEvent('PREAUTH_SINGLE_FLIGHT', {
		source: 'ensure_pre_auth',
		outcome: owner ? 'created' : 'joined',
		owner,
		waiter: !owner
	})
	if (owner) {
		bootstrapInFlight = createBootstrapEntry(false)
	}
	return bootstrapInFlight.promise
}

function createBootstrapEntry(riskChallengeRetried) {
	const entry = {
		epoch: preAuthLifecycleEpoch,
		promise: null
	}
	entry.promise = bootstrapPreAuth(riskChallengeRetried, entry.epoch)
		.finally(() => {
			if (bootstrapInFlight === entry) bootstrapInFlight = null
		})
	return entry
}

async function bootstrapPreAuth(riskChallengeRetried = false, attemptEpoch = preAuthLifecycleEpoch) {
	await ensureDeviceInstallationId()
	await ensureCookieScopeMigration()
	assertCurrentPreAuthAttempt(attemptEpoch)
	// Challenge 返回后的同步抢占只允许当前调用方执行一次 PreAuth 复查。
	const challengeRecheck = claimRiskChallengeRecheck()
	const platform = clientPlatform()
	const headers = {
		'Content-Type': 'application/json',
		'X-Client-Platform': platform,
		'X-Device-Installation-Id': getDeviceInstallationId()
	}
	const existing = currentPreAuthToken()
	if (usesExplicitTokenTransport(platform) && existing) {
		headers['X-AIT-PreAuth'] = existing
	}
	if (resetRequested) headers['X-AIT-PreAuth-Reset'] = '1'
	let response
	try {
		response = await requestBootstrap(headers)
	} catch (error) {
		assertCurrentPreAuthAttempt(attemptEpoch)
		if (error.reauthenticationRequired) clearSession()
		if (presentRiskBlock(error)) throw error
		if (error.code === 'RISK_CHALLENGE_REQUIRED') {
			if (platform === 'ANDROID') {
				if (riskChallengeRetried) {
					throw repeatedAndroidRiskChallengeError(error)
				}
				await acceptAndroidRiskChallenge(error)
				assertCurrentPreAuthAttempt(attemptEpoch)
				try {
					return await bootstrapPreAuth(true, attemptEpoch)
				} catch (cause) {
					throw normalizeRiskChallengeRecheckError(cause)
				}
			}
			beginRiskChallenge(error)
		}
		if (challengeRecheck) failRiskChallengeRecheck()
		throw error
	}
	assertCurrentPreAuthAttempt(attemptEpoch)
	if (response?.status === 'DISABLED') {
		if (platform === ClientPlatform.ANDROID) {
			saveAndroidSessionCredentials({
				...loadAndroidSessionCredentials(),
				preAuthToken: ''
			})
		} else if (platform === ClientPlatform.WECHAT_MINI_PROGRAM) {
			saveWechatSessionCredentials({
				...loadWechatSessionCredentials(),
				preAuthToken: ''
			})
		}
		resetRequested = false
		ready = true
		if (challengeRecheck) completeRiskChallengeRecheck()
		return ''
	}
	if (response?.status !== 'READY') {
		if (challengeRecheck) failRiskChallengeRecheck()
		throw preAuthError(
			riskChallengeRetried
				? 'RISK_CHALLENGE_RECHECK_FAILED'
				: 'PREAUTH_BOOTSTRAP_INVALID',
			'预登录安全状态无效。')
	}
	if (response?.reauthenticationRequired) clearSession()
	if (usesExplicitTokenTransport(platform)) {
		if (!response?.preAuthToken) {
			if (challengeRecheck) failRiskChallengeRecheck()
			throw preAuthError('PREAUTH_BOOTSTRAP_INVALID', '预登录安全凭证初始化失败。')
		}
		if (platform === ClientPlatform.ANDROID) {
			saveAndroidSessionCredentials({
				...loadAndroidSessionCredentials(),
				preAuthToken: response.preAuthToken
			})
		} else if (platform === ClientPlatform.WECHAT_MINI_PROGRAM) {
			saveWechatSessionCredentials({
				...loadWechatSessionCredentials(),
				preAuthToken: response.preAuthToken
			})
		}
	}
	resetRequested = false
	ready = true
	if (challengeRecheck) completeRiskChallengeRecheck()
	return currentPreAuthToken()
}

export async function acceptAndroidRiskChallenge(error) {
	if (clientPlatform() !== 'ANDROID') return false
	if (error?.code !== 'RISK_CHALLENGE_REQUIRED' || !error.preAuthToken) {
		throw preAuthError(
			'RISK_CHALLENGE_RECHECK_FAILED',
			'安全验证参数不完整。')
	}
	await ensureAndroidRiskChallenge(error)
	saveAndroidSessionCredentials({
		...loadAndroidSessionCredentials(),
		preAuthToken: error.preAuthToken
	})
	return true
}

export function recheckPreAuthAfterRiskChallenge() {
	preAuthLifecycleEpoch += 1
	ready = false
	resetRequested = false
	bootstrapInFlight = null
	const entry = createBootstrapEntry(true)
	entry.promise = entry.promise
		.catch(cause => { throw normalizeRiskChallengeRecheckError(cause) })
	bootstrapInFlight = entry
	return entry.promise
}

function assertCurrentPreAuthAttempt(attemptEpoch) {
	if (attemptEpoch === preAuthLifecycleEpoch) return
	recordAuthDiagnosticEvent('PREAUTH_STALE_COMPLETION_IGNORED', {
		source: 'preauth_bootstrap',
		outcome: 'stale_epoch'
	})
	throw preAuthError(
		'PREAUTH_ATTEMPT_STALE',
		'预登录安全状态已更新，请重新发起请求。')
}

function requestBootstrap(headers) {
	return runAndroidRequestWithEdgeRecovery(
		() => requestBootstrapOnce(headers))
}

function requestBootstrapOnce(headers) {
	const authDiagnostic = createAuthRequestDiagnostic(
		PRE_AUTH_PATH,
		'preauth_bootstrap')
	const diagnosticHeaders = authDiagnosticRequestHeaders(authDiagnostic)
	return new Promise((resolve, reject) => {
		uni.request({
			url: `${AUTH_API_BASE_URL}${PRE_AUTH_PATH}`,
			method: 'POST',
			header: androidEdgeRequestHeaders({ ...headers, ...diagnosticHeaders }),
			withCredentials: true,
			timeout: 10000,
			success(response) {
				recordAuthDiagnosticResponse(authDiagnostic, response)
				const diagnostics = inspectAuthResponse(response)
				if (diagnostics.classification === 'EDGE_CHALLENGE') {
					const error = preAuthError(
						'EDGE_CHALLENGE',
						'Cloudflare 安全检查尚未完成，请完成人机验证。')
					error.statusCode = response.statusCode
					reject(applyDiagnosticsToError(error, diagnostics))
					return
				}
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
				recordAuthDiagnosticFailure(authDiagnostic, error)
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

function normalizeRiskChallengeRecheckError(cause) {
	if ([
		'PREAUTH_ATTEMPT_STALE',
		'RISK_CHALLENGE_CANCELLED',
		'RISK_CHALLENGE_TIMEOUT',
		'RISK_CHALLENGE_COOKIE_FAILED',
		'RISK_CHALLENGE_RECHECK_FAILED',
		'RISK_CHALLENGE_REPEATED'
	].includes(cause?.code)) return cause
	const error = preAuthError(
		'RISK_CHALLENGE_RECHECK_FAILED',
		'安全验证后的状态复查失败。')
	error.cause = cause
	return error
}

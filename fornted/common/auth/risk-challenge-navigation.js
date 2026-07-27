import {
	createRiskChallengeFlow,
	RISK_CHALLENGE_ACTION,
	RISK_CHALLENGE_FAILURE_REASON
} from '@shared-auth/risk-challenge-state-machine.js'

const STORAGE_KEY = 'ait.risk.challenge.flow.v2'
const ALLOWED_PATH = '/api/_edge/risk-challenge'
const COMPLETE_PAGE = '/pages/risk/challenge-complete'
const FAILURE_PAGE = '/pages/risk/challenge-failed'
const DEFAULT_RETURN_PAGE = '/pages/auth/login'

let failureNavigationInFlight = false

export function beginRiskChallenge(error) {
	if (error?.code !== 'RISK_CHALLENGE_REQUIRED') return false
	// #ifdef H5
	const result = challengeFlow().handleChallengeRequired(
		error,
		currentBrowserRoute(),
		ALLOWED_PATH
	)
	if (result.action === RISK_CHALLENGE_ACTION.NAVIGATE) {
		window.location.assign(result.challengeUrl)
		throw controlledError('RISK_CHALLENGE_NAVIGATING', '正在进入安全验证。')
	}
	if (result.action === RISK_CHALLENGE_ACTION.FAILURE) {
		presentRiskChallengeFailure()
		throw controlledError('RISK_CHALLENGE_FAILED', '安全验证未能完成。')
	}
	throw controlledError('RISK_CHALLENGE_IN_PROGRESS', '安全验证正在进行。')
	// #endif
	// #ifndef H5
	throw controlledError(
		'RISK_CHALLENGE_UNAVAILABLE',
		'当前网络需要浏览器安全验证，请切换可信网络后重试。'
	)
	// #endif
}

export function restoreRiskChallengeReturn(fallbackRoute = DEFAULT_RETURN_PAGE) {
	// #ifdef H5
	const result = challengeFlow().markReturned()
	if (result.action === RISK_CHALLENGE_ACTION.RETURN) {
		window.location.replace(result.returnPath)
		return
	}
	presentRiskChallengeFailure()
	// #endif
	// #ifndef H5
	uni.reLaunch({ url: fallbackRoute })
	// #endif
}

export function claimRiskChallengeRecheck() {
	// #ifdef H5
	const flow = challengeFlow()
	const claimed = flow.claimRecheck()
	if (!claimed && flow.state()) {
		if (!flow.failureReason()) {
			flow.failRecheck(
				RISK_CHALLENGE_FAILURE_REASON.RECHECK_ERROR,
				DEFAULT_RETURN_PAGE
			)
		}
		presentRiskChallengeFailure()
		throw controlledError('RISK_CHALLENGE_FAILED', '安全验证未能完成。')
	}
	return claimed
	// #endif
	// #ifndef H5
	return false
	// #endif
}

export function completeRiskChallengeRecheck() {
	// #ifdef H5
	return challengeFlow().completeRecheck()
	// #endif
	// #ifndef H5
	return false
	// #endif
}

export function failRiskChallengeRecheck(
	reason = RISK_CHALLENGE_FAILURE_REASON.RECHECK_ERROR
) {
	// #ifdef H5
	challengeFlow().failRecheck(reason, DEFAULT_RETURN_PAGE)
	presentRiskChallengeFailure()
	throw controlledError('RISK_CHALLENGE_FAILED', '安全状态复查未能完成。')
	// #endif
	// #ifndef H5
	presentRiskChallengeFailure()
	return true
	// #endif
}

export function clearRiskChallengeFlow() {
	// #ifdef H5
	challengeFlow().clear()
	// #endif
}

export function riskChallengeFailureReason() {
	// #ifdef H5
	return challengeFlow().failureReason()
	// #endif
	// #ifndef H5
	return RISK_CHALLENGE_FAILURE_REASON.RECHECK_ERROR
	// #endif
}

export function resetRiskChallengeForManualRetry() {
	// #ifdef H5
	const returnPath = challengeFlow().resetForManualRetry(DEFAULT_RETURN_PAGE)
	window.location.replace(returnPath)
	return
	// #endif
	// #ifndef H5
	uni.reLaunch({ url: DEFAULT_RETURN_PAGE })
	// #endif
}

export function isRiskChallengeFlowPage(launchPath = '') {
	const explicitRoute = normalizePageRoute(launchPath)
	if (explicitRoute === COMPLETE_PAGE || explicitRoute === FAILURE_PAGE) return true
	// #ifdef H5
	const path = String(window.location?.pathname || '')
	return path === COMPLETE_PAGE || path === FAILURE_PAGE
	// #endif
	// #ifndef H5
	const route = currentRoute()
	return route === COMPLETE_PAGE.slice(1) || route === FAILURE_PAGE.slice(1)
	// #endif
}

function normalizePageRoute(value) {
	const raw = String(value || '').split(/[?#]/, 1)[0]
	if (!raw) return ''
	return raw.startsWith('/') ? raw : `/${raw}`
}

function challengeFlow() {
	return createRiskChallengeFlow({
		storage: sessionStorage,
		storageKey: STORAGE_KEY,
		defaultReturnPath: DEFAULT_RETURN_PAGE,
		allowedReturnPrefixes: ['/pages/']
	})
}

function presentRiskChallengeFailure() {
	if (failureNavigationInFlight) return
	failureNavigationInFlight = true
	// #ifdef H5
	if (String(window.location?.pathname || '') !== FAILURE_PAGE) {
		window.location.replace(FAILURE_PAGE)
	}
	return
	// #endif
	// #ifndef H5
	uni.reLaunch({
		url: FAILURE_PAGE,
		success() { failureNavigationInFlight = false },
		fail() { failureNavigationInFlight = false },
		complete() { failureNavigationInFlight = false }
	})
	// #endif
}

function currentBrowserRoute() {
	return `${window.location.pathname}${window.location.search}${window.location.hash}`
}

function currentRoute() {
	const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
	return pages.length ? pages[pages.length - 1].route || '' : ''
}

function controlledError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

export const AndroidRiskChallengeError = Object.freeze({
	CANCELLED: 'RISK_CHALLENGE_CANCELLED',
	TIMEOUT: 'RISK_CHALLENGE_TIMEOUT',
	COOKIE_FAILED: 'RISK_CHALLENGE_COOKIE_FAILED',
	RECHECK_FAILED: 'RISK_CHALLENGE_RECHECK_FAILED',
	REPEATED: 'RISK_CHALLENGE_REPEATED'
})

const CHALLENGE_TIMEOUT_MILLIS = 120000
const BRIDGE_COOKIE_MAX_AGE_SECONDS = 180
const BASE64URL_256_PATTERN = /^[A-Za-z0-9_-]{43}$/
const ABSOLUTE_PATH_PATTERN = /^\/(?!\/)[A-Za-z0-9/_-]+$/
const HTTPS_URL_PATTERN = /^https:\/\/([^/?#]+)(\/[^?#]*)?(\?[^#]*)?(#.*)?$/i
const HOSTNAME_LABEL_PATTERN = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/

export function validateAndroidRiskChallenge(error, rawConfig, now = Date.now()) {
	const config = normalizeConfig(rawConfig)
	if (error?.code !== 'RISK_CHALLENGE_REQUIRED'
		|| error.challengePath !== config.challengePath
		|| !BASE64URL_256_PATTERN.test(String(error.challengeRef || ''))
		|| !BASE64URL_256_PATTERN.test(String(error.preAuthToken || ''))) {
		throw riskChallengeError(
			AndroidRiskChallengeError.RECHECK_FAILED,
			'服务器返回的安全验证参数无效。')
	}
	const expiresAt = Date.parse(String(error.expiresAt || ''))
	if (!Number.isFinite(expiresAt) || expiresAt <= Number(now)) {
		throw riskChallengeError(
			AndroidRiskChallengeError.RECHECK_FAILED,
			'安全验证已经过期，请重新发起请求。')
	}

	const challengeUrl = `${config.origin}${config.challengePath}`
		+ `?ref=${encodeURIComponent(String(error.challengeRef))}`
	const completionUrl = `${config.origin}${config.completionPath}`
	const cookiePrefix = `${config.cookieName}=${error.preAuthToken}`
	return Object.freeze({
		origin: config.origin,
		webviewId: config.webviewId,
		challengeUrl,
		completionUrl,
		cookiePrefix,
		cookie: `${cookiePrefix}; Path=/; Max-Age=${BRIDGE_COOKIE_MAX_AGE_SECONDS}; Secure; HttpOnly; SameSite=Strict`,
		expiredCookie: `${config.cookieName}=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Strict`
	})
}

export function createAndroidRiskChallengeCoordinator(rawConfig) {
	const config = normalizeConfig(rawConfig)
	let challengeInFlight = null
	return Object.freeze({
		ensure(error) {
			if (!challengeInFlight) {
				challengeInFlight = openAndroidRiskChallenge(error, config)
					.finally(() => { challengeInFlight = null })
			}
			return challengeInFlight
		}
	})
}

export async function executeWithAndroidRiskChallengeRecovery(
	executeRequest,
	ensureChallenge
) {
	try {
		return await executeRequest()
	} catch (error) {
		if (error?.code !== 'RISK_CHALLENGE_REQUIRED') throw error
		await ensureChallenge(error)
	}
	try {
		return await executeRequest()
	} catch (error) {
		if (error?.code !== 'RISK_CHALLENGE_REQUIRED') throw error
		throw repeatedAndroidRiskChallengeError(error)
	}
}

export function repeatedAndroidRiskChallengeError(error) {
	const repeated = riskChallengeError(
		AndroidRiskChallengeError.REPEATED,
		'安全验证未能完成，请稍后重试。')
	repeated.statusCode = Number(error?.statusCode || 0)
	return repeated
}

function openAndroidRiskChallenge(error, config) {
	return new Promise((resolve, reject) => {
		let contract
		try {
			contract = validateAndroidRiskChallenge(error, config)
			writeBridgeCookie(contract)
		} catch (cause) {
			reject(asCookieOrContractError(cause))
			return
		}

		let webview = null
		let settled = false
		let timeoutHandle = null
		const closeWebview = () => {
			if (!webview) return
			const active = webview
			webview = null
			try { active.close('slide-out-bottom', 180) } catch (_) {}
		}
		const settle = (errorValue = null) => {
			if (settled) return
			settled = true
			if (timeoutHandle) clearTimeout(timeoutHandle)
			let finalError = errorValue
			try {
				expireBridgeCookie(contract)
			} catch (_) {
				finalError = riskChallengeError(
					AndroidRiskChallengeError.COOKIE_FAILED,
					'临时安全凭证未能清理。')
			}
			closeWebview()
			if (finalError) reject(finalError)
			else resolve()
		}
		const acceptCompletion = value => {
			if (!isExactCompletionUrl(value, contract.completionUrl)) return false
			settle()
			return true
		}

		try {
			webview = plus.webview.create(
				contract.challengeUrl,
				contract.webviewId,
				{
					top: '0px',
					bottom: '0px',
					left: '0px',
					right: '0px',
					background: '#0b0d0c'
				})
			webview.overrideUrlLoading(
				{ mode: 'reject', match: `${contract.completionUrl}*` },
				event => { acceptCompletion(String(event?.url || '')) })
			webview.addEventListener('loading', () => {
				try { acceptCompletion(String(webview?.getURL?.() || '')) } catch (_) {}
			})
			webview.addEventListener('close', () => {
				webview = null
				if (!settled) {
					settle(riskChallengeError(
						AndroidRiskChallengeError.CANCELLED,
						'已取消安全验证。'))
				}
			})
			webview.addEventListener('error', () => {
				settle(riskChallengeError(
					AndroidRiskChallengeError.RECHECK_FAILED,
					'安全验证页面加载失败。'))
			})
			timeoutHandle = setTimeout(() => {
				settle(riskChallengeError(
					AndroidRiskChallengeError.TIMEOUT,
					'安全验证等待超时。'))
			}, CHALLENGE_TIMEOUT_MILLIS)
			webview.show('slide-in-bottom', 180)
		} catch (_) {
			settle(riskChallengeError(
				AndroidRiskChallengeError.RECHECK_FAILED,
				'无法打开安全验证页面。'))
		}
	})
}

function writeBridgeCookie(contract) {
	const manager = androidCookieManager()
	plus.android.invoke(manager, 'setAcceptCookie', true)
	plus.android.invoke(manager, 'setCookie', contract.origin, contract.cookie)
	plus.android.invoke(manager, 'flush')
	const cookieHeader = String(plus.android.invoke(
		manager,
		'getCookie',
		contract.origin) || '')
	if (!hasCookiePair(cookieHeader, contract.cookiePrefix)) {
		throw riskChallengeError(
			AndroidRiskChallengeError.COOKIE_FAILED,
			'临时安全凭证未能写入。')
	}
}

function expireBridgeCookie(contract) {
	const manager = androidCookieManager()
	plus.android.invoke(manager, 'setCookie', contract.origin, contract.expiredCookie)
	plus.android.invoke(manager, 'flush')
	const cookieHeader = String(plus.android.invoke(
		manager,
		'getCookie',
		contract.origin) || '')
	if (hasCookiePair(cookieHeader, contract.cookiePrefix)) {
		throw riskChallengeError(
			AndroidRiskChallengeError.COOKIE_FAILED,
			'临时安全凭证未能清理。')
	}
}

function androidCookieManager() {
	const CookieManager = plus.android.importClass('android.webkit.CookieManager')
	return plus.android.invoke(CookieManager, 'getInstance')
}

function hasCookiePair(header, pair) {
	return String(header || '').split(';')
		.some(item => item.trim() === pair)
}

function isExactCompletionUrl(value, expected) {
	const actualUrl = parseAbsoluteHttpsUrl(value)
	const expectedUrl = parseAbsoluteHttpsUrl(expected)
	return Boolean(actualUrl && expectedUrl
		&& actualUrl.origin === expectedUrl.origin
		&& actualUrl.pathname === expectedUrl.pathname
		&& actualUrl.search === ''
		&& actualUrl.hash === '')
}

function normalizeConfig(rawConfig) {
	const originUrl = parseAbsoluteHttpsUrl(rawConfig?.origin)
	const challengePath = String(rawConfig?.challengePath || '')
	const completionPath = String(rawConfig?.completionPath || '')
	const cookieName = String(rawConfig?.cookieName || '')
	const webviewId = String(rawConfig?.webviewId || '')
	if (!originUrl
		|| originUrl.pathname !== '/'
		|| originUrl.search
		|| originUrl.hash
		|| !ABSOLUTE_PATH_PATTERN.test(challengePath)
		|| !ABSOLUTE_PATH_PATTERN.test(completionPath)
		|| !/^__Host-[A-Za-z0-9_-]+$/.test(cookieName)
		|| !/^[A-Za-z0-9_-]{1,64}$/.test(webviewId)) {
		throw riskChallengeError(
			AndroidRiskChallengeError.RECHECK_FAILED,
			'Android 安全验证配置无效。')
	}
	return Object.freeze({
		origin: originUrl.origin,
		challengePath,
		completionPath,
		cookieName,
		webviewId
	})
}

function parseAbsoluteHttpsUrl(rawValue) {
	const match = HTTPS_URL_PATTERN.exec(String(rawValue || ''))
	if (!match) return null
	const authority = normalizeHttpsAuthority(match[1])
	if (!authority) return null
	return Object.freeze({
		origin: `https://${authority}`,
		pathname: match[2] || '/',
		search: match[3] || '',
		hash: match[4] || ''
	})
}

function normalizeHttpsAuthority(rawAuthority) {
	const match = /^([A-Za-z0-9.-]+)(?::([0-9]{1,5}))?$/.exec(
		String(rawAuthority || ''))
	if (!match) return ''
	const hostname = match[1].toLowerCase()
	if (hostname.length > 253
		|| !hostname.split('.').every(label => HOSTNAME_LABEL_PATTERN.test(label))) {
		return ''
	}
	const portText = match[2] || ''
	const port = portText ? Number(portText) : 443
	if (!Number.isInteger(port) || port < 1 || port > 65535) return ''
	return port === 443 ? hostname : `${hostname}:${port}`
}

function asCookieOrContractError(error) {
	if (Object.values(AndroidRiskChallengeError).includes(error?.code)) return error
	return riskChallengeError(
		AndroidRiskChallengeError.COOKIE_FAILED,
		'临时安全凭证未能写入。')
}

function riskChallengeError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

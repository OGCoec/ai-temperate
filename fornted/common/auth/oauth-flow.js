import { authApi } from './auth-api.js'
import { AUTH_ROUTES, clientPlatform } from './config.js'
import {
	loadAndroidOAuthFlow,
	clearAndroidOAuthFlow
} from './android-flow-keystore.js'
// #ifdef APP-PLUS
import {
	isGoogleSignInAvailable,
	signInWithGoogle
} from '@/uni_modules/ait-google-signin'
// #endif

const GOOGLE_OAUTH_LOG_PREFIX = '[AIT_GOOGLE_OAUTH]'
const GOOGLE_NATIVE_TIMEOUT_MS = 30000
const GOOGLE_NATIVE_COMPLETE_TIMEOUT_MS = 30000
const GOOGLE_NATIVE_COMPLETE_TIMEOUT_CODE = 'GOOGLE_NATIVE_COMPLETE_TIMEOUT'

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

async function continueFromStatus(status) {
	if (!status?.state) return null
	if (['PHONE_REQUIRED', 'HUMAN_VERIFICATION_REQUIRED', 'CODE_READY'].includes(status.state)) {
		uni.navigateTo({ url: AUTH_ROUTES.oauthPhone })
		return status
	}
	if (status.state === 'READY_TO_COMPLETE') {
		const result = await authApi.oauthComplete()
		if (result?.status === 'TOTP_REQUIRED') {
			uni.navigateTo({ url: AUTH_ROUTES.totpLogin })
		} else if (result?.status === 'AUTHENTICATED') {
			uni.reLaunch({ url: AUTH_ROUTES.home })
		}
		return result
	}
	if (status.state === 'TOTP_REQUIRED') {
		uni.navigateTo({ url: AUTH_ROUTES.totpLogin })
		return status
	}
	if (status.state === 'AUTHENTICATED') {
		if (clientPlatform() === 'ANDROID') clearAndroidOAuthFlow()
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

export async function startOAuth(provider) {
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
		response = await authApi.oauthStart(
			provider,
			nativeGoogle ? 'GOOGLE_NATIVE' : 'BROWSER')
		logGoogleOAuth('oauth_start_success', {
			mode: response?.mode,
			elapsedMs: Date.now() - oauthStartStartedAt
		})
	} catch (error) {
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
	let status
	try {
		status = await withClientTimeout(
			authApi.oauthNativeGoogleComplete(idToken),
			GOOGLE_NATIVE_COMPLETE_TIMEOUT_MS,
			GOOGLE_NATIVE_COMPLETE_TIMEOUT_CODE,
			'Google 登录完成请求超时。')
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
	}
	return await continueFromStatus(status)
}

export async function resumePendingOAuth() {
	if (clientPlatform() !== 'ANDROID') return null
	const flow = loadAndroidOAuthFlow()
	if (!flow) return null
	if (resumePromise) return resumePromise
	resumePromise = authApi.oauthStatus(flow)
		.then(continueFromStatus)
		.catch(error => {
			if (['FLOW_NOT_FOUND', 'FLOW_EXPIRED', 'FLOW_FORBIDDEN'].includes(error?.code)) {
				clearAndroidOAuthFlow()
			}
			throw error
		})
		.finally(() => { resumePromise = null })
	return resumePromise
}

export async function completeH5OAuthReturn() {
	const status = await authApi.oauthStatus()
	if (status?.state === 'PROVIDER_PENDING') {
		const error = new Error('第三方登录回调未通过校验，请返回登录页重新尝试。')
		error.code = 'OAUTH_CALLBACK_REJECTED'
		throw error
	}
	return continueFromStatus(status)
}

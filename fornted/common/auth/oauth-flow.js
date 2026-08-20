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
		if (clientPlatform() !== 'ANDROID') {
			const error = new Error('当前设备不支持 Google 原生登录。')
			error.code = 'GOOGLE_NATIVE_UNAVAILABLE'
			reject(error)
			return
		}
		// #ifdef APP-PLUS
		signInWithGoogle(serverClientId, nonce, {
			success: result => resolve(result.idToken),
			cancel: () => resolve(''),
			fail: (code, message) => {
				const error = new Error(message || 'Google 原生登录不可用。')
				error.code = code || 'GOOGLE_NATIVE_UNAVAILABLE'
				reject(error)
			}
		})
		return
		// #endif
		const error = new Error('当前设备不支持 Google 原生登录。')
		error.code = 'GOOGLE_NATIVE_UNAVAILABLE'
		reject(error)
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
	const response = await authApi.oauthStart(
		provider,
		nativeGoogle ? 'GOOGLE_NATIVE' : 'BROWSER')
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
	try {
		const idToken = await requestNativeGoogle(
			response.googleServerClientId, response.nonce)
		if (!idToken) {
			try { await authApi.oauthCancel() } catch (ignored) { }
			return { cancelled: true }
		}
		// ID Token 只存在于当前局部变量，上传结束后不写入任何持久化存储。
		const status = await authApi.oauthNativeGoogleComplete(idToken)
		return await continueFromStatus(status)
	} catch (error) {
		if (['GOOGLE_NATIVE_UNAVAILABLE', 'GOOGLE_NATIVE_NO_ACCOUNT'].includes(error?.code)) {
			// 原生请求已创建服务端 Flow；失败时显式取消，避免旧 nonce 在本机继续保持待处理状态。
			try { await authApi.oauthCancel() } catch (ignored) { }
			return { nativeUnavailable: true, code: error.code }
		}
		throw error
	}
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

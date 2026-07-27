import { authApi } from './auth-api.js'
import { clientPlatform } from './config.js'

const TURNSTILE_SDK_PROMISE_KEY = '__AIT_TURNSTILE_SDK_PROMISE__'

let turnstileConfigPromise = null
let turnstileConfig = null

function currentSdkPromise() {
	if (clientPlatform() !== 'H5' || typeof window === 'undefined') return Promise.resolve(null)
	if (window.turnstile?.render) return Promise.resolve(window.turnstile)
	return window[TURNSTILE_SDK_PROMISE_KEY] || Promise.resolve(null)
}

export function getTurnstileConfig() {
	if (turnstileConfig) return Promise.resolve(turnstileConfig)
	if (turnstileConfigPromise) return turnstileConfigPromise

	turnstileConfigPromise = authApi.turnstileConfig()
		.then((config) => {
			turnstileConfig = config
			return config
		})
		.catch((error) => {
			// 预热失败不能污染后续真实验证；清空单例后，组件挂载时仍可重新请求。
			turnstileConfigPromise = null
			throw error
		})
	return turnstileConfigPromise
}

export async function prewarmTurnstile() {
	if (clientPlatform() !== 'H5') return
	// 启动预热只准备 SDK 与公开 Site Key，不创建 Widget，也不提前生成一次性 Token。
	await Promise.allSettled([
		currentSdkPromise(),
		getTurnstileConfig()
	])
}

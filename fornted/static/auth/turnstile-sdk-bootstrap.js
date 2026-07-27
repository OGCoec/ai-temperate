(() => {
	'use strict'

	const promiseKey = '__AIT_TURNSTILE_SDK_PROMISE__'
	const callbackName = 'aitTurnstileSdkReady'
	const scriptId = 'ait-turnstile-sdk'
	const readyTimeoutMs = 15000
	if (window.turnstile && typeof window.turnstile.render === 'function') {
		window[promiseKey] = Promise.resolve(window.turnstile)
		return
	}
	if (window[promiseKey]) return

	let script
	let readyTimer
	let sdkPromise
	sdkPromise = new Promise(resolve => {
		let settled = false
		const finish = api => {
			if (settled) return
			settled = true
			clearTimeout(readyTimer)
			if (window[callbackName] === ready) window[callbackName] = undefined
			resolve(api)
		}
		const fail = () => {
			if (settled) return
			if (script) script.remove()
			if (window[promiseKey] === sdkPromise) window[promiseKey] = undefined
			finish(null)
		}
		const ready = () => {
			if (!window.turnstile || typeof window.turnstile.render !== 'function') {
				fail()
				return
			}
			finish(window.turnstile)
		}

		// 先注册 ready 回调再下载 SDK，避免首次加载完成与全局 API 初始化之间出现竞态。
		window[callbackName] = ready
		script = document.createElement('script')
		script.id = scriptId
		script.src =
			'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=' +
			callbackName
		script.async = true
		script.defer = true
		script.onerror = fail
		readyTimer = setTimeout(fail, readyTimeoutMs)
		document.head.appendChild(script)
	})
	window[promiseKey] = sdkPromise
})()

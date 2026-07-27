import { ADMIN_API_BASE_URL, adminClientPlatform } from './admin-config.js'
import { hcaptchaErrorPolicy } from './admin-hcaptcha-error.js'

const SCRIPT_ID = 'ait-admin-hcaptcha-script'
const HCAPTCHA_READY_CALLBACK = 'aitAdminHcaptchaSdkReady'
const SCRIPT_URL = `https://js.hcaptcha.com/1/api.js?render=explicit&recaptchacompat=off&onload=${HCAPTCHA_READY_CALLBACK}`
const ANDROID_WEBVIEW_ID = 'ait-admin-hcaptcha'
const SDK_READY_TIMEOUT_MS = 15_000
const AUTO_RETRY_DELAY_MS = 1_000
const MAX_AUTO_RETRIES = 1
let scriptPromise

function loadScript() {
	if (globalThis.hcaptcha?.render) return Promise.resolve(globalThis.hcaptcha)
	if (scriptPromise) return scriptPromise
	scriptPromise = new Promise((resolve, reject) => {
		const staleScript = document.getElementById(SCRIPT_ID)
		if (staleScript) staleScript.remove()
		const script = document.createElement('script')
		script.id = SCRIPT_ID
		script.src = SCRIPT_URL
		script.async = true
		script.defer = true
		let settled = false
		let readyTimeout
		const removeReadyCallback = () => {
			if (globalThis[HCAPTCHA_READY_CALLBACK] === ready) globalThis[HCAPTCHA_READY_CALLBACK] = undefined
		}
		const fail = error => {
			if (settled) return
			settled = true
			clearTimeout(readyTimeout)
			script.remove()
			removeReadyCallback()
			reject(error)
		}
		const ready = () => {
			if (settled) return
			if (!globalThis.hcaptcha?.render) {
				fail(new Error('hCaptcha SDK ready 回调未提供 render。'))
				return
			}
			settled = true
			clearTimeout(readyTimeout)
			removeReadyCallback()
			resolve(globalThis.hcaptcha)
		}
		// DOM load 只代表脚本字节下载完成；供应商专用回调才代表显式 render API 已可安全调用。
		globalThis[HCAPTCHA_READY_CALLBACK] = ready
		script.onerror = () => fail(new Error('hCaptcha SDK 脚本加载失败。'))
		readyTimeout = setTimeout(
			() => fail(new Error('hCaptcha SDK ready 超时。')),
			SDK_READY_TIMEOUT_MS
		)
		document.head.appendChild(script)
	}).catch(error => {
		// rejected Promise 不得跨重试缓存，否则用户解除网络阻断后仍无法重新下载 SDK。
		scriptPromise = undefined
		throw error
	})
	return scriptPromise
}

function createButton(label, primary = false) {
	const button = document.createElement('button')
	button.type = 'button'
	button.textContent = label
	button.style.cssText = [
		'min-height:44px', 'padding:0 18px', 'border-radius:10px', 'cursor:pointer',
		primary ? 'border:0' : 'border:1px solid rgba(179,204,208,.3)',
		primary ? 'background:#69d4e2' : 'background:transparent',
		primary ? 'color:#071014' : 'color:#d8e7e9',
		'font:600 14px/1 system-ui'
	].join(';')
	return button
}

function createOverlay() {
	const overlay = document.createElement('div')
	overlay.setAttribute('role', 'dialog')
	overlay.setAttribute('aria-modal', 'true')
	overlay.setAttribute('aria-labelledby', 'ait-admin-hcaptcha-title')
	overlay.style.cssText = [
		'position:fixed', 'inset:0', 'z-index:2147483647',
		'display:flex', 'align-items:center', 'justify-content:center',
		'padding:24px', 'background:rgba(3,7,9,.78)', 'box-sizing:border-box'
	].join(';')
	const panel = document.createElement('div')
	panel.style.cssText = [
		'width:min(100%,420px)', 'padding:28px', 'border-radius:16px',
		'border:1px solid rgba(105,212,226,.3)', 'background:#10161a',
		'box-shadow:0 24px 80px rgba(0,0,0,.5)', 'box-sizing:border-box'
	].join(';')
	const title = document.createElement('div')
	title.id = 'ait-admin-hcaptcha-title'
	title.textContent = '安全验证'
	title.style.cssText = 'margin-bottom:10px;color:#f3f8f8;font:700 22px/1.3 system-ui'
	const status = document.createElement('p')
	status.setAttribute('role', 'status')
	status.setAttribute('aria-live', 'polite')
	status.style.cssText = 'min-height:20px;margin:0 0 14px;color:#a9bdc0;font:14px/1.45 system-ui'
	const widget = document.createElement('div')
	widget.style.cssText = 'min-height:70px'
	const error = document.createElement('p')
	error.setAttribute('role', 'alert')
	error.setAttribute('aria-live', 'assertive')
	error.style.cssText = 'display:none;margin:14px 0 0;color:#ffb8b8;font:14px/1.45 system-ui'
	const actions = document.createElement('div')
	actions.style.cssText = 'display:flex;justify-content:flex-end;gap:10px;margin-top:20px'
	const retryButton = createButton('重新验证', true)
	retryButton.style.display = 'none'
	const cancelButton = createButton('取消')
	actions.append(cancelButton, retryButton)
	panel.append(title, status, widget, error, actions)
	overlay.append(panel)
	document.body.appendChild(overlay)
	cancelButton.focus()
	return { overlay, widget, status, error, retryButton, cancelButton }
}

function requestAndroidToken(siteKey, challengeId) {
	return new Promise((resolve, reject) => {
		// #ifdef APP-PLUS
		let settled = false
		const close = () => {
			const current = plus.webview.getWebviewById(ANDROID_WEBVIEW_ID)
			if (current) current.close('none')
		}
		const finish = (callback, value) => {
			if (settled) return
			settled = true
			close()
			callback(value)
		}
		// 公开 Site Key 与 Challenge 只进入临时 Fragment，不会进入服务器访问日志或代理查询串。
		const url = `${ADMIN_API_BASE_URL}/api/admin/auth/hcaptcha/page#siteKey=${encodeURIComponent(siteKey)}&challenge=${encodeURIComponent(challengeId)}`
		const webview = plus.webview.create(url, ANDROID_WEBVIEW_ID, {
			top: '0px',
			bottom: '0px',
			background: '#080b0d'
		})
		webview.overrideUrlLoading(
			{ mode: 'reject', match: 'aithcaptcha://*' },
			event => {
				try {
					const parsed = new URL(event.url)
					if (parsed.searchParams.get('cancelled') === '1') {
						throw new Error('已取消 hCaptcha 验证。')
					}
					const token = parsed.searchParams.get('token') || ''
					const returnedChallenge = parsed.searchParams.get('challenge') || ''
					if (!token || returnedChallenge !== challengeId) {
						throw new Error('hCaptcha 未返回有效 Token。')
					}
					finish(resolve, token)
				} catch (error) {
					finish(reject, error)
				}
			})
		webview.addEventListener('error', () => {
			finish(reject, new Error('hCaptcha 受控页面加载失败。'))
		})
		webview.addEventListener('close', () => {
			if (!settled) finish(reject, new Error('已取消 hCaptcha 验证。'))
		})
		plus.webview.currentWebview().append(webview)
		webview.show('fade-in', 180)
		return
		// #endif
		// #ifndef APP-PLUS
		reject(new Error('当前客户端没有 Android hCaptcha WebView。'))
		// #endif
	})
}

export async function requestAdminHcaptchaToken(siteKey, challengeId) {
	if (!siteKey) throw new Error('hCaptcha Site Key 不可用。')
	if (adminClientPlatform() === 'ANDROID') {
		if (!challengeId) throw new Error('hCaptcha Challenge 不可用。')
		return requestAndroidToken(siteKey, challengeId)
	}
	if (typeof document === 'undefined') {
		throw new Error('当前客户端没有可用的受控 hCaptcha WebView。')
	}
	const view = createOverlay()
	return new Promise((resolve, reject) => {
		let api
		let widgetId
		let settled = false
		let sessionGeneration = 0
		let autoRetryCount = 0
		let retryTimer
		let errorHandled = false

		const setStatus = message => {
			view.status.textContent = message
		}
		const hideFailure = () => {
			view.error.textContent = ''
			view.error.style.display = 'none'
			view.retryButton.style.display = 'none'
		}
		const showFailure = policy => {
			setStatus('验证未完成，请手动重试。')
			view.error.textContent = policy.message
			view.error.style.display = 'block'
			view.retryButton.style.display = ''
			view.retryButton.focus()
		}
		const cleanup = () => {
			clearTimeout(retryTimer)
			document.removeEventListener('keydown', onKeydown)
			globalThis.removeEventListener('pagehide', onPageHide)
			view.overlay.removeEventListener('click', onOverlayClick)
			view.retryButton.removeEventListener('click', onManualRetry)
			view.cancelButton.removeEventListener('click', cancel)
			try {
				if (api && widgetId !== undefined) api.reset(widgetId)
			} catch (ignored) {
				// 会话已结束，清理阶段的供应商异常不能阻止 Promise 只完成一次。
			} finally {
				view.overlay.remove()
			}
		}
		const finish = (callback, value) => {
			if (settled) return
			settled = true
			sessionGeneration += 1
			cleanup()
			callback(value)
		}
		const cancel = () => finish(reject, new Error('已取消 hCaptcha 验证。'))
		const onPageHide = () => cancel()
		const onKeydown = event => {
			if (event.key === 'Escape') cancel()
		}
		const onOverlayClick = event => {
			if (event.target === view.overlay) cancel()
		}

		const resetWidget = () => {
			if (settled || !api || widgetId === undefined) return
			errorHandled = false
			hideFailure()
			setStatus('请完成下方安全验证。')
			try {
				api.reset(widgetId)
			} catch (ignored) {
				handleError('internal-error')
			}
		}
		const startSdkAndRender = async () => {
			const generation = ++sessionGeneration
			errorHandled = false
			hideFailure()
			setStatus('正在加载安全验证…')
			try {
				const loadedApi = await loadScript()
				if (settled || generation !== sessionGeneration) return
				api = loadedApi
				widgetId = api.render(view.widget, {
					sitekey: siteKey,
					callback: token => {
						if (settled || generation !== sessionGeneration) return
						finish(resolve, token)
					},
					'expired-callback': () => {
						if (settled || generation !== sessionGeneration) return
						handleError('challenge-error')
					},
					'error-callback': code => {
						if (settled || generation !== sessionGeneration) return
						handleError(code)
					},
					'close-callback': () => {
						if (settled || generation !== sessionGeneration) return
						cancel()
					}
				})
				setStatus('请完成下方安全验证。')
			} catch (error) {
				if (settled || generation !== sessionGeneration) return
				handleError('script-error')
			}
		}
		const retryCurrentSession = () => {
			if (api && widgetId !== undefined) {
				resetWidget()
				return
			}
			startSdkAndRender()
		}
		const handleError = rawCode => {
			if (settled || errorHandled) return
			errorHandled = true
			const policy = hcaptchaErrorPolicy(rawCode)
			if (policy.retryable && autoRetryCount < MAX_AUTO_RETRIES) {
				autoRetryCount += 1
				setStatus(`验证出现暂时异常（代码：${policy.code}），正在自动重试…`)
				retryTimer = setTimeout(() => {
					retryTimer = undefined
					errorHandled = false
					retryCurrentSession()
				}, AUTO_RETRY_DELAY_MS)
				return
			}
			showFailure(policy)
		}
		const onManualRetry = () => {
			clearTimeout(retryTimer)
			retryTimer = undefined
			autoRetryCount = 0
			errorHandled = false
			retryCurrentSession()
		}

		view.overlay.addEventListener('click', onOverlayClick)
		view.retryButton.addEventListener('click', onManualRetry)
		view.cancelButton.addEventListener('click', cancel)
		document.addEventListener('keydown', onKeydown)
		globalThis.addEventListener('pagehide', onPageHide)
		startSdkAndRender()
	})
}

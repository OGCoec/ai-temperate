const NATIVE_SPLASH_CONTROLLER = 'site.niko000o.aitemperate.launch.EagleSplashController'
const SHIMMER_CYCLE_MILLIS = 1900
const HANDOFF_FALLBACK_MILLIS = 1200
const FALLBACK_FRAME_MILLIS = 17
const ALLOWED_DISMISS_REASONS = new Set([
	'login-ready',
	'home-ready',
	'session-error',
	'route-failed',
	'legacy'
])

let nativeController = null

function nextPaintFrame(callback) {
	if (typeof requestAnimationFrame === 'function') {
		requestAnimationFrame(callback)
		return
	}
	setTimeout(callback, FALLBACK_FRAME_MILLIS)
}

function afterTwoPaintFrames(callback) {
	nextPaintFrame(() => nextPaintFrame(callback))
}

function getNativeController() {
	// #ifdef APP-PLUS
	if (typeof plus === 'undefined' || plus.os?.name !== 'Android') return null
	if (!nativeController) {
		nativeController = plus.android.importClass(NATIVE_SPLASH_CONTROLLER)
	}
	return nativeController
	// #endif

	// #ifndef APP-PLUS
	return null
	// #endif
}

/**
 * 读取原生扫光在共享周期中的当前位置；桥接不可用时从 CSS 周期起点安全启动。
 */
export function getNativeSplashCycleOffsetMillis() {
	try {
		const controller = getNativeController()
		if (!controller) return 0

		const offset = Number(controller.getCycleOffsetMillis())
		if (!Number.isFinite(offset)) return 0
		return ((Math.trunc(offset) % SHIMMER_CYCLE_MILLIS) + SHIMMER_CYCLE_MILLIS) % SHIMMER_CYCLE_MILLIS
	} catch (error) {
		return 0
	}
}

/**
 * 使用固定原因请求原生层淡出；幂等由原生控制器统一保证，失败时保留原生 30 秒安全兜底。
 */
export function dismissNativeSplash(reason = 'legacy') {
	try {
		reason = typeof reason === 'string' ? reason : 'legacy'
		if (!ALLOWED_DISMISS_REASONS.has(reason)) return

		const controller = getNativeController()
		if (!controller) return

		controller.dismiss(reason)
	} catch (error) {
		// 原生覆盖层自身有超时释放机制，此处不得阻断登录恢复与路由流程。
	}
}

/**
 * 当前页面内容不发生 WebView 切换时，等待连续两帧提交后再关闭原生层。
 */
export function dismissNativeSplashAfterPaint(reason = 'legacy') {
	afterTwoPaintFrames(() => dismissNativeSplash(reason))
}

/**
 * 跨页面冷启动交接同时等待 Vue 内容提交和 App WebView 真正显示；事件不可用时以有界回退避免永久遮挡。
 */
export function createNativeSplashHandoff(page, reason) {
	if (!ALLOWED_DISMISS_REASONS.has(reason)) {
		return {
			bindWebview() {},
			markDomReady() {},
			dispose() {}
		}
	}

	let completed = false
	let disposed = false
	let domReady = false
	let webviewShown = false
	let webview = null
	let fallbackTimer = null

	const clearFallback = () => {
		if (fallbackTimer == null) return
		clearTimeout(fallbackTimer)
		fallbackTimer = null
	}

	const removeWebviewListeners = () => {
		// #ifdef APP-PLUS
		try {
			webview?.removeEventListener?.('show', handleWebviewShow)
			webview?.removeEventListener?.('loaded', handleWebviewLoaded)
		} catch (error) {
			// DCloud 窗口可能已随 reLaunch 销毁，清理失败不应阻断页面进入。
		}
		// #endif
		webview = null
	}

	const complete = () => {
		if (completed || disposed || !domReady || !webviewShown) return
		completed = true
		clearFallback()
		removeWebviewListeners()
		afterTwoPaintFrames(() => {
			if (!disposed) dismissNativeSplash(reason)
		})
	}

	function handleWebviewShow() {
		webviewShown = true
		complete()
	}

	function handleWebviewLoaded() {
		// 某些无显示动画的 DCloud 窗口只稳定触发 loaded；只有同时可见时才视为已呈现。
		try {
			if (webview?.isVisible?.()) webviewShown = true
		} catch (error) {
			// isVisible 不可用时继续等待 show 或有界回退。
		}
		complete()
	}

	const bindWebview = () => {
		if (completed || disposed) return
		// #ifdef APP-PLUS
		try {
			const candidate = page?.$scope?.$getAppWebview?.()
			if (!candidate || candidate === webview) return
			removeWebviewListeners()
			webview = candidate
			webview.addEventListener('show', handleWebviewShow, false)
			webview.addEventListener('loaded', handleWebviewLoaded, false)
			// 监听器绑定可能晚于 DCloud 的 show 事件；此时以当前可见状态补齐同一信号，
			// 仍需与 Vue DOM 就绪同时满足后才允许交接。
			if (webview.isVisible?.()) webviewShown = true
			complete()
		} catch (error) {
			// 页面作用域尚未绑定原生窗口时，由 onShow 再次尝试。
		}
		// #endif
	}

	const markDomReady = () => {
		if (completed || disposed || domReady) return
		domReady = true
		bindWebview()
		fallbackTimer = setTimeout(() => {
			webviewShown = true
			complete()
		}, HANDOFF_FALLBACK_MILLIS)
		complete()
	}

	const dispose = () => {
		if (disposed) return
		disposed = true
		clearFallback()
		removeWebviewListeners()
	}

	bindWebview()
	return { bindWebview, markDomReady, dispose }
}

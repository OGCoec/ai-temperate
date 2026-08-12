import {
	ANDROID_TURNSTILE_RESULT_URL_MATCH,
	parseAndroidTurnstileResult
} from './android-turnstile-result.js'
import {
	ANDROID_TURNSTILE_HOST_HEIGHT,
	ANDROID_TURNSTILE_WIDTH
} from './android-turnstile-anchor.js'

const DEFAULT_TIMEOUT_MILLIS = 120000
const MIN_TIMEOUT_MILLIS = 1000
const MAX_TIMEOUT_MILLIS = 180000
const TRANSIENT_RESULT_DEDUPLICATION_MILLIS = 750

function finitePixel(value, minimum = 0) {
	const number = Number(value)
	if (!Number.isFinite(number) || number < minimum || Math.abs(number) > 100000) return null
	return Math.round(number)
}

function boundsStyle(bounds) {
	const left = finitePixel(bounds?.left)
	const top = finitePixel(bounds?.top, -100000)
	const measuredWidth = finitePixel(bounds?.width, 1)
	const measuredHeight = finitePixel(bounds?.height, 1)
	if (left === null || top === null || measuredWidth === null || measuredHeight === null) return null
	return {
		left: `${left}px`,
		top: `${top}px`,
		width: `${ANDROID_TURNSTILE_WIDTH}px`,
		height: `${ANDROID_TURNSTILE_HOST_HEIGHT}px`
	}
}

function styleSignature(style) {
	return `${style.left}|${style.top}|${style.width}|${style.height}`
}

function boundedTimeout(value) {
	const timeout = Number(value)
	if (!Number.isFinite(timeout)) return DEFAULT_TIMEOUT_MILLIS
	return Math.max(MIN_TIMEOUT_MILLIS, Math.min(MAX_TIMEOUT_MILLIS, Math.round(timeout)))
}

/**
 * 创建受当前页面管理的Android Turnstile子WebView，保证监听先于导航且关闭操作幂等。
 */
export function createAndroidTurnstileWebViewSession(options = {}) {
	const manager = options.webviewManager
	const parentWebview = options.parentWebview
	const initialBoundsStyle = boundsStyle(options.bounds)
	if (
		!manager || typeof manager.create !== 'function' ||
		!parentWebview || typeof parentWebview.append !== 'function' ||
		typeof options.load !== 'function' ||
		!initialBoundsStyle
	) {
		throw new Error('Android Turnstile WebView context is unavailable.')
	}

	const setTimer = typeof options.setTimer === 'function' ? options.setTimer : setTimeout
	const clearTimer = typeof options.clearTimer === 'function' ? options.clearTimer : clearTimeout
	const now = typeof options.now === 'function' ? options.now : Date.now
	const seenResults = new Set()
	let closed = false
	let ownerClosing = false
	let timer = null
	let lastStyleSignature = styleSignature(initialBoundsStyle)
	let pageLoadedDelivered = false
	let verifiedDelivered = false
	let lastTransientSignature = ''
	let lastTransientAt = 0
	let webview = null

	const close = () => {
		if (closed) return false
		closed = true
		ownerClosing = true
		if (timer !== null) {
			clearTimer(timer)
			timer = null
		}
		const closingWebview = webview
		webview = null
		try {
			closingWebview?.close?.('none')
		} catch (_) {
			// WebView可能已被系统回收；重复关闭不得改变组件状态。
		}
		return true
	}

	const acceptResultUrl = (rawUrl, source) => {
		if (closed) return false
		const result = parseAndroidTurnstileResult(String(rawUrl || ''), options.channel)
		if (!result) return false
		if (verifiedDelivered) return false
		const signature = result.type === 'ERROR' ? `${result.type}:${result.code}` : result.type
		if (result.type === 'ERROR') {
			const receivedAt = now()
			if (signature === lastTransientSignature &&
				receivedAt - lastTransientAt <= TRANSIENT_RESULT_DEDUPLICATION_MILLIS) return false
			lastTransientSignature = signature
			lastTransientAt = receivedAt
		} else {
			if (seenResults.has(signature) || (result.type === 'VERIFIED' && verifiedDelivered)) return false
			seenResults.add(signature)
		}
		if (result.type === 'VERIFIED') verifiedDelivered = true
		options.onResult?.(result, source)
		return true
	}

	const inspectCurrentUrl = (source) => {
		try {
			return acceptResultUrl(webview?.getURL?.(), source)
		} catch (_) {
			return false
		}
	}

	try {
		webview = manager.create('', String(options.webviewId || 'ait-auth-turnstile'), {
			...initialBoundsStyle,
			position: 'static',
			background: 'transparent',
			plusrequire: 'none',
			render: 'always'
		})
		if (!webview || typeof webview.overrideUrlLoading !== 'function') {
			throw new Error('Android Turnstile WebView could not be created.')
		}
		options.onCreated?.()
		webview.overrideUrlLoading({
			mode: 'reject',
			effect: 'instant',
			exclude: 'none',
			match: ANDROID_TURNSTILE_RESULT_URL_MATCH
		}, (event) => acceptResultUrl(event?.url, 'override'))
		webview.addEventListener('loading', () => inspectCurrentUrl('loading'))
		webview.addEventListener('loaded', () => {
			inspectCurrentUrl('loaded')
			if (closed || verifiedDelivered || pageLoadedDelivered) return
			let loadedUrl = ''
			try {
				loadedUrl = String(webview?.getURL?.() || '')
			} catch (_) {
				return
			}
			if (!/^https:\/\//i.test(loadedUrl)) return
			pageLoadedDelivered = true
			options.onLoaded?.()
		})
		webview.addEventListener('error', () => {
			if (closed || verifiedDelivered) return
			options.onError?.('TURNSTILE_WEBVIEW_LOAD_FAILED')
			close()
		})
		webview.addEventListener('close', () => {
			if (closed || ownerClosing) return
			closed = true
			if (timer !== null) {
				clearTimer(timer)
				timer = null
			}
			webview = null
			if (!verifiedDelivered) options.onClosed?.()
		})
		parentWebview.append(webview)
		options.load(webview)
		timer = setTimer(() => {
			if (closed) return
			options.onTimeout?.()
			close()
		}, boundedTimeout(options.timeoutMillis))
	} catch (error) {
		close()
		throw error
	}

	return Object.freeze({
		close,
		setBounds(bounds) {
			if (closed || !webview) return false
			const style = boundsStyle(bounds)
			if (!style) return false
			const signature = styleSignature(style)
			if (signature === lastStyleSignature) return false
			lastStyleSignature = signature
			try {
				webview.setStyle(style)
				return true
			} catch (_) {
				return false
			}
		}
	})
}

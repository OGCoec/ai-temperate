export const ANDROID_TURNSTILE_WIDTH = 240
export const ANDROID_TURNSTILE_HOST_HEIGHT = 76

function finiteCoordinate(value, minimum) {
	const number = Number(value)
	if (!Number.isFinite(number) || number < minimum || Math.abs(number) > 100000) return null
	return number
}

/**
 * 把 Vue 宿主在可视区中的坐标转换成父 WebView 的文档坐标，使 static 子 WebView 与页面内容一起滚动。
 */
export function resolveAndroidTurnstileAnchor(rect, scrollTop = 0) {
	const left = finiteCoordinate(rect?.left, 0)
	const viewportTop = finiteCoordinate(rect?.top, -100000)
	const measuredWidth = finiteCoordinate(rect?.width, 1)
	const pageScrollTop = finiteCoordinate(scrollTop, 0)
	if (left === null || viewportTop === null || measuredWidth === null || pageScrollTop === null) return null

	return {
		left: Math.round(left),
		top: Math.round(viewportTop + pageScrollTop),
		width: ANDROID_TURNSTILE_WIDTH,
		height: ANDROID_TURNSTILE_HOST_HEIGHT
	}
}

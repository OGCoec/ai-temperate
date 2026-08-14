function mediaQuery() {
	try {
		return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
			? window.matchMedia('(prefers-reduced-motion: reduce)')
			: null
	} catch (_) {
		return null
	}
}

function androidAnimationScaleReduced() {
	try {
		const runtime = globalThis.plus?.android
		if (!runtime?.importClass || !runtime.runtimeMainActivity) return false
		const settings = runtime.importClass('android.provider.Settings$Global')
		const activity = runtime.runtimeMainActivity()
		const resolver = activity?.getContentResolver?.()
		if (!settings || !resolver) return false
		const scales = [
			settings.getFloat(resolver, 'animator_duration_scale', 1),
			settings.getFloat(resolver, 'transition_animation_scale', 1),
			settings.getFloat(resolver, 'window_animation_scale', 1)
		]
		return scales.some(scale => Number.isFinite(Number(scale)) && Number(scale) <= 0)
	} catch (_) {
		return false
	}
}

export function readAiSystemReducedMotion() {
	const query = mediaQuery()
	// Android WebView can expose matchMedia without reflecting the user's animator scale.
	// Both platform signals therefore reduce motion independently.
	return Boolean(query?.matches) || androidAnimationScaleReduced()
}

export function createAiMotionPreferenceController(onChange) {
	let systemReduced = readAiSystemReducedMotion()
	let destroyed = false
	const query = mediaQuery()

	const emit = () => {
		if (destroyed) return
		onChange?.({
			systemReduced,
			reduced: systemReduced
		})
	}
	const refresh = () => {
		systemReduced = readAiSystemReducedMotion()
		emit()
	}
	const onMediaChange = () => refresh()
	const onVisibilityChange = () => {
		if (document.visibilityState !== 'hidden') refresh()
	}

	if (query?.addEventListener) query.addEventListener('change', onMediaChange)
	else query?.addListener?.(onMediaChange)
	if (typeof document !== 'undefined') {
		document.addEventListener('visibilitychange', onVisibilityChange)
	}

	emit()
	return Object.freeze({
		isReduced: () => systemReduced,
		refresh,
		destroy() {
			if (destroyed) return
			destroyed = true
			if (query?.removeEventListener) query.removeEventListener('change', onMediaChange)
			else query?.removeListener?.(onMediaChange)
			if (typeof document !== 'undefined') {
				document.removeEventListener('visibilitychange', onVisibilityChange)
			}
		}
	})
}

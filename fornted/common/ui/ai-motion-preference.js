export const AI_MOTION_PREFERENCES = Object.freeze({
	SYSTEM: 'SYSTEM',
	REDUCE: 'REDUCE'
})

export const AI_MOTION_PREFERENCE_STORAGE_KEY = 'ait.ui.motion-preference.v1'

export function normalizeAiMotionPreference(value) {
	return value === AI_MOTION_PREFERENCES.REDUCE
		? AI_MOTION_PREFERENCES.REDUCE
		: AI_MOTION_PREFERENCES.SYSTEM
}

export function resolveAiMotionReduced({
	preference = AI_MOTION_PREFERENCES.SYSTEM,
	systemReduced = false
} = {}) {
	return normalizeAiMotionPreference(preference)
		=== AI_MOTION_PREFERENCES.REDUCE || systemReduced === true
}

function storage() {
	try {
		return globalThis.uni?.getStorageSync && globalThis.uni
	} catch (_) {
		return null
	}
}

export function readAiMotionPreference() {
	try {
		return normalizeAiMotionPreference(
			storage()?.getStorageSync(AI_MOTION_PREFERENCE_STORAGE_KEY))
	} catch (_) {
		return AI_MOTION_PREFERENCES.SYSTEM
	}
}

export function writeAiMotionPreference(value) {
	const preference = normalizeAiMotionPreference(value)
	try {
		storage()?.setStorageSync(AI_MOTION_PREFERENCE_STORAGE_KEY, preference)
	} catch (_) {
		// 存储不可用时仍返回规范化值，当前页面可以继续遵循用户选择。
	}
	return preference
}

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
	let preference = readAiMotionPreference()
	let systemReduced = readAiSystemReducedMotion()
	let destroyed = false
	const query = mediaQuery()

	const emit = () => {
		if (destroyed) return
		onChange?.({
			preference,
			systemReduced,
			reduced: resolveAiMotionReduced({ preference, systemReduced })
		})
	}
	const refresh = () => {
		systemReduced = readAiSystemReducedMotion()
		emit()
	}
	const onMediaChange = event => {
		systemReduced = Boolean(event?.matches)
		emit()
	}
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
		getPreference: () => preference,
		isReduced: () => resolveAiMotionReduced({ preference, systemReduced }),
		refresh,
		setPreference(value) {
			preference = writeAiMotionPreference(value)
			emit()
			return preference
		},
		toggleManualReduce() {
			return this.setPreference(
				preference === AI_MOTION_PREFERENCES.REDUCE
					? AI_MOTION_PREFERENCES.SYSTEM
					: AI_MOTION_PREFERENCES.REDUCE)
		},
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

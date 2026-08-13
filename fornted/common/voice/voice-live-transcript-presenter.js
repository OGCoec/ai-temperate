const DEFAULT_CHARACTER_INTERVAL_MS = 24

function unicodeCharacters(value) {
	return Array.from(String(value || ''))
}

function sharedPrefixLength(left, right) {
	const limit = Math.min(left.length, right.length)
	let index = 0
	while (index < limit && left[index] === right[index]) index += 1
	return index
}

export function createVoiceLiveTranscriptPresenter({
	onDisplay,
	characterIntervalMs = DEFAULT_CHARACTER_INTERVAL_MS,
	schedule = setTimeout,
	cancel = clearTimeout
} = {}) {
	const emit = typeof onDisplay === 'function' ? onDisplay : () => {}
	const scheduleTask = typeof schedule === 'function' ? schedule : setTimeout
	const cancelTask = typeof cancel === 'function' ? cancel : clearTimeout
	const intervalMs = Number.isFinite(Number(characterIntervalMs))
		&& Number(characterIntervalMs) > 0
		? Number(characterIntervalMs)
		: DEFAULT_CHARACTER_INTERVAL_MS
	let displayed = []
	let target = []
	let timer = null
	let disposed = false

	function cancelPendingCharacter() {
		if (timer === null) return
		try { cancelTask(timer) } catch (_) {}
		timer = null
	}

	function publish() {
		if (disposed) return
		try { emit(displayed.join('')) } catch (_) {}
	}

	function scheduleNextCharacter() {
		if (disposed || timer !== null || displayed.length >= target.length) return
		try {
			timer = scheduleTask(() => {
				timer = null
				if (disposed || displayed.length >= target.length) return
				displayed.push(target[displayed.length])
				publish()
				scheduleNextCharacter()
			}, intervalMs)
		} catch (_) {
			timer = null
		}
	}

	return Object.freeze({
		setTarget(value, { reduced = false } = {}) {
			if (disposed) return
			const nextTarget = unicodeCharacters(value)
			const prefixLength = sharedPrefixLength(displayed, nextTarget)
			const visibleChanged = prefixLength < displayed.length
			cancelPendingCharacter()
			target = nextTarget
			if (visibleChanged) {
				displayed = displayed.slice(0, prefixLength)
				publish()
			}
			if (reduced === true) {
				const nextDisplay = target.join('')
				if (nextDisplay !== displayed.join('')) {
					displayed = [...target]
					publish()
				}
				return
			}
			scheduleNextCharacter()
		},
		reset() {
			if (disposed) return
			cancelPendingCharacter()
			displayed = []
			target = []
			publish()
		},
		dispose() {
			if (disposed) return
			cancelPendingCharacter()
			disposed = true
			displayed = []
			target = []
		}
	})
}

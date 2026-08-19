function defaultSchedule(callback) {
	if (typeof globalThis.requestAnimationFrame === 'function') {
		return { type: 'animation-frame', id: globalThis.requestAnimationFrame(callback) }
	}
	return { type: 'timeout', id: globalThis.setTimeout(callback, 16) }
}

function defaultCancel(handle) {
	if (handle?.type === 'animation-frame'
		&& typeof globalThis.cancelAnimationFrame === 'function') {
		globalThis.cancelAnimationFrame(handle.id)
		return
	}
	globalThis.clearTimeout?.(handle?.id)
}

function prefersReducedMotion() {
	try {
		return Boolean(globalThis.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches)
	} catch (_) {
		return false
	}
}

function graphemes(value) {
	if (globalThis.Intl?.Segmenter) {
		const segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' })
		return Array.from(segmenter.segment(value), item => item.segment)
	}
	const clusters = []
	for (const character of Array.from(value)) {
		const codePoint = character.codePointAt(0)
		const combiningMark = (codePoint >= 0x0300 && codePoint <= 0x036f)
			|| (codePoint >= 0x1ab0 && codePoint <= 0x1aff)
			|| (codePoint >= 0x1dc0 && codePoint <= 0x1dff)
			|| (codePoint >= 0x20d0 && codePoint <= 0x20ff)
			|| (codePoint >= 0xfe20 && codePoint <= 0xfe2f)
		const variationOrModifier = (codePoint >= 0xfe00 && codePoint <= 0xfe0f)
			|| (codePoint >= 0xe0100 && codePoint <= 0xe01ef)
			|| (codePoint >= 0x1f3fb && codePoint <= 0x1f3ff)
		const regionalIndicator = codePoint >= 0x1f1e6 && codePoint <= 0x1f1ff
		const current = clusters[clusters.length - 1] || ''
		const currentRegionalCount = Array.from(current)
			.filter(item => {
				const point = item.codePointAt(0)
				return point >= 0x1f1e6 && point <= 0x1f1ff
			}).length
		if (current && (character === '\u200d'
			|| current.endsWith('\u200d')
			|| combiningMark
			|| variationOrModifier
			|| (regionalIndicator && currentRegionalCount % 2 === 1))) {
			clusters[clusters.length - 1] += character
		} else {
			clusters.push(character)
		}
	}
	return clusters
}

export const STOP_TAIL_MAX_DURATION_MS = 200
export const STOP_TAIL_MAX_GRAPHEMES = 32

/**
 * 将真实 SSE 文本片段放入短时展示队列；它只影响渲染节奏，不改变事件、用量或持久化内容。
 */
export function createAiConversationTextDrain(options = {}) {
	const onText = typeof options.onText === 'function' ? options.onText : () => {}
	const schedule = options.schedule || defaultSchedule
	const cancel = options.cancel || defaultCancel
	const now = options.now || (() => Date.now())
	const frameBudgetMs = Math.max(8, Number(options.frameBudgetMs || 16))
	const maxVisualLagMs = Math.max(frameBudgetMs, Number(options.maxVisualLagMs || 500))
	const reducedMotion = options.reducedMotion ?? prefersReducedMotion()
	let queue = []
	let scheduled = null
	let queuedAt = 0
	let terminal = null
	let closed = false
	let accepting = true
	let stopping = false
	let stopMaxDurationMs = STOP_TAIL_MAX_DURATION_MS

	function completeIfIdle() {
		if (queue.length || !terminal || closed) return
		const callback = terminal
		terminal = null
		if (stopping) closed = true
		callback()
	}

	function drainFrame() {
		scheduled = null
		if (closed) return
		if (!queue.length) {
			completeIfIdle()
			return
		}
		const elapsed = Math.max(0, now() - queuedAt)
		const visualLagMs = stopping ? stopMaxDurationMs : maxVisualLagMs
		const remainingFrames = Math.max(
			1,
			Math.ceil((visualLagMs - elapsed) / frameBudgetMs)
		)
		const take = elapsed >= visualLagMs
			? queue.length
			: Math.max(1, Math.ceil(queue.length / remainingFrames))
		onText(queue.splice(0, take).join(''))
		if (queue.length) ensureScheduled()
		else completeIfIdle()
	}

	function ensureScheduled() {
		if (scheduled !== null || closed) return
		scheduled = schedule(drainFrame)
	}

	return Object.freeze({
		push(text) {
			if (closed || !accepting || !text) return
			if (reducedMotion) {
				onText(String(text))
				return
			}
			if (!queue.length) queuedAt = now()
			queue.push(...graphemes(String(text)))
			ensureScheduled()
		},
		finish(callback) {
			if (closed || stopping) return
			terminal = typeof callback === 'function' ? callback : () => {}
			if (reducedMotion && queue.length) {
				onText(queue.join(''))
				queue = []
			}
			completeIfIdle()
			if (queue.length) ensureScheduled()
		},
		stopWithTail(stopOptions = {}, callback) {
			if (closed || stopping) return
			stopping = true
			accepting = false
			const configuredDuration = Number(stopOptions.maxDurationMs)
			const configuredGraphemes = Number(stopOptions.maxGraphemes)
			stopMaxDurationMs = Number.isFinite(configuredDuration)
				? Math.min(STOP_TAIL_MAX_DURATION_MS, Math.max(0, configuredDuration))
				: STOP_TAIL_MAX_DURATION_MS
			const maxGraphemes = Number.isFinite(configuredGraphemes)
				? Math.min(STOP_TAIL_MAX_GRAPHEMES,
					Math.max(0, Math.floor(configuredGraphemes)))
				: STOP_TAIL_MAX_GRAPHEMES
			// Stop 的可见尾部只能来自调用瞬间已有队列；复制后丢弃其余内容，后续 push 已被入口门控。
			queue = queue.slice(0, maxGraphemes)
			queuedAt = now()
			let completed = false
			terminal = () => {
				if (completed) return
				completed = true
				if (typeof callback === 'function') callback()
			}
			if (scheduled !== null) cancel(scheduled)
			scheduled = null
			if ((reducedMotion || stopMaxDurationMs === 0) && queue.length) {
				onText(queue.join(''))
				queue = []
			}
			completeIfIdle()
			if (queue.length) ensureScheduled()
		},
		close() {
			closed = true
			accepting = false
			queue = []
			terminal = null
			if (scheduled !== null) cancel(scheduled)
			scheduled = null
		}
	})
}

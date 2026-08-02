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
	return Array.from(value)
}

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

	function completeIfIdle() {
		if (queue.length || !terminal || closed) return
		const callback = terminal
		terminal = null
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
		const remainingFrames = Math.max(
			1,
			Math.ceil((maxVisualLagMs - elapsed) / frameBudgetMs)
		)
		const take = elapsed >= maxVisualLagMs
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
			if (closed || !text) return
			if (reducedMotion) {
				onText(String(text))
				return
			}
			if (!queue.length) queuedAt = now()
			queue.push(...graphemes(String(text)))
			ensureScheduled()
		},
		finish(callback) {
			if (closed) return
			terminal = typeof callback === 'function' ? callback : () => {}
			if (reducedMotion && queue.length) {
				onText(queue.join(''))
				queue = []
			}
			completeIfIdle()
			if (queue.length) ensureScheduled()
		},
		close() {
			closed = true
			queue = []
			terminal = null
			if (scheduled !== null) cancel(scheduled)
			scheduled = null
		}
	})
}

const DEFAULT_REVISION = -1

function asText(value) {
	return value == null ? '' : String(value)
}

function asSequence(value) {
	const sequence = Number(value)
	return Number.isFinite(sequence) ? sequence : null
}

export function createAiMarkdownRenderState(options = {}) {
	const onText = typeof options.onText === 'function' ? options.onText : () => {}
	const onSnapshot = typeof options.onSnapshot === 'function' ? options.onSnapshot : null
	const onDelta = typeof options.onDelta === 'function' ? options.onDelta : null
	const onComplete = typeof options.onComplete === 'function' ? options.onComplete : () => {}
	const emitSnapshot = onSnapshot || onText
	const emitDelta = onDelta || onText
	let text = ''
	let revision = DEFAULT_REVISION
	let highestSequence = -1
	let closed = false
	let completed = false
	const eventIds = new Set()

	function rememberEvent(eventId) {
		if (!eventId) return false
		const normalized = String(eventId)
		if (eventIds.has(normalized)) return true
		eventIds.add(normalized)
		if (eventIds.size > 2048) {
			const oldest = eventIds.values().next().value
			eventIds.delete(oldest)
		}
		return false
	}

	function applySnapshot(input = {}) {
		if (closed || completed) return false
		const nextRevision = asSequence(input.revision) ?? 0
		const nextText = asText(input.text)
		if (nextRevision < revision) return false
		if (nextRevision === revision && nextText === text) return false

		revision = nextRevision
		text = nextText
		highestSequence = -1
		eventIds.clear()
		emitSnapshot(text)
		return true
	}

	function applyDelta(input = {}) {
		if (closed || completed) return false
		if (rememberEvent(input.eventId)) return false
		const sequence = asSequence(input.sequence)
		if (sequence != null) {
			if (sequence <= highestSequence) return false
			highestSequence = sequence
		}
		const chunk = asText(input.text)
		if (!chunk) return false
		text += chunk
		emitDelta(chunk)
		return true
	}

	function complete(input = {}) {
		if (closed || completed) return false
		completed = true
		if (input.finalText != null) text = asText(input.finalText)
		onComplete(text)
		return true
	}

	function close() {
		if (closed) return false
		closed = true
		eventIds.clear()
		return true
	}

	return Object.freeze({
		applySnapshot,
		applyDelta,
		complete,
		close,
		getText: () => text,
		getRevision: () => revision,
		isClosed: () => closed,
		isCompleted: () => completed
	})
}

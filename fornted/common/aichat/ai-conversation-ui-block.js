const DEFAULT_ALLOWED_TYPES = Object.freeze(['dialog'])
const COMMAND_ID_PATTERN = /^[a-z][a-z0-9_.:-]{0,63}$/

function asText(value) {
	return value == null ? '' : String(value)
}

function normalizeActions(actions) {
	if (actions == null) return []
	if (!Array.isArray(actions) || actions.length > 8) return null
	const normalized = []
	for (const action of actions) {
		const id = asText(action?.id).trim()
		const label = asText(action?.label).trim()
		const commandId = asText(action?.commandId).trim()
		if (!id || !label || !COMMAND_ID_PATTERN.test(commandId)) return null
		normalized.push({ id, label, commandId })
	}
	return normalized
}

function normalizeBlock(event, allowedTypes) {
	if (!event || (event.type !== 'ui_block' && event.type !== 'block')) return null
	const blockId = asText(event.blockId).trim()
	const blockType = asText(event.blockType).trim()
	const sequence = Number(event.sequence)
	if (!blockId || !allowedTypes.has(blockType) || !Number.isFinite(sequence)) return null
	const payload = event.payload && typeof event.payload === 'object' ? event.payload : {}
	const actions = normalizeActions(payload.actions)
	if (actions == null) return null
	return Object.freeze({
		blockId,
		blockType,
		sequence,
		payload: Object.freeze({
			title: asText(payload.title).slice(0, 240),
			body: asText(payload.body).slice(0, 10000),
			actions: Object.freeze(actions)
		})
	})
}

export function createAiConversationUiBlockReducer(options = {}) {
	const allowedTypes = new Set(options.allowedTypes || DEFAULT_ALLOWED_TYPES)
	const blocks = new Map()

	function apply(event) {
		const block = normalizeBlock(event, allowedTypes)
		if (!block) return false
		const previous = blocks.get(block.blockId)
		if (previous && block.sequence <= previous.sequence) return false
		blocks.set(block.blockId, block)
		options.onChange?.(list())
		return true
	}

	function remove(blockId) {
		const removed = blocks.delete(asText(blockId))
		if (removed) options.onChange?.(list())
		return removed
	}

	function list() {
		return Array.from(blocks.values())
	}

	return Object.freeze({
		apply,
		remove,
		get: blockId => blocks.get(asText(blockId)) || null,
		list
	})
}

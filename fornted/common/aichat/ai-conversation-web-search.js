export const AI_CONVERSATION_WEB_SEARCH_MODES = Object.freeze({
	OFF: 'OFF',
	AUTO: 'AUTO',
	REQUIRED: 'REQUIRED'
})

export const AI_CONVERSATION_WEB_SEARCH_OPTIONS = Object.freeze([
	Object.freeze({ value: 'OFF', label: '联网 · 关闭' }),
	Object.freeze({ value: 'AUTO', label: '联网 · 自动' }),
	Object.freeze({ value: 'REQUIRED', label: '联网 · 必须' })
])

export function aiConversationWebSearchEnabled() {
	try {
		if (typeof __AI_CONVERSATION_WEB_SEARCH_ENABLED__ !== 'undefined') {
			return Boolean(__AI_CONVERSATION_WEB_SEARCH_ENABLED__)
		}
	} catch (_) {}
	return globalThis.__AI_CONVERSATION_WEB_SEARCH_ENABLED__ === true
}

export function modelSupportsAiConversationWebSearch(model) {
	if (!aiConversationWebSearchEnabled() || !model) return false
	const capabilities = new Set(model.capabilities || [])
	return capabilities.has('RESPONSES')
		&& capabilities.has('WEB_SEARCH')
		&& !capabilities.has('IMAGE_GENERATION')
}

export function normalizeAiConversationWebSearchMode(mode, model) {
	const normalized = String(mode || 'OFF').toUpperCase()
	if (!modelSupportsAiConversationWebSearch(model)) return 'OFF'
	return Object.prototype.hasOwnProperty.call(
		AI_CONVERSATION_WEB_SEARCH_MODES, normalized)
		? normalized
		: 'OFF'
}

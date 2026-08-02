const state = {
	conversations: [],
	nextCursor: null,
	hasMoreConversations: false,
	conversationsLoaded: false,
	conversationLoading: false,
	conversationError: '',
	currentConversationPublicId: null,
	messages: [],
	nextBefore: null,
	hasMoreMessages: false,
	messagesLoading: false,
	historyStale: false
}

export function clearAiConversationStore() {
	state.conversations = []
	state.nextCursor = null
	state.hasMoreConversations = false
	state.conversationsLoaded = false
	state.conversationLoading = false
	state.conversationError = ''
	state.currentConversationPublicId = null
	state.messages = []
	state.nextBefore = null
	state.hasMoreMessages = false
	state.messagesLoading = false
	state.historyStale = false
	return snapshot()
}

function snapshot() {
	return {
		...state,
		conversations: state.conversations.map(item => ({ ...item })),
		messages: state.messages.map(item => ({ ...item }))
	}
}

export function readAiConversationStore() {
	return snapshot()
}

export function markAiConversationHistoryStale() {
	state.historyStale = true
	return snapshot()
}

export function clearAiConversationHistoryStale() {
	state.historyStale = false
	return snapshot()
}

export function resetCurrentConversation() {
	state.currentConversationPublicId = null
	state.messages = []
	state.nextBefore = null
	state.hasMoreMessages = false
	state.messagesLoading = false
	return snapshot()
}

export function selectConversation(conversationPublicId) {
	state.currentConversationPublicId = conversationPublicId
	state.messages = []
	state.nextBefore = null
	state.hasMoreMessages = false
	return snapshot()
}

export function setConversationPage(page, append = false) {
	const incoming = page.conversations || []
	state.conversations = append ? [...state.conversations, ...incoming] : [...incoming]
	state.nextCursor = page.nextCursor || null
	state.hasMoreConversations = page.hasMore === true
	state.conversationsLoaded = true
	state.conversationLoading = false
	state.conversationError = ''
	return snapshot()
}

export function setConversationLoading(value) {
	state.conversationLoading = value === true
	return snapshot()
}

export function setConversationError(message) {
	state.conversationLoading = false
	state.conversationError = String(message || '')
	return snapshot()
}

export function setMessagePage(page, prepend = false) {
	const incoming = page.messages || []
	state.messages = prepend ? [...incoming, ...state.messages] : [...incoming]
	state.nextBefore = page.nextBefore || null
	state.hasMoreMessages = page.hasMore === true
	state.messagesLoading = false
	return snapshot()
}

export function setMessagesLoading(value) {
	state.messagesLoading = value === true
	return snapshot()
}

export function appendLocalMessage(message) {
	state.messages = [...state.messages, { ...message }]
	return snapshot()
}

export function patchLocalMessage(localId, patch) {
	state.messages = state.messages.map(item => item.localId === localId
		? { ...item, ...patch }
		: item)
	return snapshot()
}

export function patchLatestMessage(patch) {
	if (!state.messages.length) return snapshot()
	const lastIndex = state.messages.length - 1
	state.messages = state.messages.map((item, index) => index === lastIndex
		? { ...item, ...patch }
		: item)
	return snapshot()
}

export function patchMessage(messageKey, patch) {
	state.messages = state.messages.map(item =>
		(item.localId || item.messagePublicId) === messageKey
			? { ...item, ...patch }
			: item)
	return snapshot()
}

export function discardTransientMessages() {
	// 中断片段只允许停留在当前可见页面内存；离开页面后不能重新混入 PostgreSQL 历史。
	state.messages = state.messages.filter(message => Boolean(message.messagePublicId))
	if (!state.messages.length
		&& !state.historyStale
		&& !state.conversations.some(item => item.conversationPublicId === state.currentConversationPublicId)) {
		state.currentConversationPublicId = null
	}
	return snapshot()
}

export function setAcceptedConversation(conversationPublicId) {
	state.currentConversationPublicId = conversationPublicId
	return snapshot()
}

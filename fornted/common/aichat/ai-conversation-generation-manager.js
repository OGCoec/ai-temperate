const STORAGE_KEY = 'ait.user.ai.generations.v1'
const TERMINAL_STATUSES = new Set(['SETTLED', 'REFUNDED', 'RECONCILE_REQUIRED', 'COMPLETED'])

const tasks = new Map()
const pendingRequests = new Map()
const observers = new Map()
const listeners = new Map()
let hydrated = false

function storage() {
	try { return globalThis.sessionStorage || null } catch (_) { return null }
}

function hydrate() {
	if (hydrated) return
	hydrated = true
	try {
		const parsed = JSON.parse(storage()?.getItem(STORAGE_KEY) || '[]')
		const storedTasks = Array.isArray(parsed) ? parsed : parsed?.tasks
		const storedPending = Array.isArray(parsed?.pendingRequests) ? parsed.pendingRequests : []
		if (Array.isArray(storedTasks)) storedTasks.forEach(item => {
			if (item?.generationPublicId) tasks.set(item.generationPublicId, { ...item })
		})
		storedPending.forEach(item => {
			if (item?.idempotencyKey) pendingRequests.set(item.idempotencyKey, { ...item })
		})
	} catch (_) {}
}

function persist() {
	try {
		storage()?.setItem(STORAGE_KEY, JSON.stringify({
			tasks: [...tasks.values()],
			pendingRequests: [...pendingRequests.values()]
		}))
	} catch (_) {
		// sessionStorage 不可用时仅退化为本页内存状态，不改变后端 Generation。
	}
}

function copy(value) {
	return value ? { ...value } : null
}

function notify(generationPublicId) {
	const snapshot = copy(tasks.get(generationPublicId))
	listeners.get(generationPublicId)?.forEach(listener => {
		try { listener(snapshot) } catch (_) {
			// 单个页面监听器失败不能关闭全局 Observer 或改变后台任务状态。
		}
	})
}

export function asyncGenerationEnabled() {
	try {
		if (typeof __AI_CONVERSATION_ASYNC_GENERATION_ENABLED__ !== 'undefined') {
			return Boolean(__AI_CONVERSATION_ASYNC_GENERATION_ENABLED__)
		}
	} catch (_) {}
	return globalThis.__AI_CONVERSATION_ASYNC_GENERATION_ENABLED__ === true
}

export function registerGeneration(value) {
	hydrate()
	if (!value?.generationPublicId) throw new Error('generationPublicId is required')
	const existing = tasks.get(value.generationPublicId) || {}
	const next = {
		status: 'RUNNING',
		observerAttached: true,
		revision: 0,
		responseText: '',
		...existing,
		...value
	}
	tasks.set(next.generationPublicId, next)
	if (next.idempotencyKey) pendingRequests.delete(next.idempotencyKey)
	persist()
	notify(next.generationPublicId)
	return copy(next)
}

export function registerPendingGeneration(value) {
	hydrate()
	if (!value?.idempotencyKey) throw new Error('idempotencyKey is required')
	pendingRequests.set(value.idempotencyKey, { ...value })
	persist()
	return copy(value)
}

export function listPendingGenerationRequests() {
	hydrate()
	return [...pendingRequests.values()].map(copy)
}

export function updateGeneration(generationPublicId, patch) {
	hydrate()
	const current = tasks.get(generationPublicId)
	if (!current) return null
	Object.assign(current, patch || {})
	persist()
	notify(generationPublicId)
	return copy(current)
}

export function getGeneration(generationPublicId) {
	hydrate()
	return copy(tasks.get(generationPublicId))
}

export function listActiveGenerations() {
	hydrate()
	return [...tasks.values()]
		.filter(item => !TERMINAL_STATUSES.has(item.status))
		.map(copy)
}

export function detachGenerationObserver(generationPublicId) {
	observers.get(generationPublicId)?.close?.('CLIENT_DETACHED')
	observers.delete(generationPublicId)
	return updateGeneration(generationPublicId, { observerAttached: false })
}

export function bindGenerationObserver(generationPublicId, observer) {
	const previous = observers.get(generationPublicId)
	if (previous && previous !== observer) previous.close?.('OBSERVER_REPLACED')
	observers.set(generationPublicId, observer)
	return updateGeneration(generationPublicId, { observerAttached: true })
}

export function subscribeGeneration(generationPublicId, listener) {
	if (typeof listener !== 'function') throw new Error('generation listener is required')
	const registered = listeners.get(generationPublicId) || new Set()
	registered.add(listener)
	listeners.set(generationPublicId, registered)
	listener(getGeneration(generationPublicId))
	return () => {
		const current = listeners.get(generationPublicId)
		current?.delete(listener)
		if (current?.size === 0) listeners.delete(generationPublicId)
	}
}

export function markGenerationTerminal(generationPublicId, status) {
	observers.delete(generationPublicId)
	return updateGeneration(generationPublicId, {
		status,
		observerAttached: false,
		idempotencyKey: null
	})
}

export function clearGenerationManager() {
	observers.forEach(observer => observer?.close?.('SESSION_CLEARED'))
	observers.clear()
	listeners.clear()
	tasks.clear()
	pendingRequests.clear()
	hydrated = true
	persist()
}

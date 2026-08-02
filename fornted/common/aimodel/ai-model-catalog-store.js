function emptyCatalogState() {
	return {
		models: [],
		activeKeyword: '',
		total: 0,
		pageNum: 0,
		hasNext: false,
		hasLoaded: false,
		initialLoading: false,
		refreshing: false,
		appending: false,
		initialError: '',
		refreshError: '',
		appendError: '',
		failedIconIds: {}
	}
}

let catalogState = emptyCatalogState()
let catalogGeneration = 0
let initialRequest = null
let appendRequest = null

function snapshot() {
	return {
		...catalogState,
		models: [...catalogState.models],
		failedIconIds: { ...catalogState.failedIconIds }
	}
}

function replaceState(patch) {
	catalogState = { ...catalogState, ...patch }
	return snapshot()
}

function applyIfCurrent(generation, patch) {
	return generation === catalogGeneration ? replaceState(patch) : snapshot()
}

function messageOf(error, fallback) {
	return typeof error?.message === 'string' && error.message.trim()
		? error.message.trim()
		: fallback
}

export function readAiModelCatalog() {
	return snapshot()
}

export function clearAiModelCatalog() {
	catalogGeneration += 1
	initialRequest = null
	appendRequest = null
	catalogState = emptyCatalogState()
	return snapshot()
}

export function setAiModelCatalogKeyword(keyword) {
	const normalized = typeof keyword === 'string' ? keyword.trim() : ''
	if (normalized === catalogState.activeKeyword) return snapshot()
	catalogGeneration += 1
	initialRequest = null
	appendRequest = null
	catalogState = { ...emptyCatalogState(), activeKeyword: normalized }
	return snapshot()
}

export function refreshAiModelCatalog(loadFirstPage) {
	if (initialRequest) return initialRequest
	if (appendRequest) return Promise.resolve(snapshot())

	const generation = catalogGeneration
	const retainList = catalogState.models.length > 0
	replaceState({
		initialLoading: !retainList,
		refreshing: retainList,
		initialError: '',
		refreshError: ''
	})

	const request = Promise.resolve()
		.then(loadFirstPage)
		.then(page => applyIfCurrent(generation, {
			models: page.models,
			total: page.total,
			pageNum: page.pageNum,
			hasNext: page.hasNext,
			hasLoaded: true,
			initialLoading: false,
			refreshing: false,
			appendError: '',
			failedIconIds: {}
		}))
		.catch(error => applyIfCurrent(generation, retainList
			? {
				refreshing: false,
				refreshError: '刷新失败，当前列表仍可继续查看。'
			}
			: {
				initialLoading: false,
				initialError: messageOf(error, '请检查网络后重试。')
			}))
		.finally(() => {
			if (initialRequest === request) initialRequest = null
		})

	initialRequest = request
	return request
}

export function loadNextAiModelCatalog(loadNextPage) {
	if (initialRequest || appendRequest || !catalogState.hasNext) {
		return appendRequest || Promise.resolve(snapshot())
	}

	const generation = catalogGeneration
	replaceState({ appending: true, appendError: '' })
	const nextPageNum = catalogState.pageNum + 1
	const request = Promise.resolve()
		.then(() => loadNextPage(nextPageNum))
		.then(page => applyIfCurrent(generation, {
			models: [...catalogState.models, ...page.models],
			total: page.total,
			pageNum: page.pageNum,
			hasNext: page.hasNext,
			appending: false
		}))
		.catch(error => applyIfCurrent(generation, {
			appending: false,
			appendError: messageOf(error, '加载更多模型失败，请重试。')
		}))
		.finally(() => {
			if (appendRequest === request) appendRequest = null
		})

	appendRequest = request
	return request
}

export function markAiModelIconFailed(publicId) {
	if (!publicId) return snapshot()
	return replaceState({
		failedIconIds: { ...catalogState.failedIconIds, [publicId]: true }
	})
}

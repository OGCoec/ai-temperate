const STATE_MARKER = '__adminWorkspace'

/**
 * 连接工作台状态与浏览器 History；popstate 只上报目标，不再次写入历史，避免前进后退循环。
 */
export function createAdminWorkspaceHistoryH5({ windowObject, onPop }) {
	let started = false
	const listener = event => {
		if (event?.state?.[STATE_MARKER] && event.state.adminWorkspaceLocation) {
			onPop?.(event.state.adminWorkspaceLocation)
		}
	}

	function state(location) {
		// 保留 uni-app 写入的 History 元数据，只增加工作台命名空间，避免破坏框架自身的页面栈识别。
		return {
			...(windowObject.history.state || {}),
			[STATE_MARKER]: true,
			adminWorkspaceLocation: { ...location }
		}
	}

	return Object.freeze({
		start(initialLocation = null, initialUrl = null) {
			if (started) return
			started = true
			windowObject.addEventListener('popstate', listener)
			const location = initialLocation || { view: 'dashboard' }
			const url = initialUrl || `${windowObject.location.pathname}${windowObject.location.search}`
			windowObject.history.replaceState(state(location), '', url)
		},
		push(location, url) {
			windowObject.history.pushState(state(location), '', url)
		},
		replace(location, url) {
			windowObject.history.replaceState(state(location), '', url)
		},
		releaseToSystem() {
			windowObject.history.back()
		},
		destroy() {
			if (!started) return
			started = false
			windowObject.removeEventListener('popstate', listener)
		}
	})
}

const STATE_MARKER = '__adminWorkspace'
const WORKSPACE_PATH = '/pages/admin/workspace'
const STATIC_HASHES = new Set([
	'',
	'#/ai-models',
	'#/ai-models/discovery',
	'#/ai-models/new',
	'#/ai-model-icons',
	'#/ip2location/keys',
	'#/mail-inspection/openai',
	'#/mail-inspection/kiro',
	'#/mail-inspection/ip2location/registration',
	'#/mail-inspection/ip2location/verify-link'
])
const MODEL_DETAIL_HASH_PATTERN = /^#\/ai-models\/[A-Za-z0-9_-]{11}$/

function requireWorkspaceUrl(value) {
	const url = new URL(String(value || ''), 'https://admin.invalid')
	if (url.pathname !== WORKSPACE_PATH || url.search) {
		throw new Error('History URL must use the registered workspace pathname.')
	}
	if (!STATIC_HASHES.has(url.hash) && !MODEL_DETAIL_HASH_PATTERN.test(url.hash)) {
		throw new Error('History URL must use a published workspace fragment.')
	}
	return `${url.pathname}${url.hash}`
}

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
			adminWorkspaceLocation: {
				view: String(location?.view || 'dashboard'),
				mode: String(location?.mode || ''),
				publicId: String(location?.publicId || '')
			}
		}
	}

	return Object.freeze({
		start(initialLocation = null, initialUrl = null) {
			if (started) return
			started = true
			windowObject.addEventListener('popstate', listener)
			const location = initialLocation || { view: 'dashboard' }
			const currentUrl = `${windowObject.location.pathname}${windowObject.location.hash || ''}`
			const url = requireWorkspaceUrl(initialUrl || currentUrl)
			windowObject.history.replaceState(state(location), '', url)
		},
		push(location, url) {
			windowObject.history.pushState(state(location), '', requireWorkspaceUrl(url))
		},
		replace(location, url) {
			windowObject.history.replaceState(state(location), '', requireWorkspaceUrl(url))
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

import { normalizeAdminWorkspaceLocation } from './admin-workspace-route.js'

const MODEL_CHILD_VIEWS = new Set(['ai-model-create', 'ai-model-detail'])

function sameLocation(left, right) {
	return left.view === right.view
		&& left.mode === right.mode
		&& left.publicId === right.publicId
}

async function callPanel(panel, method, ...args) {
	if (typeof panel?.[method] !== 'function') return undefined
	return panel[method](...args)
}

/**
 * 管理持久化工作台内的视图切换和返回优先级；页面栈写入由平台适配器负责，业务面板不会直接操作 uni 路由。
 */
export function createAdminWorkspaceController({
	initialLocation,
	historyAdapter,
	resolvePanel = () => null,
	onChange = () => undefined
}) {
	let location = normalizeAdminWorkspaceLocation(initialLocation)
	let drawerOpen = false
	const internalHistory = []

	function snapshot() {
		return Object.freeze({
			location: { ...location },
			drawerOpen,
			historyDepth: internalHistory.length
		})
	}

	function publish() {
		onChange(snapshot())
	}

	async function canLeave(nextLocation) {
		const allowed = await callPanel(resolvePanel(location), 'beforeWorkspaceLeave', nextLocation)
		return allowed !== false
	}

	async function applyLocation(nextValue, options = {}) {
		const nextLocation = normalizeAdminWorkspaceLocation(nextValue)
		if (sameLocation(location, nextLocation)) return true
		if (!options.skipLeaveCheck && !(await canLeave(nextLocation))) return false

		const previous = location
		await callPanel(resolvePanel(previous), 'onWorkspaceDeactivated', nextLocation)
		if (options.track !== false) {
			if (internalHistory.length >= 50) internalHistory.shift()
			internalHistory.push({ ...previous })
		}
		location = nextLocation
		drawerOpen = false
		if (options.replace) historyAdapter?.replace?.(location)
		else historyAdapter?.push?.(location)
		publish()
		await callPanel(resolvePanel(location), 'onWorkspaceActivated', previous)
		return true
	}

	async function navigate(nextValue, options = {}) {
		return applyLocation(nextValue, options)
	}

	async function acceptPlatformLocation(nextValue) {
		const nextLocation = normalizeAdminWorkspaceLocation(nextValue)
		if (sameLocation(location, nextLocation)) return true
		if (!(await canLeave(nextLocation))) return false

		const previous = location
		await callPanel(resolvePanel(previous), 'onWorkspaceDeactivated', nextLocation)
		// 浏览器已经完成前进或后退，此处只对齐内部历史，禁止再次写入 History 形成返回循环。
		let matchingIndex = -1
		for (let index = internalHistory.length - 1; index >= 0; index -= 1) {
			if (sameLocation(internalHistory[index], nextLocation)) {
				matchingIndex = index
				break
			}
		}
		if (matchingIndex >= 0) {
			internalHistory.splice(matchingIndex)
		} else {
			if (internalHistory.length >= 50) internalHistory.shift()
			internalHistory.push({ ...previous })
		}
		location = nextLocation
		drawerOpen = false
		publish()
		await callPanel(resolvePanel(location), 'onWorkspaceActivated', previous)
		return true
	}

	function openDrawer() {
		if (drawerOpen) return
		drawerOpen = true
		publish()
	}

	function closeDrawer() {
		if (!drawerOpen) return false
		drawerOpen = false
		publish()
		return true
	}

	async function back() {
		if (closeDrawer()) return true
		const panel = resolvePanel(location)
		if (await callPanel(panel, 'closeWorkspaceOverlay')) return true

		if (MODEL_CHILD_VIEWS.has(location.view)) {
			if (!(await canLeave({ view: 'ai-models' }))) return true
			await callPanel(panel, 'onWorkspaceDeactivated', { view: 'ai-models' })
			if (internalHistory[internalHistory.length - 1]?.view === 'ai-models') internalHistory.pop()
			location = normalizeAdminWorkspaceLocation({ view: 'ai-models' })
			historyAdapter?.replace?.(location)
			publish()
			await callPanel(resolvePanel(location), 'onWorkspaceActivated')
			return true
		}

		if (internalHistory.length > 0) {
			const previous = internalHistory[internalHistory.length - 1]
			if (!(await canLeave(previous))) return true
			internalHistory.pop()
			await callPanel(panel, 'onWorkspaceDeactivated', previous)
			location = normalizeAdminWorkspaceLocation(previous)
			historyAdapter?.replace?.(location)
			publish()
			await callPanel(resolvePanel(location), 'onWorkspaceActivated')
			return true
		}

		historyAdapter?.releaseToSystem?.()
		return false
	}

	return Object.freeze({
		acceptPlatformLocation,
		back,
		closeDrawer,
		navigate,
		openDrawer,
		snapshot
	})
}

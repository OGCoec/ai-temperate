/**
 * 保存 Android 工作台内部视图历史；内部栈耗尽后才把返回动作交还系统页面栈。
 */
export function createAdminWorkspaceHistoryApp({ onSystemBack = () => undefined } = {}) {
	const entries = []

	return Object.freeze({
		push(location) {
			entries.push({ ...location })
		},
		replace(location) {
			if (entries.length === 0) entries.push({ ...location })
			else entries[entries.length - 1] = { ...location }
		},
		pop() {
			if (entries.length <= 1) return null
			entries.pop()
			return { ...entries[entries.length - 1] }
		},
		peek() {
			return entries.length ? { ...entries[entries.length - 1] } : null
		},
		releaseToSystem() {
			onSystemBack()
		}
	})
}

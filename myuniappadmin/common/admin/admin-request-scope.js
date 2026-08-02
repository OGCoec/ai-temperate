/**
 * 管理同一个界面激活周期内的只读请求；界面停用后统一取消，避免过期响应回写新页面。
 */
export function createAdminRequestScope() {
	let active = true
	const tasks = new Set()

	return Object.freeze({
		isActive() {
			return active
		},
		track(task) {
			if (!task || typeof task.abort !== 'function') return false
			if (!active) {
				try {
					task.abort()
				} catch (_error) {
					// 已结束的原生任务可能拒绝重复取消，作用域仍保持关闭。
				}
				return false
			}
			tasks.add(task)
			return true
		},
		release(task) {
			tasks.delete(task)
		},
		abortAll() {
			if (!active) return
			active = false
			const pending = [...tasks]
			tasks.clear()
			for (const task of pending) {
				try {
					task.abort()
				} catch (_error) {
					// 取消是清理动作；单个运行时任务失效不得阻断其余请求的回收。
				}
			}
		}
	})
}

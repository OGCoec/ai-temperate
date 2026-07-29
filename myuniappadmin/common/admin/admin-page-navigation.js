const ADMIN_DASHBOARD_ROUTE = '/pages/index/index'

function normalizePageRoute(value) {
	const route = String(value || '').split(/[?#]/, 1)[0]
	if (!route) return ''
	return route.startsWith('/') ? route : `/${route}`
}

/**
 * 管理员子页面返回导航只在页面栈存在真实父页面时回退；直接访问或重复页面栈
 * 统一重建到控制台，避免浏览器历史把管理员留在无效的同页循环中。
 */
export function leaveAdminChildPage(dependencies = {}) {
	const getPages = dependencies.getPages || (() => getCurrentPages())
	const navigateBack = dependencies.navigateBack || (options => uni.navigateBack(options))
	const reLaunch = dependencies.reLaunch || (options => uni.reLaunch(options))
	const pages = getPages()
	const currentRoute = normalizePageRoute(pages?.[pages.length - 1]?.route)
	const parentRoute = normalizePageRoute(pages?.[pages.length - 2]?.route)

	if (!parentRoute || parentRoute === currentRoute) {
		reLaunch({ url: ADMIN_DASHBOARD_ROUTE })
		return 'DASHBOARD'
	}

	navigateBack({
		delta: 1,
		fail: () => reLaunch({ url: ADMIN_DASHBOARD_ROUTE })
	})
	return 'BACK'
}

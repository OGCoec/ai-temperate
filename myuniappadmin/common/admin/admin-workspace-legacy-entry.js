import { legacyAdminRouteToWorkspaceUrl } from './admin-workspace-route.js'

/**
 * 旧管理员页面只负责把公开参数一次性替换到规范工作台 URL，不在兼容入口发起任何业务请求。
 */
export function redirectLegacyAdminWorkspace(path, options = {}) {
	const query = []
	if (options.mode) query.push(`mode=${encodeURIComponent(String(options.mode))}`)
	if (options.publicId) query.push(`publicId=${encodeURIComponent(String(options.publicId))}`)
	const legacyUrl = `${path}${query.length ? `?${query.join('&')}` : ''}`
	return uni.redirectTo({ url: legacyAdminRouteToWorkspaceUrl(legacyUrl) })
}

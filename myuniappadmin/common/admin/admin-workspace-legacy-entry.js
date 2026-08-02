import { legacyAdminRouteToWorkspaceUrl } from './admin-workspace-route.js'

/**
 * 旧管理员页面只负责进入工作台首页，不再读取或传递历史路由参数。
 */
export function redirectLegacyAdminWorkspace(_path, _options = {}) {
	return uni.redirectTo({ url: legacyAdminRouteToWorkspaceUrl() })
}

import { ADMIN_WORKSPACE_PATH, normalizeAdminWorkspaceLocation } from './admin-workspace-route.js'

let pendingLocation = null

/**
 * 在平台页面导航前暂存一次工作台目标，避免把业务状态重新放回 Query 参数。
 */
export function stageAdminWorkspaceEntryLocation(value) {
	pendingLocation = { ...normalizeAdminWorkspaceLocation(value) }
	return ADMIN_WORKSPACE_PATH
}

export function consumeAdminWorkspaceEntryLocation() {
	if (!pendingLocation) return null
	const consumed = { ...pendingLocation }
	pendingLocation = null
	return consumed
}

export function clearAdminWorkspaceEntryLocation() {
	pendingLocation = null
}

export const H5_SIDEBAR_PUSH_MIN_WIDTH = 768
export const H5_SIDEBAR_WIDE_MIN_WIDTH = 1100
export const H5_SIDEBAR_MEDIUM_WIDTH = 240
export const H5_SIDEBAR_WIDE_WIDTH = 272
export const H5_FOLLOW_LATEST_MAX_DISTANCE = 96

function viewportWidth(value) {
	const width = Number(value)
	return Number.isFinite(width) && width > 0 ? width : 0
}

export function resolveH5SidebarMode(width) {
	return viewportWidth(width) < H5_SIDEBAR_PUSH_MIN_WIDTH ? 'overlay' : 'push'
}

export function resolveH5SidebarWidth(width) {
	return viewportWidth(width) >= H5_SIDEBAR_WIDE_MIN_WIDTH
		? H5_SIDEBAR_WIDE_WIDTH
		: H5_SIDEBAR_MEDIUM_WIDTH
}

export function defaultH5SidebarOpen(width) {
	return resolveH5SidebarMode(width) === 'push'
}

export function resolveH5SidebarOpen(currentOpen, preferenceTouched, width) {
	return preferenceTouched ? Boolean(currentOpen) : defaultH5SidebarOpen(width)
}

export function resolveH5GenerationSettingsPresentation(width) {
	return viewportWidth(width) < H5_SIDEBAR_PUSH_MIN_WIDTH ? 'sheet' : 'popover'
}

export function resolveH5FollowLatest({
	previousScrollTop,
	nextScrollTop,
	distanceToBottom,
	hasHiddenTurnsAfter,
	turnWindowMoving
} = {}) {
	const userScrolledUp = !turnWindowMoving
		&& Number(nextScrollTop || 0) < Number(previousScrollTop || 0) - 1
	if (userScrolledUp) return false
	return !hasHiddenTurnsAfter
		&& Number(distanceToBottom || 0) <= H5_FOLLOW_LATEST_MAX_DISTANCE
}

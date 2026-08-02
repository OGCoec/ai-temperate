(() => {
	'use strict'

	const workspacePath = '/pages/admin/workspace'
	const requestedPath = String(window.location.pathname || '')
	if (!requestedPath.startsWith(`${workspacePath}/`)) return

	// UniApp 只注册固定工作台页面；旧深层链接必须在框架启动前单向迁移为 Fragment。
	const legacyFragment = requestedPath.slice(workspacePath.length)
	window.history.replaceState(
		window.history.state,
		'',
		`${workspacePath}#${legacyFragment}`)
})()

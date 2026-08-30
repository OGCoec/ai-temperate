(() => {
	'use strict'

	const workspaceBodyClass = 'ait-workspace-active'
	const workspacePaths = new Set([
		'/pages/ai-chat/index',
		'/pages/account/profile',
		'/pages/account/api-keys'
	])

	function normalizedPathname() {
		return window.location.pathname.replace(/\/+$/, '') || '/'
	}

	function syncWorkspaceBodyClass() {
		if (!document.body) return
		document.body.classList.toggle(
			workspaceBodyClass,
			workspacePaths.has(normalizedPathname())
		)
	}

	function scheduleWorkspaceBodyClassSync() {
		Promise.resolve().then(syncWorkspaceBodyClass)
	}

	for (const methodName of ['pushState', 'replaceState']) {
		const originalMethod = window.history[methodName]
		if (typeof originalMethod !== 'function') continue
		window.history[methodName] = function (...args) {
			const result = Reflect.apply(originalMethod, this, args)
			scheduleWorkspaceBodyClassSync()
			return result
		}
	}

	window.addEventListener('popstate', scheduleWorkspaceBodyClassSync)
	window.addEventListener('hashchange', scheduleWorkspaceBodyClassSync)
	window.addEventListener('pageshow', scheduleWorkspaceBodyClassSync)

	if (document.readyState === 'loading') {
		document.addEventListener(
			'DOMContentLoaded',
			syncWorkspaceBodyClass,
			{ once: true }
		)
	} else {
		syncWorkspaceBodyClass()
	}

	const coverSupport =
		'CSS' in window &&
		typeof CSS.supports === 'function' &&
		(CSS.supports('top: env(a)') || CSS.supports('top: constant(a)'))
	const viewport = document.createElement('meta')
	viewport.name = 'viewport'
	viewport.content =
		'width=device-width, user-scalable=no, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0' +
		(coverSupport ? ', viewport-fit=cover' : '')
	document.head.appendChild(viewport)
})()

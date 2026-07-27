(() => {
	'use strict'

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

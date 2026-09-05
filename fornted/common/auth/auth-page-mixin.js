import { requireAuthenticatedPage } from './page-guard.js'
import { isProtectedRoute, normalizeRoutePath } from './protected-routes.js'
import { isRuntimeTerminalSessionActive, runtimeAuthenticationVersion, runtimeSessionRequestGeneration } from './authenticated-session-state.js'

function currentRouteFromPages() {
	if (typeof getCurrentPages !== 'function') return ''
	const pages = getCurrentPages()
	const current = pages[pages.length - 1]
	if (!current) return ''
	const route = current.route || current.__route__ || current.$page?.route || ''
	return route ? `/${route}` : ''
}

function currentRoute(instance) {
	return normalizeRoutePath(
		instance?.$page?.fullPath ||
		instance?.$page?.route ||
		instance?.$mp?.page?.route ||
		instance?.$scope?.route ||
		instance?.$scope?.__route__ ||
		currentRouteFromPages()
	)
}

export default {
	data() {
		return {
			authReady: false,
			__aitAuthenticationInFlight: null,
			__aitAuthenticationGeneration: null,
			__aitAuthenticationVersion: -1
		}
	},
	onLoad() {
		this.__aitRequireAuthenticatedPage()
	},
	onShow() {
		this.__aitRequireAuthenticatedPage()
	},
	methods: {
		async __aitRequireAuthenticatedPage() {
			const route = currentRoute(this)
			if (!isProtectedRoute(route)) {
				this.authReady = true
				return true
			}
			const version = runtimeAuthenticationVersion()
			const generation = runtimeSessionRequestGeneration()
			if (!isRuntimeTerminalSessionActive() && this.authReady && this.__aitAuthenticationVersion === version) return true
			if (this.__aitAuthenticationInFlight && this.__aitAuthenticationGeneration === generation) return this.__aitAuthenticationInFlight

			const authentication = requireAuthenticatedPage(route)
				.then((allowed) => {
					if (generation !== runtimeSessionRequestGeneration()) return false
					allowed = allowed && !isRuntimeTerminalSessionActive()
					this.authReady = allowed
					this.__aitAuthenticationVersion = runtimeAuthenticationVersion()
					if (allowed && typeof this.onAuthenticatedPageReady === 'function') {
						this.onAuthenticatedPageReady()
					}
					return allowed
				})
				.finally(() => {
					if (this.__aitAuthenticationInFlight === authentication) this.__aitAuthenticationInFlight = null
				})

			this.__aitAuthenticationInFlight = authentication
			this.__aitAuthenticationGeneration = generation
			return authentication
		}
	}
}

import { requireAuthenticatedPage } from './page-guard.js'
import { isProtectedRoute, normalizeRoutePath } from './protected-routes.js'

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
		return { authReady: false }
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
			const allowed = await requireAuthenticatedPage(route)
			this.authReady = allowed
			if (allowed && typeof this.onAuthenticatedPageReady === 'function') {
				this.onAuthenticatedPageReady()
			}
			return allowed
		}
	}
}

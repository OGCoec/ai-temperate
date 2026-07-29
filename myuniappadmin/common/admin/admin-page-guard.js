import { guardAdminPage } from './admin-route-guard-runtime.js'

/**
 * 管理员受保护页面的生命周期适配器，确保业务组件和首个网络请求都在
 * 会话验证成功后才启动；它不负责业务数据加载，也不改变后端授权结果。
 */
export function createAdminPageGuardMixin(route) {
	return {
		data() {
			return {
				guardLoading: true,
				adminRouteReady: false,
				adminRoutePromise: null
			}
		},
		methods: {
			ensureAdminRouteAccess() {
				if (!this.adminRoutePromise) {
					this.guardLoading = true
					this.adminRoutePromise = guardAdminPage(route)
						.then(allowed => {
							this.adminRouteReady = allowed === true
							return this.adminRouteReady
						})
						.catch(() => {
							this.adminRouteReady = false
							return false
						})
						.finally(() => {
							this.guardLoading = false
							this.adminRoutePromise = null
						})
				}
				return this.adminRoutePromise
			},
			runAfterAdminRouteGuard(action) {
				return this.ensureAdminRouteAccess()
					.then(allowed => allowed ? action() : undefined)
			}
		}
	}
}

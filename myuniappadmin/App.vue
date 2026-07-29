<script>
	import {
		ensureAdminCookieScopeMigration
	} from '@/common/admin/admin-cookie-scope-migration.js'
	import { ensureAdminPreAuth } from '@/common/admin/admin-pre-auth.js'
	import {
		presentAdminRiskBlock
	} from '@/common/admin/admin-risk-block-navigation.js'
	import {
		isAdminRiskChallengeFlowPage
	} from '@/common/admin/admin-risk-challenge-navigation.js'
	// #ifdef H5
	import {
		ensureAdminWebRtcVerified,
		presentAdminWebRtcFailure
	} from '@/common/admin/admin-webrtc-verification.js'
	// #endif

	export default {
		onLaunch(options) {
			if (!isAdminRiskChallengeFlowPage(options?.path)) {
				// 管理员认证先完成父域 Cookie 清理，再建立独立 Host-only PreAuth。
				void ensureAdminCookieScopeMigration().catch(() => {})
				// #ifdef H5
				// 与普通端保持一致：启动时只执行一次 PreAuth 与 WebRTC 校验，后续网络变化由业务请求按需发现。
				void ensureAdminPreAuth()
					.then(() => ensureAdminWebRtcVerified())
					.catch(error => {
						if (presentAdminRiskBlock(error)) return
						presentAdminWebRtcFailure(error)
					})
				// #endif
				// #ifndef H5
				void ensureAdminPreAuth().catch(error => {
					presentAdminRiskBlock(error)
				})
				// #endif
			}
		},
		onShow() {},
		onHide() {}
	}
</script>

<style>
	/* 原生页面、H5 根节点和 uni-app 页面容器必须在任何模块加载前保持同一深色画布。 */
	html,
	body,
	#app,
	uni-app,
	uni-page,
	uni-page-wrapper,
	uni-page-body,
	page {
		min-height: 100%;
		background: #080b0d;
		color: #f2f7f7;
	}

	body {
		margin: 0;
	}
</style>

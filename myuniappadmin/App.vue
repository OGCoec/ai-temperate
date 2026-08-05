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
	import {
		startAdminWebRtcVerificationInBackground,
		presentAdminWebRtcFailure
	} from '@/common/admin/admin-webrtc-verification.js'

	export default {
		onLaunch(options) {
			if (!isAdminRiskChallengeFlowPage(options?.path)) {
				// 管理员认证先完成父域 Cookie 清理，再建立独立 Host-only PreAuth。
				void ensureAdminCookieScopeMigration().catch(() => {})
				// 普通端和管理员端都在 PreAuth 建立后后台探测，页面和业务请求不等待 Report。
				void ensureAdminPreAuth()
					.then(() => startAdminWebRtcVerificationInBackground())
					.catch(error => {
						if (presentAdminRiskBlock(error)) return
						presentAdminWebRtcFailure(error)
					})
			}
		}
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

	/* #ifdef H5 */
	* {
		scrollbar-width: thin;
		scrollbar-color: rgba(139, 156, 154, .46) transparent;
	}

	*::-webkit-scrollbar {
		width: 6px;
		height: 6px;
		background: transparent;
	}

	*::-webkit-scrollbar-track {
		background: transparent;
	}

	*::-webkit-scrollbar-corner {
		background: transparent;
	}

	*::-webkit-scrollbar-thumb {
		min-height: 32px;
		border: 1px solid transparent;
		border-radius: 999px;
		background: rgba(139, 156, 154, .46);
		background-clip: padding-box;
	}

	*::-webkit-scrollbar-thumb:hover {
		background: rgba(160, 180, 177, .68);
		background-clip: padding-box;
	}

	*::-webkit-scrollbar-button,
	*::-webkit-scrollbar-button:single-button {
		display: none;
		width: 0;
		height: 0;
		background: transparent;
	}
	/* #endif */
</style>

<script>
	import { version } from './package.json'
	// #ifdef H5
	import { ensureCookieScopeMigration } from '@/common/auth/cookie-scope-migration.js'
	import { ensurePreAuth } from '@/common/auth/pre-auth.js'
	import { presentRiskBlock } from '@/common/auth/risk-block-navigation.js'
	import { isRiskChallengeFlowPage } from '@/common/auth/risk-challenge-navigation.js'
	import {
		ensureWebRtcVerified,
		presentWebRtcFailure
	} from '@/common/auth/webrtc-verification.js'
	import { prewarmTurnstile } from '@/common/auth/turnstile-prewarm.js'
	// #endif
	// #ifdef APP
	import checkUpdate from '@/uni_modules/uni-upgrade-center-app/utils/check-update'
	// #endif

	export default {
		onLaunch(options) {
			// #ifdef H5
			console.log(
				`%c AI Temperate %c v${version} `,
				'background:#10251d;padding:1px;border-radius:3px 0 0 3px;color:#dff8ed',
				'background:#37d39a;padding:1px;border-radius:0 3px 3px 0;color:#04110c;font-weight:bold'
			)
			if (!isRiskChallengeFlowPage(options?.path)) {
				// 所有认证请求共享迁移和 PreAuth Promise，启动阶段不弹出重复错误。
				void ensureCookieScopeMigration().catch(() => {})
				void ensurePreAuth()
					.then(() => ensureWebRtcVerified())
					.catch(error => {
						if (presentRiskBlock(error)) return
						presentWebRtcFailure(error)
					})
				// 启动阶段只预热官方 SDK，真实挑战仍等待后端下发。
				void prewarmTurnstile()
			}
			// #endif
			console.log('App Launch')
			// #ifdef APP-PLUS
			if (plus.runtime.appid !== 'HBuilder') {
				checkUpdate()
			}
			// #endif
		},
		onShow() {
			console.log('App Show')
		},
		onHide() {
			console.log('App Hide')
		},
		globalData: {
			test: ''
		}
	}
</script>

<style lang="scss">
	@import '@/uni_modules/uni-scss/index.scss';
	@import './common/app-theme.scss';
	/* #ifndef APP-PLUS-NVUE */
	/* #ifdef H5 */
	@media screen and (min-width: 768px) {
		body {
			overflow-y: scroll;
		}
	}

	uni-page-body {
		background-color: #0b0d0c !important;
		min-height: 100% !important;
		height: auto !important;
	}
	/* #endif */

	page {
		background-color: #0b0d0c;
		color: #f3f5f4;
		height: 100%;
		font-size: 28rpx;
	}
	/* #endif*/
</style>

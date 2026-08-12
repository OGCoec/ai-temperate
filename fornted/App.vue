<script>
	import { version } from './package.json'
	import { ensurePreAuth } from '@/common/auth/pre-auth.js'
	import { presentRiskBlock } from '@/common/auth/risk-block-navigation.js'
	import { isRiskChallengeFlowPage } from '@/common/auth/risk-challenge-navigation.js'
	import { presentWebRtcFailure } from '@/common/auth/webrtc-verification.js'
	// #ifdef H5
	import { ensureCookieScopeMigration } from '@/common/auth/cookie-scope-migration.js'
	import {
		ensureH5WebRtcVerified
	} from '@/common/auth/webrtc-verification.js'
	import { prewarmTurnstile } from '@/common/auth/turnstile-prewarm.js'
	// #endif
	// #ifdef APP-PLUS
	import {
		presentAndroidEdgeChallengeFailure
	} from '@/common/auth/android-edge-challenge.js'
	import {
		startAndroidWebRtcVerificationInBackground
	} from '@/common/auth/webrtc-verification.js'
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
			// #endif
			if (!isRiskChallengeFlowPage(options?.path)) {
				// #ifdef H5
				// H5 保留浏览器 Cookie、PreAuth 和 RTCPeerConnection 的既有执行顺序。
				void ensureCookieScopeMigration().catch(() => {})
				void ensurePreAuth()
					.then(() => ensureH5WebRtcVerified())
					.catch(error => {
						if (presentRiskBlock(error)) return
						presentWebRtcFailure(error)
					})
				// 启动阶段只预热官方 SDK，真实挑战仍等待后端下发。
				void prewarmTurnstile()
				// #endif
				// #ifdef APP-PLUS
				// Android 只启动屏幕外本地 WebView 探测，不允许回退到 H5 浏览器实现。
				void ensurePreAuth()
					.then(() => startAndroidWebRtcVerificationInBackground())
					.catch(error => {
						if (presentRiskBlock(error)) return
						if (presentWebRtcFailure(error)) return
						presentAndroidEdgeChallengeFailure(error)
					})
				// #endif
			}
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

		body.ait-workspace-active {
			overflow: hidden;
		}
	}

	uni-page-body {
		background-color: #0b0d0c !important;
		min-height: 100% !important;
		height: auto !important;
	}

	* {
		scrollbar-width: thin;
		scrollbar-color: rgba(135, 148, 141, .46) transparent;
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
		background: rgba(135, 148, 141, .46);
		background-clip: padding-box;
	}

	*::-webkit-scrollbar-thumb:hover {
		background: rgba(155, 169, 161, .68);
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

	page {
		background-color: #0b0d0c;
		color: #f3f5f4;
		height: 100%;
		font-size: 28rpx;
	}
	/* #endif*/
</style>

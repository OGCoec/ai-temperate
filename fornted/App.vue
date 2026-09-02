<script>
	import { version } from './package.json'
	import { ensurePreAuth } from '@/common/auth/pre-auth.js'
	import { presentRiskBlock } from '@/common/auth/risk-block-navigation.js'
	import { isRiskChallengeFlowPage } from '@/common/auth/risk-challenge-navigation.js'
	import {
		cancelActiveWebRtcVerification,
		installH5WebRtcDiagnosticLifecycle,
		presentWebRtcFailure
	} from '@/common/auth/webrtc-verification.js'
	import {
		installH5AuthDiagnosticLifecycle,
		recordAuthDiagnosticEvent,
		renewAuthDiagnosticPage
	} from '@/common/auth/auth-diagnostics.js'

	// #ifdef H5
	import { ensureCookieScopeMigration } from '@/common/auth/cookie-scope-migration.js'
	import {
		scheduleH5WebRtcVerification
	} from '@/common/auth/webrtc-verification.js'
	import { prewarmTurnstile } from '@/common/auth/turnstile-prewarm.js'
	import { ownsH5WebRtcScheduling } from '@/common/auth/h5-oauth-webrtc-gate.js'
	import {
		hasPendingH5OAuthWebRtcVerdict,
		settlePendingH5OAuthWebRtcVerdict
	} from '@/common/auth/oauth-flow.js'
	// #endif
	// #ifdef APP-PLUS
	import {
		presentAndroidEdgeChallengeFailure
	} from '@/common/auth/android-edge-challenge.js'
	import {
		startAndroidWebRtcVerificationInBackground
	} from '@/common/auth/webrtc-verification.js'
	import { resumePendingOAuth } from '@/common/auth/oauth-flow.js'
	import {
		androidOAuthCoordinator,
		isBlockingWebRtc as isAndroidOAuthBlockingWebRtc
	} from '@/common/auth/android-oauth-coordinator.js'
	import { loadAndroidOAuthFlow } from '@/common/auth/android-flow-keystore.js'
	// #endif
	// #ifdef APP
	import checkUpdate from '@/uni_modules/uni-upgrade-center-app/utils/check-update'
	// #endif

	function isH5OAuthReturnPath(path) {
		const normalized = `/${String(path || '')}`
			.split(/[?#]/, 1)[0]
			.replace(/^\/+/, '/')
		return normalized === '/pages/auth/oauth-return'
	}

	function shouldScheduleInitialH5WebRtc() {
		// #ifdef H5
		return !ownsH5WebRtcScheduling()
		// #endif
		// #ifndef H5
		return true
		// #endif
	}

	export default {
		onLaunch(options) {
			renewAuthDiagnosticPage(options?.path || '', 'app_launch')
			// #ifdef H5
			// WebRTC 监听器先注册，使 pagehide 时的活动快照能被认证日志监听器一并同步落盘。
			installH5WebRtcDiagnosticLifecycle()
			installH5AuthDiagnosticLifecycle()
			console.log(
				`%c Eagle AI %c v${version} `,
				'background:#10251d;padding:1px;border-radius:3px 0 0 3px;color:#dff8ed',
				'background:#37d39a;padding:1px;border-radius:0 3px 3px 0;color:#04110c;font-weight:bold'
			)
			// #endif
			let h5OAuthReturn = false
			let h5OAuthPending = false
			// #ifdef H5
			h5OAuthReturn = isH5OAuthReturnPath(options?.path)
			h5OAuthPending = hasPendingH5OAuthWebRtcVerdict()
			// #endif
			if (h5OAuthReturn) {
				recordAuthDiagnosticEvent('OAUTH_WEBRTC_GATE_SKIPPED', {
					path: '/pages/auth/oauth-return',
					source: 'app_launch_background_probe',
					outcome: 'oauth_callback_gate_owns_probe'
				})
			}
			// #ifdef H5
			if (h5OAuthPending) {
				void settlePendingH5OAuthWebRtcVerdict().catch(() => {})
			}
			// #endif
			if (!isRiskChallengeFlowPage(options?.path)
				&& !h5OAuthReturn
				&& shouldScheduleInitialH5WebRtc()) {
				// #ifdef H5
				// H5 只等待 PreAuth；RTCPeerConnection 探测由 single-flight 在后台完成。
				void ensureCookieScopeMigration()
					.then(() => ensurePreAuth())
					.then(() => scheduleH5WebRtcVerification({
						path: options?.path || '',
						source: 'app_launch'
					}))
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
					.then(() => {
						// 持久化 OAuth Flow 或活动原生回调拥有优先权，避免探测改写其 PreAuth/epoch。
						if (isAndroidOAuthBlockingWebRtc() || loadAndroidOAuthFlow()) return null
						return startAndroidWebRtcVerificationInBackground()
					})
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
			recordAuthDiagnosticEvent('PAGE_SHOW', {
				source: 'app_show',
				pageState: 'active'
			})
			console.log('App Show')
			// #ifdef H5
			if (hasPendingH5OAuthWebRtcVerdict()) {
				void settlePendingH5OAuthWebRtcVerdict().catch(() => {})
			}
			// #endif
			// #ifdef APP-PLUS
			// 原生回调和 onShow 只加入同一个 Promise；没有活动操作时才从 KeyStore 恢复待处理 Flow。
			const activeOAuth = androidOAuthCoordinator.join('google-native')
			const oauthResume = activeOAuth || resumePendingOAuth()
			void Promise.resolve(oauthResume)
				.catch(error => {
					uni.showToast({
						title: error?.message || '第三方登录恢复失败',
						icon: 'none'
					})
				})
				.finally(() => {
					// OAuth 完成并释放协调器后，才允许用当前 PreAuth 启动后台探测。
					if (isAndroidOAuthBlockingWebRtc() || loadAndroidOAuthFlow()) return
					void ensurePreAuth()
						.then(() => startAndroidWebRtcVerificationInBackground())
						.catch(error => {
							if (presentRiskBlock(error)) return
							if (presentWebRtcFailure(error)) return
							presentAndroidEdgeChallengeFailure(error)
						})
				})
			// #endif
		},
		onHide() {
			// #ifdef APP-PLUS
			// 只终止 WebRTC 隐藏 WebView，不干预 Credential Manager 或 OAuth 回调。
			cancelActiveWebRtcVerification('APP_HIDDEN')
			// #endif
			recordAuthDiagnosticEvent('PAGE_HIDE', {
				source: 'app_hide',
				pageState: 'hidden'
			})
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
	@import '@/uni_modules/uni-icons/components/uni-icons/uniicons.css';

	/* HBuilderX 导出 Web 时可能漏掉按需组件样式；在应用入口固定声明图标字体。 */
	@font-face {
		font-family: uniicons;
		src: url('@/uni_modules/uni-icons/components/uni-icons/uniicons.ttf') format('truetype');
		font-display: block;
	}

	.uni-icons {
		font-family: uniicons;
	}

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

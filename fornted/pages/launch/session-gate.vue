<template>
	<view
		class="session-gate"
		role="status"
		aria-live="polite"
		:aria-busy="restoring"
		:style="{
			'--eagle-shimmer-delay': shimmerDelay,
			'--eagle-shimmer-play-state': shimmerPlayState
		}"
	>
		<view class="session-mark">
			<view class="session-mark-core"></view>
		</view>
		<text class="session-kicker">WELCOME BACK</text>
		<text class="session-title">{{ unavailable ? '暂时无法连接' : '正在恢复会话' }}</text>
		<text class="session-description">{{ unavailable ? '请检查网络后重新尝试。' : '正在安全确认登录状态，请稍候。' }}</text>
		<button v-if="unavailable" class="session-retry" type="button" @click="restoreSession">重新尝试</button>
	</view>
</template>

<script>
	import { AUTH_ROUTES } from '@/common/auth/config.js'
	import { ensureDeviceInstallationId } from '@/common/auth/device-installation.js'
	import { assertAuthorizedSessionCurrent, clearTerminalSessionState, redirectTerminalSessionToLogin, restorePersistedSession } from '@/common/auth/http-client.js'
	import { isTerminalSessionError, SessionRequestPurpose } from '@/common/auth/session-retry-policy.js'
	import { clearSession } from '@/common/auth/session-vault.js'
	import {
		beginRuntimeTerminalSessionTransition,
		isRuntimeTerminalSessionActive,
		markRuntimeSessionAuthenticated,
		runtimeSessionRequestGeneration
	} from '@/common/auth/authenticated-session-state.js'
	import {
		dismissNativeSplashAfterPaint,
		getNativeSplashCycleOffsetMillis
	} from '@/common/launch/eagle-native-splash.js'
	import {
		clearCurrentUserProfile,
		loadCurrentUserProfile
	} from '@/common/user/current-user-profile.js'

	export default {
		data() {
			return {
				routing: false,
				unavailable: false,
				restoring: false,
				restoringGeneration: null,
				shimmerDelay: '0ms',
				shimmerPlayState: 'paused',
				frontendShimmerStarted: false,
				nativeSplashDismissScheduled: false
			}
		},
		onLoad() {
			this.restoreSession()
		},
		onReady() {
			this.startFrontendShimmer()
		},
		methods: {
			startFrontendShimmer() {
				if (this.frontendShimmerStarted) return
				this.frontendShimmerStarted = true

				const cycleOffset = getNativeSplashCycleOffsetMillis()
				this.shimmerDelay = `-${cycleOffset}ms`
				this.shimmerPlayState = 'running'
			},
			revealSessionGate(reason) {
				if (this.nativeSplashDismissScheduled) return
				this.nativeSplashDismissScheduled = true
				this.startFrontendShimmer()

				// 当前页面不切换 WebView，连续提交两帧即可安全交接错误内容和重试按钮。
				this.$nextTick(() => {
					dismissNativeSplashAfterPaint(reason)
				})
			},
			async restoreSession() {
				const sessionGeneration = runtimeSessionRequestGeneration()
				if ((this.restoring || this.routing) && this.restoringGeneration === sessionGeneration) return
				this.restoringGeneration = sessionGeneration
				this.routing = false
				this.restoring = true
				this.unavailable = false
				try {
					assertAuthorizedSessionCurrent(sessionGeneration)
					await ensureDeviceInstallationId()
					assertAuthorizedSessionCurrent(sessionGeneration)
					const restored = await restorePersistedSession(null, sessionGeneration)
					assertAuthorizedSessionCurrent(sessionGeneration)
					if (!restored) {
						const error = new Error('SESSION_NOT_FOUND')
						error.code = 'SESSION_NOT_FOUND'
						throw error
					}
					await loadCurrentUserProfile({ force: true })
					assertAuthorizedSessionCurrent(sessionGeneration)
					markRuntimeSessionAuthenticated()
					this.go(AUTH_ROUTES.home)
				} catch (error) {
					if (sessionGeneration !== runtimeSessionRequestGeneration()) return
					if (isTerminalSessionError(error, SessionRequestPurpose.SESSION_RECOVERY) || error?.code === 'SESSION_NOT_FOUND' || isRuntimeTerminalSessionActive()) {
						clearTerminalSessionState(error, { source: 'session_gate' }, sessionGeneration, SessionRequestPurpose.SESSION_RECOVERY)
						if (beginRuntimeTerminalSessionTransition()) {
							clearCurrentUserProfile()
							clearSession()
						}
						this.routing = true
						const redirected = redirectTerminalSessionToLogin(error, { source: 'session_gate' }, sessionGeneration, () => {
							this.routing = false
							this.unavailable = true
							this.revealSessionGate('route-failed')
						})
						if (!redirected) this.routing = false
					} else {
						this.unavailable = true
						this.revealSessionGate('session-error')
					}
				} finally {
					if (this.restoringGeneration === sessionGeneration) this.restoring = false
				}
			},
			go(url) {
				if (this.routing) return
				this.routing = true
				uni.reLaunch({
					url,
					fail: () => {
						this.routing = false
						this.unavailable = true
						this.revealSessionGate('route-failed')
					}
				})
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/ui/user-material.scss';

	.session-gate {
		@include user-safe-viewport;
		box-sizing: border-box;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		padding: 32px 20px calc(32px + env(safe-area-inset-bottom));
		background: #0b0d0c;
		color: #f3f5f4;
	}

	.session-mark {
		position: relative;
		isolation: isolate;
		width: 132px;
		height: 132px;
		margin-bottom: 30px;
		overflow: hidden;
		border: 1px solid rgba(108, 255, 200, .24);
		border-radius: 38px;
		background: #0b0d0c;
		box-shadow:
			0 22px 54px rgba(0, 0, 0, .36),
			0 0 0 1px rgba(85, 241, 184, .04),
			inset 0 1px rgba(255, 255, 255, .08);

		&::before {
			content: '';
			position: absolute;
			z-index: 0;
			inset: -58%;
			background: linear-gradient(
				115deg,
				transparent calc(50% - 38px),
				rgba(104, 255, 200, .06) calc(50% - 19px),
				rgba(132, 255, 211, .76) 50%,
				rgba(104, 255, 200, .06) calc(50% + 19px),
				transparent calc(50% + 38px)
			);
			transform: translate3d(-46%, 0, 0);
			animation: eagle-shimmer-sweep 1.9s cubic-bezier(.4, 0, .2, 1) infinite;
			animation-delay: var(--eagle-shimmer-delay, 0ms);
			animation-play-state: var(--eagle-shimmer-play-state, paused);
		}

		&::after {
			content: '';
			position: absolute;
			z-index: 3;
			inset: 0;
			border-radius: inherit;
			box-shadow:
				inset 0 0 18px rgba(84, 246, 185, .12),
				0 0 24px rgba(55, 211, 154, .08);
			animation: eagle-halo-pulse 1.9s ease-in-out infinite;
			animation-delay: var(--eagle-shimmer-delay, 0ms);
			animation-play-state: var(--eagle-shimmer-play-state, paused);
			pointer-events: none;
		}
	}

	.session-mark-core {
		position: absolute;
		z-index: 1;
		inset: 8px;
		overflow: hidden;
		border: 1px solid rgba(113, 255, 203, .16);
		border-radius: 31px;
		background: #0b0d0c;
		box-shadow:
			inset 0 0 28px rgba(71, 229, 171, .09),
			0 0 24px rgba(48, 215, 157, .08);

		&::before {
			content: '';
			position: absolute;
			z-index: 0;
			inset: -60%;
			background: linear-gradient(
				115deg,
				transparent calc(50% - 38px),
				rgba(92, 248, 190, .05) calc(50% - 19px),
				rgba(109, 255, 201, .50) 50%,
				rgba(92, 248, 190, .05) calc(50% + 19px),
				transparent calc(50% + 38px)
			);
			transform: translate3d(-46%, 0, 0);
			animation: eagle-shimmer-sweep 1.9s cubic-bezier(.4, 0, .2, 1) infinite;
			animation-delay: var(--eagle-shimmer-delay, 0ms);
			animation-play-state: var(--eagle-shimmer-play-state, paused);
		}

		&::after {
			content: '';
			position: absolute;
			z-index: 2;
			inset: 12px;
			background: center / contain no-repeat url('../../static/branding/eagle-mark.png');
			filter: drop-shadow(0 0 12px rgba(255, 255, 255, .12));
			pointer-events: none;
		}
	}

	.session-kicker {
		font-size: 13px;
		font-weight: 700;
		letter-spacing: 2px;
		color: #37d39a;
		margin-bottom: 12px;
	}

	.session-title {
		font-size: 28px;
		font-weight: 700;
		line-height: 1.25;
	}

	.session-description {
		margin-top: 12px;
		font-size: 15px;
		line-height: 1.6;
		color: #8b9690;
	}

	.session-retry {
		@include user-frosted-control;
		min-width: 132px;
		min-height: 48px;
		margin-top: 24px;
		border: 1px solid rgba(123, 238, 190, .36);
		border-radius: 14px;
		background: rgba(55, 211, 154, .82);
		color: #0b0d0c;
		font-size: 16px;
		font-weight: 700;
	}

	@keyframes eagle-shimmer-sweep {
		0%, 6% { transform: translate3d(-46%, 0, 0); }
		88%, 100% { transform: translate3d(46%, 0, 0); }
	}

	@keyframes eagle-halo-pulse {
		0%, 18%, 100% { opacity: .46; }
		52% { opacity: 1; }
	}

	@media (prefers-reduced-motion: reduce) {
		.session-mark::before,
		.session-mark-core::before {
			animation: none;
			transform: translate3d(-46%, 0, 0);
		}

		.session-mark::after {
			animation: none;
			opacity: .46;
		}
	}
</style>

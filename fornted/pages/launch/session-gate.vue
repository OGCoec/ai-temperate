<template>
	<view class="session-gate" role="status" aria-live="polite" :aria-busy="restoring">
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
	import { restorePersistedSession } from '@/common/auth/http-client.js'
	import { clearSession } from '@/common/auth/session-vault.js'
	import { markRuntimeSessionAuthenticated } from '@/common/auth/authenticated-session-state.js'
	import {
		clearCurrentUserProfile,
		loadCurrentUserProfile
	} from '@/common/user/current-user-profile.js'

	const TERMINAL_SESSION_ERRORS = new Set([
		'SESSION_NOT_FOUND',
		'AT_REQUIRED',
		'AT_INVALID',
		'REFRESH_TOKEN_REQUIRED',
		'REFRESH_TOKEN_INVALID',
		'SESSION_MISMATCH',
		'DEVICE_MISMATCH',
		'CSRF_INVALID',
		'ACCOUNT_UNAVAILABLE'
	])

	export default {
		data() {
			return { routing: false, unavailable: false, restoring: false }
		},
		onLoad() {
			this.restoreSession()
		},
		methods: {
			async restoreSession() {
				if (this.restoring || this.routing) return
				this.restoring = true
				this.unavailable = false
				try {
					const restored = await restorePersistedSession()
					if (!restored) {
						const error = new Error('SESSION_NOT_FOUND')
						error.code = 'SESSION_NOT_FOUND'
						throw error
					}
					await loadCurrentUserProfile({ force: true })
					markRuntimeSessionAuthenticated()
					this.go(AUTH_ROUTES.home)
				} catch (error) {
					if (TERMINAL_SESSION_ERRORS.has(error?.code)) {
						clearCurrentUserProfile()
						clearSession()
						this.go(AUTH_ROUTES.login)
					} else {
						this.unavailable = true
					}
				} finally {
					this.restoring = false
				}
			},
			go(url) {
				if (this.routing) return
				this.routing = true
				uni.reLaunch({ url })
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
		@include user-frosted-surface;
		width: 58px;
		height: 58px;
		border-radius: 18px;
		display: flex;
		align-items: center;
		justify-content: center;
		margin-bottom: 26px;
	}

	.session-mark-core {
		width: 16px;
		height: 16px;
		border-radius: 50%;
		background: #37d39a;
		animation: session-pulse 1.1s ease-in-out infinite alternate;
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

	@keyframes session-pulse {
		from { opacity: .45; transform: scale(.82); }
		to { opacity: 1; transform: scale(1); }
	}

	@media (prefers-reduced-motion: reduce) {
		.session-mark-core { animation: none; }
	}
</style>

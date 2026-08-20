<template>
	<view class="auth-page">
		<view class="auth-container oauth-return" :aria-busy="busy">
			<text class="auth-kicker">Secure sign-in</text>
			<text class="auth-title">正在完成登录</text>
			<text v-if="busy" class="auth-subtitle" role="status" aria-live="polite">
				正在核对第三方登录结果，请稍候…
			</text>
			<view v-else-if="error" class="auth-banner" role="alert" aria-live="assertive">
				{{ error }}
			</view>
			<button
				v-if="!busy && error"
				class="auth-button"
				type="button"
				@click="backToLogin"
			>
				返回登录页
			</button>
		</view>
	</view>
</template>

<script>
	import { AUTH_ROUTES } from '@/common/auth/config.js'
	import { authErrorMessage } from '@/common/auth/auth-error.js'
	import { completeH5OAuthReturn } from '@/common/auth/oauth-flow.js'

	export default {
		data() {
			return {
				busy: true,
				error: '',
				completionPromise: null
			}
		},
		onLoad() {
			void this.completeReturn()
		},
		methods: {
			completeReturn() {
				if (this.completionPromise) return this.completionPromise
				this.completionPromise = this.runCompletion()
				return this.completionPromise
			},
			async runCompletion() {
				this.busy = true
				this.error = ''
				try {
					await completeH5OAuthReturn()
				} catch (error) {
					this.error = authErrorMessage(error)
				} finally {
					this.busy = false
				}
			},
			backToLogin() {
				uni.reLaunch({ url: AUTH_ROUTES.login })
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/auth/auth.scss';
	.oauth-return { text-align: center; }
</style>

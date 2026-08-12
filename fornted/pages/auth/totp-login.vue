<template>
	<view class="totp-page">
		<view class="totp-card" :aria-busy="busy">
			<text class="totp-kicker">SECURITY CHECK</text>
			<text class="totp-title">二次认证</text>
			<text class="totp-subtitle">打开认证器应用，输入当前显示的 6 位动态验证码。</text>
			<view v-if="error" class="totp-error" role="alert" aria-live="assertive">{{ error }}</view>
			<view class="totp-field">
				<label class="totp-label" for="totp-login-code">动态验证码</label>
				<input
					id="totp-login-code"
					v-model.trim="code"
					class="totp-input"
					type="password"
					maxlength="6"
					inputmode="numeric"
					autocomplete="one-time-code"
					placeholder="6 位数字"
					:focus="focusCode"
					:aria-invalid="Boolean(error)"
					@confirm="verify"
				/>
				<text class="totp-help">{{ countdownText }}<template v-if="attemptsRemaining"> · 还可尝试 {{ attemptsRemaining }} 次</template></text>
			</view>
			<button class="totp-submit" type="button" :loading="busy" :disabled="busy || !validCode" @click="verify">
				{{ busy ? '正在验证…' : '验证并登录' }}
			</button>
			<button class="totp-cancel" type="button" :disabled="busy" @click="restartLogin">返回重新登录</button>
		</view>
	</view>
</template>

<script>
	import { authApi } from '@/common/auth/auth-api.js'
	import { authErrorMessage } from '@/common/auth/auth-error.js'
	import { AUTH_ROUTES, clientPlatform } from '@/common/auth/config.js'
	import { initializeBrowserCsrf } from '@/common/auth/http-client.js'
	import { clearTotpLoginFlow, loadTotpLoginFlow } from '@/common/auth/totp-login-flow.js'

	export default {
		data() {
			return {
				flow: null,
				code: '',
				busy: false,
				error: '',
				focusCode: true,
				remainingSeconds: 0,
				timer: null
			}
		},
		computed: {
			validCode() { return /^\d{6}$/.test(this.code) },
			attemptsRemaining() { return Number(this.flow?.attemptsRemaining || 0) },
			countdownText() {
				if (this.remainingSeconds <= 0) return '验证流程已过期'
				const minutes = Math.floor(this.remainingSeconds / 60)
				const seconds = String(this.remainingSeconds % 60).padStart(2, '0')
				return `${minutes}:${seconds} 后过期`
			}
		},
		onLoad() {
			this.flow = loadTotpLoginFlow()
			if (!this.flow) {
				this.restartLogin()
				return
			}
			this.updateCountdown()
			this.timer = setInterval(this.updateCountdown, 1000)
			if (clientPlatform() === 'H5') {
				initializeBrowserCsrf().catch(() => {
					this.error = 'CSRF 安全令牌初始化失败，请重新登录。'
				})
			}
		},
		onUnload() { clearInterval(this.timer) },
		methods: {
			updateCountdown() {
				const expiresAt = Date.parse(this.flow?.expiresAt || '')
				this.remainingSeconds = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000))
				if (this.remainingSeconds === 0) {
					clearInterval(this.timer)
					clearTotpLoginFlow()
				}
			},
			async verify() {
				if (this.busy || !this.validCode || this.remainingSeconds <= 0) return
				this.busy = true
				this.error = ''
				try {
					const result = await authApi.totpLoginVerify(this.code)
					if (result?.status !== 'AUTHENTICATED') throw new Error('二次认证未完成。')
					uni.reLaunch({
						url: AUTH_ROUTES.home,
						success: () => uni.showToast({ title: '登录成功', icon: 'success' })
					})
				} catch (error) {
					this.error = authErrorMessage(error)
					this.code = ''
					this.focusCode = false
					this.$nextTick(() => { this.focusCode = true })
					if (['TOTP_FLOW_EXPIRED', 'TOTP_ATTEMPTS_EXHAUSTED', 'TOTP_FLOW_FORBIDDEN']
						.includes(error?.code)) setTimeout(this.restartLogin, 900)
				} finally {
					this.busy = false
				}
			},
			restartLogin() {
				clearTotpLoginFlow()
				uni.reLaunch({ url: AUTH_ROUTES.login })
			}
		}
	}
</script>

<style lang="scss">
	.totp-page {
		min-height: 100vh;
		min-height: 100dvh;
		box-sizing: border-box;
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 48rpx 40rpx;
		background: #0b0d0c;
		color: #f1f5f3;
	}

	.totp-card {
		width: 100%;
		max-width: 760rpx;
		box-sizing: border-box;
		padding: 48rpx 40rpx;
		border: 1px solid rgba(151, 170, 160, .22);
		border-radius: 24rpx;
		background: #151816;
	}

	.totp-kicker { display: block; color: #37d39a; font-size: 22rpx; letter-spacing: 0.16em; }
	.totp-title { display: block; margin-top: 14rpx; font-size: 52rpx; font-weight: 700; }
	.totp-subtitle { display: block; margin-top: 16rpx; color: #aab6b0; font-size: 28rpx; line-height: 1.65; }
	.totp-error { margin-top: 28rpx; padding: 20rpx; border: 1px solid #774945; border-radius: 14rpx; color: #ffb4ac; background: #2b1918; }
	.totp-field { margin-top: 36rpx; }
	.totp-label { display: block; margin-bottom: 14rpx; color: #dbe4df; font-size: 26rpx; }
	.totp-input { height: 104rpx; padding: 0 30rpx; border: 1px solid #3a4640; border-radius: 20rpx; background: #171a18; color: #f4faf7; font-size: 42rpx; letter-spacing: 0.28em; }
	.totp-help { display: block; margin-top: 12rpx; color: #87938d; font-size: 24rpx; }
	.totp-submit, .totp-cancel { width: 100%; min-height: 92rpx; margin-top: 28rpx; display: flex; align-items: center; justify-content: center; border-radius: 20rpx; font-size: 28rpx; }
	.totp-submit { border: 0; color: #07110d; background: #37d39a; font-weight: 700; }
	.totp-cancel { margin-top: 16rpx; border: 1px solid #36413c; color: #b8c3bd; background: transparent; }
	.totp-submit:focus-visible, .totp-cancel:focus-visible, .totp-input:focus { outline: 3px solid rgba(55, 211, 154, 0.45); outline-offset: 3px; }
	.totp-submit[disabled], .totp-cancel[disabled] { opacity: 0.55; }
</style>

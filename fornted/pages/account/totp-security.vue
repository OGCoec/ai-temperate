<template>
	<view class="security-page">
		<scroll-view class="security-scroll" scroll-y>
			<view class="security-shell" :aria-busy="busy || loading">
				<text class="security-kicker">ACCOUNT SECURITY</text>
				<text class="security-title">二次认证</text>
				<text class="security-subtitle">使用认证器中每 30 秒更新的 6 位验证码保护账号登录。</text>

				<view v-if="error" class="security-banner" role="alert" aria-live="assertive">{{ error }}</view>

				<view v-if="loading" class="security-state" role="status">正在读取安全设置…</view>

				<template v-else-if="stage === 'OVERVIEW'">
					<view class="security-status-card">
						<view class="security-status-icon" :class="{ enabled }">
							<uni-icons :type="enabled ? 'locked-filled' : 'locked'" size="25" :color="enabled ? '#07130e' : '#8b9690'" />
						</view>
						<view class="security-status-copy">
							<text class="security-status-title">{{ enabled ? '已启用' : '未启用' }}</text>
							<text class="security-status-detail">{{ enabled ? '登录完成第一因子后，必须再输入认证器动态码。' : '当前登录只要求密码、邮箱码或短信码。' }}</text>
						</view>
					</view>
					<view class="security-actions">
						<button v-if="!enabled" class="security-primary" type="button" @click="beginAction('ENABLE')">开启二次认证</button>
						<template v-else>
							<button class="security-primary" type="button" @click="beginAction('ROTATE')">更换认证器密钥</button>
							<button class="security-danger" type="button" @click="beginAction('DISABLE')">关闭二次认证</button>
						</template>
					</view>
					<view class="security-note">
						<text>安全说明</text>
						<text>新密钥只暂存 10 分钟；未输入新认证器生成的验证码前，不会覆盖当前设置。</text>
					</view>
				</template>

				<template v-else-if="stage === 'REVERIFY'">
					<text class="security-step-title">先确认是你本人</text>
					<text class="security-step-copy">本次复验凭证仅可用于“{{ actionLabel }}”，5 分钟后过期。</text>
					<view class="security-tabs" role="tablist" aria-label="安全复验方式">
						<button v-for="method in verificationMethods" :key="method.value" type="button" role="tab" class="security-tab" :class="{ active: verificationMethod === method.value }" :aria-selected="verificationMethod === method.value" :disabled="busy" @click="selectVerificationMethod(method.value)">{{ method.label }}</button>
					</view>
					<phone-delivery-method
						v-if="phoneSupportsWhatsapp"
						v-model="phoneDeliveryMethod"
						control-id="totp-security-phone-delivery"
						:disabled="busy || codeSent"
					/>

					<template v-if="verificationMethod === 'PASSWORD'">
						<label class="security-label" for="totp-security-password">当前密码</label>
						<input id="totp-security-password" v-model="password" class="security-input" type="password" maxlength="72" autocomplete="current-password" placeholder="输入当前密码" @confirm="verifyPassword" />
						<button class="security-primary" type="button" :loading="busy" :disabled="busy || !password" @click="verifyPassword">继续</button>
					</template>

					<template v-else>
						<button v-if="!codeFlow" class="security-primary" type="button" :loading="busy" :disabled="busy" @click="startCodeFlow">开始{{ verificationMethodLabel }}复验</button>
						<auth-turnstile v-else-if="!humanVerified" ref="turnstile" action="login" :challenge="codeFlow.challengeHandle" @verified="verifyHuman" />
						<template v-else>
							<button v-if="!codeSent" class="security-primary" type="button" :loading="busy" :disabled="busy || cooldown > 0" @click="sendCode">{{ cooldown > 0 ? `${cooldown}s 后可重发` : `发送${verificationMethodLabel}验证码` }}</button>
							<template v-else>
								<label class="security-label" for="totp-security-factor-code">{{ verificationMethodLabel }}验证码</label>
								<input id="totp-security-factor-code" v-model.trim="factorCode" class="security-input code" type="password" maxlength="6" inputmode="numeric" autocomplete="one-time-code" placeholder="6 位数字" @confirm="verifyFactorCode" />
								<button class="security-primary" type="button" :loading="busy" :disabled="busy || !validFactorCode" @click="verifyFactorCode">验证</button>
							</template>
						</template>
					</template>
					<button class="security-secondary" type="button" :disabled="busy" @click="resetOverview">取消</button>
				</template>

				<template v-else-if="stage === 'CURRENT_TOTP'">
					<text class="security-step-title">验证当前认证器</text>
					<text class="security-step-copy">{{ action === 'ROTATE' ? '更换密钥前，输入当前认证器显示的验证码。' : '关闭二次认证前，输入当前认证器显示的验证码。' }}</text>
					<label class="security-label" for="totp-security-current-code">当前动态验证码</label>
					<input id="totp-security-current-code" v-model.trim="currentTotpCode" class="security-input code" type="password" maxlength="6" inputmode="numeric" autocomplete="one-time-code" placeholder="6 位数字" @confirm="continueWithCurrentTotp" />
					<button class="security-primary" :class="{ danger: action === 'DISABLE' }" type="button" :loading="busy" :disabled="busy || !validCurrentTotp" @click="continueWithCurrentTotp">{{ action === 'ROTATE' ? '生成新密钥' : '确认关闭' }}</button>
					<button class="security-secondary" type="button" :disabled="busy" @click="resetOverview">取消</button>
				</template>

				<template v-else-if="stage === 'SETUP' && setup">
					<text class="security-step-title">扫描新二维码</text>
					<text class="security-step-copy">二维码只在当前设备本地生成。请用认证器扫描，再输入它显示的 6 位验证码。</text>
					<view class="security-qr-wrap">
						<image v-if="qrDataUrl" class="security-qr" :src="qrDataUrl" mode="widthFix" aria-label="TOTP 设置二维码" />
						<view v-else class="security-qr-loading" role="status">正在生成二维码…</view>
					</view>
					<text class="security-secret-label">无法扫码时手动输入 Base32 密钥</text>
					<text class="security-secret" selectable>{{ setup.secretBase32 }}</text>
					<text class="security-expiry">{{ setupCountdownText }}</text>
					<label class="security-label" for="totp-security-new-code">新认证器动态验证码</label>
					<input id="totp-security-new-code" v-model.trim="newTotpCode" class="security-input code" type="password" maxlength="6" inputmode="numeric" autocomplete="one-time-code" placeholder="6 位数字" @confirm="confirmSetup" />
					<button class="security-primary" type="button" :loading="busy" :disabled="busy || !validNewTotp || setupRemainingSeconds <= 0" @click="confirmSetup">确认并{{ action === 'ENABLE' ? '开启' : '更换' }}</button>
					<button class="security-secondary" type="button" :disabled="busy" @click="resetOverview">暂不确认</button>
				</template>
			</view>
		</scroll-view>
	</view>
</template>

<script>
	import qrcode from 'qrcode-generator'
	import AuthTurnstile from '@/components/auth/auth-turnstile.vue'
	import PhoneDeliveryMethod from '@/components/auth/phone-delivery-method.vue'
	import { authErrorMessage } from '@/common/auth/auth-error.js'
	import { AUTH_ROUTES } from '@/common/auth/config.js'
	import { totpApi } from '@/common/auth/totp-api.js'
	import { loadCurrentUserProfile } from '@/common/user/current-user-profile.js'
	import { classifyPassword } from '@shared-auth/password-policy.js'

	export default {
		components: { AuthTurnstile, PhoneDeliveryMethod },
		data() {
			return {
				loading: true,
				busy: false,
				error: '',
				enabled: false,
				stage: 'OVERVIEW',
				action: null,
				verificationMethods: [
					{ value: 'PASSWORD', label: '密码' },
					{ value: 'EMAIL_CODE', label: '邮箱码' },
					{ value: 'SMS_CODE', label: '手机验证码' }
				],
				verificationMethod: 'PASSWORD',
				phone: null,
				phoneDeliveryMethod: 'SMS',
				password: '',
				stepUpToken: '',
				codeFlow: null,
				humanVerified: false,
				codeSent: false,
				factorCode: '',
				cooldown: 0,
				currentTotpCode: '',
				setup: null,
				qrDataUrl: '',
				newTotpCode: '',
				setupRemainingSeconds: 0,
				timer: null
			}
		},
		computed: {
			actionLabel() {
				return ({ ENABLE: '开启二次认证', ROTATE: '更换认证器密钥', DISABLE: '关闭二次认证' })[this.action] || ''
			},
			phoneSupportsWhatsapp() {
				const phone = String(this.phone || '').trim()
				return this.verificationMethod === 'SMS_CODE'
					&& /^\+[1-9]\d{7,14}$/.test(phone)
					&& !phone.startsWith('+86')
			},
			verificationMethodLabel() {
				if (this.verificationMethod === 'EMAIL_CODE') return '邮箱'
				if (this.verificationMethod === 'SMS_CODE') {
					return this.phoneDeliveryMethod === 'WHATSAPP' ? 'WhatsApp' : '短信'
				}
				return ''
			},
			validFactorCode() { return /^\d{6}$/.test(this.factorCode) },
			validCurrentTotp() { return /^\d{6}$/.test(this.currentTotpCode) },
			validNewTotp() { return /^\d{6}$/.test(this.newTotpCode) },
			setupCountdownText() {
				if (this.setupRemainingSeconds <= 0) return '待确认密钥已过期，请重新申请。'
				const minutes = Math.floor(this.setupRemainingSeconds / 60)
				const seconds = String(this.setupRemainingSeconds % 60).padStart(2, '0')
				return `请在 ${minutes}:${seconds} 内确认，超时不会修改原设置。`
			}
		},
		watch: {
			phoneSupportsWhatsapp(supported) {
				if (!supported) this.phoneDeliveryMethod = 'SMS'
			}
		},
		onLoad() {
			this.timer = setInterval(() => {
				if (this.cooldown > 0) this.cooldown -= 1
				if (this.setup) this.updateSetupCountdown()
			}, 1000)
			this.loadStatus()
			this.loadProfile()
		},
		onUnload() { clearInterval(this.timer) },
		methods: {
			async loadProfile() {
				try {
					const profile = await loadCurrentUserProfile()
					this.phone = profile?.phone || null
				} catch (_) {
					this.phone = null
					this.phoneDeliveryMethod = 'SMS'
				}
			},
			async loadStatus() {
				this.loading = true
				this.error = ''
				try {
					const result = await totpApi.status()
					this.enabled = result?.enabled === true
				} catch (error) {
					this.error = authErrorMessage(error, '二次认证状态暂时无法读取。')
				} finally {
					this.loading = false
				}
			},
			beginAction(action) {
				this.action = action
				this.stage = 'REVERIFY'
				this.phoneDeliveryMethod = 'SMS'
				this.error = ''
			},
			selectVerificationMethod(method) {
				if (this.busy || this.verificationMethod === method) return
				this.verificationMethod = method
				this.password = ''
				this.codeFlow = null
				this.humanVerified = false
				this.codeSent = false
				this.phoneDeliveryMethod = 'SMS'
				this.factorCode = ''
				this.cooldown = 0
				this.error = ''
			},
			async run(action) {
				if (this.busy) return null
				this.busy = true
				this.error = ''
				try { return await action() }
				catch (error) {
					this.error = authErrorMessage(error)
					return null
				} finally { this.busy = false }
			},
			async verifyPassword() {
				if (!this.password) return
				if (classifyPassword(this.password).utf8Bytes > 72) {
					this.error = '密码不得超过 72 个 UTF-8 字节。'
					return
				}
				const proof = await this.run(() => totpApi.reverifyPassword(this.action, this.password))
				if (proof?.stepUpToken) this.afterStepUp(proof.stepUpToken)
			},
			async startCodeFlow() {
				const flow = await this.run(() => totpApi.reverificationCodeStart(this.action, this.verificationMethod))
				if (flow) this.codeFlow = flow
			},
			async verifyHuman(token) {
				const result = await this.run(() => totpApi.reverificationCodeTurnstile(this.codeFlow, token))
				if (result?.accepted) this.humanVerified = true
				else this.$refs.turnstile?.resetAfterServerRejection('验证结果未被服务器确认，请重新验证。')
			},
			async sendCode() {
				const deliveryMethod = this.verificationMethod === 'SMS_CODE'
					? this.phoneDeliveryMethod
					: undefined
				const result = await this.run(() => totpApi.reverificationCodeSend(this.codeFlow, deliveryMethod))
				if (result?.accepted) {
					this.codeSent = true
					this.cooldown = 60
					uni.showToast({ title: `${this.verificationMethodLabel}验证码已发送`, icon: 'none' })
				}
			},
			async verifyFactorCode() {
				if (!this.validFactorCode) return
				const proof = await this.run(() => totpApi.reverificationCodeVerify(
					this.codeFlow, this.action, this.verificationMethod, this.factorCode))
				if (proof?.stepUpToken) this.afterStepUp(proof.stepUpToken)
			},
			afterStepUp(token) {
				this.stepUpToken = token
				if (this.action === 'ENABLE') this.generateSetup('')
				else this.stage = 'CURRENT_TOTP'
			},
			async continueWithCurrentTotp() {
				if (!this.validCurrentTotp) return
				if (this.action === 'ROTATE') {
					await this.generateSetup(this.currentTotpCode)
					return
				}
				uni.showModal({
					title: '关闭二次认证？',
					content: '关闭后，后续登录不再要求认证器验证码；成功后所有设备都需要重新登录。',
					confirmText: '确认关闭',
					confirmColor: '#d95d59',
					success: async result => {
						if (!result.confirm) return
						const changed = await this.run(() => totpApi.disable(this.stepUpToken, this.currentTotpCode))
						if (changed?.reauthenticationRequired) this.reauthenticate('二次认证已关闭，请重新登录。')
					}
				})
			},
			async generateSetup(currentTotpCode) {
				const setup = await this.run(() => totpApi.startSetup(this.action, this.stepUpToken, currentTotpCode))
				if (!setup) return
				this.setup = setup
				this.stage = 'SETUP'
				this.updateSetupCountdown()
				try {
					const qr = qrcode(0, 'M')
					qr.addData(setup.otpauthUri)
					qr.make()
					const svg = qr.createSvgTag(5, 10)
					this.qrDataUrl = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
				} catch (_) {
					this.error = '二维码生成失败，请使用下方 Base32 密钥手动添加。'
				}
			},
			updateSetupCountdown() {
				const expiresAt = Date.parse(this.setup?.expiresAt || '')
				this.setupRemainingSeconds = Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000))
			},
			async confirmSetup() {
				if (!this.validNewTotp || this.setupRemainingSeconds <= 0) return
				const changed = await this.run(() => totpApi.confirmSetup(this.setup.setupToken, this.newTotpCode))
				if (changed?.reauthenticationRequired) this.reauthenticate(
					this.action === 'ENABLE' ? '二次认证已开启，请重新登录。' : '认证器密钥已更换，请重新登录。')
			},
			reauthenticate(message) {
				uni.showToast({ title: message, icon: 'none', duration: 1800 })
				setTimeout(() => uni.reLaunch({ url: AUTH_ROUTES.login }), 500)
			},
			resetOverview() {
				this.stage = 'OVERVIEW'
				this.action = null
				this.verificationMethod = 'PASSWORD'
				this.phoneDeliveryMethod = 'SMS'
				this.password = ''
				this.stepUpToken = ''
				this.codeFlow = null
				this.humanVerified = false
				this.codeSent = false
				this.factorCode = ''
				this.cooldown = 0
				this.currentTotpCode = ''
				this.setup = null
				this.qrDataUrl = ''
				this.newTotpCode = ''
				this.setupRemainingSeconds = 0
				this.error = ''
			}
		}
	}
</script>

<style lang="scss">
	.security-page, .security-scroll { min-height: 100vh; background: #0b0d0c; color: #f3f5f4; }
	.security-shell { width: 100%; max-width: 760px; min-height: 100vh; margin: 0 auto; padding: 42px 24px 72px; box-sizing: border-box; }
	.security-kicker { display: block; color: #37d39a; font-size: 12px; font-weight: 700; letter-spacing: .16em; }
	.security-title { display: block; margin-top: 8px; font-size: 36px; font-weight: 760; }
	.security-subtitle { display: block; max-width: 580px; margin-top: 10px; color: #9aa59f; font-size: 15px; line-height: 1.65; }
	.security-banner { margin-top: 22px; padding: 14px 16px; border: 1px solid #754641; border-radius: 13px; color: #ffb4ac; background: #2a1917; }
	.security-state, .security-status-card, .security-note { margin-top: 28px; border: 1px solid #303733; border-radius: 18px; background: #121614; }
	.security-state { padding: 36px; color: #9aa59f; text-align: center; }
	.security-status-card { display: flex; align-items: center; gap: 16px; padding: 20px; }
	.security-status-icon { width: 48px; height: 48px; flex: 0 0 48px; display: flex; align-items: center; justify-content: center; border-radius: 15px; background: #252b28; }
	.security-status-icon.enabled { background: #37d39a; }
	.security-status-copy { min-width: 0; display: flex; flex-direction: column; }
	.security-status-title { font-size: 18px; font-weight: 700; }
	.security-status-detail, .security-step-copy { margin-top: 6px; color: #98a39d; font-size: 14px; line-height: 1.55; }
	.security-actions { display: flex; flex-direction: column; gap: 12px; margin-top: 24px; }
	.security-note { display: flex; flex-direction: column; gap: 7px; padding: 17px 18px; color: #8f9a94; font-size: 13px; line-height: 1.55; }
	.security-note text:first-child { color: #cdd6d1; font-weight: 700; }
	.security-step-title { display: block; margin-top: 32px; font-size: 23px; font-weight: 730; }
	.security-tabs { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 22px; padding: 5px; border-radius: 14px; background: #121614; }
	.security-tab { min-height: 44px; margin: 0; border: 0; border-radius: 10px; color: #9ca7a1; background: transparent; font-size: 14px; }
	.security-tab.active { color: #07130e; background: #37d39a; font-weight: 700; }
	.security-label { display: block; margin: 24px 0 10px; color: #d8e0dc; font-size: 14px; }
	.security-input { width: 100%; height: 54px; padding: 0 16px; box-sizing: border-box; border: 1px solid #3a4640; border-radius: 13px; background: #0d100f; color: #f3f5f4; font-size: 16px; }
	.security-input.code { font-size: 24px; letter-spacing: .24em; font-variant-numeric: tabular-nums; }
	.security-primary, .security-secondary, .security-danger { width: 100%; min-height: 50px; margin-top: 22px; display: flex; align-items: center; justify-content: center; border-radius: 14px; font-size: 16px; font-weight: 680; }
	.security-primary { border: 0; color: #07130e; background: #37d39a; }
	.security-primary.danger, .security-danger { border: 1px solid #d95d59; color: #ff9c95; background: rgba(217, 93, 89, .13); }
	.security-secondary { margin-top: 11px; border: 1px solid #36413c; color: #b6c0bb; background: transparent; }
	.security-primary[disabled], .security-secondary[disabled], .security-danger[disabled], .security-tab[disabled] { opacity: .52; }
	.security-primary::after, .security-secondary::after, .security-danger::after, .security-tab::after { border: 0; }
	.security-qr-wrap { width: 292px; min-height: 292px; margin: 26px auto 0; padding: 16px; box-sizing: border-box; display: flex; align-items: center; justify-content: center; border-radius: 20px; background: #fff; }
	.security-qr { width: 260px; }
	.security-qr-loading { color: #35413b; font-size: 14px; }
	.security-secret-label, .security-expiry { display: block; margin-top: 20px; color: #8e9993; font-size: 13px; text-align: center; }
	.security-secret { display: block; margin-top: 10px; padding: 14px; border: 1px dashed #4a5a52; border-radius: 12px; color: #d9e4de; background: #101311; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 14px; line-height: 1.55; text-align: center; word-break: break-all; }
	.security-expiry { color: #c7a46b; }
	.security-primary:focus-visible, .security-secondary:focus-visible, .security-danger:focus-visible, .security-tab:focus-visible, .security-input:focus { outline: 3px solid rgba(55, 211, 154, .35); outline-offset: 3px; }
	@media screen and (min-width: 768px) { .security-shell { padding-top: 58px; } }
	@media (prefers-reduced-motion: reduce) { .security-primary, .security-secondary, .security-danger, .security-tab { transition: none; } }
</style>

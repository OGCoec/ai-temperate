<template>
	<view class="auth-page">
		<view class="auth-container" :aria-busy="busy">
			<text class="auth-kicker">One more step</text>
			<text class="auth-title">验证手机号</text>
			<text class="auth-subtitle">首次使用第三方登录或账号尚未绑定手机号时，需要先完成手机验证。</text>
			<view v-if="error" class="auth-banner" role="alert" aria-live="assertive">{{ error }}</view>

			<template v-if="!humanVerified">
				<identifier-fields
					v-if="!flow"
					ref="identifierFields"
					type="PHONE"
					:errors="fieldErrors"
					:focus-field="focusedField"
					:country-id="countryId"
					:country-resolving="countryResolving"
					v-model:phone="phoneNumber"
					@update:country-id="handleCountryUserSelection"
					@field-blur="validatePhone"
					@country-picker-visibility-change="countryPickerOpen = $event"
				/>
				<button
					v-if="!flow"
					class="auth-button"
					type="button"
					:loading="busy"
					:disabled="busy"
					:aria-busy="busy"
					@click="startPhoneFlow"
				>
					继续验证
				</button>
				<auth-turnstile
					v-else
					ref="turnstile"
					action="oauth_phone"
					:challenge="flow.turnstileChallenge"
					:page-scroll-top="turnstilePageScrollTop"
					@verified="verifyHuman"
				/>
			</template>

			<template v-else>
				<verification-identity-summary :phone-presentation="lockedPhonePresentation" />
				<view class="auth-links">
					<button class="auth-link" type="button" :disabled="busy" @click="changePhone">更换手机号</button>
				</view>
				<view class="auth-field">
					<label class="auth-label" for="oauth-phone-code">手机验证码</label>
					<view class="auth-control" :class="{ invalid: fieldErrors.code }">
						<input
							id="oauth-phone-code"
							v-model.trim="code"
							class="auth-control-input"
							type="password"
							maxlength="6"
							inputmode="numeric"
							autocomplete="one-time-code"
							placeholder="6 位数字"
							:focus="focusedField === 'code'"
							:aria-invalid="Boolean(fieldErrors.code)"
							:aria-describedby="fieldErrors.code ? 'oauth-phone-code-error' : 'oauth-phone-code-help'"
							@blur="validateCode"
						/>
					</view>
					<text id="oauth-phone-code-help" class="auth-help">
						{{ sent ? `${deliveryLabel}验证码已发送，5 分钟内有效。` : `点击下方按钮发送${deliveryLabel}验证码。` }}
					</text>
					<text v-if="fieldErrors.code" id="oauth-phone-code-error" class="auth-error" role="alert">
						{{ fieldErrors.code }}
					</text>
				</view>
				<phone-delivery-method
					v-if="phoneSupportsWhatsapp"
					v-model="deliveryMethod"
					control-id="oauth-phone-delivery"
					:disabled="busy"
				/>
				<button
					v-if="!sent"
					class="auth-button"
					type="button"
					:loading="busy"
					:disabled="busy || cooldown > 0"
					:aria-busy="busy"
					@click="sendCode"
				>
					发送{{ deliveryLabel }}验证码
				</button>
				<button
					v-else
					class="auth-button"
					type="button"
					:loading="busy"
					:disabled="busy"
					:aria-busy="busy"
					@click="verifyAndComplete"
				>
					验证并继续
				</button>
				<view v-if="sent" class="resend-row">
					<button class="auth-link" type="button" :disabled="busy || cooldown > 0" @click="sendCode">
						{{ cooldown > 0 ? `${cooldown}s 后可重发` : `重新发送${deliveryLabel}验证码` }}
					</button>
				</view>
			</template>

			<view class="auth-links">
				<button class="auth-link" type="button" :disabled="busy" @click="cancelFlow">取消第三方登录</button>
			</view>
		</view>
	</view>
</template>

<script>
	import AuthTurnstile from '@/components/auth/auth-turnstile.vue'
	import IdentifierFields from '@/components/auth/identifier-fields.vue'
	import PhoneDeliveryMethod from '@/components/auth/phone-delivery-method.vue'
	import VerificationIdentitySummary from '@/components/auth/verification-identity-summary.vue'
	import { authApi } from '@/common/auth/auth-api.js'
	import { authErrorMessage } from '@/common/auth/auth-error.js'
	import { AUTH_ROUTES } from '@/common/auth/config.js'
	import {
		getCurrentPhoneCountrySelection,
		resolveInitialPhoneCountry,
		selectPhoneCountry
	} from '@/common/auth/phone-country-default.js'
	import { findPhoneCountryById } from '@shared-auth/phone-country-search.js'
	import { formatLocalPhoneNumberInput, isValidLocalPhoneNumber } from '@shared-auth/phone-validation.js'

	export default {
		components: { AuthTurnstile, IdentifierFields, PhoneDeliveryMethod, VerificationIdentitySummary },
		data() {
			return {
				countryId: '',
				countryResolving: true,
				phoneNumber: '',
				flow: null,
				lockedPhonePresentation: null,
				humanVerified: false,
				deliveryMethod: 'SMS',
				sent: false,
				code: '',
				cooldown: 0,
				timer: null,
				busy: false,
				error: '',
				fieldErrors: { phoneNumber: '', code: '' },
				focusedField: '',
				countryPickerOpen: false,
				turnstilePageScrollTop: 0
			}
		},
		computed: {
			country() { return findPhoneCountryById(this.countryId) },
			phoneDisplay() { return formatLocalPhoneNumberInput(this.phoneNumber, this.country?.iso2) },
			phoneSupportsWhatsapp() {
				const dialCode = this.lockedPhonePresentation?.dialCode || this.country?.dialCode || ''
				return Boolean(dialCode && dialCode !== '+86')
			},
			deliveryLabel() { return this.deliveryMethod === 'WHATSAPP' ? 'WhatsApp' : '短信' }
		},
		watch: {
			phoneSupportsWhatsapp(supported) { if (!supported) this.deliveryMethod = 'SMS' },
			code() { this.fieldErrors.code = '' }
		},
		onLoad() {
			void this.initializePhoneCountry()
			this.timer = setInterval(() => { if (this.cooldown > 0) this.cooldown -= 1 }, 1000)
		},
		onPageScroll(event) {
			const value = Number(event?.scrollTop)
			if (Number.isFinite(value) && value >= 0) this.turnstilePageScrollTop = value
			this.$refs.turnstile?.syncAndroidBounds({ scrollTop: this.turnstilePageScrollTop, reason: 'scroll' })
		},
		onUnload() { clearInterval(this.timer) },
		onBackPress() {
			if (!this.countryPickerOpen) return false
			this.$refs.identifierFields?.closeCountryPicker()
			return true
		},
		methods: {
			async initializePhoneCountry() {
				const current = getCurrentPhoneCountrySelection()
				if (current.source !== 'UNRESOLVED') {
					this.countryId = current.countryId
					this.countryResolving = false
					return
				}
				const resolved = await resolveInitialPhoneCountry()
				this.countryId = resolved.countryId
				this.countryResolving = false
			},
			handleCountryUserSelection(countryId) {
				const selected = selectPhoneCountry(countryId)
				this.countryId = selected.countryId
				this.countryResolving = false
			},
			validatePhone() {
				this.fieldErrors.phoneNumber = this.country && isValidLocalPhoneNumber(this.phoneNumber, this.country.iso2)
					? '' : '请输入与所选国家或地区匹配的有效本地手机号。'
				return !this.fieldErrors.phoneNumber
			},
			validateCode() {
				this.fieldErrors.code = /^\d{6}$/.test(this.code) ? '' : '请输入 6 位验证码。'
				return !this.fieldErrors.code
			},
			async run(action) {
				if (this.busy) return null
				this.busy = true
				this.error = ''
				try { return await action() }
				catch (error) { this.error = authErrorMessage(error); return null }
				finally { this.busy = false }
			},
			async startPhoneFlow() {
				if (!this.validatePhone()) {
					this.focusedField = 'phoneNumber'
					return
				}
				const result = await this.run(() => authApi.oauthPhoneStart({
					countryIso2: this.country.iso2.toUpperCase(),
					phoneNumber: this.phoneNumber
				}))
				if (!result) return
				this.flow = result
				this.lockedPhonePresentation = {
					dialCode: this.country.dialCode,
					nationalDisplay: this.phoneDisplay,
					countryIso2: this.country.iso2.toUpperCase(),
					countryName: this.country.name,
					flag: this.country.flag || ''
				}
			},
			async verifyHuman(token) {
				if (!token || !this.flow || this.busy) return
				this.$refs.turnstile?.markServerVerificationStarted()
				const result = await this.run(() => authApi.oauthPhoneTurnstile(token))
				if (result?.accepted) {
					this.$refs.turnstile?.markServerAccepted()
					this.humanVerified = true
				} else {
					this.$refs.turnstile?.resetAfterServerRejection('人机验证未被服务器确认，请重试。')
				}
			},
			async sendCode() {
				if (this.cooldown > 0) return
				const result = await this.run(() => authApi.oauthPhoneSend(this.deliveryMethod))
				if (result?.accepted) {
					this.sent = true
					this.cooldown = 60
					uni.showToast({ title: `${this.deliveryLabel}验证码已发送`, icon: 'none' })
				}
			},
			async verifyAndComplete() {
				if (!this.validateCode()) {
					this.focusedField = 'code'
					return
				}
				const verified = await this.run(() => authApi.oauthPhoneVerify(this.code))
				if (!verified?.state || verified.state !== 'READY_TO_COMPLETE') return
				const result = await this.run(() => authApi.oauthComplete())
				if (result?.status === 'TOTP_REQUIRED') {
					uni.navigateTo({ url: AUTH_ROUTES.totpLogin })
				} else if (result?.status === 'AUTHENTICATED') {
					uni.reLaunch({ url: AUTH_ROUTES.home })
				}
			},
			changePhone() {
				this.flow = null
				this.humanVerified = false
				this.sent = false
				this.code = ''
				this.cooldown = 0
				this.phoneNumber = ''
				this.lockedPhonePresentation = null
				this.deliveryMethod = 'SMS'
				this.error = ''
			},
			async cancelFlow() {
				await this.run(() => authApi.oauthCancel())
				uni.reLaunch({ url: AUTH_ROUTES.login })
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/auth/auth.scss';
</style>

<template>
	<view class="auth-page">
		<view class="auth-container" :aria-busy="busy">
			<text class="auth-kicker">Account recovery</text>
			<text class="auth-title">找回密码</text>
			<text class="auth-subtitle">通过邮箱或手机验证码完成一次验证。成功后需要重新登录。</text>
			<view class="auth-progress" role="progressbar" aria-label="重置密码进度" :aria-valuenow="progress" aria-valuemin="1" aria-valuemax="3">
				<view v-for="index in 3" :key="index" class="auth-progress-segment" :class="{ active: progress >= index }" />
			</view>
			<view v-if="error" class="auth-banner" role="alert" aria-live="assertive">{{ error }}</view>

			<view v-if="stage === 'START'">
				<view class="auth-segments" role="tablist" aria-label="找回方式">
					<button
						v-for="(item, index) in channelOptions"
						ref="channelTabs"
						:key="item.value"
						class="auth-segment"
						:class="{ active: channel === item.value }"
						type="button"
						role="tab"
						:aria-selected="channel === item.value"
						:tabindex="channel === item.value ? 0 : -1"
						:disabled="busy"
						@click="changeChannel(item.value)"
						@keydown="onChannelKeydown($event, index)"
					>
						{{ item.label }}
					</button>
				</view>
				<identifier-fields
					ref="identifierFields"
					:type="channel === 'EMAIL' ? 'EMAIL' : 'PHONE'"
					:errors="fieldErrors"
					:focus-field="focusedField"
					:country-id="countryId"
					:country-resolving="countryResolving"
					v-model:email="email"
					v-model:phone="phoneNumber"
					@update:country-id="handleCountryUserSelection"
					@field-blur="validateField"
					@country-picker-visibility-change="countryPickerOpen = $event"
				/>
				<button type="button" class="auth-button" :loading="busy" :disabled="busy" :aria-busy="busy" @click="start">
					{{ busy ? '正在开始…' : '继续' }}
				</button>
			</view>

			<view v-else-if="stage === 'HUMAN'">
				<auth-turnstile
					ref="turnstile"
					action="password_reset"
					:challenge="flow.challengeHandle"
					:page-scroll-top="turnstilePageScrollTop"
					@verified="verifyHuman"
				/>
			</view>

			<view v-else-if="stage === 'CODE'">
				<view class="verified-note" role="status">
					<uni-icons type="checkmarkempty" size="18" color="#37d39a" aria-hidden="true" />
					<text>安全验证已通过</text>
				</view>
				<view class="auth-field">
					<label class="auth-label" for="auth-reset-code">{{ channel === 'SMS' ? '手机验证码' : '邮箱验证码' }}</label>
					<view class="auth-control" :class="{ invalid: fieldErrors.code }">
					<input
						id="auth-reset-code"
						v-model.trim="code"
						class="auth-control-input"
						type="password"
						maxlength="6"
						inputmode="numeric"
						autocomplete="one-time-code"
						placeholder="6 位数字"
						:focus="focusedField === 'code'"
						:aria-invalid="Boolean(fieldErrors.code)"
						:aria-describedby="fieldErrors.code ? 'auth-reset-code-error' : 'auth-reset-code-help'"
						@blur="validateField('code')"
					/>
					</view>
					<text id="auth-reset-code-help" class="auth-help">{{ sent ? `如果账号存在，${codeDeliveryLabel}验证码已经发送。` : `点击下方按钮发送${codeDeliveryLabel}验证码。` }}</text>
					<text v-if="fieldErrors.code" id="auth-reset-code-error" class="auth-error" role="alert">{{ fieldErrors.code }}</text>
				</view>
				<phone-delivery-method
					v-if="phoneSupportsWhatsapp"
					v-model="phoneDeliveryMethod"
					control-id="auth-reset-phone-delivery"
					:disabled="busy"
				/>
				<button v-if="!sent" type="button" class="auth-button" :loading="busy" :disabled="busy || cooldown > 0" :aria-busy="busy" @click="send">
					{{ busy ? '正在发送…' : `发送${codeDeliveryLabel}验证码` }}
				</button>
				<button v-else type="button" class="auth-button" :loading="busy" :disabled="busy" :aria-busy="busy" @click="verify">
					{{ busy ? '正在验证…' : '验证并继续' }}
				</button>
				<view v-if="sent" class="resend-row">
					<button class="auth-link" type="button" :disabled="busy || cooldown > 0" @click="send">
						{{ cooldown > 0 ? `${cooldown}s 后可重发` : `重新发送${codeDeliveryLabel}验证码` }}
					</button>
				</view>
			</view>

			<view v-else-if="stage === 'PASSWORD'">
				<auth-password-fields
					v-model:password="password"
					v-model:confirmation="passwordConfirmation"
					:force-errors="passwordTouched"
					:focus-field="focusedField"
					@validity="passwordValid = $event"
				/>
				<button type="button" class="auth-button" :loading="busy" :disabled="busy" :aria-busy="busy" @click="complete">
					{{ busy ? '正在更新密码…' : '重置密码' }}
				</button>
			</view>

			<view v-else class="auth-success" role="status" aria-live="polite">
				<view class="auth-success-icon" aria-hidden="true">✓</view>
				<text class="auth-success-title">密码已更新</text>
				<text class="auth-success-copy">所有旧会话已经撤销，请使用新密码重新登录。</text>
				<button type="button" class="auth-button" @click="goLogin">返回登录</button>
			</view>

			<view v-if="stage !== 'DONE'" class="auth-links">
				<button class="auth-link" type="button" :disabled="busy" @click="goLogin">返回登录</button>
			</view>
		</view>
	</view>
</template>

<script>
	import AuthPasswordFields from '@/components/auth/auth-password-fields.vue'
	import AuthTurnstile from '@/components/auth/auth-turnstile.vue'
	import IdentifierFields from '@/components/auth/identifier-fields.vue'
	import PhoneDeliveryMethod from '@/components/auth/phone-delivery-method.vue'
	import { authApi } from '@/common/auth/auth-api.js'
	import { authErrorMessage } from '@/common/auth/auth-error.js'
	import { passwordError } from '@shared-auth/password-policy.js'
	import { AUTH_ROUTES, clientPlatform } from '@/common/auth/config.js'
	import {
		clearAndroidPasswordResetFlow,
		loadAndroidPasswordResetFlow,
		saveAndroidPasswordResetFlow
	} from '@/common/auth/android-flow-keystore.js'
	import { isValidEmailAddress } from '@shared-auth/email-validation.js'
	import {
		getCurrentPhoneCountrySelection,
		resolveInitialPhoneCountry,
		selectPhoneCountry
	} from '@/common/auth/phone-country-default.js'
	import { findPhoneCountryById } from '@shared-auth/phone-country-search.js'
	import { isValidLocalPhoneNumber } from '@shared-auth/phone-validation.js'

	function emptyFieldErrors() {
		return { email: '', phoneNumber: '', password: '', passwordConfirmation: '', code: '' }
	}

	export default {
		components: { AuthPasswordFields, AuthTurnstile, IdentifierFields, PhoneDeliveryMethod },
		data() {
			return {
				channelOptions: [
					{ value: 'EMAIL', label: '邮箱' },
					{ value: 'SMS', label: '短信' }
				],
				stage: 'START',
				channel: 'EMAIL',
				email: '',
				countryId: '',
				countryResolving: true,
				phoneCountryPageActive: false,
				phoneNumber: '',
				flow: null,
				sent: false,
				phoneDeliveryMethod: 'SMS',
				code: '',
				cooldown: 0,
				turnstilePageScrollTop: 0,
				password: '',
				passwordConfirmation: '',
				passwordValid: false,
				passwordTouched: false,
				busy: false,
				error: '',
				timer: null,
				fieldErrors: emptyFieldErrors(),
				focusedField: '',
				countryPickerOpen: false
			}
		},
		computed: {
			country() { return findPhoneCountryById(this.countryId) },
			phoneSupportsWhatsapp() {
				const dialCode = this.country?.dialCode || ''
				return this.channel === 'SMS' && Boolean(dialCode && dialCode !== '+86')
			},
			codeDeliveryLabel() {
				if (this.channel === 'EMAIL') return '邮箱'
				return this.phoneDeliveryMethod === 'WHATSAPP' ? 'WhatsApp' : '短信'
			},
			progress() { return this.stage === 'START' ? 1 : ['HUMAN', 'CODE'].includes(this.stage) ? 2 : 3 }
		},
		watch: {
			email() { this.fieldErrors.email = '' },
			countryId() { this.fieldErrors.phoneNumber = '' },
			phoneSupportsWhatsapp(supported) {
				if (!supported) this.phoneDeliveryMethod = 'SMS'
			},
			phoneNumber() { this.fieldErrors.phoneNumber = '' },
			code() { this.fieldErrors.code = '' },
			password() { this.fieldErrors.password = '' },
			passwordConfirmation() { this.fieldErrors.passwordConfirmation = '' }
		},
		onLoad() {
			this.phoneCountryPageActive = true
			this.initializePhoneCountry()
			this.timer = setInterval(() => { if (this.cooldown > 0) this.cooldown -= 1 }, 1000)
		},
		onShow() {
			this.syncPhoneCountrySelection()
			this.$nextTick(() => this.syncTurnstileBounds({ reason: 'show' }))
		},
		onPageScroll(event) {
			this.syncTurnstileBounds({ scrollTop: event?.scrollTop, reason: 'scroll' })
		},
		onResize() {
			this.$nextTick(() => this.syncTurnstileBounds({ reason: 'resize' }))
		},
		onUnload() {
			this.phoneCountryPageActive = false
			clearInterval(this.timer)
		},
		onBackPress() {
			if (!this.countryPickerOpen) return false
			this.$refs.identifierFields?.closeCountryPicker()
			return true
		},
		methods: {
			syncTurnstileBounds(context = {}) {
				const scrollTop = Number(context?.scrollTop)
				if (Number.isFinite(scrollTop) && scrollTop >= 0) this.turnstilePageScrollTop = scrollTop
				this.$refs.turnstile?.syncAndroidBounds({ ...context, scrollTop: this.turnstilePageScrollTop })
			},
			isAndroid() { return clientPlatform() === 'ANDROID' },
			syncPhoneCountrySelection() {
				const current = getCurrentPhoneCountrySelection()
				if (current.source === 'UNRESOLVED') return false
				this.countryId = current.countryId
				this.countryResolving = false
				return true
			},
			initializePhoneCountry() {
				if (this.syncPhoneCountrySelection()) {
					return Promise.resolve(getCurrentPhoneCountrySelection())
				}
				this.countryResolving = true
				return resolveInitialPhoneCountry().then((result) => {
					if (this.phoneCountryPageActive) {
						this.countryId = result.countryId
						this.countryResolving = false
					}
					return result
				})
			},
			handleCountryUserSelection(countryId) {
				const selected = selectPhoneCountry(countryId)
				this.countryId = selected.countryId
				this.countryResolving = false
			},
			async ensurePhoneCountry() {
				if (this.country) return true
				await this.initializePhoneCountry()
				if (this.country) return true
				this.fieldErrors.phoneNumber = '请选择国家或地区。'
				return false
			},
			changeChannel(value) {
				if (this.busy || this.channel === value) return
				this.channel = value
				this.phoneDeliveryMethod = 'SMS'
				this.error = ''
				this.fieldErrors.email = ''
				this.fieldErrors.phoneNumber = ''
			},
			onChannelKeydown(event, index) {
				if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
				event.preventDefault()
				let nextIndex = index
				if (event.key === 'ArrowLeft') nextIndex = (index - 1 + this.channelOptions.length) % this.channelOptions.length
				if (event.key === 'ArrowRight') nextIndex = (index + 1) % this.channelOptions.length
				if (event.key === 'Home') nextIndex = 0
				if (event.key === 'End') nextIndex = this.channelOptions.length - 1
				this.changeChannel(this.channelOptions[nextIndex].value)
				// #ifdef H5
				this.$nextTick(() => {
					const tab = (this.$refs.channelTabs || [])[nextIndex]
					const element = tab?.$el || tab
					if (element?.focus) element.focus()
				})
				// #endif
			},
			focusFirstField(fields) {
				const field = fields.find(name => this.fieldErrors[name])
				if (!field) return
				this.focusedField = ''
				this.$nextTick(() => { this.focusedField = field })
			},
			validateField(field) {
				if (field === 'email') {
					this.fieldErrors.email = isValidEmailAddress(this.email) ? '' : '请输入有效邮箱。'
				}
				if (field === 'phoneNumber') {
					this.fieldErrors.phoneNumber = this.country && isValidLocalPhoneNumber(this.phoneNumber, this.country.iso2)
						? ''
						: this.country
							? '请输入与所选国家或地区匹配的有效本地手机号。'
							: '请选择国家或地区。'
				}
				if (field === 'code') {
					this.fieldErrors.code = /^\d{6}$/.test(this.code) ? '' : '请输入 6 位验证码。'
				}
				return !this.fieldErrors[field]
			},
			async run(action, onFailure) {
				if (this.busy) return null
				this.busy = true
				this.error = ''
				try { return await action() }
				catch (error) {
					this.error = authErrorMessage(error)
					if (typeof onFailure === 'function') onFailure()
					return null
				} finally { this.busy = false }
			},
			async start() {
				const field = this.channel === 'EMAIL' ? 'email' : 'phoneNumber'
				if (field === 'phoneNumber' && !(await this.ensurePhoneCountry())) {
					this.focusFirstField([field])
					return
				}
				if (!this.validateField(field)) {
					this.focusFirstField([field])
					return
				}
				const data = this.channel === 'EMAIL'
					? { channel: 'EMAIL', email: this.email }
					: { channel: 'SMS', countryIso2: this.country?.iso2.toUpperCase() || '', phoneNumber: this.phoneNumber }
				const result = await this.run(() => authApi.passwordResetStart(data))
				if (result) {
					if (this.isAndroid()) {
						saveAndroidPasswordResetFlow(result)
						this.flow = loadAndroidPasswordResetFlow() || result
					} else {
						this.flow = {
							challengeHandle: result.challengeHandle,
							expiresAt: result.expiresAt
						}
					}
					this.stage = 'HUMAN'
					this.focusedField = ''
				}
			},
			async verifyHuman(token) {
				let verificationFailed = false
				this.$refs.turnstile?.markServerVerificationStarted()
				const result = await this.run(
					() => authApi.passwordResetTurnstile(this.flow, token),
					() => { verificationFailed = true }
				)
				if (result?.accepted) {
					this.$refs.turnstile?.markServerAccepted()
					this.stage = 'CODE'
					return
				}
				if (verificationFailed) {
					this.$nextTick(() => {
						this.$refs.turnstile?.resetAfterServerRejection(
							'验证结果未被服务器确认，请重新验证。'
						)
					})
				}
			},
			async send() {
				if (this.busy || this.cooldown > 0) return
				const deliveryMethod = this.channel === 'SMS' ? this.phoneDeliveryMethod : undefined
				const result = await this.run(() => authApi.passwordResetSend(this.flow, deliveryMethod))
				if (result?.accepted) {
					this.sent = true
					this.cooldown = 60
					uni.showToast({ title: `${this.codeDeliveryLabel}验证码已发送`, icon: 'none' })
				}
			},
			async verify() {
				if (!this.validateField('code')) {
					this.focusFirstField(['code'])
					return
				}
				const result = await this.run(() => authApi.passwordResetVerify(this.flow, this.code))
				if (result) {
					if (this.isAndroid()) {
						saveAndroidPasswordResetFlow({
							forgetToken: result.forgetToken,
							challengeHandle: this.flow?.challengeHandle,
							expiresAt: result.expiresAt
						})
						this.flow = loadAndroidPasswordResetFlow() || this.flow
					}
					this.stage = 'PASSWORD'
					this.focusedField = ''
				}
			},
			async complete() {
				this.passwordTouched = true
				const currentPasswordError = passwordError(this.password, this.passwordConfirmation)
				if (currentPasswordError || !this.passwordValid) {
					this.fieldErrors.password = currentPasswordError || '请按要求设置并确认密码。'
					this.error = this.fieldErrors.password
					this.focusedField = ''
					this.$nextTick(() => { this.focusedField = 'password' })
					return
				}
				this.fieldErrors.password = ''
				const result = await this.run(() => authApi.passwordResetComplete(
					this.password,
					this.passwordConfirmation
				))
				if (result?.passwordReset) {
					if (this.isAndroid()) clearAndroidPasswordResetFlow()
					this.stage = 'DONE'
				}
			},
			goLogin() {
				if (this.busy) return
				if (this.isAndroid()) clearAndroidPasswordResetFlow()
				uni.reLaunch({ url: AUTH_ROUTES.login })
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/auth/auth.scss';
</style>

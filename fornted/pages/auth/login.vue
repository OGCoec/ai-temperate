<template>
	<view class="auth-page">
		<view class="auth-container" :aria-busy="busy">
			<text class="auth-kicker">Welcome back</text>
			<text class="auth-title">登录</text>
			<text class="auth-subtitle">使用密码、邮箱验证码或手机验证码继续。</text>
			<view v-if="error" class="auth-banner" role="alert" aria-live="assertive">{{ error }}</view>
			<text v-if="successMessage" class="auth-sr-only" role="status" aria-live="polite">{{ successMessage }}</text>

			<view class="auth-segments" role="tablist" aria-label="登录方式">
				<button
					v-for="(item, index) in methods"
					ref="methodTabs"
					:key="item.value"
					class="auth-segment"
					:class="{ active: method === item.value }"
					type="button"
					role="tab"
					:aria-selected="method === item.value"
					:tabindex="method === item.value ? 0 : -1"
					:disabled="busy"
					@click="changeMethod(item.value)"
					@keydown="onSegmentKeydown($event, index, methods, 'method')"
				>
					{{ item.label }}
				</button>
			</view>

			<template v-if="method === 'PASSWORD'">
				<view class="auth-segments compact" role="tablist" aria-label="账号类型">
					<button
						v-for="(item, index) in identifierTypes"
						ref="identifierTabs"
						:key="item.value"
						class="auth-segment"
						:class="{ active: identifierType === item.value }"
						type="button"
						role="tab"
						:aria-selected="identifierType === item.value"
						:tabindex="identifierType === item.value ? 0 : -1"
						:disabled="busy"
						@click="changeIdentifierType(item.value)"
						@keydown="onSegmentKeydown($event, index, identifierTypes, 'identifier')"
					>
						{{ item.label }}
					</button>
				</view>
				<identifier-fields
					ref="identifierFields"
					:type="identifierType"
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
				<view class="auth-field">
					<label class="auth-label" for="auth-login-password">密码</label>
					<view class="auth-control password-row" :class="{ invalid: fieldErrors.password }">
						<input
							id="auth-login-password"
							v-model="password"
							class="auth-control-input password-input"
							:type="showPassword ? 'text' : 'password'"
							maxlength="72"
							autocomplete="current-password"
							placeholder="输入密码"
							:focus="focusedField === 'password'"
							:aria-invalid="Boolean(fieldErrors.password)"
							:aria-describedby="fieldErrors.password ? 'auth-login-password-error' : ''"
							@blur="validateField('password')"
						/>
						<button
							class="password-toggle"
							type="button"
							:aria-label="showPassword ? '隐藏密码' : '显示密码'"
							:aria-pressed="showPassword"
							@click="showPassword = !showPassword"
						>
							<uni-icons :type="showPassword ? 'eye-slash' : 'eye'" size="20" color="#8b9690" aria-hidden="true" />
						</button>
					</view>
					<text v-if="fieldErrors.password" id="auth-login-password-error" class="auth-error" role="alert">{{ fieldErrors.password }}</text>
				</view>
				<button type="button" class="auth-button" :loading="busy" :disabled="busy" :aria-busy="busy" @click="passwordLogin">
					{{ busy ? '正在登录…' : '登录' }}
				</button>
			</template>

			<template v-else>
				<identifier-fields
					ref="identifierFields"
					:type="method === 'EMAIL_CODE' ? 'EMAIL' : 'PHONE'"
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
				<button v-if="!flow" type="button" class="auth-button" :loading="busy" :disabled="busy" :aria-busy="busy" @click="startCodeFlow">
					{{ busy ? '正在开始…' : '继续' }}
				</button>
				<auth-turnstile
					v-else-if="!humanVerified"
					ref="turnstile"
					action="login"
					:challenge="flow.challengeHandle"
					@verified="verifyHuman"
					@visibility-change="turnstileOpen = $event"
				/>
				<template v-else>
					<view class="auth-field">
						<label class="auth-label" for="auth-login-code">{{ method === 'SMS_CODE' ? '手机验证码' : '邮箱验证码' }}</label>
						<view class="auth-control" :class="{ invalid: fieldErrors.code }">
						<input
							id="auth-login-code"
							v-model.trim="code"
							class="auth-control-input"
							type="password"
							maxlength="6"
							inputmode="numeric"
							autocomplete="one-time-code"
							placeholder="6 位数字"
							:focus="focusedField === 'code'"
							:aria-invalid="Boolean(fieldErrors.code)"
							:aria-describedby="fieldErrors.code ? 'auth-login-code-error' : 'auth-login-code-help'"
							@blur="validateField('code')"
						/>
						</view>
						<text id="auth-login-code-help" class="auth-help">{{ sent ? `${codeDeliveryLabel}验证码已发送，5 分钟内有效。` : `点击下方按钮发送${codeDeliveryLabel}验证码。` }}</text>
						<text v-if="fieldErrors.code" id="auth-login-code-error" class="auth-error" role="alert">{{ fieldErrors.code }}</text>
					</view>
					<phone-delivery-method
						v-if="phoneSupportsWhatsapp"
						v-model="phoneDeliveryMethod"
						control-id="auth-login-phone-delivery"
						:disabled="busy"
					/>
					<button v-if="!sent" type="button" class="auth-button" :loading="busy" :disabled="busy || cooldown > 0" :aria-busy="busy" @click="sendCode">
						{{ busy ? '正在发送…' : `发送${codeDeliveryLabel}验证码` }}
					</button>
					<button v-else type="button" class="auth-button" :loading="busy" :disabled="busy" :aria-busy="busy" @click="verifyCode">
						{{ busy ? '正在登录…' : '验证并登录' }}
					</button>
					<view v-if="sent" class="resend-row">
						<button class="auth-link" type="button" :disabled="busy || cooldown > 0" @click="sendCode">
							{{ cooldown > 0 ? `${cooldown}s 后可重发` : `重新发送${codeDeliveryLabel}验证码` }}
						</button>
					</view>
				</template>
			</template>

			<view class="auth-links">
				<button class="auth-link" type="button" :disabled="busy" @click="goRegister">创建账号</button>
				<button class="auth-link" type="button" :disabled="busy" @click="goReset">忘记密码</button>
			</view>
		</view>
	</view>
</template>

<script>
	import AuthTurnstile from '@/components/auth/auth-turnstile.vue'
	import IdentifierFields from '@/components/auth/identifier-fields.vue'
	import PhoneDeliveryMethod from '@/components/auth/phone-delivery-method.vue'
	import { authApi } from '@/common/auth/auth-api.js'
	import { authErrorMessage } from '@/common/auth/auth-error.js'
	import { AUTH_ROUTES, clientPlatform } from '@/common/auth/config.js'
	import { initializeBrowserCsrf } from '@/common/auth/http-client.js'
	import { presentRiskBlock } from '@/common/auth/risk-block-navigation.js'
	import { isValidEmailAddress } from '@shared-auth/email-validation.js'
	import {
		getCurrentPhoneCountrySelection,
		resolveInitialPhoneCountry,
		selectPhoneCountry
	} from '@/common/auth/phone-country-default.js'
	import { findPhoneCountryById } from '@shared-auth/phone-country-search.js'
	import { isValidLocalPhoneNumber } from '@shared-auth/phone-validation.js'
	import { classifyPassword } from '@shared-auth/password-policy.js'

	function emptyFieldErrors() {
		return { email: '', phoneNumber: '', password: '', passwordConfirmation: '', code: '' }
	}

	export default {
		components: { AuthTurnstile, IdentifierFields, PhoneDeliveryMethod },
		data() {
			return {
				methods: [
					{ value: 'PASSWORD', label: '密码' },
					{ value: 'EMAIL_CODE', label: '邮箱验证码' },
					{ value: 'SMS_CODE', label: '手机验证码' }
				],
				identifierTypes: [
					{ value: 'EMAIL', label: '邮箱' },
					{ value: 'PHONE', label: '手机号' }
				],
				method: 'PASSWORD',
				identifierType: 'EMAIL',
				email: '',
				countryId: '',
				countryResolving: true,
				phoneCountryPageActive: false,
				phoneNumber: '',
				password: '',
				showPassword: false,
				flow: null,
				humanVerified: false,
				sent: false,
				phoneDeliveryMethod: 'SMS',
				code: '',
				cooldown: 0,
				timer: null,
				busy: false,
				error: '',
				successMessage: '',
				fieldErrors: emptyFieldErrors(),
				focusedField: '',
				countryPickerOpen: false,
				turnstileOpen: false
			}
		},
		computed: {
			country() { return findPhoneCountryById(this.countryId) },
			phoneSupportsWhatsapp() {
				const dialCode = this.country?.dialCode || ''
				return this.method === 'SMS_CODE' && Boolean(dialCode && dialCode !== '+86')
			},
			codeDeliveryLabel() {
				if (this.method === 'EMAIL_CODE') return '邮箱'
				return this.phoneDeliveryMethod === 'WHATSAPP' ? 'WhatsApp' : '短信'
			}
		},
		watch: {
			email() { this.fieldErrors.email = '' },
			countryId() { this.fieldErrors.phoneNumber = '' },
			phoneSupportsWhatsapp(supported) {
				if (!supported) this.phoneDeliveryMethod = 'SMS'
			},
			phoneNumber() { this.fieldErrors.phoneNumber = '' },
			password() { this.fieldErrors.password = '' },
			code() { this.fieldErrors.code = '' }
		},
		onLoad() {
			this.phoneCountryPageActive = true
			this.initializePhoneCountry()
			this.initializePageCsrf()
			this.timer = setInterval(() => { if (this.cooldown > 0) this.cooldown -= 1 }, 1000)
		},
		onShow() { this.syncPhoneCountrySelection() },
		onUnload() {
			this.phoneCountryPageActive = false
			clearInterval(this.timer)
		},
		onBackPress() {
			if (this.turnstileOpen) {
				this.$refs.turnstile?.closeVerification()
				return true
			}
			if (!this.countryPickerOpen) return false
			this.$refs.identifierFields?.closeCountryPicker()
			return true
		},
		methods: {
			completePrimaryFactor(result) {
				if (result?.status === 'TOTP_REQUIRED') {
					uni.navigateTo({ url: AUTH_ROUTES.totpLogin })
					return
				}
				if (result?.status === 'AUTHENTICATED') this.completeLogin()
			},
			async initializePageCsrf() {
				if (clientPlatform() !== 'H5') return
				try {
					const token = await initializeBrowserCsrf()
					if (token) return
					const error = new Error('CSRF 安全令牌初始化失败，请重试。')
					error.code = 'CSRF_INVALID'
					throw error
				} catch (error) {
					if (presentRiskBlock(error)) return
					this.error = authErrorMessage(error)
				}
			},
			completeLogin() {
				this.successMessage = '登录成功'
				uni.reLaunch({
					url: AUTH_ROUTES.home,
					success: () => {
						uni.showToast({ title: '登录成功', icon: 'success' })
					},
					fail: () => {
						this.error = '登录成功，但页面跳转失败，请重试。'
					}
				})
			},
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
			changeMethod(value) {
				if (this.busy || this.method === value) return
				this.method = value
				this.flow = null
				this.humanVerified = false
				this.sent = false
				this.phoneDeliveryMethod = 'SMS'
				this.code = ''
				this.cooldown = 0
				this.error = ''
				this.successMessage = ''
				this.fieldErrors = emptyFieldErrors()
			},
			changeIdentifierType(value) {
				if (this.busy || this.identifierType === value) return
				this.identifierType = value
				this.error = ''
				this.fieldErrors.email = ''
				this.fieldErrors.phoneNumber = ''
			},
			onSegmentKeydown(event, index, items, group) {
				if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
				event.preventDefault()
				let nextIndex = index
				if (event.key === 'ArrowLeft') nextIndex = (index - 1 + items.length) % items.length
				if (event.key === 'ArrowRight') nextIndex = (index + 1) % items.length
				if (event.key === 'Home') nextIndex = 0
				if (event.key === 'End') nextIndex = items.length - 1
				if (group === 'method') this.changeMethod(items[nextIndex].value)
				else this.changeIdentifierType(items[nextIndex].value)
				this.focusTab(group === 'method' ? 'methodTabs' : 'identifierTabs', nextIndex)
			},
			focusTab(refName, index) {
				// #ifdef H5
				this.$nextTick(() => {
					const tab = (this.$refs[refName] || [])[index]
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
				if (field === 'password') {
					const assessment = classifyPassword(this.password)
					if (!this.password) this.fieldErrors.password = '请输入密码。'
					else if (assessment.score < 2) this.fieldErrors.password = '密码强度不足，请重置密码。'
					else if (assessment.utf8Bytes > 72) this.fieldErrors.password = '密码不得超过 72 个 UTF-8 字节。'
					else this.fieldErrors.password = ''
				}
				if (field === 'code') {
					this.fieldErrors.code = /^\d{6}$/.test(this.code) ? '' : '请输入 6 位验证码。'
				}
				return !this.fieldErrors[field]
			},
			validateIdentifier(type) {
				const field = type === 'EMAIL' ? 'email' : 'phoneNumber'
				return this.validateField(field)
			},
			payload(type) {
				return type === 'EMAIL'
					? { email: this.email }
					: { countryIso2: this.country?.iso2.toUpperCase() || '', phoneNumber: this.phoneNumber }
			},
			async run(action, onFailure) {
				if (this.busy) return null
				this.busy = true
				this.error = ''
				this.successMessage = ''
				try { return await action() }
				catch (error) {
					if (error?.code === 'PASSWORD_RESET_REQUIRED') {
						this.error = '密码强度不足，请重置密码。'
						uni.showToast({ title: this.error, icon: 'none' })
						setTimeout(() => uni.navigateTo({ url: AUTH_ROUTES.passwordReset }), 0)
					} else {
						this.error = authErrorMessage(error)
					}
					if (typeof onFailure === 'function') onFailure()
					return null
				} finally { this.busy = false }
			},
			async passwordLogin() {
				if (this.identifierType === 'PHONE' && !(await this.ensurePhoneCountry())) {
					this.focusFirstField(['phoneNumber'])
					return
				}
				const identifierValid = this.validateIdentifier(this.identifierType)
				const passwordValid = this.validateField('password')
				if (!identifierValid || !passwordValid) {
					this.focusFirstField([this.identifierType === 'EMAIL' ? 'email' : 'phoneNumber', 'password'])
					if (!passwordValid && this.password) {
						this.error = this.fieldErrors.password
						uni.showToast({ title: this.fieldErrors.password, icon: 'none' })
						setTimeout(() => uni.navigateTo({ url: AUTH_ROUTES.passwordReset }), 0)
					}
					return
				}
				const result = await this.run(() => authApi.passwordLogin({
					...this.payload(this.identifierType),
					password: this.password
				}))
				if (result) this.completePrimaryFactor(result)
			},
			async startCodeFlow() {
				const type = this.method === 'EMAIL_CODE' ? 'EMAIL' : 'PHONE'
				if (type === 'PHONE' && !(await this.ensurePhoneCountry())) {
					this.focusFirstField(['phoneNumber'])
					return
				}
				if (!this.validateIdentifier(type)) {
					this.focusFirstField([type === 'EMAIL' ? 'email' : 'phoneNumber'])
					return
				}
				const result = await this.run(() => authApi.loginCodeStart({
					strategyType: this.method,
					...this.payload(type)
				}))
				if (result) this.flow = result
			},
			async verifyHuman(token) {
				let verificationFailed = false
				const result = await this.run(
					() => authApi.loginCodeTurnstile(this.flow, token),
					() => { verificationFailed = true }
				)
				if (result?.accepted) {
					this.humanVerified = true
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
			async sendCode() {
				if (this.busy || this.cooldown > 0) return
				const deliveryMethod = this.method === 'SMS_CODE' ? this.phoneDeliveryMethod : undefined
				const result = await this.run(() => authApi.loginCodeSend(this.flow, deliveryMethod))
				if (result?.accepted) {
					this.sent = true
					this.cooldown = 60
					uni.showToast({ title: `${this.codeDeliveryLabel}验证码已发送`, icon: 'none' })
				}
			},
			async verifyCode() {
				if (!this.validateField('code')) {
					this.focusFirstField(['code'])
					return
				}
				const result = await this.run(() => authApi.loginCodeVerify(this.flow, this.method, this.code))
				if (result) this.completePrimaryFactor(result)
			},
			goRegister() { if (!this.busy) uni.navigateTo({ url: AUTH_ROUTES.register }) },
			goReset() { if (!this.busy) uni.navigateTo({ url: AUTH_ROUTES.passwordReset }) }
		}
	}
</script>

<style lang="scss">
	@import '@/common/auth/auth.scss';
	.compact { width: 210px; margin-bottom: 20px; }
	@media screen and (max-width: 359px) { .compact { width: 100%; } }
</style>

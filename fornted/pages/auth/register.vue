<template>
	<view class="auth-page">
		<view class="auth-container" :aria-busy="busy">
			<text class="auth-kicker">Create account</text>
			<text class="auth-title">创建新账号</text>
			<text class="auth-subtitle">同时验证邮箱和国际手机号。注册完成后请返回登录。</text>

			<view v-if="step < 4" class="auth-progress" role="progressbar" aria-label="注册进度" :aria-valuenow="step" aria-valuemin="1" aria-valuemax="3">
				<view v-for="index in 3" :key="index" class="auth-progress-segment" :class="{ active: step >= index }" />
			</view>
			<view v-if="error" class="auth-banner" role="alert" aria-live="assertive">{{ error }}</view>

			<view v-if="step === 1 || (step === 2 && !humanVerified && !flowSuperseded)">
				<view class="auth-field">
					<label class="auth-label" for="auth-register-email">邮箱</label>
					<view class="auth-control" :class="{ invalid: fieldErrors.email }">
					<input
						id="auth-register-email"
						v-model.trim="email"
						class="auth-control-input"
						type="text"
						maxlength="254"
						autocomplete="email"
						placeholder="name@example.com"
						:focus="focusedField === 'email'"
						:aria-invalid="Boolean(fieldErrors.email)"
						:aria-describedby="fieldErrors.email ? 'auth-register-email-error' : ''"
						@blur="validateField('email')"
					/>
					</view>
					<text v-if="fieldErrors.email" id="auth-register-email-error" class="auth-error" role="alert">{{ fieldErrors.email }}</text>
				</view>
				<phone-country-picker
					ref="countryPicker"
					:model-value="countryId"
					:resolving="countryResolving"
					@update:model-value="handleCountryUserSelection"
					@visibility-change="countryPickerOpen = $event"
				/>
				<view class="auth-field">
					<label class="auth-label" for="auth-register-phone">手机号</label>
					<view class="auth-control phone-row" :class="{ invalid: fieldErrors.phoneNumber }">
						<text v-if="!internationalDraft" class="dial-prefix" aria-hidden="true">{{ dialCode }}</text>
						<input
							:key="phoneInputKey"
							id="auth-register-phone"
							:value="effectivePhoneDisplay"
							class="auth-control-input phone-input"
							type="tel"
							maxlength="32"
							autocomplete="tel"
							placeholder="本地手机号或含 + 的国际手机号"
							:focus="focusedField === 'phoneNumber'"
							:aria-label="phoneAriaLabel"
							:aria-invalid="Boolean(fieldErrors.phoneNumber)"
							:aria-describedby="fieldErrors.phoneNumber ? 'auth-register-phone-error' : 'auth-register-phone-help'"
							@input="handlePhoneNumberInput"
							@blur="validateField('phoneNumber')"
						/>
					</view>
					<text id="auth-register-phone-help" class="auth-help">请选择号码所属地区；后端会规范化为 E.164。</text>
					<text v-if="fieldErrors.phoneNumber" id="auth-register-phone-error" class="auth-error" role="alert">{{ fieldErrors.phoneNumber }}</text>
				</view>
				<button v-if="step === 1" type="button" class="auth-button" :loading="busy" :disabled="busy" :aria-busy="busy" @click="start">
					{{ busy ? '正在提交…' : '继续' }}
				</button>
				<auth-turnstile
					v-else-if="flow && !flowSuperseded"
					ref="turnstile"
					action="register"
					:challenge="flow.challengeHandle"
					:page-scroll-top="turnstilePageScrollTop"
					@verified="verifyHuman"
				/>
			</view>

			<view v-else-if="step === 2 && flowSuperseded">
				<view v-if="flowSuperseded" class="verified-note" role="alert" aria-live="assertive">
					<text>另一个标签页已开始新的注册流程，请在最新标签页继续。</text>
				</view>
			</view>

			<view v-else-if="step === 2">
				<template v-if="humanVerified">
					<view class="verified-note" role="status">
						<uni-icons type="checkmarkempty" size="18" color="#37d39a" aria-hidden="true" />
						<text>安全验证已通过</text>
					</view>
					<template v-if="canDisplayRegistrationIdentity">
						<verification-identity-summary
							:email="registrationEmail"
							:phone-presentation="registrationPhonePresentation"
						/>
						<view class="auth-links">
							<button class="auth-link" type="button" :disabled="busy" @click="restartIdentityVerification">重新填写</button>
						</view>
						<view class="auth-code-row">
							<view class="auth-field">
								<label class="auth-label" for="auth-register-email-code">邮箱验证码</label>
								<view class="auth-control" :class="{ invalid: fieldErrors.code }">
									<input
										id="auth-register-email-code"
										v-model.trim="emailCode"
										class="auth-control-input"
										type="password"
										maxlength="6"
										inputmode="numeric"
										autocomplete="one-time-code"
										placeholder="6 位数字"
										:focus="focusedField === 'code'"
										:aria-invalid="Boolean(fieldErrors.code)"
										:aria-describedby="fieldErrors.code ? 'auth-register-code-error' : ''"
									/>
								</view>
							</view>
							<button type="button" class="auth-secondary" :disabled="busy || emailCooldown > 0" @click="sendCode('email')">
								{{ cooldownText(emailCooldown) }}
							</button>
						</view>
						<view class="auth-code-row">
							<view class="auth-field">
								<label class="auth-label" for="auth-register-sms-code">手机验证码</label>
								<view class="auth-control" :class="{ invalid: fieldErrors.code }">
									<input
										id="auth-register-sms-code"
										v-model.trim="smsCode"
										class="auth-control-input"
										type="password"
										maxlength="6"
										inputmode="numeric"
										autocomplete="one-time-code"
										placeholder="6 位数字"
										:aria-invalid="Boolean(fieldErrors.code)"
										:aria-describedby="fieldErrors.code ? 'auth-register-code-error' : ''"
									/>
								</view>
							</view>
							<button type="button" class="auth-secondary" :disabled="busy || smsCooldown > 0" @click="sendCode('phone')">
								{{ smsCooldown > 0 ? `${smsCooldown}s` : `发送${phoneDeliveryLabel}` }}
								</button>
						</view>
						<phone-delivery-method
							v-if="phoneSupportsWhatsapp"
							v-model="phoneDeliveryMethod"
							control-id="auth-register-phone-delivery"
							:disabled="busy"
						/>
						<text class="auth-help code-help">两个验证码必须在同一次提交中同时正确。</text>
						<text v-if="fieldErrors.code" id="auth-register-code-error" class="auth-error" role="alert">{{ fieldErrors.code }}</text>
						<button type="button" class="auth-button" :loading="busy" :disabled="busy" :aria-busy="busy" @click="verifyCodes">
							{{ busy ? '正在校验…' : '验证并继续' }}
						</button>
					</template>
					<view v-else class="auth-banner" role="alert">
						注册联系方式暂时无法恢复，请重新开始注册。
					</view>
				</template>
			</view>

			<view v-else-if="step === 3">
				<auth-password-fields
					v-model:password="password"
					v-model:confirmation="passwordConfirmation"
					:force-errors="passwordTouched"
					:focus-field="focusedField"
					@validity="passwordValid = $event"
				/>
				<button type="button" class="auth-button" :loading="busy" :disabled="busy" :aria-busy="busy" @click="complete">
					{{ busy ? '正在创建账号…' : '完成注册' }}
				</button>
			</view>

			<view v-else class="auth-success" role="status" aria-live="polite">
				<view class="auth-success-icon" aria-hidden="true">✓</view>
				<text class="auth-success-title">注册完成</text>
				<text class="auth-success-copy">账号已经创建，本次注册不会自动登录。</text>
				<button type="button" class="auth-button" @click="goLogin">返回登录</button>
			</view>

			<view v-if="step < 4" class="auth-links">
				<button class="auth-link" type="button" :disabled="busy" @click="goLogin">已有账号？登录</button>
			</view>
		</view>
	</view>
</template>

<script>
	import PhoneCountryPicker from '@/components/auth/phone-country-picker.vue'
	import AuthPasswordFields from '@/components/auth/auth-password-fields.vue'
	import AuthTurnstile from '@/components/auth/auth-turnstile.vue'
	import PhoneDeliveryMethod from '@/components/auth/phone-delivery-method.vue'
	import VerificationIdentitySummary from '@/components/auth/verification-identity-summary.vue'
	import { authApi } from '@/common/auth/auth-api.js'
	import { authErrorMessage } from '@/common/auth/auth-error.js'
	import { passwordError } from '@shared-auth/password-policy.js'
	import { AUTH_ROUTES, clientPlatform } from '@/common/auth/config.js'
	import {
		loadAndroidRegisterFlow,
		saveAndroidRegisterFlow
	} from '@/common/auth/android-flow-keystore.js'
	import {
		clearRegistrationFlowState,
		isRegistrationRedirectHandled
	} from '@/common/auth/registration-flow-guard.js'
	import { isValidEmailAddress } from '@shared-auth/email-validation.js'
	import { createTurnstileAttemptId } from '@/common/auth/turnstile-response-diagnostics.js'
	import {
		getCurrentPhoneCountrySelection,
		resolveInitialPhoneCountry,
		selectPhoneCountry
	} from '@/common/auth/phone-country-default.js'
	import { findPhoneCountryById, getPhoneCountryByIso2 } from '@shared-auth/phone-country-search.js'
	import {
		formatLocalPhoneNumberInput,
		isValidLocalPhoneNumber,
		normalizePhoneInputForCountry,
		normalizeInternationalPhoneInput
	} from '@shared-auth/phone-validation.js'
	import { derivePhonePresentation } from '@/common/user/phone-presentation.js'

	function emptyFieldErrors() {
		return { email: '', phoneNumber: '', password: '', passwordConfirmation: '', code: '' }
	}

	function logTurnstileAttempt(event, attemptId, details = {}) {
		if (typeof console === 'undefined' || typeof console.info !== 'function') return
		// 只输出有限诊断字段；Token、Cookie、Challenge、邮箱、手机号和错误正文都不进入浏览器日志。
		console.info('[auth-turnstile]', {
			event,
			attemptId,
			code: typeof details.code === 'string' ? details.code : '',
			statusCode: Number.isInteger(details.statusCode) ? details.statusCode : 0,
			contentType: typeof details.contentType === 'string' ? details.contentType : '',
			cfMitigated: typeof details.cfMitigated === 'string' ? details.cfMitigated : '',
			classification: typeof details.classification === 'string' ? details.classification : '',
			traceId: typeof details.traceId === 'string' ? details.traceId : '',
			cfRay: typeof details.cfRay === 'string' ? details.cfRay : ''
		})
	}

	function turnstileErrorMessage(error, diagnostics) {
		const message = authErrorMessage(error)
		const traceId = error?.traceId || diagnostics?.traceId || ''
		return /^[A-Za-z0-9_-]{1,128}$/.test(traceId)
			? `${message}（追踪号：${traceId}）`
			: message
	}

	export default {
		components: {
			PhoneCountryPicker,
			AuthPasswordFields,
			AuthTurnstile,
			PhoneDeliveryMethod,
			VerificationIdentitySummary
		},
		data() {
			return {
				step: 1,
				email: '',
				countryId: '',
				countryResolving: true,
				phoneCountryPageActive: false,
				phoneNumber: '',
				registrationIdentity: { email: '', phoneE164: '' },
				flow: null,
				pendingIdentity: null,
				humanVerified: false,
				emailCode: '',
				smsCode: '',
				password: '',
				passwordConfirmation: '',
				passwordValid: false,
				passwordTouched: false,
				emailCooldown: 0,
				smsCooldown: 0,
				phoneDeliveryMethod: 'SMS',
				turnstilePageScrollTop: 0,
				timer: null,
				busy: false,
				error: '',
				fieldErrors: emptyFieldErrors(),
				focusedField: '',
				phoneInputKey: 0,
				/**
				 * 国际号码草稿：用户输入 `+...` 但尚未完成有效 E.164 时，
				 * 原样保存在此字段中，不写入正式 phoneNumber 状态。
				 */
				internationalDraft: '',
				countryPickerOpen: false,
				turnstileVerifying: false,
				flowSuperseded: false,
				registrationFlowChannel: null
			}
		},
		computed: {
			country() { return findPhoneCountryById(this.countryId) },
			dialCode() { return this.country?.dialCode || '—' },
			phoneDisplay() {
				return formatLocalPhoneNumberInput(this.phoneNumber, this.country?.iso2)
			},
			/**
			 * 输入框实际展示值：国际草稿期间显示草稿原文，
			 * 否则显示按当前国家格式化的本地号码。
			 */
			effectivePhoneDisplay() {
				return this.internationalDraft || this.phoneDisplay
			},
			registrationEmail() {
				return this.registrationIdentity.email || this.email
			},
			registrationPhonePresentation() {
				const serverPhone = derivePhonePresentation(this.registrationIdentity.phoneE164)
				if (serverPhone.bound) return serverPhone
				return {
					bound: Boolean(this.phoneNumber),
					valid: Boolean(this.country && this.phoneNumber),
					displayNumber: `${this.dialCode} ${this.phoneDisplay}`.trim(),
					nationalNumber: this.phoneNumber,
					nationalDisplay: this.phoneDisplay,
					dialCode: this.country?.dialCode || '',
					countryIso2: this.country?.iso2?.toUpperCase() || '',
					countryId: this.country?.id || '',
					countryName: this.country?.name || '未知国家或地区',
					flag: this.country?.flag || '',
					countryResolved: Boolean(this.country)
				}
			},
			canDisplayRegistrationIdentity() {
				return Boolean(
					this.humanVerified &&
					this.registrationEmail &&
					this.registrationPhonePresentation.nationalDisplay
				)
			},
			phoneAriaLabel() {
				if (this.internationalDraft) return '国际手机号输入中，请输入完整号码'
				return this.country
					? `手机号，当前国家区号 ${this.country.dialCode}`
					: '手机号，国家或地区仍在识别'
			},
			phoneSupportsWhatsapp() {
				const dialCode = this.registrationPhonePresentation?.dialCode || this.country?.dialCode || ''
				return Boolean(dialCode && dialCode !== '+86')
			},
			phoneDeliveryLabel() {
				return this.phoneDeliveryMethod === 'WHATSAPP' ? 'WhatsApp' : '短信'
			}
		},
		watch: {
			email() {
				this.fieldErrors.email = ''
				this.invalidatePendingHumanFlow()
			},
			countryId() {
				this.fieldErrors.phoneNumber = ''
				// 国家变化时不重格式化尚未完成的国际草稿。
				if (!this.internationalDraft) {
					this.formatExistingPhoneNumber()
				}
				this.invalidatePendingHumanFlow()
			},
			phoneSupportsWhatsapp(supported) {
				if (!supported) this.phoneDeliveryMethod = 'SMS'
			},
			phoneNumber() {
				this.fieldErrors.phoneNumber = ''
				this.invalidatePendingHumanFlow()
			},
			emailCode() { this.fieldErrors.code = '' },
			smsCode() { this.fieldErrors.code = '' },
			password() { this.fieldErrors.password = '' },
			passwordConfirmation() { this.fieldErrors.passwordConfirmation = '' }
		},
		onLoad() {
			this.phoneCountryPageActive = true
			this.openRegistrationFlowChannel()
			this.initializePhoneCountry()
			this.timer = setInterval(() => {
				if (this.emailCooldown > 0) this.emailCooldown -= 1
				if (this.smsCooldown > 0) this.smsCooldown -= 1
			}, 1000)
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
			this.clearRegistrationIdentityMemory()
			clearInterval(this.timer)
			this.closeRegistrationFlowChannel()
		},
		onBackPress() {
			if (!this.countryPickerOpen) return false
			this.$refs.countryPicker?.closePicker()
			return true
		},
		methods: {
			syncTurnstileBounds(context = {}) {
				const scrollTop = Number(context?.scrollTop)
				if (Number.isFinite(scrollTop) && scrollTop >= 0) this.turnstilePageScrollTop = scrollTop
				this.$refs.turnstile?.syncAndroidBounds({ ...context, scrollTop: this.turnstilePageScrollTop })
			},
			isAndroid() { return clientPlatform() === 'ANDROID' },
			applyRegistrationIdentity(status) {
				if (status?.humanVerified !== true) return false
				const email = typeof status.email === 'string' && isValidEmailAddress(status.email)
					? status.email
					: this.registrationIdentity.email
				const phoneE164 = typeof status.phoneE164 === 'string' &&
					/^\+[1-9][0-9]{1,14}$/.test(status.phoneE164)
					? status.phoneE164
					: this.registrationIdentity.phoneE164
				this.registrationIdentity = { email, phoneE164 }
				return Boolean(email && phoneE164)
			},
			capturePendingIdentity() {
				return {
					email: this.email.trim(),
					countryId: this.country?.id || '',
					countryIso2: this.country?.iso2?.toUpperCase() || '',
					phoneNumber: this.phoneNumber
				}
			},
			pendingIdentityMatchesCurrent(identity = this.pendingIdentity) {
				if (!identity) return false
				const current = this.capturePendingIdentity()
				return identity.email === current.email &&
					identity.countryId === current.countryId &&
					identity.countryIso2 === current.countryIso2 &&
					identity.phoneNumber === current.phoneNumber
			},
			invalidatePendingHumanFlow() {
				if (!this.flow || this.humanVerified || !this.pendingIdentity) return
				if (this.pendingIdentityMatchesCurrent()) return

				// 人机结果只能绑定创建流程时的身份快照；身份变化后旧挑战不得继续锁定新输入。
				this.$refs.turnstile?.resetAfterServerRejection('联系方式已更改，请重新验证。')
				this.flow = null
				this.pendingIdentity = null
				this.step = 1
				this.flowSuperseded = false
				this.registrationIdentity = { email: '', phoneE164: '' }
				this.emailCode = ''
				this.smsCode = ''
				this.emailCooldown = 0
				this.smsCooldown = 0
				this.fieldErrors.code = ''
				clearRegistrationFlowState()
				this.error = '联系方式已更改，请重新点击继续并完成人机验证。'
			},
			restartIdentityVerification() {
				if (this.busy) return
				this.flow = null
				this.pendingIdentity = null
				this.humanVerified = false
				this.step = 1
				this.flowSuperseded = false
				this.emailCooldown = 0
				this.smsCooldown = 0
				this.password = ''
				this.passwordConfirmation = ''
				this.passwordValid = false
				this.passwordTouched = false
				this.error = ''
				this.fieldErrors = emptyFieldErrors()
				this.focusedField = ''
				this.clearRegistrationIdentityMemory()
				this.phoneInputKey += 1
				clearRegistrationFlowState()
			},
			clearRegistrationIdentityMemory() {
				this.email = ''
				this.phoneNumber = ''
				this.countryId = ''
				this.internationalDraft = ''
				this.registrationIdentity = { email: '', phoneE164: '' }
				this.emailCode = ''
				this.smsCode = ''
				this.phoneDeliveryMethod = 'SMS'
			},
			openRegistrationFlowChannel() {
				if (this.isAndroid() || typeof BroadcastChannel === 'undefined') return
				this.registrationFlowChannel = new BroadcastChannel('ait-registration-flow-v1')
				this.registrationFlowChannel.onmessage = (event) => {
					const message = event?.data
					if (message?.type !== 'FLOW_REPLACED') return
					if (typeof message.challengeHandle !== 'string' ||
						!/^[A-Za-z0-9_-]{8,128}$/.test(message.challengeHandle)) return
					if (!this.flow?.challengeHandle || message.challengeHandle === this.flow.challengeHandle) return
					this.$refs.turnstile?.resetAfterServerRejection('本页注册流程已被替换。')
					this.flowSuperseded = true
					this.flow = null
					this.pendingIdentity = null
					this.humanVerified = false
					this.clearRegistrationIdentityMemory()
					clearRegistrationFlowState()
					this.error = '另一个标签页已开始新的注册流程，请在最新标签页继续。'
				}
			},
			closeRegistrationFlowChannel() {
				if (!this.registrationFlowChannel) return
				this.registrationFlowChannel.close()
				this.registrationFlowChannel = null
			},
			broadcastRegistrationFlow() {
				if (!this.registrationFlowChannel || !this.flow?.challengeHandle) return
				this.registrationFlowChannel.postMessage({
					type: 'FLOW_REPLACED',
					challengeHandle: this.flow.challengeHandle
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
			handlePhoneNumberInput(event) {
				const rawValue = event?.detail?.value || ''

				// 首字符为 `+` 时进入国际号码识别流程。
				if (typeof rawValue === 'string' && rawValue.startsWith('+')) {
					const intl = normalizeInternationalPhoneInput(rawValue)
					if (intl && intl.pendingInternational) {
						// 未完成的国际输入只保留草稿；清空正式号码，避免提交上一次的本地号码。
						this.internationalDraft = intl.sanitized
						this.phoneNumber = ''
						return
					}
					if (intl && !intl.pendingInternational && intl.detectedCountryIso2) {
						// 完整有效国际号码：先切换国家，再写入本地数字，清除草稿。
						const detectedCountry = getPhoneCountryByIso2(intl.detectedCountryIso2)
						if (detectedCountry) {
							this.internationalDraft = ''
							if (detectedCountry.id !== this.countryId) {
								this.handleCountryUserSelection(detectedCountry.id)
							}
							this.phoneNumber = intl.localDigits
							this.phoneInputKey += 1
							return
						}

						// 号码库识别出的国家若不在前端选项中，仍保留草稿且禁止产生错误的正式号码。
						this.internationalDraft = intl.sanitized
						this.phoneNumber = ''
						return
					}
				}

				// 首字符不是 `+` 时才按当前所选国家处理本地号码。
				this.internationalDraft = ''
				const normalized = normalizePhoneInputForCountry(
					rawValue,
					this.country?.iso2,
					this.phoneDisplay
				)
				let countryChanged = false
				if (normalized.detectedCountryIso2) {
					// 无 `+` 的完整 NANP 号码只在严格有效后切换国家，输入过程保持当前选择。
					const detectedCountry = getPhoneCountryByIso2(normalized.detectedCountryIso2)
					if (detectedCountry && detectedCountry.id !== this.countryId) {
						this.handleCountryUserSelection(detectedCountry.id)
						countryChanged = true
					}
				}
				const shouldRefreshInput = countryChanged ||
					(normalized.digits === this.phoneNumber && normalized.display !== rawValue)
				this.phoneNumber = normalized.digits
				if (shouldRefreshInput) this.phoneInputKey += 1
			},
			formatExistingPhoneNumber() {
				if (!this.phoneNumber || this.internationalDraft) return
				const normalized = normalizePhoneInputForCountry(this.phoneNumber, this.country?.iso2)
				if (normalized.digits !== this.phoneNumber) this.phoneNumber = normalized.digits
			},
			async ensurePhoneCountry() {
				if (this.country) return true
				await this.initializePhoneCountry()
				if (this.country) return true
				this.fieldErrors.phoneNumber = '请选择国家或地区。'
				return false
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
				return !this.fieldErrors[field]
			},
			async run(action) {
				if (this.busy) return null
				this.busy = true
				this.error = ''
				try { return await action() }
				catch (error) {
					if (isRegistrationRedirectHandled(error)) return null
					this.error = authErrorMessage(error)
					return null
				} finally { this.busy = false }
			},
			async start() {
				const countryValid = await this.ensurePhoneCountry()
				const emailValid = this.validateField('email')
				const phoneValid = countryValid && this.validateField('phoneNumber')
				if (!emailValid || !countryValid || !phoneValid) {
					this.focusFirstField(['email', 'phoneNumber'])
					return
				}
				const submittedIdentity = this.capturePendingIdentity()
				const result = await this.run(() => authApi.registerStart({
					email: submittedIdentity.email,
					countryIso2: submittedIdentity.countryIso2,
					phoneNumber: submittedIdentity.phoneNumber
				}))
				if (!result) return
				this.registrationIdentity = { email: '', phoneE164: '' }
				this.emailCode = ''
				this.smsCode = ''
				if (this.isAndroid()) {
					saveAndroidRegisterFlow(result)
					this.flow = loadAndroidRegisterFlow() || result
				} else {
					this.flow = {
						challengeHandle: result.challengeHandle,
						expiresAt: result.expiresAt
					}
				}
				this.pendingIdentity = submittedIdentity
				if (!this.pendingIdentityMatchesCurrent(submittedIdentity)) {
					this.invalidatePendingHumanFlow()
					return
				}
				this.step = 2
				this.flowSuperseded = false
				this.broadcastRegistrationFlow()
				this.focusedField = ''
			},
			async verifyHuman(token) {
				if (this.turnstileVerifying || this.busy || this.flowSuperseded || !token) return
				const submittedFlow = this.flow
				const submittedIdentity = this.pendingIdentity
				if (!submittedFlow || !submittedIdentity || !this.pendingIdentityMatchesCurrent(submittedIdentity)) {
					this.invalidatePendingHumanFlow()
					return
				}
				this.$refs.turnstile?.markServerVerificationStarted()
				const attemptId = createTurnstileAttemptId()
				let responseDiagnostics = null
				this.turnstileVerifying = true
				this.busy = true
				this.error = ''
				logTurnstileAttempt('TOKEN_RECEIVED', attemptId)
				try {
					// H5 Cookie 会被同域标签页共享；提交一次性 Token 前必须确认页面挑战仍对应服务端当前流程。
					logTurnstileAttempt('PREFLIGHT_REQUEST_STARTED', attemptId)
					const currentStatus = await authApi.registerStatus(submittedFlow, {
						attemptId,
						onResponse: (diagnostics) => {
							responseDiagnostics = diagnostics
							logTurnstileAttempt('PREFLIGHT_RESPONSE', attemptId, diagnostics)
						}
					})
					if (this.flow !== submittedFlow) return
					if (this.pendingIdentity !== submittedIdentity) return
					if (currentStatus?.humanVerified) {
						this.applyRegistrationIdentity(currentStatus)
						this.$refs.turnstile?.markServerAccepted()
						this.humanVerified = true
						logTurnstileAttempt('ALREADY_CONFIRMED', attemptId)
						return
					}
					if (!currentStatus?.challengeHandle ||
						currentStatus.challengeHandle !== submittedFlow.challengeHandle) {
						const flowError = new Error('注册流程已在另一个页面更新，请在最新标签页继续。')
						flowError.code = 'REGISTRATION_FLOW_REPLACED'
						throw flowError
					}
					logTurnstileAttempt('API_REQUEST_STARTED', attemptId)
					const status = await authApi.registerTurnstile(submittedFlow, token, {
						attemptId,
						onResponse: (diagnostics) => {
							responseDiagnostics = diagnostics
							logTurnstileAttempt('HTTP_RESPONSE', attemptId, diagnostics)
						}
					})
					if (this.flow !== submittedFlow) return
					if (this.pendingIdentity !== submittedIdentity) return
					if (!status?.humanVerified) {
						const confirmationError = new Error('验证结果未被服务器确认，请重新验证。')
						confirmationError.code = 'TURNSTILE_NOT_CONFIRMED'
						throw confirmationError
					}
					this.applyRegistrationIdentity(status)
					this.$refs.turnstile?.markServerAccepted()
					this.humanVerified = true
					logTurnstileAttempt('CONFIRMED', attemptId, responseDiagnostics || {})
				} catch (error) {
					if (this.flow !== submittedFlow || this.pendingIdentity !== submittedIdentity) return
					if (isRegistrationRedirectHandled(error)) return
					if (error?.code === 'REGISTRATION_FLOW_REPLACED') {
						this.flowSuperseded = true
						this.flow = null
						this.pendingIdentity = null
						this.humanVerified = false
						this.clearRegistrationIdentityMemory()
						clearRegistrationFlowState()
					}
					this.error = turnstileErrorMessage(error, responseDiagnostics)
					const resetMessage = error?.code === 'EDGE_CHALLENGE'
						? 'Cloudflare 安全检查未完成，请重新验证。'
						: '验证结果未被服务器确认，请重新验证。'
					this.$nextTick(() => {
						this.$refs.turnstile?.resetAfterServerRejection(resetMessage)
					})
					logTurnstileAttempt('REJECTED', attemptId, {
						code: error?.code,
						statusCode: error?.statusCode,
						contentType: error?.contentType || responseDiagnostics?.contentType,
						cfMitigated: error?.cfMitigated || responseDiagnostics?.cfMitigated,
						classification: error?.responseClassification || responseDiagnostics?.classification,
						traceId: error?.traceId || responseDiagnostics?.traceId,
						cfRay: error?.cfRay || responseDiagnostics?.cfRay
					})
				} finally {
					this.turnstileVerifying = false
					this.busy = false
				}
			},
			async sendCode(channel) {
				if (this.busy || (channel === 'email' ? this.emailCooldown : this.smsCooldown) > 0) return
				const deliveryMethod = channel === 'phone' ? this.phoneDeliveryMethod : undefined
				const result = await this.run(() => authApi.registerSend(this.flow, channel, deliveryMethod))
				if (result) {
					if (channel === 'email') this.emailCooldown = 60
					else this.smsCooldown = 60
					const label = channel === 'email' ? '邮件' : this.phoneDeliveryLabel
					uni.showToast({ title: `${label}验证码已发送`, icon: 'none' })
				}
			},
			cooldownText(seconds) { return seconds > 0 ? `${seconds}s` : '发送' },
			async verifyCodes() {
				if (!/^\d{6}$/.test(this.emailCode) || !/^\d{6}$/.test(this.smsCode)) {
					this.fieldErrors.code = '请完整填写两个 6 位验证码。'
					this.focusFirstField(['code'])
					return
				}
				const status = await this.run(() => authApi.registerVerify(this.flow, this.emailCode, this.smsCode))
				this.applyRegistrationIdentity(status)
				if (status?.status === 'READY_TO_COMPLETE') {
					this.step = 3
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
				const result = await this.run(() => authApi.registerComplete(this.flow, this.password, this.passwordConfirmation))
				if (result?.registered) {
					this.pendingIdentity = null
					this.clearRegistrationIdentityMemory()
					this.step = 4
				}
			},
			goLogin() {
				if (this.busy) return
				clearRegistrationFlowState()
				this.pendingIdentity = null
				this.clearRegistrationIdentityMemory()
				uni.reLaunch({ url: AUTH_ROUTES.login })
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/auth/auth.scss';
</style>

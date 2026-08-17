<template>
	<view>
		<view v-if="type === 'EMAIL'" class="auth-field">
			<label class="auth-label" for="auth-email">邮箱</label>
			<view class="auth-control" :class="{ invalid: errors.email }">
				<input
					id="auth-email"
					:value="email"
					class="auth-control-input"
					type="text"
					maxlength="254"
					autocomplete="email"
					placeholder="name@example.com"
					:focus="focusField === 'email'"
					:aria-invalid="Boolean(errors.email)"
					:aria-describedby="errors.email ? 'auth-email-error' : ''"
					@input="$emit('update:email', $event.detail.value)"
					@blur="$emit('field-blur', 'email')"
				/>
			</view>
			<text v-if="errors.email" id="auth-email-error" class="auth-error" role="alert">{{ errors.email }}</text>
		</view>

		<template v-else>
			<phone-country-picker
				ref="countryPicker"
				v-model="countryProxy"
				:resolving="countryResolving"
				@visibility-change="$emit('country-picker-visibility-change', $event)"
			/>
			<view class="auth-field">
				<label class="auth-label" for="auth-phone">手机号</label>
				<view class="auth-control phone-row" :class="{ invalid: errors.phoneNumber }">
					<text v-if="!internationalDraft" class="dial-prefix" aria-hidden="true">{{ dialCode }}</text>
					<input
						:key="phoneInputKey"
						id="auth-phone"
						:value="effectivePhoneDisplay"
						class="auth-control-input phone-input"
						type="tel"
						maxlength="32"
						autocomplete="tel"
						placeholder="本地手机号或含 + 的国际手机号"
						:focus="focusField === 'phoneNumber'"
						:aria-label="phoneAriaLabel"
						:aria-invalid="Boolean(errors.phoneNumber)"
						:aria-describedby="errors.phoneNumber ? 'auth-phone-error' : ''"
						@input="updatePhone"
						@blur="$emit('field-blur', 'phoneNumber')"
					/>
				</view>
				<text v-if="errors.phoneNumber" id="auth-phone-error" class="auth-error" role="alert">{{ errors.phoneNumber }}</text>
			</view>
		</template>
	</view>
</template>

<script>
	import PhoneCountryPicker from './phone-country-picker.vue'
	import { findPhoneCountryById, getPhoneCountryByIso2 } from '@shared-auth/phone-country-search.js'
	import {
		formatLocalPhoneNumberInput,
		normalizePhoneInputForCountry,
		normalizeInternationalPhoneInput
	} from '@shared-auth/phone-validation.js'

	export default {
		name: 'IdentifierFields',
		components: { PhoneCountryPicker },
		emits: [
			'update:email',
			'update:countryId',
			'update:phone',
			'field-blur',
			'country-picker-visibility-change'
		],
		props: {
			type: { type: String, required: true },
			email: { type: String, default: '' },
			countryId: { type: String, default: '' },
			countryResolving: { type: Boolean, default: false },
			phone: { type: String, default: '' },
			errors: { type: Object, default: () => ({}) },
			focusField: { type: String, default: '' }
		},
		data() {
			return {
				phoneInputKey: 0,
				/**
				 * 国际号码草稿：用户输入 `+...` 但尚未完成有效 E.164 时，
				 * 原样保存在此字段中，不写入正式 phoneNumber 状态。
				 */
				internationalDraft: ''
			}
		},
		computed: {
			country() { return findPhoneCountryById(this.countryId) },
			dialCode() { return this.country?.dialCode || '--' },
			phoneDisplay() {
				return formatLocalPhoneNumberInput(this.phone, this.country?.iso2)
			},
			/**
			 * 输入框实际展示值：国际草稿期间显示草稿原文，
			 * 否则显示按当前国家格式化的本地号码。
			 */
			effectivePhoneDisplay() {
				return this.internationalDraft || this.phoneDisplay
			},
			phoneAriaLabel() {
				if (this.internationalDraft) return '国际手机号输入中，请输入完整号码'
				return this.country
					? `手机号，当前国家区号 ${this.country.dialCode}`
					: '手机号，国家或地区仍在识别'
			},
			countryProxy: {
				get() { return this.countryId },
				set(countryId) { this.$emit('update:countryId', countryId) }
			}
		},
		watch: {
			countryId() {
				// 国家变化时不重格式化尚未完成的国际草稿。
				if (!this.internationalDraft) {
					this.formatExistingPhone()
				}
			}
		},
		methods: {
			updatePhone(event) {
				const rawValue = event?.detail?.value || ''

				// 首字符为 `+` 时进入国际号码识别流程。
				if (typeof rawValue === 'string' && rawValue.startsWith('+')) {
					const intl = normalizeInternationalPhoneInput(rawValue)
					if (intl && intl.pendingInternational) {
						// 未完成的国际输入只保留草稿；清空正式号码，避免提交上一次的本地号码。
						this.internationalDraft = intl.sanitized
						this.$emit('update:phone', '')
						return
					}
					if (intl && !intl.pendingInternational && intl.detectedCountryIso2) {
						// 完整有效国际号码：先切换国家，再写入本地数字，清除草稿。
						const detectedCountry = getPhoneCountryByIso2(intl.detectedCountryIso2)
						if (detectedCountry) {
							this.internationalDraft = ''
							if (detectedCountry.id !== this.countryId) {
								this.$emit('update:countryId', detectedCountry.id)
							}
							this.$emit('update:phone', intl.localDigits)
							this.phoneInputKey += 1
							return
						}

						// 号码库识别出的国家若不在前端选项中，仍保留草稿且禁止产生错误的正式号码。
						this.internationalDraft = intl.sanitized
						this.$emit('update:phone', '')
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
					// 管理端与用户端共用完整 NANP 识别结果，避免平台之间出现不同的国家归属。
					const detectedCountry = getPhoneCountryByIso2(normalized.detectedCountryIso2)
					if (detectedCountry && detectedCountry.id !== this.countryId) {
						this.$emit('update:countryId', detectedCountry.id)
						countryChanged = true
					}
				}
				const shouldRefreshInput = countryChanged ||
					(normalized.digits === this.phone && normalized.display !== rawValue)
				this.$emit('update:phone', normalized.digits)
				if (shouldRefreshInput) this.phoneInputKey += 1
			},
			formatExistingPhone() {
				if (!this.phone) return
				const normalized = normalizePhoneInputForCountry(this.phone, this.country?.iso2)
				if (normalized.digits !== this.phone) this.$emit('update:phone', normalized.digits)
			},
			closeCountryPicker() {
				return this.$refs.countryPicker?.closePicker() || false
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/auth/auth-controls.scss';
</style>

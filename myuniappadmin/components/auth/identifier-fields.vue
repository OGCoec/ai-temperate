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
					<text class="dial-prefix" aria-hidden="true">{{ dialCode }}</text>
					<input
						:key="phoneInputKey"
						id="auth-phone"
						:value="phoneDisplay"
						class="auth-control-input phone-input"
						type="tel"
						maxlength="32"
						autocomplete="tel-national"
						placeholder="本地手机号"
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
	import { findPhoneCountryById } from '@/common/auth/phone-country-search.js'
	import {
		formatLocalPhoneNumberInput,
		normalizePhoneInputForCountry
	} from '@/common/auth/phone-validation.js'

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
				phoneInputKey: 0
			}
		},
		computed: {
			country() { return findPhoneCountryById(this.countryId) },
			dialCode() { return this.country?.dialCode || '--' },
			phoneDisplay() {
				return formatLocalPhoneNumberInput(this.phone, this.country?.iso2)
			},
			phoneAriaLabel() {
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
				this.formatExistingPhone()
			}
		},
		methods: {
			updatePhone(event) {
				const rawValue = event?.detail?.value || ''
				const normalized = normalizePhoneInputForCountry(
					rawValue,
					this.country?.iso2,
					this.phoneDisplay
				)
				if (normalized.digits === this.phone && normalized.display !== rawValue) {
					this.phoneInputKey += 1
				}
				this.$emit('update:phone', normalized.digits)
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

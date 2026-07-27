<template>
	<view>
		<view class="auth-field">
			<label class="auth-label" for="admin-email-code">邮箱验证码</label>
			<view class="auth-control" :class="{ invalid: errors.emailCode }">
				<input
					id="admin-email-code"
					:value="emailCode"
					class="auth-control-input"
					type="text"
					inputmode="numeric"
					maxlength="6"
					autocomplete="one-time-code"
					placeholder="6 位数字"
					:aria-invalid="Boolean(errors.emailCode)"
					:aria-describedby="errors.emailCode ? 'admin-email-code-error' : ''"
					@input="updateEmailCode"
				/>
			</view>
			<text v-if="errors.emailCode" id="admin-email-code-error" class="auth-error" role="alert">
				{{ errors.emailCode }}
			</text>
		</view>

		<view class="auth-field">
			<label class="auth-label" for="admin-phone-code">手机验证码</label>
			<view class="auth-control" :class="{ invalid: errors.phoneCode }">
				<input
					id="admin-phone-code"
					:value="phoneCode"
					class="auth-control-input"
					type="text"
					inputmode="numeric"
					maxlength="6"
					autocomplete="one-time-code"
					placeholder="6 位数字"
					:aria-invalid="Boolean(errors.phoneCode)"
					:aria-describedby="errors.phoneCode ? 'admin-phone-code-error' : ''"
					@input="updatePhoneCode"
				/>
			</view>
			<text v-if="errors.phoneCode" id="admin-phone-code-error" class="auth-error" role="alert">
				{{ errors.phoneCode }}
			</text>
		</view>
	</view>
</template>

<script>
	const codeDigits = value => String(value || '').replace(/\D/g, '').slice(0, 6)

	export default {
		name: 'AdminVerificationCodeFields',
		emits: ['update:emailCode', 'update:phoneCode'],
		props: {
			emailCode: { type: String, default: '' },
			phoneCode: { type: String, default: '' },
			errors: { type: Object, default: () => ({}) }
		},
		methods: {
			updateEmailCode(event) {
				this.$emit('update:emailCode', codeDigits(event?.detail?.value))
			},
			updatePhoneCode(event) {
				this.$emit('update:phoneCode', codeDigits(event?.detail?.value))
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/auth/auth-controls.scss';
</style>

<template>
	<view class="auth-field">
		<label class="auth-label" for="admin-password">密码</label>
		<view class="auth-control password-input-wrap" :class="{ invalid: error }">
			<input
				id="admin-password"
				:value="modelValue"
				class="auth-control-input password-input"
				:type="showPassword ? 'text' : 'password'"
				maxlength="256"
				autocomplete="current-password"
				placeholder="请输入管理员密码"
				:aria-invalid="Boolean(error)"
				:aria-describedby="error ? 'admin-password-error' : ''"
				@input="$emit('update:modelValue', $event.detail.value)"
			/>
			<button
				class="password-toggle"
				type="button"
				:aria-label="showPassword ? '隐藏密码' : '显示密码'"
				:aria-pressed="showPassword"
				@click="showPassword = !showPassword"
			>
				<uni-icons :type="showPassword ? 'eye-slash' : 'eye'" size="20" color="#91a2a8" aria-hidden="true" />
			</button>
		</view>
		<text v-if="error" id="admin-password-error" class="auth-error" role="alert">{{ error }}</text>
	</view>
</template>

<script>
	export default {
		name: 'AdminLoginPasswordField',
		emits: ['update:modelValue'],
		props: {
			modelValue: { type: String, default: '' },
			error: { type: String, default: '' }
		},
		data() {
			return { showPassword: false }
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/auth/auth-controls.scss';
</style>

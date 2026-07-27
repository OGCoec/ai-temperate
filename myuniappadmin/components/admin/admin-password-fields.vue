<template>
	<view>
		<view class="auth-field">
			<label class="auth-label" for="admin-new-password">密码</label>
			<view class="auth-control password-input-wrap" :class="{ invalid: errors.password }">
				<input
					id="admin-new-password"
					class="auth-control-input password-input"
					:type="showPassword ? 'text' : 'password'"
					maxlength="256"
					autocomplete="new-password"
					:value="password"
					placeholder="至少达到中等强度"
					:aria-invalid="Boolean(errors.password)"
					:aria-describedby="errors.password ? 'admin-new-password-error' : 'admin-password-help'"
					@input="$emit('update:password', $event.detail.value)"
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
			<text id="admin-password-help" class="auth-help">强度：{{ strength.label }} · {{ strength.utf8Bytes }}/72 bytes</text>
			<text v-if="errors.password" id="admin-new-password-error" class="auth-error" role="alert">{{ errors.password }}</text>
		</view>
		<view class="auth-field">
			<label class="auth-label" for="admin-password-confirmation">确认密码</label>
			<view class="auth-control" :class="{ invalid: errors.passwordConfirmation }">
				<input
					id="admin-password-confirmation"
					class="auth-control-input"
					:type="showPassword ? 'text' : 'password'"
					maxlength="256"
					autocomplete="new-password"
					:value="confirmation"
					placeholder="再次输入密码"
					:aria-invalid="Boolean(errors.passwordConfirmation)"
					:aria-describedby="errors.passwordConfirmation ? 'admin-password-confirmation-error' : ''"
					@input="$emit('update:confirmation', $event.detail.value)"
				/>
			</view>
			<text v-if="errors.passwordConfirmation" id="admin-password-confirmation-error" class="auth-error" role="alert">
				{{ errors.passwordConfirmation }}
			</text>
		</view>
	</view>
</template>

<script>
	import { classifyPassword } from '@shared-auth/password-policy.js'

	export default {
		name: 'AdminPasswordFields',
		emits: ['update:password', 'update:confirmation'],
		props: {
			password: { type: String, default: '' },
			confirmation: { type: String, default: '' },
			errors: { type: Object, default: () => ({}) }
		},
		data() {
			return { showPassword: false }
		},
		computed: {
			strength() { return classifyPassword(this.password) }
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/auth/auth-controls.scss';
</style>

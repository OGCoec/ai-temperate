<template>
	<view>
		<view class="auth-field">
			<label class="auth-label" for="auth-new-password">密码</label>
			<view class="auth-control password-input-wrap" :class="{ invalid: showError && policyError }">
				<input
					id="auth-new-password"
					class="auth-control-input password-input"
					:type="showPassword ? 'text' : 'password'"
					:value="password"
					maxlength="72"
					passwordrules="required: minlength(7); maxlength(72);"
					autocomplete="new-password"
					placeholder="至少达到中等强度"
					:focus="focusField === 'password'"
					:aria-invalid="Boolean(showError && policyError)"
					:aria-describedby="showError && policyError ? 'auth-password-policy-error' : 'auth-password-help'"
					@input="$emit('update:password', $event.detail.value)"
					@blur="touched = true"
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
			<view class="strength" role="status" aria-live="polite">
				<view class="strength-track" aria-hidden="true">
					<view class="strength-fill" :class="strengthClass" :style="{ transform: `scaleX(${strengthScale})` }" />
				</view>
				<text class="strength-label">强度：{{ classification.label }}</text>
			</view>
			<text id="auth-password-help" class="auth-help">密码至少达到中等强度，最多 72 个 UTF-8 字节。</text>
			<text v-if="showError && policyError" id="auth-password-policy-error" class="auth-error" role="alert">{{ policyError }}</text>
		</view>

		<view class="auth-field">
			<label class="auth-label" for="auth-password-confirmation">确认密码</label>
			<view class="auth-control" :class="{ invalid: showError && confirmationError }">
			<input
				id="auth-password-confirmation"
				class="auth-control-input"
				:type="showPassword ? 'text' : 'password'"
				:value="confirmation"
				maxlength="72"
				autocomplete="new-password"
				placeholder="再次输入密码"
				:focus="focusField === 'passwordConfirmation'"
				:aria-invalid="Boolean(showError && confirmationError)"
				:aria-describedby="showError && confirmationError ? 'auth-password-confirmation-error' : ''"
				@input="$emit('update:confirmation', $event.detail.value)"
				@blur="touched = true"
			/>
			</view>
			<text v-if="showError && confirmationError" id="auth-password-confirmation-error" class="auth-error" role="alert">{{ confirmationError }}</text>
		</view>
	</view>
</template>

<script>
	import { classifyPassword, passwordError } from '@shared-auth/password-policy.js'

	export default {
		name: 'AuthPasswordFields',
		emits: ['update:password', 'update:confirmation', 'validity'],
		props: {
			password: { type: String, default: '' },
			confirmation: { type: String, default: '' },
			forceErrors: { type: Boolean, default: false },
			focusField: { type: String, default: '' }
		},
		data() { return { showPassword: false, touched: false } },
		computed: {
			classification() { return classifyPassword(this.password) },
			policyError() { return passwordError(this.password, this.password) },
			confirmationError() {
				if (this.policyError || this.password === this.confirmation) return ''
				return '两次输入的密码不一致。'
			},
			error() { return this.policyError || this.confirmationError },
			showError() { return this.forceErrors || this.touched },
			strengthScale() {
				return this.classification.score / 4
			},
			strengthClass() { return `strength-${this.classification.className}` }
		},
		watch: {
			error: {
				immediate: true,
				handler(value) { this.$emit('validity', !value) }
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/auth/auth-controls.scss';

	.strength { margin-top: 9px; display: flex; align-items: center; gap: 10px; }
	.strength-track { height: 4px; flex: 1; overflow: hidden; border-radius: 2px; background: #303733; }
	.strength-fill {
		height: 100%;
		transform-origin: left center;
		transition: transform 180ms ease, background-color 180ms ease;
	}
	.strength-none { background: transparent; }
	.strength-weak { background: #ef7777; }
	.strength-medium { background: #e4b55c; }
	.strength-strong { background: #37d39a; }
	.strength-very-strong { background: #45a8ff; }
	.strength-label { color: #8b9690; font-size: 12px; }
	@media (prefers-reduced-motion: reduce) { .strength-fill { transition: none; } }
</style>

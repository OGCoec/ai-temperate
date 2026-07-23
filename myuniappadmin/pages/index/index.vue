<template>
	<view class="admin-page">
		<view class="admin-shell">
			<view class="admin-intro" aria-hidden="true">
				<view class="admin-mark">
					<view class="admin-mark-core"></view>
				</view>
				<text class="admin-kicker">AI Temperate Admin</text>
				<text class="admin-title">管理员登录</text>
				<text class="admin-copy">独立管理端入口，当前仅启用本地前端校验。</text>
				<view class="status-list">
					<view class="status-item">
						<text class="status-dot"></text>
						<text class="status-copy">HTTPS 3001 独立端口</text>
					</view>
				</view>
			</view>

			<view class="login-panel" :aria-busy="busy">
				<view class="panel-header">
					<text class="panel-title">安全登录</text>
					<text class="panel-subtitle">邮箱、电话与密码强度全部通过后，才进入后续安全验证。</text>
				</view>

				<view v-if="message" class="admin-banner" :class="messageType" role="status" aria-live="polite">
					{{ message }}
				</view>

				<identifier-fields
					ref="emailFields"
					type="EMAIL"
					:email="email"
					:errors="fieldErrors"
					:focus-field="focusedField"
					@update:email="email = $event"
					@field-blur="validateField"
				/>

				<identifier-fields
					ref="phoneFields"
					type="PHONE"
					:country-id="countryId"
					:country-resolving="false"
					:phone="phoneNumber"
					:errors="fieldErrors"
					:focus-field="focusedField"
					@update:country-id="countryId = $event"
					@update:phone="phoneNumber = $event"
					@field-blur="validateField"
					@country-picker-visibility-change="countryPickerOpen = $event"
				/>

				<view class="auth-field">
					<label class="auth-label" for="admin-password">密码</label>
					<view class="auth-control password-row" :class="{ invalid: fieldErrors.password }">
						<input
							id="admin-password"
							v-model="password"
							class="auth-control-input password-input"
							:type="showPassword ? 'text' : 'password'"
							maxlength="16"
							passwordrules="required: minlength(8); maxlength(16);"
							autocomplete="current-password"
							placeholder="输入管理员密码"
							:focus="focusedField === 'password'"
							:aria-invalid="Boolean(fieldErrors.password)"
							:aria-describedby="fieldErrors.password ? 'admin-password-error' : 'admin-password-help'"
							@blur="validateField('password')"
						/>
						<button
							class="password-icon-toggle"
							type="button"
							:aria-label="showPassword ? '隐藏密码' : '显示密码'"
							:aria-pressed="showPassword"
							@click="showPassword = !showPassword"
						>
							<uni-icons
								:type="showPassword ? 'eye-slash' : 'eye'"
								size="22"
								color="#dceced"
							/>
						</button>
					</view>
					<text id="admin-password-help" class="auth-help">密码必须达到中等或更高强度。</text>
					<text v-if="fieldErrors.password" id="admin-password-error" class="auth-error" role="alert">{{ fieldErrors.password }}</text>
				</view>

				<button class="admin-submit" type="button" :loading="busy" :disabled="busy" @click="submitLogin">
					<text class="admin-submit-label">{{ busy ? '正在确认' : '确认登录' }}</text>
				</button>
			</view>
		</view>

		<view
			v-if="securityOverlayVisible"
			class="security-overlay"
			role="dialog"
			aria-modal="true"
			aria-labelledby="security-overlay-title"
		>
			<view class="security-backdrop" @click="closeSecurityOverlay"></view>
			<view class="security-dialog">
				<button
					class="security-close"
					type="button"
					aria-label="关闭安全验证"
					@click="closeSecurityOverlay"
				>
					<uni-icons type="closeempty" size="24" color="#f4f8ea" />
				</button>
				<view class="security-lock">
					<uni-icons type="locked-filled" size="26" color="#0b1108" />
				</view>
				<text id="security-overlay-title" class="security-title">安全验证</text>
				<text class="security-copy">邮箱、电话和密码强度已经通过本地校验。后续接入 hCaptcha 后，验证码会在这里显示。</text>
				<view class="hcaptcha-frame" aria-hidden="true">
					<text class="hcaptcha-brand">hCaptcha</text>
					<text class="hcaptcha-copy">验证容器预留</text>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
	import IdentifierFields from '@/components/auth/identifier-fields.vue'
	import { isValidEmailAddress } from '@/common/auth/email-validation.js'
	import { DEFAULT_PHONE_COUNTRY_ID } from '@/common/auth/phone-countries.js'
	import { findPhoneCountryById } from '@/common/auth/phone-country-search.js'
	import { isValidLocalPhoneNumber } from '@/common/auth/phone-validation.js'
	import { classifyPassword, passwordError } from '@/common/auth/password-policy.js'

	function emptyFieldErrors() {
		return { email: '', phoneNumber: '', password: '' }
	}

	export default {
		components: { IdentifierFields },
		data() {
			return {
				email: '',
				countryId: DEFAULT_PHONE_COUNTRY_ID,
				phoneNumber: '',
				password: '',
				showPassword: false,
				busy: false,
				message: '',
				messageType: '',
				securityOverlayVisible: false,
				fieldErrors: emptyFieldErrors(),
				focusedField: '',
				countryPickerOpen: false
			}
		},
		computed: {
			country() { return findPhoneCountryById(this.countryId) }
		},
		watch: {
			email() { this.fieldErrors.email = ''; this.clearMessage() },
			countryId() { this.fieldErrors.phoneNumber = ''; this.clearMessage() },
			phoneNumber() { this.fieldErrors.phoneNumber = ''; this.clearMessage() },
			password() { this.fieldErrors.password = ''; this.clearMessage() }
		},
		onBackPress() {
			if (this.securityOverlayVisible) {
				this.closeSecurityOverlay()
				return true
			}
			if (!this.countryPickerOpen) return false
			this.$refs.phoneFields?.closeCountryPicker()
			return true
		},
		methods: {
			clearMessage() {
				this.message = ''
				this.messageType = ''
			},
			closeSecurityOverlay() {
				this.securityOverlayVisible = false
			},
			passwordStrengthError() {
				const policyError = passwordError(this.password, this.password)
				if (policyError) return policyError
				const level = classifyPassword(this.password).level
				if (!['中', '强'].includes(level)) return '密码强度至少需要达到中等。'
				return ''
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
					this.fieldErrors.password = this.passwordStrengthError()
				}
				return !this.fieldErrors[field]
			},
			focusFirstInvalid() {
				const field = ['email', 'phoneNumber', 'password'].find(name => this.fieldErrors[name])
				if (!field) return
				this.focusedField = ''
				this.$nextTick(() => { this.focusedField = field })
			},
			submitLogin() {
				if (this.busy) return
				this.clearMessage()
				const emailValid = this.validateField('email')
				const phoneValid = this.validateField('phoneNumber')
				const passwordValid = this.validateField('password')
				if (!emailValid || !phoneValid || !passwordValid) {
					this.message = '请先修正标记字段，再继续。'
					this.messageType = 'warning'
					this.focusFirstInvalid()
					return
				}
				this.busy = true
				setTimeout(() => {
					this.busy = false
					this.securityOverlayVisible = true
				}, 180)
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/auth/auth-controls.scss';

	page {
		min-height: 100%;
		background: #080b0d;
	}

	.admin-page {
		min-height: 100vh;
		min-height: 100dvh;
		padding: calc(28rpx + env(safe-area-inset-top)) 28rpx calc(34rpx + env(safe-area-inset-bottom));
		background:
			radial-gradient(circle at 12% 18%, rgba(57, 214, 210, .18), transparent 28%),
			linear-gradient(135deg, #080b0d 0%, #0e1519 54%, #07090b 100%);
		box-sizing: border-box;
		color: #f3f8f8;
	}

	.admin-shell {
		width: 100%;
		max-width: 1180px;
		min-height: calc(100vh - 62rpx);
		margin: 0 auto;
		display: flex;
		flex-direction: column;
		gap: 28rpx;
		justify-content: center;
	}

	.admin-intro,
	.login-panel {
		box-sizing: border-box;
	}

	.admin-intro {
		padding: 10rpx 4rpx 0;
	}

	.admin-mark {
		width: 72rpx;
		height: 72rpx;
		margin-bottom: 24rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		border: 1px solid rgba(105, 212, 226, .36);
		border-radius: 18rpx;
		background: rgba(20, 29, 34, .78);
		box-shadow: 0 10rpx 24rpx rgba(0, 0, 0, .22);
	}

	.admin-mark-core {
		width: 28rpx;
		height: 28rpx;
		border-radius: 50%;
		background: #39d6d2;
		box-shadow: 0 0 20rpx rgba(57, 214, 210, .42);
	}

	.admin-kicker,
	.admin-title,
	.admin-copy,
	.panel-title,
	.panel-subtitle,
	.status-copy,
	.security-title,
	.security-copy,
	.hcaptcha-brand,
	.hcaptcha-copy {
		display: block;
	}

	.admin-kicker {
		color: #69d4e2;
		font-size: 24rpx;
		font-weight: 700;
		line-height: 1.35;
	}

	.admin-title {
		margin-top: 12rpx;
		color: #f3f8f8;
		font-size: 56rpx;
		font-weight: 760;
		line-height: 1.08;
		letter-spacing: 0;
	}

	.admin-copy {
		max-width: 560rpx;
		margin-top: 18rpx;
		color: #a8b8bd;
		font-size: 28rpx;
		line-height: 1.55;
	}

	.status-list {
		margin-top: 28rpx;
		display: flex;
		flex-direction: column;
		gap: 14rpx;
	}

	.status-item {
		display: flex;
		align-items: center;
		gap: 12rpx;
	}

	.status-dot {
		width: 14rpx;
		height: 14rpx;
		border-radius: 50%;
		background: #39d6d2;
		box-shadow: 0 0 16rpx rgba(57, 214, 210, .36);
	}

	.status-dot.amber {
		background: #f0bd62;
		box-shadow: 0 0 16rpx rgba(240, 189, 98, .28);
	}

	.status-copy {
		color: #c6d2d5;
		font-size: 24rpx;
		line-height: 1.45;
	}

	.login-panel {
		width: 100%;
		padding: 34rpx 30rpx;
		border: 1px solid rgba(105, 212, 226, .22);
		border-radius: 16rpx;
		background: rgba(16, 22, 26, .78);
		backdrop-filter: blur(18px) saturate(150%);
		box-shadow: 0 20rpx 60rpx rgba(0, 0, 0, .28);
		animation: panel-in 280ms cubic-bezier(.2, .8, .2, 1);
	}

	.panel-header {
		margin-bottom: 26rpx;
	}

	.panel-title {
		color: #f3f8f8;
		font-size: 38rpx;
		font-weight: 760;
		line-height: 1.2;
	}

	.panel-subtitle {
		margin-top: 8rpx;
		color: #91a2a8;
		font-size: 24rpx;
		line-height: 1.5;
	}

	.admin-banner {
		margin-bottom: 22rpx;
		padding: 18rpx 20rpx;
		border: 1px solid rgba(105, 212, 226, .24);
		border-radius: 10rpx;
		background: rgba(57, 214, 210, .09);
		color: #d9fbfb;
		font-size: 24rpx;
		line-height: 1.45;
	}

	.admin-banner.warning {
		border-color: rgba(240, 189, 98, .38);
		background: rgba(240, 189, 98, .1);
		color: #ffe2a8;
	}

	.admin-banner.success {
		border-color: rgba(57, 214, 210, .38);
	}

	.password-icon-toggle {
		width: 72rpx;
		height: 72rpx;
		min-width: 72rpx;
		margin: 0;
		padding: 0;
		display: flex;
		align-items: center;
		justify-content: center;
		border: 0;
		border-radius: 10rpx;
		background: transparent;
		line-height: 1;
		transition: background-color 160ms ease, transform 160ms ease;
	}

	.password-icon-toggle::after,
	.admin-submit::after,
	.security-close::after {
		border: 0;
	}

	.password-icon-toggle:active {
		background: rgba(57, 214, 210, .1);
		transform: scale(.96);
	}

	.admin-submit {
		width: 100%;
		height: 92rpx;
		min-height: 92rpx;
		margin: 0;
		padding: 0 24rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		border: 0;
		border-radius: 12rpx;
		background: linear-gradient(135deg, #d2e85c 0%, #97c93f 100%);
		color: #101707;
		font-size: 30rpx;
		font-weight: 780;
		line-height: 1.15;
		text-align: center;
		box-shadow: 0 12rpx 26rpx rgba(151, 201, 63, .24);
		transition: background-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
	}

	.admin-submit-label {
		width: 100%;
		line-height: 1.15;
		text-align: center;
	}

	.admin-submit:active {
		background: linear-gradient(135deg, #c8df52 0%, #86b837 100%);
		transform: scale(.985);
		box-shadow: 0 7rpx 18rpx rgba(151, 201, 63, .2);
	}

	.admin-submit[disabled] {
		opacity: .58;
		box-shadow: none;
	}

	.security-overlay {
		position: fixed;
		inset: 0;
		z-index: 50;
		display: flex;
		align-items: center;
		justify-content: center;
		padding: calc(32rpx + env(safe-area-inset-top)) 28rpx calc(32rpx + env(safe-area-inset-bottom));
		box-sizing: border-box;
	}

	.security-backdrop {
		position: absolute;
		inset: 0;
		background: rgba(3, 7, 9, .72);
		backdrop-filter: blur(18px) saturate(126%);
	}

	.security-dialog {
		position: relative;
		z-index: 1;
		width: 100%;
		max-width: 520rpx;
		padding: 54rpx 34rpx 34rpx;
		border: 1px solid rgba(214, 232, 92, .32);
		border-radius: 18rpx;
		background: rgba(15, 20, 18, .92);
		box-shadow: 0 30rpx 80rpx rgba(0, 0, 0, .42);
		box-sizing: border-box;
		animation: security-in 260ms cubic-bezier(.2, .8, .2, 1);
	}

	.security-close {
		position: absolute;
		top: 14rpx;
		right: 14rpx;
		width: 60rpx;
		height: 60rpx;
		margin: 0;
		padding: 0;
		display: flex;
		align-items: center;
		justify-content: center;
		border: 0;
		border-radius: 999px;
		background: rgba(255, 255, 255, .06);
		line-height: 1;
	}

	.security-lock {
		width: 64rpx;
		height: 64rpx;
		margin-bottom: 22rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		border-radius: 18rpx;
		background: #d2e85c;
		box-shadow: 0 0 26rpx rgba(210, 232, 92, .24);
	}

	.security-title {
		color: #f4f8ea;
		font-size: 38rpx;
		font-weight: 780;
		line-height: 1.2;
	}

	.security-copy {
		margin-top: 12rpx;
		color: #b7c2bb;
		font-size: 24rpx;
		line-height: 1.55;
	}

	.hcaptcha-frame {
		min-height: 132rpx;
		margin-top: 28rpx;
		padding: 24rpx;
		display: flex;
		flex-direction: column;
		justify-content: center;
		border: 1px dashed rgba(214, 232, 92, .4);
		border-radius: 14rpx;
		background: rgba(214, 232, 92, .08);
		box-sizing: border-box;
	}

	.hcaptcha-brand {
		color: #edf6a6;
		font-size: 28rpx;
		font-weight: 780;
		line-height: 1.25;
	}

	.hcaptcha-copy {
		margin-top: 8rpx;
		color: #c7cda8;
		font-size: 22rpx;
		line-height: 1.45;
	}

	@keyframes panel-in {
		from { opacity: 0; transform: translateY(14rpx) scale(.99); }
		to { opacity: 1; transform: translateY(0) scale(1); }
	}

	@keyframes security-in {
		from { opacity: 0; transform: translateY(18rpx) scale(.98); }
		to { opacity: 1; transform: translateY(0) scale(1); }
	}

	@media screen and (min-width: 760px) {
		.admin-page {
			padding: 56px 42px;
		}
		.admin-shell {
			min-height: calc(100vh - 112px);
			display: grid;
			grid-template-columns: minmax(300px, 1fr) minmax(360px, 460px);
			align-items: center;
			gap: 56px;
		}
		.admin-intro {
			padding-top: 0;
		}
		.admin-title {
			font-size: 44px;
		}
		.admin-copy {
			font-size: 17px;
		}
		.login-panel {
			padding: 34px 32px;
		}
	}

	@media (hover: hover) and (pointer: fine) {
		.password-icon-toggle:hover {
			background: rgba(57, 214, 210, .1);
		}
		.admin-submit:hover {
			background: linear-gradient(135deg, #dbef66 0%, #a7d84c 100%);
		}
		.security-close:hover {
			background: rgba(255, 255, 255, .1);
		}
	}

	@media (prefers-reduced-motion: reduce) {
		.login-panel,
		.security-dialog {
			animation: none;
		}
		.password-icon-toggle,
		.admin-submit,
		.security-close {
			transition: none;
		}
	}

	@media (prefers-reduced-transparency: reduce) {
		.login-panel,
		.security-dialog {
			background: #10161a;
			backdrop-filter: none;
		}
		.security-backdrop {
			background: rgba(3, 7, 9, .9);
			backdrop-filter: none;
		}
	}
</style>

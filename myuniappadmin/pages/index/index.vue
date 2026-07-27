<template>
	<view class="admin-page">
		<view class="admin-shell">
			<view class="admin-intro">
				<view class="admin-mark" aria-hidden="true"><view class="admin-mark-core" /></view>
				<text class="admin-kicker">AI TEMPERATE · ADMIN</text>
				<text class="admin-title">{{ pageTitle }}</text>
				<text class="admin-copy">{{ pageCopy }}</text>
				<view class="security-note">
					<text>单管理员 · hCaptcha 后端校验 · 六小时滑动会话</text>
				</view>
			</view>

			<view class="admin-panel">
				<view v-if="message" class="admin-banner" :class="messageType" role="alert">
					{{ message }}
				</view>

				<view v-if="screenState === 'LOADING'" class="center-state" aria-live="polite">
					<view class="loading-ring" aria-hidden="true" />
					<text>正在检查管理员配置与会话…</text>
				</view>

				<view v-else-if="screenState === 'CORRUPT' || screenState === 'DISABLED'" class="center-state">
					<text class="state-title">{{ screenState === 'CORRUPT' ? '管理员配置需要检查' : '管理员已停用' }}</text>
					<text class="state-copy">
						{{ screenState === 'CORRUPT'
							? '请检查 .admin/complete.yaml 与初始化标记。系统已关闭注册和登录。'
							: '管理员配置当前为 DISABLED，认证与管理接口均不可用。' }}
					</text>
					<button class="primary-button" type="button" :loading="busy" @click="loadState(true)">重新检查</button>
				</view>

				<view v-else-if="screenState === 'AUTHENTICATED'" class="dashboard">
					<view class="profile-badge">{{ profileFlag }}</view>
					<text class="state-title">管理员会话已验证</text>
					<view class="profile-row">
						<text class="profile-label">邮箱</text>
						<text class="profile-value">{{ profile.email }}</text>
					</view>
					<view class="profile-row">
						<text class="profile-label">国际手机号</text>
						<text class="profile-value">{{ profile.phoneE164 }}</text>
					</view>
					<view class="profile-row">
						<text class="profile-label">会话到期</text>
						<text class="profile-value">{{ expiresLabel }}</text>
					</view>
					<button class="credential-button" type="button" :disabled="busy" @click="navigateToIp2LocationKeys">
						<text class="credential-kicker">NETWORK RISK</text>
						<text class="credential-title">IP 信誉凭据</text>
						<text class="credential-copy">导入、查看和撤销 IP2Location 加密调用凭据</text>
					</button>
					<button class="credential-button model-catalog-button" type="button" :disabled="busy" @click="navigateToAiModels">
						<text class="credential-kicker">MODEL OPERATIONS</text>
						<text class="credential-title">AI 模型目录</text>
						<text class="credential-copy">分页查询、新增、编辑并单个或批量启停模型</text>
					</button>
					<button class="credential-button model-catalog-button" type="button" :disabled="busy" @click="navigateToAiModelIcons">
						<text class="credential-kicker">MODEL ASSETS</text>
						<text class="credential-title">模型图标库</text>
						<text class="credential-copy">上传 OSS 图片、登记外部 URL 并维护模型复用图标</text>
					</button>
					<button class="primary-button" type="button" :loading="busy" @click="refreshProfile">刷新会话</button>
					<button class="secondary-button" type="button" :disabled="busy" @click="logoutCurrent">退出当前设备</button>
					<button class="danger-button" type="button" :disabled="busy" @click="logoutEverywhere">退出所有设备</button>
				</view>

				<view v-else-if="screenState === 'UNINITIALIZED'">
					<view class="step-row" aria-label="注册进度">
						<view v-for="(label, index) in registerSteps" :key="label" class="step-item">
							<view class="step-dot" :class="{ active: index <= registerStepIndex }">{{ index + 1 }}</view>
							<text>{{ label }}</text>
						</view>
					</view>

					<view v-if="registerStep === 'IDENTITY'">
						<text class="panel-title">初始化唯一管理员</text>
						<text class="panel-copy">邮箱和国际手机号将同时成为登录凭证，初始化后不能在线修改。</text>
						<identity-form
							v-model:email="form.email"
							:country-id="form.countryId"
							:country-resolving="countryResolving"
							v-model:phone="form.phoneNumber"
							:errors="fieldErrors"
							@update:country-id="selectCountry"
						/>
						<button class="primary-button" type="button" :loading="busy" @click="startRegistration">
							继续并完成人机验证
						</button>
					</view>

					<view v-else-if="registerStep === 'VERIFICATION'">
						<text class="panel-title">验证邮箱与手机号</text>
						<text class="panel-copy">两个验证码必须在同一次提交中同时正确。</text>
						<view class="dispatch-grid">
							<button class="secondary-button" type="button" :disabled="busy" @click="sendEmailCode">发送邮箱验证码</button>
							<button class="secondary-button" type="button" :disabled="busy" @click="sendPhoneCode">发送手机验证码</button>
						</view>
						<view v-if="allowWhatsApp" class="delivery-row" role="radiogroup" aria-label="手机验证码投递方式">
							<button type="button" :class="{ selected: deliveryMethod === 'SMS' }" @click="deliveryMethod = 'SMS'">SMS</button>
							<button type="button" :class="{ selected: deliveryMethod === 'WHATSAPP' }" @click="deliveryMethod = 'WHATSAPP'">WhatsApp</button>
						</view>
						<verification-code-fields
							:email-code="form.emailCode"
							:phone-code="form.phoneCode"
							:errors="fieldErrors"
							@update:email-code="updateEmailCode"
							@update:phone-code="updatePhoneCode"
						/>
						<button class="primary-button" type="button" :loading="busy" @click="verifyCodes">同时校验两个验证码</button>
					</view>

					<view v-else>
						<text class="panel-title">设置管理员密码</text>
						<text class="panel-copy">密码强度至少为中，且不得超过 72 个 UTF-8 字节。</text>
						<password-fields
							v-model:password="form.password"
							v-model:confirmation="form.passwordConfirmation"
							:errors="fieldErrors"
						/>
						<button class="primary-button" type="button" :loading="busy" @click="completeRegistration">完成初始化</button>
					</view>
				</view>

				<view v-else>
					<text class="panel-title">管理员登录</text>
					<text class="panel-copy">邮箱、国际手机号和密码必须全部匹配；每次登录均需完成 hCaptcha。</text>
					<identity-form
						v-model:email="form.email"
						:country-id="form.countryId"
						:country-resolving="countryResolving"
						v-model:phone="form.phoneNumber"
						:errors="fieldErrors"
						@update:country-id="selectCountry"
					/>
					<login-password-field
						:model-value="form.password"
						:error="fieldErrors.password"
						@update:model-value="updateLoginPassword"
					/>
					<button class="primary-button" type="button" :loading="busy" @click="login">hCaptcha 验证并登录</button>
				</view>
			</view>
		</view>
	</view>
</template>

	<script>
	import IdentityForm from '@/components/admin/admin-identity-form.vue'
	import PasswordFields from '@/components/admin/admin-password-fields.vue'
	import VerificationCodeFields from '@/components/admin/admin-verification-code-fields.vue'
	import LoginPasswordField from '@/components/admin/admin-login-password-field.vue'
	import { adminApi } from '@/common/admin/admin-api.js'
	import { requestAdminHcaptchaToken } from '@/common/admin/admin-hcaptcha.js'
	import { clearAdminFlow } from '@/common/admin/admin-secure-vault.js'
	import {
		getCurrentAdminPhoneCountrySelection,
		resolveInitialAdminPhoneCountry,
		selectAdminPhoneCountry
	} from '@/common/admin/admin-phone-country-default.js'
	import { isValidEmailAddress } from '@shared-auth/email-validation.js'
	import { findPhoneCountryById } from '@shared-auth/phone-country-search.js'
	import { digitsOnlyPhoneInput, isValidLocalPhoneNumber } from '@shared-auth/phone-validation.js'
	import { passwordError } from '@shared-auth/password-policy.js'

	const flagFromIso2 = iso2 => String(iso2 || '').toUpperCase().replace(/[A-Z]/g,
		character => String.fromCodePoint(127397 + character.charCodeAt(0)))

	const emptyErrors = () => ({
		email: '',
		phoneNumber: '',
		emailCode: '',
		phoneCode: '',
		password: '',
		passwordConfirmation: ''
	})

	export default {
		components: { IdentityForm, PasswordFields, VerificationCodeFields, LoginPasswordField },
		data() {
			return {
				screenState: 'LOADING',
				registerStep: 'IDENTITY',
				registerSteps: ['身份', '双重验证', '密码'],
				busy: false,
				message: '',
				messageType: '',
				countryResolving: false,
				siteKey: '',
				deliveryMethod: 'SMS',
				fieldErrors: emptyErrors(),
				profile: {},
				form: {
					email: '',
					countryId: '',
					phoneNumber: '',
					emailCode: '',
					phoneCode: '',
					password: '',
					passwordConfirmation: ''
				}
			}
		},
		computed: {
			registerStepIndex() {
				return { IDENTITY: 0, VERIFICATION: 1, PASSWORD: 2 }[this.registerStep] || 0
			},
			country() { return findPhoneCountryById(this.form.countryId) },
			allowWhatsApp() { return this.country?.iso2 !== 'CN' },
			phoneE164() {
				const dial = digitsOnlyPhoneInput(this.country?.dialCode || '')
				const local = digitsOnlyPhoneInput(this.form.phoneNumber)
				return dial && local ? `+${dial}${local}` : ''
			},
			pageTitle() {
				if (this.screenState === 'AUTHENTICATED') return '管理员控制台'
				if (this.screenState === 'UNINITIALIZED') return '首次安全初始化'
				return '管理员身份入口'
			},
			pageCopy() {
				if (this.screenState === 'UNINITIALIZED') return '一次性创建唯一管理员。完成后注册入口永久关闭。'
				if (this.screenState === 'AUTHENTICATED') return '会话已绑定当前设备；任何受保护操作都会滑动续期。'
				return '配置状态、凭证校验与会话边界均由后端强制执行。'
			},
			profileFlag() { return flagFromIso2(this.profile.countryIso2) || 'A' },
			expiresLabel() {
				if (!this.profile.expiresAt) return '未知'
				return new Date(this.profile.expiresAt).toLocaleString()
			}
		},
		onLoad() {
			this.loadState(false)
			this.resolveInitialCountry()
		},
		methods: {
			navigateToIp2LocationKeys() {
				uni.navigateTo({ url: '/pages/risk/ip2location-keys' })
			},
			navigateToAiModels() {
				uni.navigateTo({ url: '/pages/ai-models/index' })
			},
			navigateToAiModelIcons() {
				uni.navigateTo({ url: '/pages/ai-model-icons/index' })
			},
			updateEmailCode(value) {
				this.form.emailCode = value
				this.fieldErrors.emailCode = ''
			},
			updatePhoneCode(value) {
				this.form.phoneCode = value
				this.fieldErrors.phoneCode = ''
			},
			updateLoginPassword(value) {
				this.form.password = value
				this.fieldErrors.password = ''
			},
			async resolveInitialCountry() {
				const current = getCurrentAdminPhoneCountrySelection()
				if (current.countryId) {
					this.form.countryId = current.countryId
					return
				}
				this.countryResolving = true
				try {
					const resolved = await resolveInitialAdminPhoneCountry()
					this.form.countryId = resolved.countryId || ''
				} finally {
					this.countryResolving = false
				}
			},
			selectCountry(countryId) {
				const selected = selectAdminPhoneCountry(countryId)
				this.form.countryId = selected.countryId
			},
			setError(error) {
				this.message = error?.message || '请求未完成，请稍后重试。'
				this.messageType = 'error'
			},
			clearMessage() {
				this.message = ''
				this.messageType = ''
			},
			async run(action) {
				if (this.busy) return
				this.busy = true
				this.clearMessage()
				try {
					return await action()
				} catch (error) {
					this.setError(error)
					throw error
				} finally {
					this.busy = false
				}
			},
			async loadState(recheck) {
				if (this.busy) return
				this.screenState = 'LOADING'
				try {
					await this.run(async () => {
						const state = await adminApi.state()
						if (!['UNINITIALIZED', 'ACTIVE', 'CORRUPT', 'DISABLED'].includes(state?.state)) {
							throw new Error('管理员配置状态响应无效。')
						}
						this.screenState = state.state
						if (state.state === 'UNINITIALIZED') {
							if (adminApi.hasRegistrationFlow()) {
								try {
									const registration = await adminApi.registerStatus()
									if (registration.emailVerified && registration.phoneVerified) {
										this.registerStep = 'PASSWORD'
									} else if (registration.humanVerified) {
										this.registerStep = 'VERIFICATION'
									}
								} catch (_) {
									clearAdminFlow('register')
									this.registerStep = 'IDENTITY'
								}
							} else {
								this.registerStep = 'IDENTITY'
							}
						}
						if (state.state === 'ACTIVE') {
							try {
								this.profile = await adminApi.bootstrap()
								this.screenState = 'AUTHENTICATED'
							} catch (error) {
								if (error.code !== 'ADMIN_SESSION_INVALID') throw error
								this.screenState = 'ACTIVE'
							}
						}
					})
				} catch (_) {
					// 无法确认配置状态时前端同样 Fail Closed，绝不猜测为可登录或可注册。
					if (this.screenState === 'LOADING') this.screenState = 'CORRUPT'
				}
			},
			validateIdentity() {
				this.fieldErrors = emptyErrors()
				if (!isValidEmailAddress(this.form.email)) this.fieldErrors.email = '请输入有效邮箱。'
				if (!this.country || !isValidLocalPhoneNumber(this.form.phoneNumber, this.country.iso2)) {
					this.fieldErrors.phoneNumber = this.country
						? '请输入与所选国家或地区匹配的有效手机号。'
						: '请选择国家或地区。'
				}
				return !this.fieldErrors.email && !this.fieldErrors.phoneNumber
			},
			async hcaptchaToken(siteKey, challengeId) {
				const key = siteKey || this.siteKey || (await adminApi.hcaptchaConfig()).siteKey
				this.siteKey = key
				// 一次性 Token 仅存在于当前调用栈；提交或异常后不写入组件状态、日志或持久化存储。
				return requestAdminHcaptchaToken(key, challengeId)
			},
			async startRegistration() {
				if (!this.validateIdentity()) return
				try {
					await this.run(async () => {
						const started = await adminApi.registerStart({
							email: this.form.email.trim(),
							countryIso2: this.country.iso2,
							phoneNumber: this.phoneE164
						})
						const token = await this.hcaptchaToken(started.siteKey, started.challengeId)
						await adminApi.registerHcaptcha(token)
						this.registerStep = 'VERIFICATION'
						this.message = 'hCaptcha 已通过，请发送并填写两个验证码。'
						this.messageType = 'success'
					})
				} catch (_) {}
			},
			async sendEmailCode() {
				try {
					await this.run(() => adminApi.registerSendEmail())
					this.message = '邮箱验证码发送请求已受理。'
					this.messageType = 'success'
				} catch (_) {}
			},
			async sendPhoneCode() {
				if (!this.allowWhatsApp) this.deliveryMethod = 'SMS'
				try {
					await this.run(() => adminApi.registerSendPhone(this.deliveryMethod))
					this.message = `${this.deliveryMethod} 验证码发送请求已受理。`
					this.messageType = 'success'
				} catch (_) {}
			},
			async verifyCodes() {
				this.fieldErrors.emailCode = /^\d{6}$/.test(this.form.emailCode)
					? ''
					: '请输入 6 位邮箱验证码。'
				this.fieldErrors.phoneCode = /^\d{6}$/.test(this.form.phoneCode)
					? ''
					: '请输入 6 位手机验证码。'
				if (this.fieldErrors.emailCode || this.fieldErrors.phoneCode) {
					this.message = '邮箱和手机验证码都必须是 6 位数字。'
					this.messageType = 'error'
					return
				}
				try {
					await this.run(() => adminApi.registerVerify(this.form.emailCode, this.form.phoneCode))
					this.form.emailCode = ''
					this.form.phoneCode = ''
					this.registerStep = 'PASSWORD'
				} catch (_) {}
			},
			async completeRegistration() {
				const error = passwordError(this.form.password, this.form.passwordConfirmation)
				this.fieldErrors.password = error
				if (error) return
				try {
					await this.run(() => adminApi.registerComplete(
						this.form.password, this.form.passwordConfirmation))
					this.form.password = ''
					this.form.passwordConfirmation = ''
					this.registerStep = 'IDENTITY'
					this.screenState = 'ACTIVE'
					this.message = '管理员初始化完成，请使用三项凭证登录。'
					this.messageType = 'success'
				} catch (_) {}
			},
			async login() {
				if (!this.validateIdentity()) return
				if (!this.form.password) {
					this.fieldErrors.password = '请输入管理员密码。'
					return
				}
				try {
					await this.run(async () => {
						const started = await adminApi.loginStart()
						const hcaptchaToken = await this.hcaptchaToken(
							started.siteKey, started.challengeId)
						const response = await adminApi.loginComplete({
							email: this.form.email.trim(),
							countryIso2: this.country.iso2,
							phoneNumber: this.phoneE164,
							password: this.form.password,
							hcaptchaToken
						})
						this.form.password = ''
						this.profile = response.admin
						this.screenState = 'AUTHENTICATED'
					})
				} catch (_) {
					this.form.password = ''
					clearAdminFlow('login')
				}
			},
			async refreshProfile() {
				try {
					await this.run(async () => {
						this.profile = await adminApi.me()
						this.message = '管理员会话已续期。'
						this.messageType = 'success'
					})
				} catch (error) {
					if (error.code === 'ADMIN_SESSION_INVALID') this.screenState = 'ACTIVE'
				}
			},
			async logoutCurrent() {
				try {
					await this.run(() => adminApi.logout())
					this.profile = {}
					this.screenState = 'ACTIVE'
				} catch (_) {}
			},
			async logoutEverywhere() {
				try {
					await this.run(() => adminApi.logoutAll())
					this.profile = {}
					this.screenState = 'ACTIVE'
				} catch (_) {}
			}
		}
	}
</script>

<style lang="scss">
	page { min-height: 100%; background: #080b0d; }
	button::after { border: 0; }
	.admin-page {
		min-height: 100vh;
		padding: calc(30rpx + env(safe-area-inset-top)) 28rpx calc(36rpx + env(safe-area-inset-bottom));
		box-sizing: border-box;
		color: #f3f8f8;
		background:
			radial-gradient(circle at 12% 16%, rgba(57, 214, 210, .17), transparent 28%),
			linear-gradient(135deg, #080b0d 0%, #0e1519 54%, #07090b 100%);
	}
	.admin-shell { width: 100%; max-width: 1180px; margin: 0 auto; display: grid; gap: 30rpx; }
	.admin-intro { padding: 10rpx 4rpx; }
	.admin-mark {
		width: 72rpx; height: 72rpx; display: flex; align-items: center; justify-content: center;
		border: 1px solid rgba(105, 212, 226, .36); border-radius: 18rpx; background: rgba(20, 29, 34, .78);
	}
	.admin-mark-core { width: 28rpx; height: 28rpx; border-radius: 50%; background: #39d6d2; box-shadow: 0 0 20rpx rgba(57,214,210,.42); }
	.admin-kicker, .admin-title, .admin-copy, .panel-title, .panel-copy, .state-title, .state-copy { display: block; }
	.admin-kicker { margin-top: 22rpx; color: #69d4e2; font-size: 24rpx; font-weight: 700; }
	.admin-title { margin-top: 12rpx; font-size: 54rpx; font-weight: 760; line-height: 1.08; }
	.admin-copy { max-width: 620rpx; margin-top: 18rpx; color: #a8b8bd; font-size: 27rpx; line-height: 1.55; }
	.security-note { margin-top: 24rpx; color: #c6d2d5; font-size: 23rpx; }
	.admin-panel {
		padding: 34rpx 30rpx; border: 1px solid rgba(105, 212, 226, .22); border-radius: 18rpx;
		background: rgba(16, 22, 26, .84); backdrop-filter: blur(18px); box-shadow: 0 20rpx 60rpx rgba(0,0,0,.28);
	}
	.panel-title, .state-title { color: #f3f8f8; font-size: 36rpx; font-weight: 760; line-height: 1.2; }
	.panel-copy, .state-copy { margin: 10rpx 0 26rpx; color: #91a2a8; font-size: 24rpx; line-height: 1.55; }
	.admin-banner { margin-bottom: 22rpx; padding: 18rpx 20rpx; border-radius: 10rpx; background: rgba(57,214,210,.1); color: #d9fbfb; font-size: 24rpx; }
	.admin-banner.error { background: rgba(232,98,98,.12); color: #ffb8b8; }
	.admin-banner.success { background: rgba(57,214,210,.12); color: #c8ffff; }
	.center-state { min-height: 360rpx; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 20rpx; text-align: center; color: #a8b8bd; }
	.loading-ring { width: 48rpx; height: 48rpx; border: 4rpx solid rgba(105,212,226,.2); border-top-color: #69d4e2; border-radius: 50%; animation: spin .8s linear infinite; }
	.step-row { display: flex; justify-content: space-between; margin-bottom: 30rpx; color: #91a2a8; font-size: 20rpx; }
	.step-item { display: flex; align-items: center; gap: 8rpx; }
	.step-dot { width: 38rpx; height: 38rpx; display: flex; align-items: center; justify-content: center; border-radius: 50%; background: #263238; }
	.step-dot.active { background: #39d6d2; color: #071012; }
	.primary-button, .secondary-button, .danger-button {
		width: 100%; min-height: 86rpx; margin: 24rpx 0 0; border: 0; border-radius: 12rpx;
		display: flex; align-items: center; justify-content: center; font-size: 27rpx; font-weight: 740;
	}
	.primary-button { background: linear-gradient(135deg, #d2e85c, #97c93f); color: #101707; }
	.secondary-button { border: 1px solid rgba(105,212,226,.34); background: rgba(57,214,210,.08); color: #d9fbfb; }
	.danger-button { border: 1px solid rgba(232,98,98,.35); background: rgba(232,98,98,.08); color: #ffb8b8; }
	.credential-button {
		width: 100%; min-height: 126rpx; margin-top: 28rpx; padding: 22rpx 24rpx; border: 1px solid rgba(57,214,210,.32);
		border-radius: 14rpx; background: rgba(57,214,210,.06); color: #f3f8f8; text-align: left;
		display: flex; flex-direction: column; align-items: flex-start; justify-content: center;
	}
	.model-catalog-button { border-color: rgba(105,212,226,.42); background: linear-gradient(135deg, rgba(57,214,210,.08), rgba(105,212,226,.04)); }
	.credential-kicker { color: #39d6d2; font-size: 18rpx; font-weight: 760; letter-spacing: .12em; }
	.credential-title { margin-top: 6rpx; font-size: 28rpx; font-weight: 760; }
	.credential-copy { margin-top: 6rpx; color: #91a2a8; font-size: 21rpx; line-height: 1.45; }
	.dispatch-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14rpx; }
	.delivery-row { display: flex; gap: 12rpx; margin-top: 16rpx; }
	.delivery-row button { flex: 1; margin: 0; border: 1px solid rgba(145,162,168,.3); background: transparent; color: #a8b8bd; font-size: 23rpx; }
	.delivery-row button.selected { border-color: #39d6d2; background: rgba(57,214,210,.12); color: #d9fbfb; }
	.profile-badge { width: 88rpx; height: 88rpx; margin-bottom: 20rpx; display: flex; align-items: center; justify-content: center; border-radius: 24rpx; background: rgba(57,214,210,.12); font-size: 45rpx; }
	.profile-row { padding: 22rpx 0; border-bottom: 1px solid rgba(145,162,168,.16); }
	.profile-label, .profile-value { display: block; }
	.profile-label { color: #91a2a8; font-size: 21rpx; }
	.profile-value { margin-top: 7rpx; color: #f3f8f8; font-size: 27rpx; overflow-wrap: anywhere; }
	@keyframes spin { to { transform: rotate(360deg); } }
	@media screen and (min-width: 760px) {
		.admin-page { padding: 56px 42px; }
		.admin-shell { min-height: calc(100vh - 112px); grid-template-columns: minmax(300px, 1fr) minmax(400px, 500px); align-items: center; gap: 60px; }
		.admin-title { font-size: 44px; }
		.admin-panel { padding: 36px 34px; }
	}
	@media (prefers-reduced-motion: reduce) { .loading-ring { animation: none; } }
</style>

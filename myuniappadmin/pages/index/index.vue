<template>
	<admin-page-shell
		v-if="screenState === 'AUTHENTICATED'"
		current-path="/pages/index/index"
		kicker="管理员工作台"
		title="控制台"
		description="集中查看当前管理员会话，并进入模型、凭据与邮件证据工作流。"
		:busy="busy"
		@navigate="navigateProtected"
	>
		<template #meta>
			<view class="session-chip">
				<view class="session-chip-dot" aria-hidden="true" />
				<text>会话已验证</text>
			</view>
			<text class="session-expiry">滑动到期：{{ expiresLabel }}</text>
		</template>
		<template #actions>
			<admin-action-button tone="teal" size="compact" :loading="busy" @click="refreshProfile">
				刷新会话
			</admin-action-button>
			<admin-action-button tone="neutral" size="compact" :disabled="busy" @click="logoutCurrent">
				退出当前设备
			</admin-action-button>
		</template>

		<admin-feedback-banner
			v-if="message"
			:tone="feedbackTone"
			:message="message"
			:dismissible="true"
			@dismiss="clearMessage"
		/>

		<view class="dashboard-overview">
			<view class="session-panel">
				<view class="session-identity">
					<view class="profile-badge">{{ profileFlag }}</view>
					<view>
						<text class="section-heading">管理员会话</text>
						<text class="section-copy">当前设备已通过后端会话校验。</text>
					</view>
				</view>
				<view class="profile-list">
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
				</view>
			</view>

			<view class="operations-panel">
				<view class="section-intro">
					<view>
						<text class="section-heading">工作区</text>
						<text class="section-copy">选择任务后，前端会再次确认管理员会话。</text>
					</view>
					<text class="operation-count">4 个入口</text>
				</view>
				<view class="operation-list">
					<button class="operation-row" type="button" :disabled="busy" @click="navigateToAiModels">
						<text class="operation-symbol">AI</text>
						<view class="operation-copy">
							<text class="operation-title">AI 模型目录</text>
							<text class="operation-description">分页查询、新增、编辑并单个或批量启停模型</text>
						</view>
						<text class="operation-arrow" aria-hidden="true">›</text>
					</button>
					<button class="operation-row" type="button" :disabled="busy" @click="navigateToAiModelIcons">
						<text class="operation-symbol">◇</text>
						<view class="operation-copy">
							<text class="operation-title">模型图标库</text>
							<text class="operation-description">上传 OSS 图片、登记外部 URL 并维护复用图标</text>
						</view>
						<text class="operation-arrow" aria-hidden="true">›</text>
					</button>
					<button class="operation-row" type="button" :disabled="busy" @click="navigateToIp2LocationKeys">
						<text class="operation-symbol">IP</text>
						<view class="operation-copy">
							<text class="operation-title">IP 信誉凭据</text>
							<text class="operation-description">导入、查看和撤销 IP2Location 加密调用凭据</text>
						</view>
						<text class="operation-arrow" aria-hidden="true">›</text>
					</button>
					<button class="operation-row" type="button" :disabled="busy" @click="navigateToMailInspection">
						<text class="operation-symbol">@</text>
						<view class="operation-copy">
							<text class="operation-title">邮箱证据检查</text>
							<text class="operation-description">Microsoft OAuth · Outlook IMAP · OpenAI / Kiro / IP2Location</text>
						</view>
						<text class="operation-arrow" aria-hidden="true">›</text>
					</button>
				</view>
			</view>

			<view class="security-panel">
				<view>
					<text class="section-heading">安全边界</text>
					<text class="section-copy">所有权限仍由后端强制执行，前端守卫只负责拦截页面和减少无效请求。</text>
				</view>
				<view class="security-facts">
					<text>单管理员</text>
					<text>hCaptcha 后端校验</text>
					<text>六小时滑动会话</text>
				</view>
				<admin-action-button tone="danger" size="compact" :disabled="busy" @click="logoutEverywhere">
					退出所有设备
				</admin-action-button>
			</view>
		</view>
	</admin-page-shell>

	<view v-else class="admin-auth-page">
		<view class="ambient-light ambient-light-primary" aria-hidden="true" />
		<view class="ambient-light ambient-light-secondary" aria-hidden="true" />
		<view class="auth-shell">
			<view class="admin-intro">
				<view class="admin-mark" aria-hidden="true"><view class="admin-mark-core" /></view>
				<text class="admin-kicker">AI Temperate 管理端</text>
				<text class="admin-title">{{ pageTitle }}</text>
				<text class="admin-copy">{{ pageCopy }}</text>
				<view class="security-note">
					<view class="security-note-dot" aria-hidden="true" />
					<text>单管理员 · hCaptcha 后端校验 · 六小时滑动会话</text>
				</view>
			</view>

			<view class="admin-panel">
				<admin-feedback-banner
					v-if="message"
					:tone="feedbackTone"
					:message="message"
					:dismissible="true"
					@dismiss="clearMessage"
				/>

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
	import {
		guardedAdminNavigate,
		markAdminSessionAuthenticated
	} from '@/common/admin/admin-route-guard-runtime.js'
	import { takeAdminSessionExpiryNotice } from '@/common/admin/admin-session-expiry-navigation.js'
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
			feedbackTone() {
				return {
					error: 'danger',
					success: 'success',
					warning: 'warning'
				}[this.messageType] || 'info'
			},
			profileFlag() { return flagFromIso2(this.profile.countryIso2) || 'A' },
			expiresLabel() {
				if (!this.profile.expiresAt) return '未知'
				return new Date(this.profile.expiresAt).toLocaleString()
			}
		},
		onLoad() {
			const sessionNotice = takeAdminSessionExpiryNotice()
			this.loadState(false).finally(() => {
				if (sessionNotice) {
					this.message = sessionNotice
					this.messageType = 'error'
				}
			})
			this.resolveInitialCountry()
		},
		methods: {
			navigateProtected(route) {
				if (route === '/pages/index/index') return
				return guardedAdminNavigate(route)
			},
			navigateToIp2LocationKeys() {
				return guardedAdminNavigate('/pages/risk/ip2location-keys')
			},
			navigateToAiModels() {
				return guardedAdminNavigate('/pages/ai-models/index')
			},
			navigateToAiModelIcons() {
				return guardedAdminNavigate('/pages/ai-model-icons/index')
			},
			navigateToMailInspection() {
				return guardedAdminNavigate('/pages/mail-inspection/openai/index')
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
								markAdminSessionAuthenticated()
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
						markAdminSessionAuthenticated()
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
					if (error.code === 'ADMIN_SESSION_INVALID') {
						this.profile = {}
						this.screenState = 'ACTIVE'
					}
				}
			},
			async logoutCurrent() {
				try {
					await this.run(() => adminApi.logout())
					this.profile = {}
					this.screenState = 'ACTIVE'
				} catch (error) {
					if (error.code === 'ADMIN_SESSION_INVALID') {
						this.profile = {}
						this.screenState = 'ACTIVE'
					}
				}
			},
			async logoutEverywhere() {
				try {
					await this.run(() => adminApi.logoutAll())
					this.profile = {}
					this.screenState = 'ACTIVE'
				} catch (error) {
					if (error.code === 'ADMIN_SESSION_INVALID') {
						this.profile = {}
						this.screenState = 'ACTIVE'
					}
				}
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/app-theme.scss';

	page {
		min-height: 100%;
		background: $app-canvas;
	}

	button {
		font-family: $app-font-family;
	}

	button::after {
		border: 0;
	}

	.admin-auth-page {
		position: relative;
		min-height: 100vh;
		padding: calc(32rpx + env(safe-area-inset-top)) 28rpx calc(40rpx + env(safe-area-inset-bottom));
		box-sizing: border-box;
		overflow: hidden;
		color: $app-text;
		background: $app-canvas;
		font-family: $app-font-family;
	}

	.ambient-light {
		position: absolute;
		width: 620rpx;
		height: 620rpx;
		border-radius: 50%;
		filter: blur(80px);
		opacity: .16;
		pointer-events: none;
	}

	.ambient-light-primary {
		top: -280rpx;
		left: -220rpx;
		background: $app-green;
	}

	.ambient-light-secondary {
		right: -320rpx;
		bottom: -360rpx;
		background: $app-teal;
		opacity: .09;
	}

	.auth-shell {
		position: relative;
		z-index: 1;
		width: min(1120px, 100%);
		min-height: calc(100vh - 72rpx);
		margin: 0 auto;
		display: grid;
		align-items: center;
		gap: 48rpx;
	}

	.admin-intro {
		padding: 12rpx 4rpx;
	}

	.admin-mark {
		width: 72rpx;
		height: 72rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		border-radius: 20rpx;
		background: rgba($app-green, .11);
	}

	.admin-mark-core {
		width: 26rpx;
		height: 26rpx;
		border: 6rpx solid $app-green;
		border-radius: 50%;
		box-sizing: border-box;
	}

	.admin-kicker,
	.admin-title,
	.admin-copy,
	.panel-title,
	.panel-copy,
	.state-title,
	.state-copy {
		display: block;
	}

	.admin-kicker {
		margin-top: 24rpx;
		color: $app-green;
		font-size: $app-font-size-caption;
		font-weight: 720;
	}

	.admin-title {
		max-width: 720rpx;
		margin-top: 14rpx;
		font-size: 60rpx;
		font-weight: 790;
		line-height: 1.08;
		letter-spacing: -.03em;
		text-wrap: balance;
	}

	.admin-copy {
		max-width: 68ch;
		margin-top: 18rpx;
		color: $app-muted;
		font-size: $app-font-size-body;
		line-height: $app-line-height-body;
		text-wrap: pretty;
	}

	.security-note {
		margin-top: 28rpx;
		display: flex;
		align-items: center;
		gap: 14rpx;
		color: #c6d2d5;
		font-size: 25rpx;
	}

	.security-note-dot {
		width: 12rpx;
		height: 12rpx;
		border-radius: 50%;
		background: $app-green;
		box-shadow: 0 0 0 7rpx rgba($app-green, .1);
	}

	.admin-panel {
		padding: 40rpx 36rpx;
		border-radius: $app-radius-panel;
		box-sizing: border-box;
		@include admin-glass-chrome(true);
		box-shadow: $app-shadow-floating;
	}

	.admin-panel > .admin-feedback-banner {
		margin-bottom: $app-space-3;
	}

	.panel-title,
	.state-title {
		color: $app-text;
		font-size: $app-font-size-section;
		font-weight: 760;
		line-height: 1.25;
	}

	.panel-copy,
	.state-copy {
		margin: 12rpx 0 28rpx;
		color: $app-muted;
		font-size: 26rpx;
		line-height: $app-line-height-body;
	}

	.center-state {
		min-height: 360rpx;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 20rpx;
		text-align: center;
		color: $app-muted;
		font-size: $app-font-size-body;
	}

	.loading-ring {
		width: 48rpx;
		height: 48rpx;
		border: 4rpx solid rgba($app-teal, .18);
		border-top-color: $app-teal;
		border-radius: 50%;
		animation: spin .8s linear infinite;
	}

	.step-row {
		display: flex;
		justify-content: space-between;
		margin-bottom: 34rpx;
		color: $app-muted;
		font-size: $app-font-size-caption;
	}

	.step-item {
		display: flex;
		align-items: center;
		gap: 10rpx;
	}

	.step-dot {
		width: 42rpx;
		height: 42rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		border-radius: 50%;
		background: $app-surface-strong;
	}

	.step-dot.active {
		background: $app-green;
		color: #071012;
	}

	.primary-button,
	.secondary-button,
	.danger-button {
		width: 100%;
		min-height: 92rpx;
		margin: 26rpx 0 0;
		border-radius: $app-radius-control;
		box-sizing: border-box;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 27rpx;
		font-weight: 730;
		transition:
			transform $app-motion-micro $app-ease-out,
			background-color $app-motion-state ease,
			opacity $app-motion-state ease;
	}

	.primary-button {
		border: 0;
		background: $app-green;
		color: #071012;
	}

	.secondary-button {
		border: 1px solid rgba($app-teal, .34);
		background: rgba($app-green, .07);
		color: #d9fbfb;
	}

	.danger-button {
		border: 1px solid rgba($app-danger, .35);
		background: rgba($app-danger, .08);
		color: $app-danger-text;
	}

	.primary-button:focus-visible,
	.secondary-button:focus-visible,
	.danger-button:focus-visible,
	.delivery-row button:focus-visible,
	.operation-row:focus-visible {
		@include admin-focus-ring;
	}

	.primary-button:active,
	.secondary-button:active,
	.danger-button:active,
	.delivery-row button:active,
	.operation-row:active {
		transform: scale(.98);
	}

	.dispatch-grid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 14rpx;
	}

	.delivery-row {
		display: flex;
		gap: 12rpx;
		margin-top: 16rpx;
	}

	.delivery-row button {
		min-height: 84rpx;
		flex: 1;
		margin: 0;
		border: 1px solid $app-border-strong;
		border-radius: $app-radius-control;
		background: transparent;
		color: $app-muted;
		font-size: 25rpx;
		transition:
			transform $app-motion-micro $app-ease-out,
			background-color $app-motion-state ease;
	}

	.delivery-row button.selected {
		border-color: $app-green;
		background: rgba($app-green, .12);
		color: #d9fbfb;
	}

	.dashboard-overview {
		margin-top: $app-space-5;
		display: grid;
		grid-template-columns: minmax(300px, .72fr) minmax(430px, 1.28fr);
		gap: $app-space-3;
		align-items: start;
	}

	.session-panel,
	.operations-panel,
	.security-panel {
		padding: $app-space-4;
		@include admin-solid-panel;
	}

	.session-panel {
		position: sticky;
		top: $app-space-4;
	}

	.session-identity {
		display: flex;
		align-items: center;
		gap: $app-space-2;
	}

	.profile-badge {
		width: 76rpx;
		height: 76rpx;
		flex: 0 0 auto;
		border-radius: 22rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		background: rgba($app-green, .12);
		color: $app-text;
		font-size: 38rpx;
	}

	.section-heading,
	.section-copy,
	.profile-label,
	.profile-value,
	.operation-title,
	.operation-description {
		display: block;
	}

	.section-heading {
		font-size: 34rpx;
		font-weight: 750;
		letter-spacing: -.015em;
	}

	.section-copy {
		margin-top: 8rpx;
		color: $app-muted;
		font-size: 25rpx;
		line-height: 1.5;
	}

	.profile-list {
		margin-top: $app-space-3;
	}

	.profile-row {
		padding: 20rpx 0;
		border-bottom: 1px solid $app-border-soft;
	}

	.profile-row:last-child {
		border-bottom: 0;
	}

	.profile-label {
		color: $app-muted;
		font-size: $app-font-size-caption;
	}

	.profile-value {
		margin-top: 7rpx;
		color: $app-text;
		font-size: 27rpx;
		line-height: 1.45;
		overflow-wrap: anywhere;
	}

	.section-intro {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: $app-space-3;
	}

	.operation-count,
	.session-chip,
	.session-expiry {
		min-height: 48rpx;
		padding: 0 16rpx;
		border-radius: 999px;
		display: inline-flex;
		align-items: center;
		box-sizing: border-box;
		color: $app-muted;
		font-size: $app-font-size-caption;
	}

	.operation-count,
	.session-expiry {
		background: rgba($app-muted, .08);
	}

	.session-chip {
		gap: 10rpx;
		background: rgba($app-green, .1);
		color: #d9fbfb;
	}

	.session-chip-dot {
		width: 10rpx;
		height: 10rpx;
		border-radius: 50%;
		background: $app-green;
	}

	.operation-list {
		margin-top: $app-space-3;
	}

	.operation-row {
		width: 100%;
		min-height: 112rpx;
		margin: 0;
		padding: 20rpx 12rpx;
		border: 0;
		border-bottom: 1px solid $app-border-soft;
		border-radius: 0;
		box-sizing: border-box;
		display: flex;
		align-items: center;
		gap: $app-space-2;
		background: transparent;
		color: $app-text;
		text-align: left;
		transition:
			transform $app-motion-micro $app-ease-out,
			background-color $app-motion-state ease,
			opacity $app-motion-state ease;
	}

	.operation-row:last-child {
		border-bottom: 0;
	}

	.operation-symbol {
		width: 56rpx;
		height: 56rpx;
		flex: 0 0 auto;
		border-radius: 16rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		background: rgba($app-green, .11);
		color: $app-green;
		font-size: 21rpx;
		font-weight: 780;
	}

	.operation-copy {
		min-width: 0;
		flex: 1;
	}

	.operation-title {
		font-size: 28rpx;
		font-weight: 710;
	}

	.operation-description {
		margin-top: 5rpx;
		color: $app-muted;
		font-size: 24rpx;
		line-height: 1.45;
	}

	.operation-arrow {
		color: $app-muted;
		font-size: 38rpx;
	}

	.security-panel {
		grid-column: 1 / -1;
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto auto;
		align-items: center;
		gap: $app-space-4;
	}

	.security-facts {
		display: flex;
		flex-wrap: wrap;
		gap: 10rpx;
	}

	.security-facts text {
		padding: 10rpx 14rpx;
		border-radius: 999px;
		background: rgba($app-muted, .08);
		color: $app-muted;
		font-size: $app-font-size-caption;
	}

	@media (hover: hover) and (pointer: fine) {
		.operation-row:not(:disabled):hover {
			background: rgba($app-green, .055);
			cursor: pointer;
		}

		.primary-button:not(:disabled):hover {
			background: #57e3df;
			cursor: pointer;
		}
	}

	@keyframes spin {
		to { transform: rotate(360deg); }
	}

	@media screen and (min-width: 760px) {
		.auth-shell {
			grid-template-columns: minmax(300px, 1fr) minmax(400px, 500px);
			gap: 72px;
		}

		.admin-title {
			font-size: 46px;
		}

		.admin-panel {
			padding: 40px 38px;
		}
	}

	@media (max-width: 1023px) {
		.dashboard-overview {
			grid-template-columns: 1fr;
		}

		.session-panel {
			position: static;
		}

		.security-panel {
			grid-column: auto;
			grid-template-columns: 1fr;
		}
	}

	@media (max-width: 767px) {
		.auth-shell {
			align-content: center;
			gap: 32rpx;
		}

		.admin-title {
			font-size: 52rpx;
		}

		.admin-copy {
			font-size: 26rpx;
		}

		.admin-panel {
			padding: 32rpx 24rpx;
		}

		.dispatch-grid {
			grid-template-columns: 1fr;
		}

		.dashboard-overview {
			margin-top: $app-space-4;
		}

		.session-panel,
		.operations-panel,
		.security-panel {
			padding: 28rpx 24rpx;
		}

		.operation-row {
			min-height: 104rpx;
			padding-right: 4rpx;
			padding-left: 4rpx;
		}
	}

	@media (prefers-reduced-motion: reduce) {
		.loading-ring {
			animation: none;
		}

		.primary-button,
		.secondary-button,
		.danger-button,
		.delivery-row button,
		.operation-row {
			transition: opacity 80ms linear, background-color 80ms linear;
		}

		.primary-button:active,
		.secondary-button:active,
		.danger-button:active,
		.delivery-row button:active,
		.operation-row:active {
			transform: none;
		}
	}

	@media (prefers-reduced-transparency: reduce) {
		.ambient-light {
			display: none;
		}

		.admin-panel {
			background: $app-surface-solid;
			-webkit-backdrop-filter: none;
			backdrop-filter: none;
		}
	}

	@media (prefers-contrast: more) {
		.admin-panel,
		.session-panel,
		.operations-panel,
		.security-panel {
			border: 2px solid $app-text;
			background: $app-canvas;
		}
	}
</style>

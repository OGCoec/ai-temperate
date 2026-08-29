<template>
	<view class="profile-page" :class="{ 'is-android-client': androidClient }">
		<scroll-view class="profile-scroll" scroll-y>
			<view class="profile-shell" :aria-busy="loading">
				<view class="profile-heading-row">
					<button
						v-if="androidClient"
						class="workspace-panel-menu"
						type="button"
						aria-label="打开导航"
						@click="$emit('open-conversation-drawer')"
					>
						<uni-icons type="bars" size="18" color="#dce5e0" aria-hidden="true" />
					</button>
					<view class="profile-heading">
						<text class="profile-kicker">YOUR ACCOUNT</text>
						<text class="profile-title">个人</text>
						<!-- #ifdef H5 -->
						<text class="profile-subtitle">管理账户与偏好</text>
						<!-- #endif -->
						<!-- #ifndef H5 -->
						<text class="profile-subtitle">查看账户资料、订阅状态与当前可用额度。</text>
						<!-- #endif -->
					</view>
					<button
						class="profile-refresh"
						type="button"
						:disabled="loading"
						aria-label="刷新个人资料"
						@click="refreshProfile(true)"
					>
						<uni-icons type="refreshempty" size="19" color="#dce5e0" aria-hidden="true" />
						<text class="profile-refresh-label">刷新</text>
					</button>
				</view>

				<view v-if="!authenticated" class="profile-state" role="status">
					<uni-icons type="spinner-cycle" size="24" color="#37d39a" />
					<text>正在确认当前会话…</text>
				</view>

				<view v-else-if="loading && !profile" class="profile-state" role="status">
					<uni-icons type="spinner-cycle" size="24" color="#37d39a" />
					<text>正在读取个人资料…</text>
				</view>

				<view v-else-if="error && !profile" class="profile-state profile-state-error" role="alert">
					<uni-icons type="info" size="24" color="#65c7c2" />
					<text>{{ error }}</text>
					<button class="profile-retry" type="button" @click="refreshProfile(true)">重新加载</button>
				</view>

				<template v-else-if="profile">
					<view class="profile-workbench">
						<view
							v-if="!androidClient"
							class="profile-workbench-navigation"
							role="tablist"
							aria-label="个人中心分区"
						>
							<text class="profile-workbench-navigation-title">设置</text>
							<button
								v-for="section in profileSections"
								:key="section.key"
								:id="`profile-tab-${section.key}`"
								class="profile-workbench-nav-item"
								:class="{ 'is-active': activeProfileSection === section.key }"
								type="button"
								role="tab"
								:aria-selected="activeProfileSection === section.key"
								:aria-controls="`profile-panel-${section.key}`"
								:tabindex="activeProfileSection === section.key ? 0 : -1"
								@click="selectProfileSection(section.key)"
								@keydown="handleProfileSectionKeydown($event, section.key)"
							>
								<view class="profile-workbench-nav-icon" aria-hidden="true">
									<uni-icons :type="section.icon" size="19" :color="profileSectionIconColor(section.key)" />
								</view>
								<view class="profile-workbench-nav-copy">
									<text class="profile-workbench-nav-label">{{ section.label }}</text>
									<text class="profile-workbench-nav-detail">{{ section.detail }}</text>
								</view>
							</button>
						</view>

						<view class="profile-workbench-content">
							<view
								v-show="profileSectionVisible('account')"
								id="profile-panel-account"
								class="profile-workbench-panel profile-account-panel"
								:role="androidClient ? 'region' : 'tabpanel'"
								:aria-labelledby="androidClient ? undefined : 'profile-tab-account'"
								aria-label="账户资料"
							>
								<view class="profile-panel-heading">
									<text class="profile-panel-title">账户资料</text>
									<text class="profile-panel-description">查看身份信息和用于登录、安全通知的联系方式。</text>
								</view>
								<view class="profile-identity-card">
									<image
										v-if="displayAvatarUrl"
										class="profile-avatar profile-avatar-image"
										:src="displayAvatarUrl"
										mode="aspectFill"
										aria-label="当前用户头像"
									/>
									<view v-else class="profile-avatar" aria-hidden="true">
										<text>{{ avatarText }}</text>
									</view>
									<view class="profile-identity-copy">
										<text class="profile-name">{{ profile.displayName }}</text>
										<text class="profile-status"><text class="profile-status-dot"></text>当前会话有效</text>
									</view>
									<view class="profile-avatar-actions">
										<button class="profile-avatar-button" type="button" :disabled="avatarBusy" @click="selectAvatar">
											{{ pendingAvatar ? '重新选择头像' : '更换头像' }}
										</button>
										<template v-if="pendingAvatar && pendingAvatar.uploaded">
											<button class="profile-avatar-button profile-avatar-confirm" type="button" :disabled="avatarBusy" @click="confirmAvatar">确认使用</button>
											<button class="profile-avatar-button profile-avatar-cancel" type="button" :disabled="avatarBusy" @click="cancelAvatar">取消上传</button>
										</template>
										<text v-if="pendingAvatar && !pendingAvatar.uploaded" class="profile-avatar-progress" role="status">正在上传头像…</text>
									</view>
								</view>

								<view class="profile-section profile-contact-section">
									<view class="profile-section-heading profile-section-heading-copy">
										<text class="profile-section-title">联系方式</text>
										<text class="profile-section-description">这些信息同时用于登录和账户安全通知。</text>
									</view>
									<view class="profile-card">
										<view class="profile-row">
											<view class="profile-row-icon" aria-hidden="true"><uni-icons type="email" size="20" color="#37d39a" /></view>
											<view class="profile-row-copy">
												<text class="profile-label">邮箱</text>
												<text class="profile-value" selectable>{{ profile.email }}</text>
											</view>
										</view>
										<view class="profile-divider"></view>
										<view class="profile-row">
											<view class="profile-row-icon" aria-hidden="true"><uni-icons :type="phone.bound && !phone.countryResolved ? 'map' : 'phone'" size="20" color="#65c7c2" /></view>
											<view class="profile-row-copy">
												<text class="profile-label">电话</text>
												<text class="profile-value" selectable>{{ phone.displayNumber }}</text>
												<view v-if="phone.bound" class="profile-phone-country">
													<image v-if="phone.flag" class="profile-phone-flag" :src="phone.flag" mode="aspectFill" aria-hidden="true" />
													<text class="profile-detail">{{ phoneDetail }}</text>
												</view>
												<text v-if="phone.countryResolved && phone.nationalDisplay" class="profile-detail">本地号码：{{ phone.nationalDisplay }}</text>
											</view>
										</view>
									</view>
								</view>

								<!-- #ifdef H5 -->
								<view class="profile-membership-summary">
									<view class="profile-membership-summary-copy">
										<text class="profile-label">当前套餐</text>
										<text class="profile-membership-summary-detail">账户当前使用的订阅等级</text>
									</view>
									<text class="profile-membership-pill">{{ membershipLabel }}</text>
								</view>
								<!-- #endif -->
							</view>

							<view
								v-show="profileSectionVisible('quota')"
								id="profile-panel-quota"
								class="profile-workbench-panel profile-quota-section"
								:role="androidClient ? 'region' : 'tabpanel'"
								:aria-labelledby="androidClient ? undefined : 'profile-tab-quota'"
								aria-label="订阅与额度"
							>
								<view class="profile-panel-heading profile-panel-heading-inline">
									<view class="profile-panel-heading-copy">
										<text class="profile-panel-title">订阅与额度</text>
										<text class="profile-panel-description">查看当前套餐、额度使用情况和下一次重置时间。</text>
									</view>
									<text class="profile-membership-pill">{{ membershipLabel }}</text>
								</view>
								<view class="profile-quota-card">
									<text class="profile-label">当前可用额度</text>
									<text class="profile-quota-value">{{ quotaBalanceDisplay }}</text>
									<template v-if="quotaProgressAvailable">
										<view class="profile-quota-summary">
											<text>已用 {{ profile.quotaUsed }} / 总额 {{ profile.quotaTotal }}</text>
											<text>剩余 {{ quotaBalanceDisplay }}</text>
										</view>
										<view class="profile-quota-progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="quotaUsagePercent" :aria-valuetext="quotaProgressAriaText">
											<view class="profile-quota-progress-fill" :style="quotaProgressStyle"></view>
										</view>
									</template>
									<text v-else class="profile-quota-unavailable">额度进度暂不可用</text>
									<view class="profile-quota-divider"></view>
									<view class="profile-quota-meta">
										<uni-icons type="calendar" size="18" color="#65c7c2" aria-hidden="true" />
										<view class="profile-quota-meta-copy">
											<text class="profile-label">预计重置时间</text>
											<text class="profile-detail">{{ formatQuotaResetAt(profile.quotaResetAt) }}</text>
										</view>
									</view>
								</view>
								<!-- #ifdef H5 -->
								<button
									class="profile-api-key-card profile-membership-upgrade"
									type="button"
									:disabled="logoutBusy"
									aria-label="进入会员套餐升级页面"
									@click="openMembershipPlans"
								>
									<view class="profile-api-key-icon" aria-hidden="true">
										<uni-icons type="paperplane-filled" size="21" color="#75dfb7" />
									</view>
									<view class="profile-api-key-copy">
										<text class="profile-api-key-title">升级套餐</text>
										<text class="profile-api-key-detail">查看 Go、Plus、Pro 与 Ultra 的服务端模拟支付报价</text>
									</view>
									<text class="profile-api-key-chevron" aria-hidden="true">›</text>
								</button>
								<!-- #endif -->
							</view>

							<view
								v-show="profileSectionVisible('security')"
								id="profile-panel-security"
								class="profile-workbench-panel profile-security-section"
								:role="androidClient ? 'region' : 'tabpanel'"
								:aria-labelledby="androidClient ? undefined : 'profile-tab-security'"
								aria-label="安全设置"
							>
								<view class="profile-panel-heading">
									<text class="profile-panel-title">安全设置</text>
									<text class="profile-panel-description">通过认证器动态验证码增强账户安全。</text>
								</view>
								<button class="profile-settings-row profile-security" type="button" :disabled="logoutBusy" @click="openTotpSecurity">
									<view class="profile-settings-row-icon" aria-hidden="true"><uni-icons type="locked" size="21" color="#37d39a" /></view>
									<view class="profile-settings-row-copy">
										<text class="profile-settings-row-title">二次认证设置</text>
										<text class="profile-settings-row-detail">开启、关闭或更换 TOTP 认证器密钥</text>
									</view>
									<text class="profile-settings-row-chevron" aria-hidden="true">›</text>
								</button>
							</view>

							<view
								v-show="profileSectionVisible('developer')"
								id="profile-panel-developer"
								class="profile-workbench-panel profile-developer-section"
								:role="androidClient ? 'region' : 'tabpanel'"
								:aria-labelledby="androidClient ? undefined : 'profile-tab-developer'"
								aria-label="开发者工具"
							>
								<view class="profile-panel-heading">
									<text class="profile-panel-title">开发者工具</text>
									<text class="profile-panel-description">管理用于兼容客户端和开发工具的访问凭证。</text>
								</view>
								<button class="profile-api-key-card" type="button" :disabled="logoutBusy" aria-label="进入管理我的 API Key 页面" @click="openApiKeys">
									<view class="profile-api-key-icon" aria-hidden="true"><uni-icons type="locked-filled" size="21" color="#75dfb7" /></view>
									<view class="profile-api-key-copy">
										<text class="profile-api-key-title">管理我的 API Key</text>
										<text class="profile-api-key-detail">为 Codex、Claude Code、Apifox 和 OpenAI 兼容客户端创建访问凭证</text>
									</view>
									<text class="profile-api-key-chevron" aria-hidden="true">›</text>
								</button>
							</view>

							<view
								v-show="profileSectionVisible('sessions')"
								id="profile-panel-sessions"
								class="profile-workbench-panel profile-sessions-section"
								:role="androidClient ? 'region' : 'tabpanel'"
								:aria-labelledby="androidClient ? undefined : 'profile-tab-sessions'"
								aria-label="登录与设备"
							>
								<view class="profile-panel-heading">
									<text class="profile-panel-title">登录与设备</text>
									<text class="profile-panel-description">结束当前会话，或让所有设备上的登录同时失效。</text>
								</view>
								<view class="profile-action-group">
									<view class="profile-session-row">
										<view class="profile-session-copy">
											<text class="profile-settings-row-title">当前设备</text>
											<text class="profile-settings-row-detail">仅结束当前浏览器或客户端的会话</text>
										</view>
										<button class="profile-logout" type="button" :disabled="logoutBusy" @click="logout">{{ loggingOut ? '正在退出…' : '退出登录' }}</button>
									</view>
									<view class="profile-divider profile-session-divider"></view>
									<view class="profile-session-row">
										<view class="profile-session-copy">
											<text class="profile-settings-row-title">所有设备</text>
											<text class="profile-settings-row-detail">撤销账户下全部现有登录，需要重新验证身份</text>
										</view>
										<button class="profile-logout-all" type="button" :disabled="logoutBusy" @click="confirmLogoutAll">{{ loggingOutAll ? '正在退出所有设备…' : '退出所有设备' }}</button>
									</view>
								</view>
							</view>
						</view>
					</view>
				</template>
			</view>
		</scroll-view>
	</view>
</template>

<script>
	import { AUTH_ROUTES, clientPlatform } from '@/common/auth/config.js'
	import { logoutAllSessions, logoutSession } from '@/common/auth/http-client.js'
	import { clearAiConversationStoppedDrafts } from '@/common/aichat/ai-conversation-stopped-draft.js'
	import { clearAiConversationResearchSessions } from '@/common/aichat/ai-conversation-research-session.js'
	import { formatLocalDateTimeZhCn } from '@/common/platform/date-time.js'
	import {
		getCurrentUserProfile,
		loadCurrentUserProfile,
		updateCurrentUserAvatar
	} from '@/common/user/current-user-profile.js'
	import { currentUserApi } from '@/common/user/current-user-api.js'
	import {
		chooseAvatarImage,
		putAvatarToOss,
		readAvatarSelection
	} from '@/common/user/avatar-upload.js'
	import { derivePhonePresentation } from '@/common/user/phone-presentation.js'

	export default {
		props: {
			authenticated: {
				type: Boolean,
				default: false
			}
		},
		data() {
			return {
				profile: getCurrentUserProfile(),
				activeProfileSection: 'account',
				loading: false,
				loggingOut: false,
				loggingOutAll: false,
				avatarBusy: false,
				pendingAvatar: null,
				error: ''
			}
		},
		watch: {
			authenticated(value) {
				if (value) this.onAuthenticatedPageReady()
			}
		},
		mounted() {
			if (this.authenticated) this.onAuthenticatedPageReady()
		},
		computed: {
			androidClient() { return clientPlatform() === 'ANDROID' },
			profileSections() {
				return [
					{ key: 'account', label: '账户资料', detail: '身份与联系方式', icon: 'person' },
					{ key: 'quota', label: '订阅额度', detail: '套餐与使用情况', icon: 'list' },
					{ key: 'security', label: '安全设置', detail: '二次认证', icon: 'locked' },
					{ key: 'developer', label: '开发者工具', detail: 'API Key', icon: 'locked-filled' },
					{ key: 'sessions', label: '登录与设备', detail: '会话管理', icon: 'refreshempty' }
				]
			},
			phone() { return derivePhonePresentation(this.profile?.phone) },
			phoneDetail() {
				const parts = []
				parts.push(this.phone.countryName)
				if (this.phone.countryIso2) parts.push(this.phone.countryIso2)
				if (this.phone.dialCode) parts.push(this.phone.dialCode)
				return parts.join(' · ')
			},
			avatarText() {
				return String(this.profile?.displayName || 'U').trim().slice(0, 1).toUpperCase()
			},
			displayAvatarUrl() {
				return this.pendingAvatar?.previewUrl || this.profile?.avatarUrl || ''
			},
			logoutBusy() {
				return this.loggingOut || this.loggingOutAll
			},
			membershipLabel() {
				return ({
					FREE: 'Free',
					GO: 'Go',
					EDU: 'Education',
					TEAM: 'Team',
					PLUS: 'Plus',
					PRO: 'Pro',
					MAX: 'Ultra'
				})[this.profile?.membershipTier] || '未设置'
			},
			quotaBalanceDisplay() {
				return this.profile?.quotaBalance || '暂不可用'
			},
			quotaProgressAvailable() {
				const percent = Number(this.profile?.quotaUsagePercent)
				const decimal = /^(?:0|[1-9]\d*)(?:\.\d+)?$/
				return decimal.test(String(this.profile?.quotaUsed || ''))
					&& decimal.test(String(this.profile?.quotaTotal || ''))
					&& decimal.test(String(this.profile?.quotaBalance || ''))
					&& Number.isFinite(percent)
					&& percent >= 0
					&& percent <= 100
			},
			quotaUsagePercent() {
				return this.quotaProgressAvailable
					? Number(this.profile.quotaUsagePercent)
					: 0
			},
			quotaProgressStyle() {
				return { transform: `scaleX(${this.quotaUsagePercent / 100})` }
			},
			quotaProgressAriaText() {
				return `已使用 ${this.profile.quotaUsed}，总额度 ${this.profile.quotaTotal}，剩余 ${this.quotaBalanceDisplay}，使用率 ${this.profile.quotaUsagePercent}%`
			}
		},
		methods: {
			closeIfOpen() {
				return false
			},
			selectProfileSection(key) {
				if (this.profileSections.some(section => section.key === key)) {
					this.activeProfileSection = key
				}
			},
			handleProfileSectionKeydown(event, key) {
				const sectionKeys = this.profileSections.map(section => section.key)
				const currentIndex = sectionKeys.indexOf(key)
				if (currentIndex < 0) return

				let nextIndex = currentIndex
				if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
					nextIndex = (currentIndex + 1) % sectionKeys.length
				} else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
					nextIndex = (currentIndex - 1 + sectionKeys.length) % sectionKeys.length
				} else if (event.key === 'Home') {
					nextIndex = 0
				} else if (event.key === 'End') {
					nextIndex = sectionKeys.length - 1
				} else {
					return
				}

				event.preventDefault()
				const nextKey = sectionKeys[nextIndex]
				this.selectProfileSection(nextKey)
				this.$nextTick(() => {
					const tab = this.$el?.querySelector?.(`#profile-tab-${nextKey}`)
					tab?.focus?.()
				})
			},
			profileSectionVisible(key) {
				return this.androidClient || this.activeProfileSection === key
			},
			profileSectionIconColor(key) {
				return this.activeProfileSection === key ? '#75dfb7' : '#87938d'
			},
			openApiKeys() {
				if (!this.logoutBusy) this.$emit('open-api-keys')
			},
			openMembershipPlans() {
				if (!this.logoutBusy) uni.navigateTo({ url: AUTH_ROUTES.membershipPlans })
			},
			openTotpSecurity() {
				if (!this.logoutBusy) uni.navigateTo({ url: AUTH_ROUTES.totpSecurity })
			},
			formatQuotaResetAt(value) {
				if (value == null || value === '') return '首次使用后开始计算'
				return formatLocalDateTimeZhCn(value) || '暂不可用'
			},
			onAuthenticatedPageReady() {
				this.refreshProfile()
			},
			async refreshProfile(force = false) {
				if (this.loading) return
				this.loading = true
				this.error = ''
				try {
					this.profile = await loadCurrentUserProfile({ force })
				} catch (error) {
					this.error = '个人资料暂时无法加载，请稍后重试。'
				} finally {
					this.loading = false
				}
			},
			async selectAvatar() {
				if (this.avatarBusy) return
				try {
					const selection = await chooseAvatarImage()
					if (!selection) return
					const selected = await readAvatarSelection(selection)
					this.avatarBusy = true
					if (this.pendingAvatar?.preuploadId) {
						await currentUserApi.cancelAvatarPreupload(
							this.pendingAvatar.preuploadId,
							this.pendingAvatar.format
						).catch(() => {})
					}
					this.pendingAvatar = {
						previewUrl: selected.previewUrl,
						format: selected.format,
						preuploadId: null,
						uploaded: false
					}
					const preupload = await currentUserApi.createAvatarPreupload(
						selected.format,
						selected.sizeBytes
					)
					this.pendingAvatar.preuploadId = preupload.preuploadId
					await putAvatarToOss(
						preupload.uploadUrl,
						preupload.uploadHeaders,
						selected.bytes
					)
					this.pendingAvatar.uploaded = true
				} catch (error) {
					if (this.pendingAvatar?.preuploadId) {
						await currentUserApi.cancelAvatarPreupload(
							this.pendingAvatar.preuploadId,
							this.pendingAvatar.format
						).catch(() => {})
					}
					this.pendingAvatar = null
					uni.showToast({
						title: error?.message || '头像上传失败，请稍后重试。',
						icon: 'none'
					})
				} finally {
					this.avatarBusy = false
				}
			},
			async cancelAvatar() {
				if (this.avatarBusy || !this.pendingAvatar) return
				this.avatarBusy = true
				const pending = this.pendingAvatar
				try {
					if (pending.preuploadId) {
						await currentUserApi.cancelAvatarPreupload(
							pending.preuploadId,
							pending.format
						)
					}
					this.pendingAvatar = null
				} catch (error) {
					uni.showToast({
						title: error?.message || '取消头像上传失败，请稍后重试。',
						icon: 'none'
					})
				} finally {
					this.avatarBusy = false
				}
			},
			async confirmAvatar() {
				if (this.avatarBusy || !this.pendingAvatar?.uploaded) return
				this.avatarBusy = true
				try {
					const result = await currentUserApi.confirmAvatar(
						this.pendingAvatar.preuploadId,
						this.pendingAvatar.format
					)
					this.profile = updateCurrentUserAvatar(result.avatarUrl)
					this.pendingAvatar = null
					uni.showToast({ title: '头像已更新', icon: 'success' })
				} catch (error) {
					uni.showToast({
						title: error?.message || '头像确认失败，请稍后重试。',
						icon: 'none'
					})
				} finally {
					this.avatarBusy = false
				}
			},
			async logout() {
				if (this.logoutBusy) return
				this.loggingOut = true
				try {
					await logoutSession()
				} catch (error) {
					// 服务端不可达时本地会话仍会在 logoutSession 的 finally 中清除。
				} finally {
					clearAiConversationStoppedDrafts()
					clearAiConversationResearchSessions()
					this.loggingOut = false
					uni.reLaunch({ url: AUTH_ROUTES.login })
				}
			},
			confirmLogoutAll() {
				if (this.logoutBusy) return
				uni.showModal({
					title: '退出所有设备？',
					content: '这会让你在所有设备上的登录失效，包括当前设备。需要重新登录才能继续使用。',
					showCancel: true,
					cancelText: '取消',
					confirmText: '退出全部',
					confirmColor: '#d95d59',
					success: (result) => {
						if (result.confirm) this.logoutAll()
					}
				})
			},
			async logoutAll() {
				if (this.logoutBusy) return
				this.loggingOutAll = true
				try {
					await logoutAllSessions()
					clearAiConversationStoppedDrafts()
					clearAiConversationResearchSessions()
					uni.reLaunch({ url: AUTH_ROUTES.login })
				} catch (error) {
					uni.showToast({
						title: error?.message || '退出所有设备失败，请稍后重试。',
						icon: 'none'
					})
				} finally {
					this.loggingOutAll = false
				}
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/ui/user-material.scss';

	.profile-page {
		min-width: 0;
		min-height: 0;
		height: 100%;
		display: flex;
		background: #0b0d0c;
		color: #f3f5f4;
	}

	.profile-scroll {
		height: 100%;
		min-height: 0;
		min-width: 0;
		flex: 1;
	}

	.profile-shell {
		max-width: 800px;
		min-height: 100%;
		box-sizing: border-box;
		margin: 0 auto;
		padding: 32px 16px calc(108px + env(safe-area-inset-bottom));
	}
	.profile-page.is-android-client .profile-shell { padding: max(14px, env(safe-area-inset-top)) 12px calc(20px + env(safe-area-inset-bottom)); }
	.profile-page.is-android-client .profile-heading-row { align-items: center; gap: 8px; margin-bottom: 18px; }
	.profile-page.is-android-client .profile-kicker { font-size: 11px; letter-spacing: 1.5px; }
	.profile-page.is-android-client .profile-title { margin-top: 5px; font-size: 26px; }
	.profile-page.is-android-client .profile-subtitle { margin-top: 5px; font-size: 14px; line-height: 1.5; }
	.profile-page.is-android-client .profile-refresh { min-width: 64px; min-height: 44px; }

	.profile-heading-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; }
	.workspace-panel-menu { @include user-android-compact-control(32px, 32px, 10px); width: 44px; height: 44px; min-height: 44px; margin: 0; padding: 0; flex: 0 0 44px; }
	.workspace-panel-menu::after { border: 0; }
	.profile-heading { min-width: 0; display: flex; flex-direction: column; }
	.profile-kicker { color: #37d39a; font-size: 13px; font-weight: 700; letter-spacing: 2px; }
	.profile-title { color: #f3f5f4; font-size: 32px; line-height: 1.2; font-weight: 760; margin-top: 8px; letter-spacing: -.45px; }
	.profile-subtitle { color: #a0aaa5; font-size: 15px; line-height: 1.6; margin-top: 8px; }
	.profile-refresh {
		@include user-frosted-control;
		min-width: 72px;
		min-height: 48px;
		margin: 0;
		padding: 0 12px;
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 6px;
		border-radius: 12px;
		color: #dce5e0;
		font-size: 13px;
		font-weight: 650;
		line-height: 1.2;
		text-align: center;
	}
	.profile-refresh::after { border: 0; }
	.profile-refresh:active { transform: scale(.985); background: #202520; }
	.profile-refresh:disabled { opacity: .55; }

	.profile-state {
		@include user-frosted-surface;
		min-height: 180px;
		box-sizing: border-box;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 12px;
		padding: 28px;
		border-radius: 16px;
		color: #8b9690;
		text-align: center;
	}

	.profile-action-group {
		display: flex;
		flex-direction: column;
		gap: 12px;
		margin-top: 52px;
	}

	.profile-retry, .profile-logout, .profile-logout-all, .profile-security {
		@include user-frosted-control;
		min-height: 48px;
		border-radius: 14px;
		font-size: 16px;
		font-weight: 650;
		display: flex;
		align-items: center;
		justify-content: center;
		text-align: center;
		line-height: 1.2;
	}
	.profile-retry { margin-top: 4px; padding: 0 22px; border-color: rgba(123, 238, 190, .36); background: rgba(55, 211, 154, .82); color: #07130e; }
	.profile-retry::after, .profile-security::after, .profile-logout::after, .profile-logout-all::after { border: 0; }

	.profile-identity-card {
		@include user-frosted-surface;
		display: flex;
		align-items: center;
		gap: 16px;
		padding: 20px;
		border-radius: 16px;
	}

	.profile-avatar {
		width: 56px;
		height: 56px;
		border-radius: 18px;
		display: flex;
		align-items: center;
		justify-content: center;
		background: #37d39a;
		color: #0b0d0c;
		font-size: 22px;
		font-weight: 800;
	}
	.profile-avatar-image { display: block; background: #202520; }
	.profile-avatar-actions {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 10px;
		margin-top: 12px;
	}
	.profile-avatar-button {
		@include user-frosted-control;
		min-height: 48px;
		margin: 0;
		padding: 0 16px;
		border-radius: 12px;
		display: flex;
		align-items: center;
		justify-content: center;
		color: #dce5e0;
		font-size: 14px;
		line-height: 1.2;
		text-align: center;
	}
	.profile-avatar-button::after { border: 0; }
	.profile-avatar-confirm { border-color: #37d39a; color: #37d39a; }
	.profile-avatar-cancel { border-color: #d95d59; color: #f08a82; }
	.profile-avatar-button:disabled { opacity: .55; }
	.profile-avatar-progress { color: #8b9690; font-size: 13px; }
	.profile-identity-copy, .profile-row-copy { min-width: 0; flex: 1; display: flex; flex-direction: column; }
	.profile-name { color: #f3f5f4; font-size: 20px; font-weight: 700; }
	.profile-status { margin-top: 6px; color: #8b9690; font-size: 13px; }
	.profile-status-dot { display: inline-block; width: 7px; height: 7px; margin-right: 7px; border-radius: 50%; background: #a8db4d; }

	.profile-section { margin-top: 24px; }
	.profile-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 4px 10px; }
	.profile-section-title { display: block; margin: 0 0 10px 4px; color: #8b9690; font-size: 13px; font-weight: 650; }
	.profile-section-heading .profile-section-title { margin: 0; }
	.profile-card { @include user-frosted-surface; border-radius: 16px; overflow: hidden; }
	.profile-row { min-height: 76px; display: flex; align-items: center; gap: 14px; padding: 16px 18px; box-sizing: border-box; }
	.profile-row-icon { width: 40px; height: 40px; border-radius: 12px; display: flex; align-items: center; justify-content: center; background: #171a18; }
	.profile-label { color: #8b9690; font-size: 13px; }
	.profile-value { margin-top: 4px; color: #f3f5f4; font-size: 16px; line-height: 1.45; word-break: break-all; }
	.profile-detail { margin-top: 5px; color: #8b9690; font-size: 13px; line-height: 1.45; }
	.profile-divider { height: 1px; margin-left: 72px; background: #303733; }
	.profile-membership-pill {
		padding: 6px 10px;
		border-radius: 999px;
		background: rgba(55, 211, 154, .13);
		color: #9be4c5;
		font-size: 12px;
		font-weight: 700;
		line-height: 1;
	}
	.profile-quota-card { @include user-frosted-surface; padding: 20px; border-radius: 16px; }
	.profile-membership-upgrade { margin-top: 14px; }
	.profile-quota-value { display: block; margin-top: 7px; color: #f3f5f4; font-size: 28px; font-weight: 750; line-height: 1.2; font-variant-numeric: tabular-nums; }
	.profile-quota-summary { display: flex; justify-content: space-between; gap: 12px; margin-top: 16px; color: #aeb8b3; font-size: 13px; font-variant-numeric: tabular-nums; }
	.profile-quota-progress { height: 8px; margin-top: 10px; overflow: hidden; border-radius: 999px; background: #2b312e; }
	.profile-quota-progress-fill { width: 100%; height: 100%; border-radius: inherit; background: #37d39a; transform: scaleX(0); transform-origin: left center; transition: transform 200ms ease-out; }
	.profile-quota-unavailable { display: block; margin-top: 14px; color: #8b9690; font-size: 13px; }
	.profile-quota-divider { height: 1px; margin: 18px 0; background: #303733; }
	.profile-quota-meta { display: flex; align-items: flex-start; gap: 10px; }
	.profile-quota-meta-copy { min-width: 0; display: flex; flex: 1; flex-direction: column; }
	.profile-api-key-card {
		@include user-frosted-surface;
		width: 100%;
		min-height: 92px;
		margin: 0;
		padding: 17px 18px;
		display: flex;
		align-items: center;
		gap: 14px;
		border-radius: 16px;
		box-sizing: border-box;
		color: #f3f5f4;
		text-align: left;
		transition: background-color 150ms ease-out, border-color 150ms ease-out, transform 100ms ease-out;
	}
	.profile-api-key-card::after { border: 0; }
	.profile-api-key-icon { width: 42px; height: 42px; display: flex; align-items: center; justify-content: center; flex: 0 0 42px; border-radius: 13px; background: rgba(55, 211, 154, .09); }
	.profile-api-key-copy { min-width: 0; display: flex; flex: 1; flex-direction: column; }
	.profile-api-key-title { color: #eef3f0; font-size: 15px; font-weight: 720; }
	.profile-api-key-detail { margin-top: 5px; color: #8f9b95; font-size: 12px; line-height: 1.55; }
	.profile-api-key-chevron { flex: 0 0 auto; color: #79857f; font-size: 26px; font-weight: 300; }
	.profile-api-key-card:active { transform: scale(.988); }
	.profile-api-key-card:disabled { opacity: .55; }
	.profile-api-key-card:focus-visible { outline: 3px solid rgba(55, 211, 154, .28); outline-offset: 3px; }

	.profile-logout {
		width: 100%;
		border: 1px solid #c9822f;
		background: rgba(201, 130, 47, .14);
		color: #f2a24d;
	}
	.profile-security {
		width: 100%;
		gap: 9px;
		border: 1px solid rgba(55, 211, 154, .46);
		background: rgba(55, 211, 154, .1);
		color: #9be4c5;
	}
	.profile-logout-all {
		width: 100%;
		border: 1px solid #d95d59;
		background: rgba(217, 93, 89, .14);
		color: #f08a82;
	}
	.profile-security:active, .profile-logout:active, .profile-logout-all:active, .profile-retry:active { transform: scale(.985); }
	.profile-security:disabled, .profile-logout:disabled, .profile-logout-all:disabled { opacity: .55; }
	.profile-retry:focus-visible {
		outline: 3px solid rgba(55, 211, 154, .28);
		outline-offset: 3px;
	}
	.profile-logout:focus-visible {
		outline: 3px solid rgba(242, 162, 77, .28);
		outline-offset: 3px;
	}
	.profile-security:focus-visible {
		outline: 3px solid rgba(55, 211, 154, .28);
		outline-offset: 3px;
	}
	.profile-logout-all:focus-visible {
		outline: 3px solid rgba(217, 93, 89, .3);
		outline-offset: 3px;
	}
	.profile-refresh:focus-visible {
		outline: 3px solid rgba(55, 211, 154, .28);
		outline-offset: 3px;
	}

	.profile-workbench,
	.profile-workbench-content,
	.profile-workbench-panel { min-width: 0; }
	.profile-workbench-navigation { display: none; }
	.profile-workbench-content {
		width: 100%;
		max-width: 920px;
		display: flex;
		flex-direction: column;
		gap: 32px;
	}
	.profile-page.is-android-client .profile-workbench-content { max-width: none; gap: 24px; }
	.profile-page.is-android-client .profile-workbench-panel { min-width: 0; }

	.profile-panel-heading {
		margin-bottom: 16px;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.profile-panel-heading-inline {
		flex-direction: row;
		align-items: flex-start;
		justify-content: space-between;
		gap: 16px;
	}
	.profile-panel-heading-copy { min-width: 0; display: flex; flex-direction: column; gap: 6px; }
	.profile-panel-title { color: #f3f5f4; font-size: 20px; line-height: 1.3; font-weight: 730; }
	.profile-panel-description,
	.profile-section-description { color: #929d97; font-size: 13px; line-height: 1.55; }

	.profile-identity-card {
		min-width: 0;
		flex-wrap: wrap;
		gap: 16px;
		padding: 24px;
	}
	.profile-avatar-actions {
		width: 100%;
		min-width: 0;
		margin-top: 0;
		gap: 8px;
	}
	.profile-avatar-button { min-height: 44px; padding: 0 14px; font-size: 13px; }
	.profile-avatar-progress { width: 100%; }
	.profile-section { margin-top: 32px; }
	.profile-section-heading-copy { align-items: flex-start; margin: 0 4px 12px; }
	.profile-section-title { margin: 0; color: #d7dfda; font-size: 14px; font-weight: 680; }
	.profile-card { border-radius: 14px; }
	.profile-row { min-height: 82px; padding: 16px 20px; }
	.profile-divider { margin-left: 76px; background: rgba(174, 196, 184, .14); }
	.profile-phone-country { margin-top: 6px; display: flex; align-items: center; gap: 8px; }
	.profile-phone-country .profile-detail { margin-top: 0; }
	.profile-phone-flag {
		width: 24px;
		height: 16px;
		flex: 0 0 24px;
		border-radius: 3px;
		object-fit: cover;
	}

	.profile-quota-card { padding: 24px; border-radius: 14px; }
	.profile-quota-value { margin-top: 8px; font-size: 32px; }
	.profile-membership-pill { flex: 0 0 auto; }

	.profile-settings-row,
	.profile-api-key-card {
		@include user-frosted-surface;
		width: 100%;
		min-height: 88px;
		margin: 0;
		padding: 18px 20px;
		display: flex;
		align-items: center;
		gap: 14px;
		border-radius: 14px;
		box-sizing: border-box;
		color: #f3f5f4;
		text-align: left;
		transition: background-color 160ms ease-out, border-color 160ms ease-out, transform 120ms ease-out;
	}
	.profile-settings-row::after,
	.profile-api-key-card::after { border: 0; }
	.profile-settings-row-icon,
	.profile-api-key-icon {
		width: 42px;
		height: 42px;
		flex: 0 0 42px;
		display: flex;
		align-items: center;
		justify-content: center;
		border-radius: 12px;
		background: rgba(55, 211, 154, .09);
	}
	.profile-settings-row-copy,
	.profile-api-key-copy { min-width: 0; display: flex; flex: 1; flex-direction: column; }
	.profile-settings-row-title,
	.profile-api-key-title { color: #eef3f0; font-size: 15px; font-weight: 720; }
	.profile-settings-row-detail,
	.profile-api-key-detail { margin-top: 5px; color: #929d97; font-size: 12px; line-height: 1.55; }
	.profile-settings-row-chevron,
	.profile-api-key-chevron { flex: 0 0 auto; color: #79857f; font-size: 26px; font-weight: 300; }
	.profile-settings-row:active,
	.profile-api-key-card:active { transform: scale(.99); }
	.profile-settings-row:disabled,
	.profile-api-key-card:disabled { opacity: .55; }
	.profile-settings-row:focus-visible,
	.profile-api-key-card:focus-visible { outline: 3px solid rgba(55, 211, 154, .28); outline-offset: 3px; }
	.profile-security.profile-settings-row {
		border-color: rgba(55, 211, 154, .24);
		background: rgba(21, 28, 24, .94);
	}

	.profile-action-group {
		@include user-frosted-surface;
		gap: 0;
		margin-top: 0;
		border-radius: 14px;
		overflow: hidden;
	}
	.profile-session-row {
		min-height: 92px;
		padding: 18px 20px;
		display: flex;
		align-items: center;
		gap: 20px;
	}
	.profile-session-copy { min-width: 0; display: flex; flex: 1; flex-direction: column; }
	.profile-session-divider { margin: 0 20px; }
	.profile-logout,
	.profile-logout-all { width: auto; min-width: 152px; min-height: 44px; margin: 0; padding: 0 16px; border-radius: 11px; font-size: 13px; }

	@media screen and (min-width: 640px) {
		.profile-avatar-actions { width: auto; margin-left: auto; }
	}
	@media screen and (max-width: 639px) {
		.profile-panel-heading-inline { align-items: flex-start; }
		.profile-identity-copy { flex-basis: calc(100% - 76px); }
		.profile-avatar-actions { flex-direction: column; align-items: stretch; }
		.profile-avatar-button { width: 100%; }
		.profile-quota-summary { flex-direction: column; gap: 6px; }
		.profile-session-row { align-items: stretch; flex-direction: column; gap: 12px; }
		.profile-logout,
		.profile-logout-all { width: 100%; }
	}

	@media screen and (min-width: 768px) {
		.profile-shell { padding-top: 48px; }
	}
	@media screen and (min-width: 1024px) {
		.profile-scroll { height: 100%; }
		.profile-shell { max-width: 800px; padding: 48px 24px; }
	}
	/* #ifdef H5 */
	// H5 账户页使用紧凑的单一工作台表面，避免宽屏下导航、内容和操作入口彼此漂移。
	.profile-shell {
		width: 100%;
		max-width: 820px;
		min-height: 0;
		margin: 0 auto;
		padding: 32px 20px 48px;
		box-sizing: border-box;
	}
	.profile-heading-row { margin-bottom: 20px; align-items: flex-start; }
	.profile-kicker { font-size: 11px; letter-spacing: 1.8px; }
	.profile-title { margin-top: 6px; font-size: 36px; }
	.profile-subtitle { margin-top: 6px; font-size: 14px; }
	.profile-refresh {
		width: 40px;
		height: 40px;
		min-width: 40px;
		min-height: 40px;
		padding: 0;
		flex: 0 0 40px;
		border-radius: 11px;
	}
	.profile-refresh-label { display: none; }
	.profile-workbench {
		width: 100%;
		display: grid;
		grid-template-columns: 124px minmax(0, 1fr);
		align-items: stretch;
		overflow: hidden;
		border: 1px solid rgba(151, 170, 160, .18);
		border-radius: 16px;
		background: #101412;
		box-sizing: border-box;
	}
	.profile-workbench-navigation {
		min-width: 0;
		margin: 0;
		padding: 12px 8px;
		display: flex;
		flex-direction: column;
		gap: 6px;
		overflow: visible;
		border-right: 1px solid rgba(151, 170, 160, .14);
		background: rgba(6, 9, 7, .2);
		box-sizing: border-box;
	}
	.profile-workbench-navigation-title {
		display: block;
		padding: 4px 7px 7px;
		color: #77837c;
		font-size: 10px;
		font-weight: 700;
		letter-spacing: .8px;
	}
	.profile-workbench-nav-item {
		width: 100%;
		min-width: 0;
		min-height: 56px;
		margin: 0;
		padding: 7px 6px;
		display: flex;
		align-items: center;
		gap: 8px;
		border: 1px solid transparent;
		border-radius: 10px;
		background: transparent;
		color: #b4beb8;
		text-align: left;
		transition: background-color 160ms ease-out, border-color 160ms ease-out, color 160ms ease-out;
	}
	.profile-workbench-nav-item::after { border: 0; }
	.profile-workbench-nav-item.is-active {
		border-color: rgba(55, 211, 154, .38);
		background: rgba(55, 211, 154, .1);
		color: #eff8f3;
	}
	.profile-workbench-nav-item:focus-visible { outline: 3px solid rgba(55, 211, 154, .28); outline-offset: 2px; }
	.profile-workbench-nav-icon {
		width: 32px;
		height: 32px;
		flex: 0 0 32px;
		display: flex;
		align-items: center;
		justify-content: center;
		border-radius: 9px;
		background: rgba(255, 255, 255, .035);
	}
	.profile-workbench-nav-copy { min-width: 0; display: flex; flex-direction: column; }
	.profile-workbench-nav-label { color: inherit; font-size: 12px; font-weight: 690; line-height: 1.3; }
	.profile-workbench-nav-detail {
		margin-top: 3px;
		overflow: hidden;
		display: -webkit-box;
		-webkit-box-orient: vertical;
		-webkit-line-clamp: 2;
		color: #7f8b84;
		font-size: 10px;
		line-height: 1.3;
	}
	.profile-workbench-nav-item.is-active .profile-workbench-nav-detail { color: #99b7a8; }
	.profile-workbench-content {
		width: 100%;
		max-width: none;
		padding: 20px 22px 24px;
		display: flex;
		flex-direction: column;
		gap: 0;
		box-sizing: border-box;
	}
	.profile-workbench-panel { width: 100%; min-width: 0; }
	.profile-panel-heading { margin-bottom: 16px; gap: 5px; }
	.profile-panel-title { font-size: 20px; }
	.profile-panel-description { font-size: 13px; }
	.profile-identity-card {
		min-height: 88px;
		padding: 16px;
		flex-wrap: nowrap;
		gap: 14px;
		border-radius: 14px;
		box-sizing: border-box;
	}
	.profile-avatar-actions {
		width: auto;
		max-width: 224px;
		margin: 0 0 0 auto;
		justify-content: flex-end;
	}
	.profile-avatar-button { min-height: 40px; padding: 0 13px; }
	.profile-section { margin-top: 18px; }
	.profile-section-heading-copy { margin: 0 2px 10px; }
	.profile-section-title { font-size: 13px; }
	.profile-card { border-radius: 13px; }
	.profile-row { min-height: 70px; padding: 14px 16px; }
	.profile-row-icon { width: 38px; height: 38px; flex: 0 0 38px; }
	.profile-divider { margin-left: 68px; }
	.profile-membership-summary {
		min-height: 68px;
		margin-top: 14px;
		padding: 14px 16px;
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 16px;
		border: 1px solid rgba(151, 170, 160, .16);
		border-radius: 13px;
		background: rgba(21, 25, 22, .78);
		box-sizing: border-box;
	}
	.profile-membership-summary-copy { min-width: 0; display: flex; flex-direction: column; }
	.profile-membership-summary-detail { margin-top: 4px; color: #8f9b95; font-size: 12px; line-height: 1.4; }
	.profile-quota-card { padding: 18px; border-radius: 13px; }
	.profile-quota-value { margin-top: 8px; font-size: 30px; }
	.profile-quota-summary { margin-top: 14px; }
	.profile-settings-row,
	.profile-api-key-card { min-height: 80px; padding: 14px 16px; border-radius: 13px; }
	.profile-settings-row-icon,
	.profile-api-key-icon { width: 40px; height: 40px; flex-basis: 40px; border-radius: 11px; }
	.profile-settings-row-detail,
	.profile-api-key-detail {
		overflow: hidden;
		display: -webkit-box;
		-webkit-box-orient: vertical;
		-webkit-line-clamp: 2;
	}
	.profile-action-group { border-radius: 13px; }
	.profile-session-row { min-height: 80px; padding: 14px 16px; gap: 16px; }
	.profile-session-divider { margin: 0 16px; }
	.profile-logout,
	.profile-logout-all { min-width: 136px; }

	@media screen and (min-width: 640px) and (max-width: 767px) {
		.profile-workbench {
			grid-template-columns: 112px minmax(0, 1fr);
		}
		.profile-shell { padding-right: 16px; padding-left: 16px; }
		.profile-workbench-navigation { padding-right: 6px; padding-left: 6px; }
		.profile-workbench-nav-item { padding-right: 4px; padding-left: 4px; gap: 6px; }
		.profile-workbench-nav-icon { width: 30px; height: 30px; flex-basis: 30px; }
		.profile-workbench-content { padding-right: 18px; padding-left: 18px; }
	}
	@media screen and (max-width: 639px) {
		.profile-shell { padding: 24px 12px 36px; }
		.profile-title { font-size: 30px; }
		.profile-subtitle { font-size: 13px; }
		.profile-workbench { grid-template-columns: minmax(0, 1fr); }
		.profile-workbench-navigation {
			padding: 8px;
			flex-direction: row;
			gap: 6px;
			overflow-x: auto;
			border-right: 0;
			border-bottom: 1px solid rgba(151, 170, 160, .14);
		}
		.profile-workbench-navigation-title { display: none; }
		.profile-workbench-nav-item {
			width: auto;
			min-width: 104px;
			min-height: 48px;
			padding: 6px 8px;
			flex: 0 0 auto;
		}
		.profile-workbench-nav-detail { display: none; }
		.profile-workbench-content { padding: 16px; }
		.profile-panel-heading-inline { flex-direction: column; gap: 10px; }
		.profile-identity-card { flex-wrap: wrap; }
		.profile-identity-copy { flex-basis: calc(100% - 74px); }
		.profile-avatar-actions { width: 100%; max-width: none; margin-left: 0; align-items: stretch; flex-direction: column; }
		.profile-avatar-button { width: 100%; min-height: 44px; }
		.profile-membership-summary { align-items: flex-start; }
		.profile-quota-summary { flex-direction: column; gap: 6px; }
		.profile-session-row { align-items: stretch; flex-direction: column; gap: 12px; }
		.profile-logout,
		.profile-logout-all { width: 100%; min-width: 0; }
	}
	/* #endif */
	@media (hover: hover) and (pointer: fine) {
		.profile-refresh:hover { background: #202520; border-color: #4d6258; }
		.profile-api-key-card:hover { border-color: rgba(55, 211, 154, .34); background: #171b18; }
		.profile-settings-row:hover { border-color: rgba(55, 211, 154, .34); background: #171b18; }
		.profile-workbench-nav-item:hover { background: rgba(255, 255, 255, .045); }
		.profile-workbench-nav-item.is-active:hover { background: rgba(55, 211, 154, .12); }
	}
	@media (prefers-reduced-motion: reduce) {
		.profile-retry, .profile-security, .profile-logout, .profile-logout-all, .profile-refresh, .profile-quota-progress-fill, .profile-api-key-card, .profile-settings-row, .profile-workbench-nav-item { transition: none; }
		.profile-api-key-card:active, .profile-settings-row:active { transform: none; }
	}
</style>

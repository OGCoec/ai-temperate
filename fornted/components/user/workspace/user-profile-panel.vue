<template>
	<view class="profile-page">
		<scroll-view class="profile-scroll" scroll-y>
			<view class="profile-shell" :aria-busy="loading">
				<view class="profile-heading-row">
					<view class="profile-heading">
						<text class="profile-kicker">YOUR ACCOUNT</text>
						<text class="profile-title">个人</text>
						<text class="profile-subtitle">查看账户资料、订阅状态与当前可用额度。</text>
					</view>
					<button
						class="profile-refresh"
						type="button"
						:disabled="loading"
						aria-label="刷新个人资料"
						@click="refreshProfile(true)"
					>
						<uni-icons type="refreshempty" size="19" color="#dce5e0" aria-hidden="true" />
						<text>刷新</text>
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
					</view>

					<view class="profile-avatar-actions">
						<button
							class="profile-avatar-button"
							type="button"
							:disabled="avatarBusy"
							@click="selectAvatar"
						>
							{{ pendingAvatar ? '重新选择头像' : '选择新头像' }}
						</button>
						<template v-if="pendingAvatar && pendingAvatar.uploaded">
							<button
								class="profile-avatar-button profile-avatar-confirm"
								type="button"
								:disabled="avatarBusy"
								@click="confirmAvatar"
							>
								确认使用
							</button>
							<button
								class="profile-avatar-button profile-avatar-cancel"
								type="button"
								:disabled="avatarBusy"
								@click="cancelAvatar"
							>
								取消上传
							</button>
						</template>
						<text v-if="pendingAvatar && !pendingAvatar.uploaded" class="profile-avatar-progress">
							正在上传头像…
						</text>
					</view>

					<view class="profile-section profile-quota-section">
						<view class="profile-section-heading">
							<text class="profile-section-title">订阅与额度</text>
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
								<view
									class="profile-quota-progress"
									role="progressbar"
									aria-valuemin="0"
									aria-valuemax="100"
									:aria-valuenow="quotaUsagePercent"
									:aria-valuetext="quotaProgressAriaText"
								>
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
					</view>

					<view class="profile-section">
						<text class="profile-section-title">联系方式</text>
						<view class="profile-card">
							<view class="profile-row">
								<view class="profile-row-icon"><uni-icons type="email" size="20" color="#37d39a" /></view>
								<view class="profile-row-copy">
									<text class="profile-label">邮箱</text>
									<text class="profile-value" selectable>{{ profile.email }}</text>
								</view>
							</view>
							<view class="profile-divider"></view>
							<view class="profile-row">
								<view class="profile-row-icon"><uni-icons :type="phone.bound && !phone.countryResolved ? 'map' : 'phone'" size="20" color="#65c7c2" /></view>
								<view class="profile-row-copy">
									<text class="profile-label">电话</text>
									<text class="profile-value" selectable>{{ phone.displayNumber }}</text>
									<text v-if="phone.bound" class="profile-detail">{{ phoneDetail }}</text>
									<text v-if="phone.countryResolved && phone.nationalNumber" class="profile-detail">本地号码：{{ phone.nationalNumber }}</text>
								</view>
							</view>
						</view>
					</view>

					<view class="profile-action-group">
						<button class="profile-security" type="button" :disabled="logoutBusy" @click="openTotpSecurity">
							<uni-icons type="locked" size="19" color="#37d39a" aria-hidden="true" />
							<text>二次认证设置</text>
						</button>
						<button class="profile-logout" type="button" :disabled="logoutBusy" @click="logout">
							{{ loggingOut ? '正在退出…' : '退出登录' }}
						</button>
						<button class="profile-logout-all" type="button" :disabled="logoutBusy" @click="confirmLogoutAll">
							{{ loggingOutAll ? '正在退出所有设备…' : '退出所有设备' }}
						</button>
					</view>
				</template>
			</view>
		</scroll-view>
	</view>
</template>

<script>
	import { AUTH_ROUTES } from '@/common/auth/config.js'
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
			phone() { return derivePhonePresentation(this.profile?.phone) },
			phoneDetail() {
				const parts = []
				parts.push(`${this.phone.flag ? `${this.phone.flag} ` : ''}${this.phone.countryName}`)
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

	.profile-heading-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; }
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
	.profile-quota-value { display: block; margin-top: 7px; color: #f3f5f4; font-size: 28px; font-weight: 750; line-height: 1.2; font-variant-numeric: tabular-nums; }
	.profile-quota-summary { display: flex; justify-content: space-between; gap: 12px; margin-top: 16px; color: #aeb8b3; font-size: 13px; font-variant-numeric: tabular-nums; }
	.profile-quota-progress { height: 8px; margin-top: 10px; overflow: hidden; border-radius: 999px; background: #2b312e; }
	.profile-quota-progress-fill { width: 100%; height: 100%; border-radius: inherit; background: #37d39a; transform: scaleX(0); transform-origin: left center; transition: transform 200ms ease-out; }
	.profile-quota-unavailable { display: block; margin-top: 14px; color: #8b9690; font-size: 13px; }
	.profile-quota-divider { height: 1px; margin: 18px 0; background: #303733; }
	.profile-quota-meta { display: flex; align-items: flex-start; gap: 10px; }
	.profile-quota-meta-copy { min-width: 0; display: flex; flex: 1; flex-direction: column; }

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

	@media screen and (min-width: 768px) {
		.profile-shell { padding-top: 48px; }
	}
	@media screen and (min-width: 1024px) {
		.profile-scroll { height: 100%; }
		.profile-shell { max-width: 800px; padding: 48px 24px; }
	}
	@media (hover: hover) and (pointer: fine) {
		.profile-refresh:hover { background: #202520; border-color: #4d6258; }
	}
	@media (prefers-reduced-motion: reduce) {
		.profile-retry, .profile-security, .profile-logout, .profile-logout-all, .profile-refresh, .profile-quota-progress-fill { transition: none; }
	}
</style>

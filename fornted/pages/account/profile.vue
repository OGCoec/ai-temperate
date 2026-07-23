<template>
	<view v-if="authReady" class="profile-page">
		<scroll-view class="profile-scroll" scroll-y>
			<view class="profile-shell" :aria-busy="loading">
				<view class="profile-heading">
					<text class="profile-kicker">YOUR ACCOUNT</text>
					<text class="profile-title">个人</text>
					<text class="profile-subtitle">查看当前账号的联系方式和会话状态。</text>
				</view>

				<view v-if="loading && !profile" class="profile-state" role="status">
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
						<view class="profile-avatar" aria-hidden="true">
							<text>{{ avatarText }}</text>
						</view>
						<view class="profile-identity-copy">
							<text class="profile-name">{{ profile.displayName }}</text>
							<text class="profile-status"><text class="profile-status-dot"></text>当前会话有效</text>
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

		<view class="profile-bottom-nav" aria-hidden="true">
			<view class="profile-bottom-item active">
				<uni-icons type="person" size="25" color="#37d39a" />
				<text>个人</text>
			</view>
		</view>
	</view>
</template>

<script>
	import { AUTH_ROUTES } from '@/common/auth/config.js'
	import { logoutAllSessions, logoutSession } from '@/common/auth/http-client.js'
	import {
		getCurrentUserProfile,
		loadCurrentUserProfile
	} from '@/common/user/current-user-profile.js'
	import { derivePhonePresentation } from '@/common/user/phone-presentation.js'

	export default {
		data() {
			return {
				profile: getCurrentUserProfile(),
				loading: false,
				loggingOut: false,
				loggingOutAll: false,
				error: ''
			}
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
			logoutBusy() {
				return this.loggingOut || this.loggingOutAll
			}
		},
		methods: {
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
			async logout() {
				if (this.logoutBusy) return
				this.loggingOut = true
				try {
					await logoutSession()
				} catch (error) {
					// 服务端不可达时本地会话仍会在 logoutSession 的 finally 中清除。
				} finally {
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
	.profile-page {
		position: relative;
		height: 100%;
		min-height: 100vh;
		background: #0b0d0c;
		color: #f3f5f4;
	}

	.profile-scroll {
		height: 100%;
		min-height: 100vh;
	}

	.profile-shell {
		max-width: 720px;
		min-height: 100%;
		box-sizing: border-box;
		margin: 0 auto;
		padding: 34px 20px calc(122px + env(safe-area-inset-bottom));
	}

	.profile-heading { display: flex; flex-direction: column; margin-bottom: 28px; }
	.profile-kicker { color: #37d39a; font-size: 13px; font-weight: 700; letter-spacing: 2px; }
	.profile-title { color: #f3f5f4; font-size: 36px; line-height: 1.2; font-weight: 750; margin-top: 10px; }
	.profile-subtitle { color: #8b9690; font-size: 15px; line-height: 1.6; margin-top: 10px; }

	.profile-state {
		min-height: 180px;
		box-sizing: border-box;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 12px;
		padding: 28px;
		border: 1px solid #303733;
		border-radius: 20px;
		background: #151816;
		color: #8b9690;
		text-align: center;
	}

	.profile-action-group {
		display: flex;
		flex-direction: column;
		gap: 12px;
		margin-top: 52px;
	}

	.profile-retry, .profile-logout, .profile-logout-all {
		min-height: 48px;
		border-radius: 14px;
		font-size: 16px;
		font-weight: 650;
		display: flex;
		align-items: center;
		justify-content: center;
		text-align: center;
		line-height: 1;
		box-sizing: border-box;
		transition: transform 150ms ease, background-color 150ms ease, border-color 150ms ease;
	}
	.profile-retry { margin-top: 4px; padding: 0 22px; background: #37d39a; color: #0b0d0c; }
	.profile-retry::after, .profile-logout::after, .profile-logout-all::after { border: 0; }

	.profile-identity-card {
		display: flex;
		align-items: center;
		gap: 16px;
		padding: 20px;
		border: 1px solid #303733;
		border-radius: 20px;
		background: #151816;
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
	.profile-identity-copy, .profile-row-copy { min-width: 0; flex: 1; display: flex; flex-direction: column; }
	.profile-name { color: #f3f5f4; font-size: 20px; font-weight: 700; }
	.profile-status { margin-top: 6px; color: #8b9690; font-size: 13px; }
	.profile-status-dot { display: inline-block; width: 7px; height: 7px; margin-right: 7px; border-radius: 50%; background: #a8db4d; }

	.profile-section { margin-top: 28px; }
	.profile-section-title { display: block; margin: 0 0 10px 4px; color: #8b9690; font-size: 13px; font-weight: 650; }
	.profile-card { border: 1px solid #303733; border-radius: 20px; background: #151816; overflow: hidden; }
	.profile-row { min-height: 76px; display: flex; align-items: center; gap: 14px; padding: 16px 18px; box-sizing: border-box; }
	.profile-row-icon { width: 40px; height: 40px; border-radius: 12px; display: flex; align-items: center; justify-content: center; background: #171a18; }
	.profile-label { color: #8b9690; font-size: 13px; }
	.profile-value { margin-top: 4px; color: #f3f5f4; font-size: 16px; line-height: 1.45; word-break: break-all; }
	.profile-detail { margin-top: 5px; color: #8b9690; font-size: 13px; line-height: 1.45; }
	.profile-divider { height: 1px; margin-left: 72px; background: #303733; }

	.profile-logout {
		width: 100%;
		border: 1px solid #c9822f;
		background: rgba(201, 130, 47, .1);
		color: #f2a24d;
	}
	.profile-logout-all {
		width: 100%;
		border: 1px solid #d95d59;
		background: rgba(217, 93, 89, .1);
		color: #f08a82;
	}
	.profile-logout:active, .profile-logout-all:active, .profile-retry:active { transform: scale(.985); }
	.profile-logout:disabled, .profile-logout-all:disabled { opacity: .55; }
	.profile-retry:focus-visible {
		outline: 3px solid rgba(55, 211, 154, .28);
		outline-offset: 3px;
	}
	.profile-logout:focus-visible {
		outline: 3px solid rgba(242, 162, 77, .28);
		outline-offset: 3px;
	}
	.profile-logout-all:focus-visible {
		outline: 3px solid rgba(217, 93, 89, .3);
		outline-offset: 3px;
	}

	.profile-bottom-nav {
		position: fixed;
		right: 0;
		bottom: 0;
		left: 0;
		z-index: 20;
		min-height: 74px;
		padding-bottom: env(safe-area-inset-bottom);
		display: flex;
		align-items: center;
		justify-content: center;
		border-top: 1px solid #202520;
		background: #151816;
		box-sizing: border-box;
	}

	.profile-bottom-item {
		min-width: 90px;
		min-height: 58px;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 4px;
		color: #37d39a;
		font-size: 13px;
		font-weight: 650;
	}

	@media screen and (min-width: 768px) {
		.profile-shell { padding-top: 48px; }
	}
	@media (prefers-reduced-motion: reduce) {
		.profile-retry, .profile-logout, .profile-logout-all { transition: none; }
	}
</style>

<template>
	<view class="workspace-panel">
		<admin-page-header
			kicker="管理员工作台"
			title="控制台"
			description="集中查看当前管理员会话，并进入模型、凭据与邮件证据工作流。"
		>
			<template #meta>
				<view class="session-chip"><view class="session-chip-dot" aria-hidden="true" /><text>会话已验证</text></view>
				<text class="session-expiry">滑动到期：{{ expiresLabel }}</text>
			</template>
			<template #actions>
				<admin-action-button tone="teal" size="compact" :loading="busy" @click="refreshProfile">刷新会话</admin-action-button>
				<admin-action-button tone="neutral" size="compact" :disabled="busy" @click="logoutCurrent">退出当前设备</admin-action-button>
			</template>
		</admin-page-header>

		<admin-feedback-banner
			v-if="message"
			:tone="messageType === 'error' ? 'danger' : 'success'"
			:message="message"
			:dismissible="true"
			@dismiss="message = ''"
		/>

		<view class="dashboard-overview">
			<view class="session-panel">
				<view class="session-identity">
					<view class="profile-badge">{{ profileFlag }}</view>
					<view><text class="section-heading">管理员会话</text><text class="section-copy">当前设备已通过后端会话校验。</text></view>
				</view>
				<view class="profile-list">
					<view class="profile-row"><text class="profile-label">邮箱</text><text class="profile-value">{{ profile.email || '—' }}</text></view>
					<view class="profile-row"><text class="profile-label">国际手机号</text><text class="profile-value">{{ profile.phoneE164 || '—' }}</text></view>
					<view class="profile-row"><text class="profile-label">会话到期</text><text class="profile-value">{{ expiresLabel }}</text></view>
				</view>
			</view>

			<view class="operations-panel">
				<view class="section-intro">
					<view><text class="section-heading">工作区</text><text class="section-copy">左侧导航与安全外壳保持不变，只切换右侧业务内容。</text></view>
					<text class="operation-count">4 个入口</text>
				</view>
				<view class="operation-list">
					<button v-for="item in operations" :key="item.location.view" class="operation-row" type="button" :disabled="busy" @click="$emit('request-navigation', item.location)">
						<text class="operation-symbol">{{ item.symbol }}</text>
						<view class="operation-copy"><text class="operation-title">{{ item.title }}</text><text class="operation-description">{{ item.description }}</text></view>
						<text class="operation-arrow" aria-hidden="true">›</text>
					</button>
				</view>
			</view>

			<view class="security-panel">
				<view><text class="section-heading">安全边界</text><text class="section-copy">所有权限仍由后端强制执行，前端工作台不缓存认证结论来替代服务端校验。</text></view>
				<view class="security-facts"><text>单管理员</text><text>hCaptcha 后端校验</text><text>六小时滑动会话</text></view>
				<admin-action-button tone="danger" size="compact" :disabled="busy" @click="logoutEverywhere">退出所有设备</admin-action-button>
			</view>
		</view>
	</view>
</template>

<script>
import AdminActionButton from '@/components/admin/admin-action-button.vue'
import AdminFeedbackBanner from '@/components/admin/admin-feedback-banner.vue'
import AdminPageHeader from '@/components/admin/admin-page-header.vue'
import { adminApi } from '@/common/admin/admin-api.js'

const flagFromIso2 = iso2 => String(iso2 || '').toUpperCase().replace(/[A-Z]/g,
	character => String.fromCodePoint(127397 + character.charCodeAt(0)))

export default {
	name: 'DashboardPanel',
	components: { AdminActionButton, AdminFeedbackBanner, AdminPageHeader },
	emits: ['request-navigation', 'session-invalid'],
	data() {
		return {
			busy: false,
			profile: {},
			message: '',
			messageType: '',
			operations: [
				{ symbol: 'AI', title: 'AI 模型目录', description: '分页查询、新增、编辑并单个或批量启停模型', location: { view: 'ai-models' } },
				{ symbol: '◇', title: '模型图标库', description: '上传 OSS 图片、登记外部 URL 并维护复用图标', location: { view: 'ai-model-icons' } },
				{ symbol: 'IP', title: 'IP 信誉凭据', description: '导入、查看和撤销 IP2Location 加密调用凭据', location: { view: 'ip2location-keys' } },
				{ symbol: '@', title: '邮箱证据检查', description: 'OpenAI、Kiro 与 IP2Location 单向实时结果', location: { view: 'mail-openai' } }
			]
		}
	},
	computed: {
		profileFlag() { return flagFromIso2(this.profile.countryIso2) || 'A' },
		expiresLabel() {
			if (!this.profile.expiresAt) return '未知'
			return new Date(this.profile.expiresAt).toLocaleString()
		}
	},
	methods: {
		onWorkspaceActivated() {
			if (!this.profile.email) return this.loadProfile(false)
		},
		onWorkspaceDeactivated() {},
		async loadProfile(showNotice) {
			if (this.busy) return
			this.busy = true
			this.message = ''
			try {
				this.profile = await adminApi.me()
				if (showNotice) {
					this.message = '管理员会话已续期。'
					this.messageType = 'success'
				}
			} catch (error) {
				if (error?.code === 'ADMIN_SESSION_INVALID' || error?.statusCode === 401) {
					this.$emit('session-invalid', error)
					return
				}
				this.message = error?.message || '管理员资料暂时无法加载。'
				this.messageType = 'error'
			} finally {
				this.busy = false
			}
		},
		refreshProfile() { return this.loadProfile(true) },
		async logoutCurrent() { await this.logout(() => adminApi.logout()) },
		async logoutEverywhere() { await this.logout(() => adminApi.logoutAll()) },
		async logout(action) {
			if (this.busy) return
			this.busy = true
			try {
				await action()
				this.$emit('session-invalid')
			} catch (error) {
				if (error?.code === 'ADMIN_SESSION_INVALID' || error?.statusCode === 401) this.$emit('session-invalid', error)
				else {
					this.message = error?.message || '退出请求未完成。'
					this.messageType = 'error'
				}
			} finally {
				this.busy = false
			}
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.dashboard-overview { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); gap: $app-space-3; }
.session-panel,
.operations-panel,
.security-panel { @include admin-solid-panel; }
.session-panel,
.operations-panel { padding: $app-space-4; }
.session-identity,
.section-intro,
.security-panel { display: flex; align-items: center; justify-content: space-between; gap: $app-space-3; }
.session-identity { justify-content: flex-start; }
.profile-badge { width: 72rpx; height: 72rpx; border-radius: 22rpx; display: grid; place-items: center; background: rgba($app-green, .14); color: $app-green; font-weight: 800; }
.section-heading,
.section-copy { display: block; }
.section-heading { color: $app-text; font-size: 28rpx; font-weight: 760; }
.section-copy { margin-top: 6rpx; color: $app-muted; font-size: 24rpx; line-height: 1.45; }
.profile-list { margin-top: $app-space-4; }
.profile-row { min-height: 76rpx; border-top: 1px solid $app-border; display: flex; align-items: center; justify-content: space-between; gap: 20rpx; }
.profile-label { color: $app-muted; }
.profile-value { min-width: 0; overflow-wrap: anywhere; text-align: right; }
.operation-count,
.session-chip,
.session-expiry { color: $app-muted; font-size: 24rpx; }
.session-chip { display: inline-flex; align-items: center; gap: 10rpx; }
.session-chip-dot { width: 10rpx; height: 10rpx; border-radius: 50%; background: $app-green; }
.session-expiry { margin-left: 14rpx; }
.operation-list { margin-top: $app-space-3; }
.operation-row { width: 100%; min-height: 92rpx; margin: 0; padding: 14rpx 16rpx; border: 0; border-top: 1px solid $app-border; display: grid; grid-template-columns: 52rpx minmax(0, 1fr) auto; align-items: center; gap: 16rpx; background: transparent; color: $app-text; text-align: left; }
.operation-row::after { border: 0; }
.operation-symbol { width: 48rpx; height: 48rpx; border-radius: 14rpx; display: grid; place-items: center; background: rgba($app-green, .1); color: $app-green; font-size: 20rpx; font-weight: 760; }
.operation-copy { min-width: 0; display: flex; flex-direction: column; }
.operation-title { font-weight: 700; }
.operation-description { margin-top: 4rpx; color: $app-muted; font-size: 23rpx; }
.operation-arrow { color: $app-muted; font-size: 34rpx; }
.security-panel { grid-column: 1 / -1; padding: $app-space-3 $app-space-4; }
.security-facts { display: flex; flex-wrap: wrap; gap: 10rpx; }
.security-facts text { padding: 8rpx 14rpx; border-radius: 999px; background: rgba($app-muted, .08); color: $app-muted; font-size: 22rpx; }

@media (max-width: 900px) {
	.dashboard-overview { grid-template-columns: 1fr; }
	.security-panel { grid-column: auto; align-items: flex-start; flex-direction: column; }
}

@media (max-width: 560px) {
	.session-panel,
	.operations-panel { padding: 24rpx; }
	.operation-description { white-space: normal; }
}
</style>

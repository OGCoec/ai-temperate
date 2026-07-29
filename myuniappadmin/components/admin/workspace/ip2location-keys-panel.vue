<template>
	<view class="workspace-panel">
		<admin-page-header
			kicker="网络风险"
			title="IP 信誉凭据"
			description="管理 IP2Location 调用池。页面只显示脱敏元数据，密钥由后端加密保存。"
		>
		<template #meta>
			<view class="capacity-badge">
				<text class="numeric">{{ allKeys.length }}</text>
				<text> / 100 个凭据</text>
			</view>
		</template>
		<template #actions>
			<admin-action-button tone="neutral" :loading="loading" :disabled="importing || deleting" @click="loadKeys">
				刷新
			</admin-action-button>
			<admin-action-button class="desktop-import" tone="amber" :disabled="allKeys.length >= 100" @click="openImport">
				导入凭据
			</admin-action-button>
		</template>
		</admin-page-header>
		<view class="page-shell">
			<admin-feedback-banner
				v-if="banner.message"
				:tone="banner.type === 'error' ? 'danger' : (banner.type === 'warning' ? 'warning' : 'success')"
				:message="banner.message"
				:dismissible="true"
				@dismiss="banner = { type: '', message: '' }"
			/>

			<view class="status-strip" aria-label="凭据状态概览">
				<view class="status-item"><text class="status-name">有效</text><text class="status-value numeric">{{ summary.active }}</text></view>
				<view class="status-item"><text class="status-name">即将过期</text><text class="status-value numeric warning">{{ summary.expiring }}</text></view>
				<view class="status-item"><text class="status-name">额度耗尽</text><text class="status-value numeric danger">{{ summary.exhausted }}</text></view>
				<view class="status-item"><text class="status-name">不可用</text><text class="status-value numeric">{{ summary.unavailable }}</text></view>
			</view>

			<view class="content-panel">
				<view v-if="loadError" class="load-error" role="alert">
					<view>
						<text class="load-error-title">凭据列表未能加载</text>
						<text class="load-error-copy">{{ loadError }}</text>
					</view>
					<button type="button" @click="loadKeys">重新加载</button>
				</view>
				<ip2-location-key-list
					v-else
					ref="keyList"
					:items="pageState.items"
					:page="pageState.page"
					:page-count="pageState.pageCount"
					:loading="loading"
					@open-import="openImport"
					@page-change="changePage"
					@delete-one="confirmDeleteOne"
					@delete-selected="confirmDeleteMany"
				/>
			</view>
		</view>

		<button class="mobile-import" type="button" :disabled="allKeys.length >= 100" @click="openImport">导入凭据</button>
		<ip2-location-key-import-sheet
			ref="importSheet"
			:open="importOpen"
			:current-count="allKeys.length"
			:busy="importing"
			:server-error="importError"
			@close="closeImport"
			@submit="importKeys"
		/>
	</view>
</template>

<script>
import AdminActionButton from '@/components/admin/admin-action-button.vue'
import AdminFeedbackBanner from '@/components/admin/admin-feedback-banner.vue'
import AdminPageHeader from '@/components/admin/admin-page-header.vue'
import Ip2LocationKeyImportSheet from '@/components/admin/ip2location-key-import-sheet.vue'
import Ip2LocationKeyList from '@/components/admin/ip2location-key-list.vue'
import { adminIp2LocationKeyApi } from '@/common/admin/admin-ip2location-key-api.js'
import { adminPrefersReducedMotion } from '@/common/admin/admin-motion.js'
import {
	paginateIp2LocationKeys,
	presentIp2LocationKey,
	sortIp2LocationKeys,
	summarizeIp2LocationKeys
} from '@/common/admin/ip2location-key-presenter.js'

export default {
	name: 'Ip2locationKeysPanel',
	components: {
		AdminActionButton,
		AdminFeedbackBanner,
		AdminPageHeader,
		Ip2LocationKeyImportSheet,
		Ip2LocationKeyList
	},
	data() {
		return {
			allKeys: [],
			page: 1,
			loading: false,
			loadError: '',
			importOpen: false,
			importing: false,
			importError: '',
			deleting: false,
			banner: { type: '', message: '' }
		}
	},
	computed: {
		presentedKeys() {
			const now = Date.now()
			return sortIp2LocationKeys(this.allKeys).map(item => presentIp2LocationKey(item, now))
		},
		pageState() { return paginateIp2LocationKeys(this.presentedKeys, this.page) },
		summary() { return summarizeIp2LocationKeys(this.allKeys) }
	},
	methods: {
		onWorkspaceActivated() {
			return this.loadKeys()
		},
		onWorkspaceDeactivated() {
			this.importOpen = false
		},
		beforeWorkspaceLeave() {
			return !this.importing && !this.deleting
		},
		hasWorkspaceOverlay() {
			return this.importOpen
		},
		closeWorkspaceOverlay() {
			if (!this.importOpen) return false
			this.$refs.importSheet?.requestClose()
			return true
		},
		async loadKeys() {
			if (this.loading) return
			this.loading = true
			this.loadError = ''
			try {
				this.allKeys = await adminIp2LocationKeyApi.listAll()
				this.page = Math.min(this.page, Math.max(1, Math.ceil(this.allKeys.length / 20)))
			} catch (error) {
				this.loadError = error?.message || '管理员凭据接口暂时不可用。'
			} finally {
				this.loading = false
			}
		},
		changePage(page) {
			this.page = page
			// 分页只改变当前视图，不重新请求 Redis，保证排序基于已经收齐的完整集合。
			if (typeof window !== 'undefined') {
				window.scrollTo({
					top: 0,
					behavior: adminPrefersReducedMotion() ? 'auto' : 'smooth'
				})
			}
		},
		openImport() {
			if (this.allKeys.length >= 100) {
				this.banner = { type: 'warning', message: '凭据池已达到 100 条上限，请先删除不再使用的凭据。' }
				return
			}
			this.importError = ''
			this.importOpen = true
		},
		closeImport() {
			this.importOpen = false
			this.importError = ''
		},
		async importKeys(command) {
			if (this.importing) return
			this.importing = true
			this.importError = ''
			try {
				const result = await adminIp2LocationKeyApi.importBatch(command)
				this.$refs.importSheet?.resetSensitiveInput()
				this.importOpen = false
				this.banner = {
					type: 'success',
					message: `导入完成：新增 ${result.createdCount || 0}，更新 ${result.updatedCount || 0}，重复 ${result.duplicateCount || 0}。`
				}
				await this.loadKeys()
			} catch (error) {
				// 失败时保留当前输入，便于管理员修正；错误对象和日志中不附加原始 Key。
				this.importError = error?.message || '凭据导入失败，请稍后重试。'
			} finally {
				this.importing = false
			}
		},
		confirmDeleteOne(item) {
			this.confirmDelete([item.keyId], `确定删除凭据 ${item.maskedKey}？`)
		},
		confirmDeleteMany(keyIds) {
			this.confirmDelete(keyIds, `确定删除选中的 ${keyIds.length} 条凭据？`)
		},
		confirmDelete(keyIds, content) {
			if (this.deleting || !keyIds.length) return
			uni.showModal({
				title: '删除凭据',
				content,
				confirmText: '删除',
				confirmColor: '#ef8f91',
				success: result => { if (result.confirm) this.deleteKeys(keyIds) }
			})
		},
		async deleteKeys(keyIds) {
			this.deleting = true
			try {
				const result = await adminIp2LocationKeyApi.deleteBatch(keyIds)
				this.banner = { type: 'success', message: `已删除 ${result.deletedCount || 0} 条凭据。` }
				this.$refs.keyList?.clearSelection()
				await this.loadKeys()
			} catch (error) {
				this.banner = { type: 'error', message: error?.message || '删除失败，选择状态已保留。' }
			} finally {
				this.deleting = false
			}
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.credentials-page { min-height: 100vh; box-sizing: border-box; padding: 34rpx 34rpx calc(44rpx + env(safe-area-inset-bottom)); background: $app-bg; color: $app-text; }
.page-shell { width: min(1500px, 100%); margin: $app-space-4 auto 0; }
.context-bar { min-height: 160rpx; display: grid; grid-template-columns: 112rpx minmax(0, 1fr) auto; gap: 28rpx; align-items: center; }
.back-button, .secondary-action, .primary-action { min-height: 80rpx; border-radius: $app-radius-control; display: flex; align-items: center; justify-content: center; padding: 0 20rpx; line-height: 1; font-size: 24rpx; }
.back-button { border: 0; background: transparent; color: $app-muted; }
.title-block { min-width: 0; }
.eyebrow { display: block; color: $app-green; font-size: 24rpx; font-weight: 760; letter-spacing: .12em; }
.page-title { display: block; margin-top: 8rpx; font-size: 54rpx; line-height: 1.04; font-weight: 790; letter-spacing: -.035em; }
.page-copy { display: block; margin-top: 12rpx; color: $app-muted; font-size: 24rpx; line-height: 1.55; }
.header-actions { display: flex; align-items: center; gap: 14rpx; }
.capacity-badge { min-width: 104rpx; min-height: 52rpx; padding: 0 16rpx; border-radius: 999px; display: flex; align-items: center; justify-content: center; color: $app-muted; background: rgba($app-muted, .08); font-size: $app-font-size-caption; }
.capacity-badge .numeric { color: $app-text; font-weight: 760; }
.secondary-action { min-width: 112rpx; border: 1px solid $app-border; background: $app-raised; color: $app-text; }
.primary-action { min-width: 170rpx; border: 0; background: $app-lime; color: #171306; font-weight: 760; }
.admin-feedback-banner { margin-bottom: $app-space-3; }
.status-strip { min-height: 104rpx; @include admin-solid-panel; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); overflow: hidden; }
.status-item { display: flex; align-items: center; justify-content: space-between; padding: 0 26rpx; border-right: 1px solid $app-border; }
.status-item:last-child { border-right: 0; }
.status-name { color: $app-muted; font-size: 24rpx; }
.status-value { color: $app-text; font-size: 31rpx; font-weight: 760; }
.status-value.warning { color: $app-warning; }
.status-value.danger { color: $app-danger-text; }
.content-panel { margin-top: 26rpx; @include admin-solid-panel; overflow: hidden; }
.load-error { min-height: 360rpx; display: flex; align-items: center; justify-content: center; gap: 32rpx; padding: 40rpx; text-align: left; }
.load-error-title { display: block; color: $app-text; font-size: 28rpx; font-weight: 740; }
.load-error-copy { display: block; max-width: 600rpx; margin-top: 10rpx; color: $app-muted; font-size: 24rpx; }
.load-error button { min-height: 80rpx; padding: 0 26rpx; border: 1px solid $app-border; border-radius: $app-radius-control; background: $app-raised; color: $app-text; }
.mobile-import { display: none; }
.numeric { font-variant-numeric: tabular-nums; }
button::after { border: 0; }
button:focus-visible { outline: 2px solid $app-focus; outline-offset: 2px; }

@media (max-width: 760px) {
	.page-shell { margin-top: $app-space-3; padding-bottom: 120rpx; }
	.context-bar { min-height: auto; grid-template-columns: 96rpx minmax(0, 1fr) 96rpx; gap: 10rpx; }
	.page-title { font-size: 38rpx; }
	.eyebrow, .page-copy, .capacity-badge, .desktop-import { display: none; }
	.header-actions { justify-content: flex-end; }
	.secondary-action, .back-button { min-width: 88rpx; min-height: 96rpx; padding: 0; background: transparent; border: 0; }
	.status-strip { grid-template-columns: 1fr 1fr; }
	.status-item { min-height: 86rpx; border-bottom: 1px solid $app-border; }
	.status-item:nth-child(2) { border-right: 0; }
	.status-item:nth-child(3), .status-item:nth-child(4) { border-bottom: 0; }
	.content-panel { margin-top: 20rpx; }
	.mobile-import { position: fixed; z-index: 30; left: 24rpx; right: 24rpx; bottom: calc(18rpx + env(safe-area-inset-bottom)); display: flex; align-items: center; justify-content: center; min-height: 96rpx; border: 0; border-radius: $app-radius-control; background: $app-lime; color: #171306; font-size: 27rpx; font-weight: 780; box-shadow: 0 18rpx 48rpx rgba(0, 0, 0, .42); }
	.load-error { min-height: 320rpx; flex-direction: column; text-align: center; }
	.load-error button { min-height: 96rpx; }
}

@media (prefers-reduced-motion: reduce) {
	.mobile-import { transition: opacity 80ms linear; }
}

@media (prefers-contrast: more) {
	.status-strip,
	.content-panel {
		border: 2px solid $app-text;
		background: $app-canvas;
	}
}
</style>

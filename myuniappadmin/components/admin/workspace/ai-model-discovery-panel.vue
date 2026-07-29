<template>
	<view class="workspace-panel">
		<admin-page-header
			kicker="模型来源"
			title="网关模型"
			description="读取当前网关可用模型并与本地目录匹配。发现和刷新只读，不会自动创建模型或修改计费配置。"
		>
			<template #actions>
				<admin-action-button tone="teal" :loading="loading" @click="loadModels">
					刷新模型
				</admin-action-button>
			</template>
		</admin-page-header>

		<admin-feedback-banner
			v-if="warning"
			tone="warning"
			:message="warning"
			:dismissible="true"
			@dismiss="warning = ''"
		/>

		<view class="discovery-shell">
			<view class="query-panel" aria-label="网关模型筛选条件">
				<view class="search-field">
					<text class="control-label">模型 ID</text>
					<input
						v-model="keyword"
						type="text"
						maxlength="128"
						aria-label="搜索网关模型 ID"
						placeholder="例如 gpt、claude 或 gemini"
					/>
				</view>
				<view class="status-filter">
					<text class="control-label">本地状态</text>
					<view class="segmented" role="group" aria-label="本地登记状态">
						<button
							v-for="option in statusOptions"
							:key="option.value"
							type="button"
							:class="{ active: statusFilter === option.value }"
							:aria-pressed="statusFilter === option.value"
							@click="statusFilter = option.value"
						>{{ option.label }}</button>
					</view>
				</view>
			</view>

			<view class="result-strip">
				<view>
					<text class="result-number numeric">{{ filteredModels.length }}</text>
					<text class="result-label"> / {{ total }} 个模型</text>
				</view>
				<view class="result-meta">
					<text>来源：{{ source || 'CLI_PROXY' }}</text>
					<text>最近读取：{{ formattedFetchedAt }}</text>
				</view>
			</view>

			<view v-if="loading && !loadedOnce" class="center-state" role="status" aria-live="polite">
				<view class="loading-mark" aria-hidden="true" />
				<text class="state-title">正在读取网关模型</text>
				<text class="state-copy">后端正在请求网关并批量匹配本地模型目录。</text>
			</view>
			<view v-else-if="loadError && !loadedOnce" class="center-state error-state" role="alert">
				<text class="state-title">网关模型未能加载</text>
				<text class="state-copy">{{ loadError }}</text>
				<admin-action-button tone="teal" @click="loadModels">重新加载</admin-action-button>
			</view>
			<view v-else-if="!filteredModels.length" class="center-state">
				<text class="state-title">没有匹配的网关模型</text>
				<text class="state-copy">调整模型 ID 或登记状态筛选条件后重试。</text>
			</view>
			<view v-else class="model-table" role="table" aria-label="CLIProxyAPI 网关模型列表">
				<view class="table-head" role="row">
					<text role="columnheader">模型 ID</text>
					<text role="columnheader">所有者提示</text>
					<text role="columnheader">本地状态</text>
					<text role="columnheader">输入倍率</text>
					<text role="columnheader">缓存输入倍率</text>
					<text role="columnheader">输出倍率</text>
					<text role="columnheader">操作</text>
				</view>
				<view
					v-for="model in filteredModels"
					:key="model.modelId"
					class="model-row"
					role="row"
				>
					<view class="identity-cell" role="cell" :aria-label="`模型 ID：${model.modelId}`">
						<text class="model-id">{{ model.modelId }}</text>
						<text v-if="model.createdEpochSeconds !== null" class="model-created numeric">
							创建时间戳 {{ model.createdEpochSeconds }}
						</text>
					</view>
					<text class="owner-cell" role="cell" :aria-label="`所有者提示：${model.owner || '未提供'}`">{{ model.owner || '未提供' }}</text>
					<view class="status-cell" role="cell" :aria-label="`本地状态：${statusLabel(model)}`">
						<view
							class="status-dot"
							:class="{ matched: model.matchStatus === 'MATCHED' && model.localEnabled }"
							aria-hidden="true"
						/>
						<text>{{ statusLabel(model) }}</text>
					</view>
					<text class="ratio-cell numeric" role="cell" :aria-label="`输入倍率：${formatRatio(model.inputRatio)}`">{{ formatRatio(model.inputRatio) }}</text>
					<text class="ratio-cell numeric" role="cell" :aria-label="`缓存输入倍率：${formatRatio(model.cachedInputRatio)}`">{{ formatRatio(model.cachedInputRatio) }}</text>
					<text class="ratio-cell numeric" role="cell" :aria-label="`输出倍率：${formatRatio(model.outputRatio)}`">{{ formatRatio(model.outputRatio) }}</text>
					<view class="action-cell" role="cell">
						<admin-action-button
							v-if="model.matchStatus === 'MATCHED'"
							tone="neutral"
							size="compact"
							@click="openLocalModel(model.localModelPublicId)"
						>查看配置</admin-action-button>
						<admin-action-button
							v-else
							tone="amber"
							size="compact"
							@click="prefillCreate(model)"
						>带入新增</admin-action-button>
					</view>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
import AdminActionButton from '@/components/admin/admin-action-button.vue'
import AdminFeedbackBanner from '@/components/admin/admin-feedback-banner.vue'
import AdminPageHeader from '@/components/admin/admin-page-header.vue'
import { adminCliProxyModelApi } from '@/common/admin/admin-cli-proxy-model-api.js'

export default {
	name: 'AiModelDiscoveryPanel',
	components: { AdminActionButton, AdminFeedbackBanner, AdminPageHeader },
	emits: ['request-navigation'],
	data() {
		return {
			source: '',
			fetchedAt: '',
			total: 0,
			models: [],
			keyword: '',
			statusFilter: 'ALL',
			loading: false,
			loadedOnce: false,
			loadError: '',
			warning: '',
			statusOptions: [
				{ label: '全部', value: 'ALL' },
				{ label: '已登记', value: 'MATCHED' },
				{ label: '未登记', value: 'UNREGISTERED' }
			]
		}
	},
	computed: {
		filteredModels() {
			const keyword = this.keyword.trim().toLowerCase()
			return this.models.filter(model => {
				const matchesKeyword = !keyword
					|| model.modelId.toLowerCase().includes(keyword)
				const matchesStatus = this.statusFilter === 'ALL'
					|| model.matchStatus === this.statusFilter
				return matchesKeyword && matchesStatus
			})
		},
		formattedFetchedAt() {
			if (!this.fetchedAt) return '尚未读取'
			const timestamp = Date.parse(this.fetchedAt)
			return Number.isFinite(timestamp)
				? new Date(timestamp).toLocaleString()
				: '时间不可用'
		}
	},
	methods: {
		onWorkspaceActivated() {
			if (!this.loadedOnce) return this.loadModels()
			return undefined
		},
		onWorkspaceDeactivated() {},
		beforeWorkspaceLeave() {
			return true
		},
		async loadModels() {
			if (this.loading) return
			this.loading = true
			this.warning = ''
			if (!this.loadedOnce) this.loadError = ''
			try {
				const response = await adminCliProxyModelApi.discover()
				this.source = response.source
				this.fetchedAt = response.fetchedAt
				this.total = response.total
				this.models = response.models
				this.loadedOnce = true
				this.loadError = ''
			} catch (error) {
				const message = error?.message || '网关模型接口暂时不可用。'
				if (this.loadedOnce || this.models.length) {
					this.warning = `刷新失败，当前仍显示上一次读取结果。${message}`
				} else {
					this.loadError = message
				}
			} finally {
				this.loading = false
			}
		},
		statusLabel(model) {
			if (model.matchStatus !== 'MATCHED') return '未登记'
			return model.localEnabled ? '已登记 / 已启用' : '已登记 / 已停用'
		},
		formatRatio(value) {
			return value === null || value === undefined ? '未配置' : String(value)
		},
		openLocalModel(publicId) {
			this.$emit('request-navigation', {
				view: 'ai-model-detail',
				publicId
			})
		},
		prefillCreate(model) {
			this.$emit('request-navigation', {
				view: 'ai-model-create',
				prefill: {
					modelId: model.modelId,
					owner: model.owner || ''
				}
			})
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.admin-feedback-banner { margin: 8rpx 0 20rpx; }
.discovery-shell { display: grid; gap: $app-space-3; }
.query-panel { padding: 24rpx; @include admin-solid-panel; display: grid; grid-template-columns: minmax(260px, 1fr) auto; gap: 24rpx; align-items: end; }
.control-label { display: block; margin-bottom: 10rpx; color: $app-muted; font-size: $app-font-size-caption; font-weight: 680; }
.search-field input { min-height: 84rpx; padding: 0 22rpx; border: 1px solid $app-border; border-radius: $app-radius-control; box-sizing: border-box; background: $app-surface-soft; color: $app-text; font-size: 26rpx; }
.segmented { min-height: 84rpx; padding: 5rpx; border: 1px solid $app-border; border-radius: $app-radius-control; display: flex; background: $app-surface-soft; }
.segmented button { min-height: 72rpx; margin: 0; padding: 0 20rpx; border: 0; border-radius: 12rpx; display: inline-flex; align-items: center; justify-content: center; background: transparent; color: $app-muted; font-size: 24rpx; text-align: center; }
.segmented button.active { background: rgba($app-green, .14); color: $app-text; }
.result-strip { min-height: 80rpx; padding: 0 24rpx; display: flex; align-items: center; justify-content: space-between; gap: 20rpx; color: $app-muted; }
.result-number { color: $app-text; font-size: 34rpx; font-weight: 780; }
.result-label { margin-left: 4rpx; font-size: 24rpx; }
.result-meta { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8rpx 20rpx; font-size: 23rpx; }
.center-state { min-height: 420rpx; padding: 36rpx; @include admin-solid-panel; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
.state-title, .state-copy { display: block; }
.state-title { margin-top: 16rpx; font-size: 30rpx; font-weight: 760; }
.state-copy { max-width: 640rpx; margin: 10rpx 0 24rpx; color: $app-muted; font-size: 25rpx; line-height: 1.55; }
.loading-mark { width: 44rpx; height: 44rpx; border: 4rpx solid rgba($app-green, .18); border-top-color: $app-green; border-radius: 50%; animation: discovery-spin .8s linear infinite; }
.model-table { @include admin-solid-panel; overflow: hidden; }
.table-head,
.model-row { display: grid; grid-template-columns: minmax(230px, 1.45fr) minmax(120px, .7fr) minmax(170px, 1fr) repeat(3, minmax(100px, .65fr)) minmax(140px, .75fr); align-items: center; gap: 14rpx; }
.table-head { min-height: 70rpx; padding: 0 24rpx; border-bottom: 1px solid $app-border; color: $app-muted; font-size: 22rpx; font-weight: 680; }
.model-row { min-height: 106rpx; padding: 16rpx 24rpx; border-bottom: 1px solid $app-border-soft; }
.model-row:last-child { border-bottom: 0; }
.identity-cell { min-width: 0; display: flex; flex-direction: column; }
.model-id { overflow: hidden; color: $app-text; font-size: 26rpx; font-weight: 740; text-overflow: ellipsis; white-space: nowrap; }
.model-created, .owner-cell { margin-top: 5rpx; color: $app-muted; font-size: 22rpx; }
.owner-cell { margin-top: 0; overflow-wrap: anywhere; }
.status-cell { display: flex; align-items: center; gap: 10rpx; color: $app-muted; font-size: 23rpx; }
.status-dot { width: 12rpx; height: 12rpx; flex: 0 0 auto; border-radius: 50%; background: $app-action-orange; }
.status-dot.matched { background: $app-action-lime; }
.ratio-cell { color: $app-text; font-size: 24rpx; }
.action-cell { display: flex; justify-content: flex-end; }
button::after { border: 0; }
.segmented button:focus-visible,
.search-field input:focus-visible { @include admin-focus-ring; }

@keyframes discovery-spin { to { transform: rotate(360deg); } }

@media (hover: hover) and (pointer: fine) {
	.model-row:hover { background: rgba($app-green, .035); }
	.segmented button:not(.active):hover { background: rgba($app-muted, .07); cursor: pointer; }
}

@media (max-width: 1100px) {
	.table-head { display: none; }
	.model-table { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14rpx; padding: 14rpx; background: transparent; border: 0; }
	.model-row { min-height: 0; padding: 22rpx; border: 1px solid $app-border; border-radius: $app-radius-panel; grid-template-columns: 1fr 1fr; gap: 18rpx; @include admin-solid-panel; }
	.identity-cell, .action-cell { grid-column: 1 / -1; }
	.action-cell { justify-content: stretch; }
	.action-cell .admin-action-button { width: 100%; }
}

@media (max-width: 767px) {
	.query-panel { padding: 20rpx; grid-template-columns: 1fr; }
	.segmented button { min-width: 0; flex: 1; padding: 0 10rpx; }
	.result-strip { padding: 0 4rpx; align-items: flex-start; flex-direction: column; }
	.result-meta { justify-content: flex-start; }
	.model-table { grid-template-columns: 1fr; padding: 0; }
}

@media (prefers-reduced-motion: reduce) {
	.loading-mark { animation: none; }
}

@media (prefers-contrast: more) {
	.query-panel,
	.model-table,
	.model-row,
	.center-state { border: 2px solid $app-text; background: $app-canvas; }
}
</style>

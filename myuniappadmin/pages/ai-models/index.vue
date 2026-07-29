<template>
	<admin-page-shell
		v-if="adminRouteReady"
		current-path="/pages/ai-models/index"
		kicker="模型运营"
		title="AI 模型目录"
		description="按计费倍率审阅模型，维护能力与启用状态。停用是模型退出可用范围的唯一方式。"
		:busy="writing"
		@navigate="navigateProtected"
	>
		<template #actions>
			<admin-action-button tone="neutral" :loading="loading" :disabled="writing" @click="loadModels">
				刷新
			</admin-action-button>
			<admin-action-button tone="amber" :disabled="writing" @click="openCreate">
				新增模型
			</admin-action-button>
		</template>
		<view class="catalog-shell">
			<admin-feedback-banner
				v-if="banner.message"
				:tone="banner.type === 'error' ? 'danger' : (banner.type === 'success' ? 'success' : 'info')"
				:message="banner.message"
				:dismissible="true"
				@dismiss="clearBanner"
			/>

			<view class="query-panel desktop-query-panel" aria-label="模型查询条件">
				<view class="search-field">
					<text class="control-label">名称或厂商前缀</text>
					<view class="search-control">
						<input
							v-model="queryDraft.keyword"
							type="text"
							maxlength="128"
							aria-label="搜索模型名称或厂商"
							placeholder="例如 gpt 或 openai"
							@confirm="applyQuery"
						/>
					</view>
				</view>
				<view class="filter-group">
					<text class="control-label">启用状态</text>
					<view class="segmented" role="group" aria-label="启用状态筛选">
						<button
							v-for="option in statusOptions"
							:key="option.value"
							type="button"
							:aria-pressed="queryDraft.enabled === option.value"
							:class="{ active: queryDraft.enabled === option.value }"
							@click="setDraft('enabled', option.value)"
						>{{ option.label }}</button>
					</view>
				</view>
				<view class="filter-group">
					<text class="control-label">倍率优先级</text>
					<view class="segmented" role="group" aria-label="倍率排序优先级">
						<button
							v-for="option in priorityOptions"
							:key="option.value"
							type="button"
							:aria-pressed="queryDraft.sortPriority === option.value"
							:class="{ active: queryDraft.sortPriority === option.value }"
							@click="setDraft('sortPriority', option.value)"
						>{{ option.label }}</button>
					</view>
				</view>
				<view class="filter-group">
					<text class="control-label">方向</text>
					<view class="segmented" role="group" aria-label="排序方向">
						<button
							v-for="option in directionOptions"
							:key="option.value"
							type="button"
							:aria-pressed="queryDraft.direction === option.value"
							:class="{ active: queryDraft.direction === option.value }"
							@click="setDraft('direction', option.value)"
						>{{ option.label }}</button>
					</view>
				</view>
				<view class="query-submit">
					<text class="control-label" aria-hidden="true">执行查询</text>
					<admin-action-button tone="teal" block :loading="loading" @click="applyQuery">查询</admin-action-button>
				</view>
			</view>

			<view class="mobile-query-panel" aria-label="移动端模型查询">
				<view class="mobile-search-row">
					<view class="search-control">
						<input
							v-model="queryDraft.keyword"
							type="text"
							maxlength="128"
							aria-label="搜索模型名称或厂商"
							placeholder="搜索模型或厂商"
							@confirm="applyQuery"
						/>
					</view>
					<admin-action-button tone="teal" size="compact" :loading="loading" @click="applyQuery">查询</admin-action-button>
					<admin-action-button
						id="model-filter-trigger"
						tone="neutral"
						size="compact"
						aria-controls="model-filter-drawer"
						:aria-expanded="filtersOpen ? 'true' : 'false'"
						@click="openFilters"
					>筛选</admin-action-button>
				</view>
				<view class="active-filter-chips" aria-label="当前筛选条件">
					<text v-for="label in activeFilterLabels" :key="label" class="active-filter-chip">{{ label }}</text>
				</view>
			</view>

			<view class="result-strip">
				<view class="result-total">
					<text class="result-number numeric">{{ page.total }}</text>
					<text class="result-label">个匹配模型</text>
				</view>
				<view class="result-meta">
					<text>第 {{ page.pageNum }} / {{ Math.max(page.pages, 1) }} 页</text>
					<text>本页已选 {{ selectedIds.length }} 项</text>
				</view>
				<view v-if="selectedIds.length" class="batch-actions desktop-batch-actions">
					<admin-action-button tone="lime" size="compact" :disabled="writing" @click="confirmBatchStatus(true)">批量启用</admin-action-button>
					<admin-action-button tone="orange" size="compact" :disabled="writing" @click="confirmBatchStatus(false)">批量停用</admin-action-button>
				</view>
			</view>

			<view class="catalog-panel" :class="{ 'with-mobile-batch': selectedIds.length }" :aria-busy="loading">
				<view v-if="loading" class="skeleton-list" role="status" aria-label="正在读取模型目录">
					<view class="skeleton-head" aria-hidden="true" />
					<view v-for="index in 5" :key="index" class="skeleton-row" aria-hidden="true">
						<view class="skeleton-block skeleton-select" />
						<view class="skeleton-stack">
							<view class="skeleton-block skeleton-name" />
							<view class="skeleton-block skeleton-copy" />
						</view>
						<view class="skeleton-block skeleton-ratio" />
						<view class="skeleton-block skeleton-ratio" />
						<view class="skeleton-block skeleton-capability" />
						<view class="skeleton-block skeleton-status" />
						<view class="skeleton-block skeleton-actions" />
					</view>
				</view>
				<view v-else-if="loadError" class="center-state error-state" role="alert">
					<text class="state-title">模型目录未能加载</text>
					<text class="state-copy">{{ loadError }}</text>
					<admin-action-button tone="teal" @click="loadModels">重新加载</admin-action-button>
				</view>
				<view v-else-if="!page.models.length" class="center-state empty-state">
					<text class="state-title">没有匹配的模型</text>
					<text class="state-copy">调整查询条件，或新增一个默认停用的模型。</text>
					<admin-action-button tone="amber" @click="openCreate">新增模型</admin-action-button>
				</view>
				<view v-else class="model-list" role="list" aria-label="AI 模型列表">
					<view class="list-head" aria-hidden="true">
						<text>选择</text><text>模型</text><text>输入倍率</text><text>输出倍率</text><text>能力</text><text>状态</text><text>操作</text>
					</view>
					<view
						v-for="model in page.models"
						:key="model.publicId"
						class="model-row"
						:class="{ selected: selected(model.publicId) }"
						role="listitem"
					>
						<button
							class="select-control"
							type="button"
							:aria-label="`${selected(model.publicId) ? '取消选择' : '选择'} ${model.modelName}`"
							:aria-pressed="selected(model.publicId)"
							@click.stop="toggleSelection(model.publicId)"
						>
							<view class="selection-dot" aria-hidden="true" />
						</button>
						<button class="model-identity" type="button" :aria-label="`查看 ${model.modelName} 详情`" @click="openDetail(model.publicId)">
							<text class="model-name">{{ model.modelName }}</text>
							<text class="model-vendor">{{ model.vendor }} · {{ model.publicId }}</text>
						</button>
						<view class="ratio-cell">
							<text class="mobile-label">输入</text>
							<text class="ratio-value numeric">{{ model.inputRatio }}</text>
						</view>
						<view class="ratio-cell">
							<text class="mobile-label">输出</text>
							<text class="ratio-value numeric">{{ model.outputRatio }}</text>
						</view>
						<view class="capability-cell">
							<text
								v-for="capability in model.capabilities"
								:key="capability"
								class="capability-chip"
							>{{ capabilityLabel(capability) }}</text>
							<text v-if="capabilityOverflow(model, 2)" class="capability-chip capability-more tablet-capability-more">+{{ capabilityOverflow(model, 2) }}</text>
							<text v-if="capabilityOverflow(model, 3)" class="capability-chip capability-more mobile-capability-more">+{{ capabilityOverflow(model, 3) }}</text>
						</view>
						<view class="status-cell">
							<view class="status-dot" :class="{ enabled: model.enabled }" aria-hidden="true" />
							<text>{{ model.enabled ? '已启用' : '已停用' }}</text>
						</view>
						<view class="row-actions">
							<admin-action-button
								tone="neutral"
								size="compact"
								:disabled="writing"
								:aria-expanded="openRowMenuId === model.publicId ? 'true' : 'false'"
								:aria-controls="`model-row-menu-${model.publicId}`"
								@click="toggleRowMenu(model.publicId)"
							>更多</admin-action-button>
							<view
								v-if="openRowMenuId === model.publicId"
								:id="`model-row-menu-${model.publicId}`"
								class="row-menu"
								role="menu"
							>
								<button type="button" role="menuitem" @click="openRowDetail(model.publicId)">查看详情</button>
								<button
									type="button"
									role="menuitem"
									:class="{ warning: model.enabled }"
									@click="openRowStatus(model)"
								>{{ model.enabled ? '停用模型' : '启用模型' }}</button>
							</view>
						</view>
					</view>
				</view>
			</view>

			<view class="pagination" aria-label="模型分页">
				<admin-action-button tone="neutral" :disabled="loading || !page.hasPrevious" @click="changePage(page.pageNum - 1)">上一页</admin-action-button>
				<view class="page-position">
					<text class="numeric">{{ page.pageNum }}</text>
					<text>/</text>
					<text class="numeric">{{ Math.max(page.pages, 1) }}</text>
				</view>
				<admin-action-button tone="neutral" :disabled="loading || !page.hasNext" @click="changePage(page.pageNum + 1)">下一页</admin-action-button>
			</view>
		</view>

		<view v-if="selectedIds.length" class="mobile-batch-bar" role="region" aria-label="批量模型操作">
			<text>已选择 {{ selectedIds.length }} 项</text>
			<view class="mobile-batch-actions">
				<admin-action-button tone="lime" size="compact" :disabled="writing" @click="confirmBatchStatus(true)">批量启用</admin-action-button>
				<admin-action-button tone="orange" size="compact" :disabled="writing" @click="confirmBatchStatus(false)">批量停用</admin-action-button>
			</view>
		</view>

		<view v-if="filtersOpen" class="filter-drawer-layer" @click.self="closeFilters">
			<view
				id="model-filter-drawer"
				class="filter-drawer"
				role="dialog"
				tabindex="-1"
				aria-modal="true"
				aria-labelledby="model-filter-title"
				@keyup.esc="closeFilters"
			>
				<view class="drawer-handle" aria-hidden="true" />
				<view class="drawer-heading">
					<view>
						<text id="model-filter-title" class="drawer-title">筛选与排序</text>
						<text class="drawer-copy">应用后将从第一页重新查询。</text>
					</view>
					<admin-action-button tone="neutral" size="compact" aria-label="取消筛选修改" @click="closeFilters">取消</admin-action-button>
				</view>
				<view class="drawer-filter-group">
					<text class="control-label">启用状态</text>
					<view class="segmented" role="group" aria-label="移动端启用状态筛选">
						<button
							v-for="option in statusOptions"
							:key="option.value"
							type="button"
							:aria-pressed="queryDraft.enabled === option.value"
							:class="{ active: queryDraft.enabled === option.value }"
							@click="setDraft('enabled', option.value)"
						>{{ option.label }}</button>
					</view>
				</view>
				<view class="drawer-filter-group">
					<text class="control-label">倍率优先级</text>
					<view class="segmented" role="group" aria-label="移动端倍率排序优先级">
						<button
							v-for="option in priorityOptions"
							:key="option.value"
							type="button"
							:aria-pressed="queryDraft.sortPriority === option.value"
							:class="{ active: queryDraft.sortPriority === option.value }"
							@click="setDraft('sortPriority', option.value)"
						>{{ option.label }}</button>
					</view>
				</view>
				<view class="drawer-filter-group">
					<text class="control-label">排序方向</text>
					<view class="segmented" role="group" aria-label="移动端排序方向">
						<button
							v-for="option in directionOptions"
							:key="option.value"
							type="button"
							:aria-pressed="queryDraft.direction === option.value"
							:class="{ active: queryDraft.direction === option.value }"
							@click="setDraft('direction', option.value)"
						>{{ option.label }}</button>
					</view>
				</view>
				<view class="drawer-actions">
					<admin-action-button tone="neutral" @click="resetDraftFilters">重置</admin-action-button>
					<admin-action-button tone="amber" @click="applyMobileFilters">应用筛选</admin-action-button>
				</view>
			</view>
		</view>
	</admin-page-shell>
</template>

<script>
import AdminActionButton from '@/components/admin/admin-action-button.vue'
import { adminAiModelApi } from '@/common/admin/admin-ai-model-api.js'
import { createAdminPageGuardMixin } from '@/common/admin/admin-page-guard.js'
import { guardedAdminNavigate } from '@/common/admin/admin-route-guard-runtime.js'
import { adminPrefersReducedMotion } from '@/common/admin/admin-motion.js'
import { AI_MODEL_CAPABILITY_OPTIONS } from '@/common/admin/admin-ai-model-form.js'

function emptyPage() {
	return {
		models: [],
		pageNum: 1,
		pageSize: 50,
		total: 0,
		pages: 0,
		hasPrevious: false,
		hasNext: false
	}
}

function initialQuery() {
	return {
		pageNum: 1,
		pageSize: 50,
		keyword: '',
		enabled: '',
		sortPriority: 'INPUT_FIRST',
		direction: 'ASC'
	}
}

export default {
	mixins: [createAdminPageGuardMixin('/pages/ai-models/index')],
	components: { AdminActionButton },
	data() {
		return {
			queryDraft: initialQuery(),
			query: initialQuery(),
			page: emptyPage(),
			selectedIds: [],
			loading: false,
			writing: false,
			loadError: '',
			loadedOnce: false,
			banner: { type: '', message: '' },
			filtersOpen: false,
			openRowMenuId: '',
			filterDraftSnapshot: null,
			statusOptions: [
				{ label: '全部', value: '' },
				{ label: '已启用', value: 'true' },
				{ label: '已停用', value: 'false' }
			],
			priorityOptions: [
				{ label: '输入优先', value: 'INPUT_FIRST' },
				{ label: '输出优先', value: 'OUTPUT_FIRST' }
			],
			directionOptions: [
				{ label: '升序', value: 'ASC' },
				{ label: '降序', value: 'DESC' }
			]
		}
	},
	computed: {
		activeFilterLabels() {
			const status = this.statusOptions.find(option => option.value === this.query.enabled)?.label || '全部'
			const priority = this.priorityOptions.find(option => option.value === this.query.sortPriority)?.label || '输入优先'
			const direction = this.directionOptions.find(option => option.value === this.query.direction)?.label || '升序'
			return [`状态：${status}`, priority, direction]
		}
	},
	onLoad() {
		this.runAfterAdminRouteGuard(() => this.loadModels())
	},
	onShow() {
		this.runAfterAdminRouteGuard(() => {
			if (this.loadedOnce) return this.loadModels()
		})
	},
	onBackPress() {
		if (this.openRowMenuId) {
			this.openRowMenuId = ''
			return true
		}
		if (!this.filtersOpen) return false
		this.closeFilters(true)
		return true
	},
	methods: {
		navigateProtected(route) {
			if (route === '/pages/ai-models/index') return
			return guardedAdminNavigate(route)
		},
		goBack() {
			uni.navigateBack({ delta: 1 })
		},
		openCreate() {
			return guardedAdminNavigate('/pages/ai-models/create')
		},
		openDetail(publicId) {
			return guardedAdminNavigate(
				`/pages/ai-models/detail?publicId=${encodeURIComponent(publicId)}`)
		},
		toggleRowMenu(publicId) {
			if (this.writing) return
			this.openRowMenuId = this.openRowMenuId === publicId ? '' : publicId
		},
		openRowDetail(publicId) {
			this.openRowMenuId = ''
			return this.openDetail(publicId)
		},
		openRowStatus(model) {
			this.openRowMenuId = ''
			this.confirmSingleStatus(model)
		},
		clearBanner() {
			this.banner = { type: '', message: '' }
		},
		setDraft(field, value) {
			this.queryDraft = { ...this.queryDraft, [field]: value }
		},
		openFilters() {
			this.filterDraftSnapshot = { ...this.queryDraft }
			this.filtersOpen = true
			// #ifdef H5
			this.$nextTick(() => document.getElementById('model-filter-drawer')?.focus())
			// #endif
		},
		closeFilters(restoreDraft = true) {
			const wasOpen = this.filtersOpen
			if (restoreDraft && this.filterDraftSnapshot) {
				this.queryDraft = { ...this.filterDraftSnapshot }
			}
			this.filterDraftSnapshot = null
			this.filtersOpen = false
			// #ifdef H5
			if (wasOpen) {
				this.$nextTick(() => document.getElementById('model-filter-trigger')?.focus())
			}
			// #endif
		},
		resetDraftFilters() {
			this.queryDraft = {
				...this.queryDraft,
				enabled: '',
				sortPriority: 'INPUT_FIRST',
				direction: 'ASC'
			}
		},
		applyMobileFilters() {
			this.closeFilters(false)
			this.applyQuery()
		},
		applyQuery() {
			this.query = { ...this.queryDraft, pageNum: 1 }
			this.selectedIds = []
			this.loadModels()
		},
		async loadModels() {
			if (this.loading) return
			this.openRowMenuId = ''
			this.loading = true
			this.loadError = ''
			try {
				const enabled = this.query.enabled === ''
					? undefined
					: this.query.enabled === 'true'
				const response = await adminAiModelApi.list({
					...this.query,
					enabled
				})
				if (!response || !Array.isArray(response.models)) {
					throw new Error('模型列表响应无效。')
				}
				this.page = response
				this.query.pageNum = response.pageNum
				this.queryDraft.pageNum = response.pageNum
				this.selectedIds = []
				this.loadedOnce = true
			} catch (error) {
				this.loadError = error?.message || '模型目录接口暂时不可用。'
			} finally {
				this.loading = false
			}
		},
		changePage(pageNum) {
			if (pageNum < 1 || pageNum > Math.max(this.page.pages, 1) || this.loading) return
			this.query = { ...this.query, pageNum }
			this.queryDraft = { ...this.queryDraft, pageNum }
			this.selectedIds = []
			this.loadModels()
			// #ifdef H5
			window.scrollTo({
				top: 0,
				behavior: adminPrefersReducedMotion() ? 'auto' : 'smooth'
			})
			// #endif
		},
		selected(publicId) {
			return this.selectedIds.includes(publicId)
		},
		toggleSelection(publicId) {
			this.selectedIds = this.selected(publicId)
				? this.selectedIds.filter(id => id !== publicId)
				: [...this.selectedIds, publicId]
		},
		capabilityLabel(code) {
			return AI_MODEL_CAPABILITY_OPTIONS.find(item => item.code === code)?.label || code
		},
		capabilityOverflow(model, visibleCount) {
			return Math.max((model.capabilities?.length || 0) - visibleCount, 0)
		},
		confirmSingleStatus(model) {
			const target = !model.enabled
			uni.showModal({
				title: target ? '启用模型' : '停用模型',
				content: target
					? `启用 ${model.modelName} 后，它将进入可用模型快照。`
					: `停用 ${model.modelName} 后，主记录仍会保留，但不再进入可用模型快照。`,
				confirmText: target ? '启用' : '停用',
				confirmColor: target ? '#a8dc4a' : '#e89a4a',
				success: result => {
					if (result.confirm) this.setSingleStatus(model.publicId, target)
				}
			})
		},
		async setSingleStatus(publicId, enabled) {
			if (this.writing) return
			this.writing = true
			try {
				await adminAiModelApi.setEnabled(publicId, enabled)
				this.banner = {
					type: 'success',
					message: enabled ? '模型已启用并刷新可用快照。' : '模型已停用，主记录继续保留。'
				}
				await this.loadModels()
			} catch (error) {
				this.banner = { type: 'error', message: error?.message || '模型状态修改失败。' }
			} finally {
				this.writing = false
			}
		},
		confirmBatchStatus(enabled) {
			if (!this.selectedIds.length || this.writing) return
			uni.showModal({
				title: enabled ? '批量启用模型' : '批量停用模型',
				content: `将选中的 ${this.selectedIds.length} 个模型统一设为${enabled ? '启用' : '停用'}状态。`,
				confirmText: enabled ? '批量启用' : '批量停用',
				confirmColor: enabled ? '#a8dc4a' : '#e89a4a',
				success: result => {
					if (result.confirm) this.setBatchStatus(enabled)
				}
			})
		},
		async setBatchStatus(enabled) {
			const selected = [...this.selectedIds]
			this.writing = true
			try {
				const result = await adminAiModelApi.setEnabledBatch(selected, enabled)
				this.banner = {
					type: 'success',
					message: `批量操作完成：请求 ${result.requestedCount || selected.length}，实际更新 ${result.updatedCount || 0}。`
				}
				this.selectedIds = []
				await this.loadModels()
			} catch (error) {
				this.banner = { type: 'error', message: error?.message || '批量状态修改失败，选择已保留。' }
			} finally {
				this.writing = false
			}
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.catalog-page {
	min-height: 100vh;
	box-sizing: border-box;
	padding: 34rpx 34rpx calc(48rpx + env(safe-area-inset-bottom));
	background:
		radial-gradient(circle at 92% 0%, rgba($app-action-teal, .07), transparent 30%),
		$app-bg;
	color: $app-text;
}

.catalog-shell {
	width: min(1640px, 100%);
	margin: 0 auto;
}

.context-bar {
	min-height: 164rpx;
	display: grid;
	grid-template-columns: auto minmax(0, 1fr) auto;
	gap: 24rpx;
	align-items: center;
}

.back-button {
	min-width: 104rpx;
}

.title-block {
	min-width: 0;
}

.eyebrow {
	display: block;
	color: $app-action-teal;
	font-size: 24rpx;
	font-weight: 780;
	letter-spacing: .1em;
}

.page-title {
	display: block;
	margin-top: 8rpx;
	font-size: 54rpx;
	line-height: 1.04;
	font-weight: 800;
	letter-spacing: -.035em;
}

.page-copy {
	display: block;
	max-width: 900rpx;
	margin-top: 12rpx;
	color: $app-muted;
	font-size: 24rpx;
	line-height: 1.55;
}

.header-actions,
.batch-actions,
.mobile-batch-actions,
.drawer-actions {
	display: flex;
	gap: 12rpx;
}

.page-banner {
	min-height: 78rpx;
	margin: 14rpx 0;
	padding: 10rpx 14rpx 10rpx 22rpx;
	border: 1px solid rgba($app-action-lime, .3);
	border-radius: $app-radius-control;
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 20rpx;
	background: rgba($app-action-lime, .08);
	color: #dfffb0;
	font-size: 24rpx;
}

.page-banner.error {
	border-color: rgba($app-danger, .4);
	background: rgba($app-danger, .1);
	color: $app-danger-text;
}

.query-panel {
	margin-top: 22rpx;
	padding: 20rpx;
	border: 1px solid rgba($app-teal, .2);
	border-radius: $app-radius-panel;
	background: rgba(16, 22, 26, .94);
}

.desktop-query-panel {
	display: grid;
	gap: 14rpx;
	align-items: end;
}

.mobile-query-panel {
	display: none;
}

.control-label {
	display: block;
	margin-bottom: 9rpx;
	color: $app-muted;
	font-size: 24rpx;
	font-weight: 680;
}

.search-control {
	min-height: 88rpx;
	border: 1px solid $app-border;
	border-radius: $app-radius-control;
	display: flex;
	align-items: center;
	overflow: hidden;
	background: #0b1115;
	transition: border-color 180ms ease, background-color 180ms ease;
}

.search-control:focus-within {
	border-color: rgba($app-action-teal, .7);
	background: #0c1418;
}

.search-control input {
	min-width: 0;
	width: 100%;
	height: 88rpx;
	padding: 0 20rpx;
	box-sizing: border-box;
	color: $app-text;
	font-size: 24rpx;
}

.segmented {
	min-height: 88rpx;
	padding: 5rpx;
	border: 1px solid $app-border;
	border-radius: $app-radius-control;
	box-sizing: border-box;
	display: flex;
	gap: 4rpx;
	background: #0b1115;
}

.segmented button {
	min-height: 76rpx;
	margin: 0;
	padding: 0 16rpx;
	border: 0;
	border-radius: 9rpx;
	flex: 1;
	display: inline-flex;
	align-items: center;
	justify-content: center;
	background: transparent;
	color: $app-muted;
	font-size: 24rpx;
	line-height: 1;
	white-space: nowrap;
	transition: background-color 180ms ease, color 180ms ease, transform 120ms ease-out;
}

.segmented button.active {
	background: rgba($app-action-teal, .16);
	color: #e4ffff;
}

.segmented button:active {
	transform: scale(.98);
}

.result-strip {
	min-height: 100rpx;
	margin-top: 22rpx;
	padding: 0 22rpx;
	border-top: 1px solid $app-border;
	border-bottom: 1px solid $app-border;
	display: grid;
	grid-template-columns: auto 1fr auto;
	gap: 28rpx;
	align-items: center;
}

.result-total {
	display: flex;
	align-items: baseline;
}

.result-number {
	margin-right: 10rpx;
	color: $app-action-amber;
	font-size: 38rpx;
	font-weight: 800;
}

.result-label,
.result-meta {
	color: $app-muted;
	font-size: 24rpx;
}

.result-meta {
	display: flex;
	gap: 28rpx;
}

.catalog-panel {
	min-height: 340rpx;
	margin-top: 24rpx;
	border: 1px solid $app-border;
	border-radius: $app-radius-panel;
	background: $app-surface;
	overflow: hidden;
}

.center-state {
	min-height: 300rpx;
	padding: 40rpx;
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
	text-align: center;
	color: $app-muted;
}

.state-title {
	color: $app-text;
	font-size: 28rpx;
	font-weight: 760;
}

.state-copy {
	max-width: 620rpx;
	margin: 10rpx 0 22rpx;
	font-size: 24rpx;
	line-height: 1.5;
}

.list-head,
.model-row,
.skeleton-row {
	display: grid;
	grid-template-columns: 68rpx minmax(230rpx, 1.25fr) minmax(110rpx, .5fr) minmax(110rpx, .5fr) minmax(220rpx, 1fr) 126rpx 190rpx;
	gap: 14rpx;
	align-items: center;
}

.list-head {
	min-height: 70rpx;
	padding: 0 20rpx;
	border-bottom: 1px solid $app-border;
	color: $app-muted;
	font-size: 24rpx;
	font-weight: 680;
	letter-spacing: .03em;
}

.model-row {
	min-height: 118rpx;
	padding: 12rpx 20rpx;
	border-bottom: 1px solid rgba(145, 162, 168, .12);
	transition: background-color 180ms ease, box-shadow 180ms ease;
}

.model-row:last-child {
	border-bottom: 0;
}

.model-row.selected {
	background: rgba($app-action-teal, .08);
	box-shadow: inset 0 0 0 1px rgba($app-action-teal, .24);
}

.select-control {
	width: 64rpx;
	min-height: 88rpx;
	margin: 0;
	border: 0;
	display: grid;
	place-items: center;
	background: transparent;
}

.selection-dot {
	width: 28rpx;
	height: 28rpx;
	border: 1px solid $app-border;
	border-radius: 8rpx;
}

.select-control[aria-pressed="true"] .selection-dot {
	border-color: $app-action-teal;
	background: $app-action-teal;
	box-shadow: inset 0 0 0 6rpx #0b1115;
}

.model-identity {
	min-width: 0;
	margin: 0;
	padding: 12rpx 0;
	border: 0;
	display: block;
	background: transparent;
	text-align: left;
}

.model-name,
.model-vendor {
	display: block;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.model-name {
	color: $app-text;
	font-size: 24rpx;
	font-weight: 760;
}

.model-vendor {
	margin-top: 6rpx;
	color: $app-muted;
	font-size: 24rpx;
	font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.ratio-value {
	font-size: 24rpx;
	font-weight: 720;
}

.mobile-label {
	display: none;
}

.capability-cell {
	display: flex;
	flex-wrap: wrap;
	gap: 6rpx;
}

.capability-chip {
	padding: 5rpx 9rpx;
	border: 1px solid rgba($app-teal, .22);
	border-radius: 999rpx;
	color: #b9dce1;
	font-size: 24rpx;
}

.capability-more {
	display: none;
	border-color: rgba($app-action-amber, .3);
	color: #f6d89e;
}

.status-cell {
	display: flex;
	align-items: center;
	gap: 9rpx;
	color: $app-muted;
	font-size: 24rpx;
}

.status-dot {
	width: 14rpx;
	height: 14rpx;
	border-radius: 50%;
	background: $app-action-orange;
}

.status-dot.enabled {
	background: $app-action-lime;
	box-shadow: 0 0 0 6rpx rgba($app-action-lime, .08);
}

.row-actions {
	position: relative;
	display: grid;
	gap: 8rpx;
}

.row-actions .admin-action-button {
	min-width: 0;
	padding-left: 12rpx;
	padding-right: 12rpx;
}

.row-menu {
	min-width: 184rpx;
	padding: 8rpx;
	border: 1px solid rgba($app-teal, .22);
	border-radius: $app-radius-control;
	display: grid;
	gap: 4rpx;
	@include admin-glass-chrome(true);
}

.row-menu button {
	min-height: 64rpx;
	margin: 0;
	padding: 0 14rpx;
	border: 0;
	border-radius: 8rpx;
	display: flex;
	align-items: center;
	background: transparent;
	color: $app-text;
	font-size: 24rpx;
	text-align: left;
}

.row-menu button::after {
	border: 0;
}

.row-menu button.warning {
	color: $app-action-orange;
}

.row-menu button:focus-visible {
	@include admin-focus-ring;
}

.pagination {
	margin-top: 24rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	gap: 18rpx;
}

.pagination .admin-action-button {
	min-width: 130rpx;
}

.page-position {
	min-width: 130rpx;
	display: flex;
	justify-content: center;
	gap: 10rpx;
	color: $app-muted;
}

.page-position .numeric:first-child {
	color: $app-text;
	font-weight: 760;
}

.skeleton-head {
	height: 70rpx;
	border-bottom: 1px solid $app-border;
	background: rgba($app-raised, .72);
}

.skeleton-row {
	min-height: 118rpx;
	padding: 12rpx 20rpx;
	border-bottom: 1px solid rgba(145, 162, 168, .1);
}

.skeleton-stack {
	display: grid;
	gap: 12rpx;
}

.skeleton-block {
	min-height: 22rpx;
	border-radius: 7rpx;
	background: linear-gradient(100deg, rgba($app-raised, .72) 20%, rgba($app-teal, .12) 45%, rgba($app-raised, .72) 70%);
	background-size: 220% 100%;
	animation: skeleton-shift 1.25s ease-in-out infinite;
}

.skeleton-select {
	width: 28rpx;
	height: 28rpx;
}

.skeleton-name {
	width: 68%;
}

.skeleton-copy {
	width: 42%;
	min-height: 16rpx;
}

.skeleton-ratio {
	width: 52%;
}

.skeleton-capability {
	width: 82%;
}

.skeleton-status {
	width: 72%;
}

.skeleton-actions {
	width: 100%;
	min-height: 60rpx;
}

.mobile-batch-bar,
.filter-drawer-layer {
	display: none;
}

.numeric {
	font-variant-numeric: tabular-nums;
}

button::after {
	border: 0;
}

button:focus-visible,
input:focus-visible {
	outline: 2px solid $app-focus;
	outline-offset: 2px;
}

@supports ((backdrop-filter: blur(12px)) or (-webkit-backdrop-filter: blur(12px))) {
	.query-panel {
		background: rgba(16, 22, 26, .78);
		-webkit-backdrop-filter: blur(16px) saturate(125%);
		backdrop-filter: blur(16px) saturate(125%);
	}
}

@media (min-width: 1024px) {
	.desktop-query-panel {
		grid-template-columns: minmax(300rpx, 1fr) minmax(250rpx, auto) minmax(260rpx, auto) minmax(210rpx, auto) 128rpx;
	}
}

@media (min-width: 768px) and (max-width: 1023px) {
	.desktop-query-panel {
		grid-template-columns: repeat(6, minmax(0, 1fr));
	}

	.search-field {
		grid-column: 1 / 6;
		grid-row: 1;
	}

	.query-submit {
		grid-column: 6;
		grid-row: 1;
	}

	.filter-group {
		grid-column: span 2;
		grid-row: 2;
	}

	.list-head,
	.model-row,
	.skeleton-row {
		grid-template-columns: 58rpx minmax(190rpx, 1.2fr) 92rpx 92rpx minmax(140rpx, .8fr) 110rpx 172rpx;
		gap: 10rpx;
	}

	.capability-chip:nth-child(n + 3) {
		display: none;
	}

	.capability-cell .tablet-capability-more {
		display: inline-flex;
	}
}

@media (max-width: 767px) {
	.catalog-page {
		padding: calc(24rpx + env(safe-area-inset-top)) 22rpx calc(42rpx + env(safe-area-inset-bottom));
	}

	.context-bar {
		min-height: auto;
		grid-template-columns: auto minmax(0, 1fr);
		gap: 12rpx;
	}

	.back-button {
		min-width: 88rpx;
	}

	.page-title {
		font-size: 40rpx;
	}

	.eyebrow,
	.page-copy {
		display: none;
	}

	.header-actions {
		grid-column: 1 / -1;
	}

	.header-actions .admin-action-button {
		flex: 1;
	}

	.desktop-query-panel {
		display: none;
	}

	.mobile-query-panel {
		margin-top: 22rpx;
		display: block;
	}

	.mobile-search-row {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto auto;
		gap: 10rpx;
		align-items: center;
	}

	.mobile-search-row .search-control {
		min-width: 0;
	}

	.mobile-search-row .admin-action-button {
		padding-left: 18rpx;
		padding-right: 18rpx;
	}

	.active-filter-chips {
		margin-top: 12rpx;
		display: flex;
		flex-wrap: wrap;
		gap: 8rpx;
	}

	.active-filter-chip {
		padding: 8rpx 13rpx;
		border: 1px solid rgba($app-action-teal, .22);
		border-radius: 999rpx;
		background: rgba($app-action-teal, .06);
		color: #bddbdd;
		font-size: 24rpx;
	}

	.result-strip {
		min-height: 94rpx;
		padding: 14rpx 0;
		grid-template-columns: 1fr auto;
		gap: 14rpx;
	}

	.result-meta {
		justify-content: flex-end;
		flex-direction: column;
		gap: 4rpx;
		text-align: right;
	}

	.desktop-batch-actions {
		display: none;
	}

	.catalog-panel {
		border: 0;
		background: transparent;
		overflow: visible;
	}

	.catalog-panel.with-mobile-batch {
		margin-bottom: 150rpx;
	}

	.list-head,
	.skeleton-head {
		display: none;
	}

	.model-list,
	.skeleton-list {
		display: grid;
		gap: 16rpx;
	}

	.model-row,
	.skeleton-row {
		min-height: 0;
		padding: 22rpx;
		border: 1px solid $app-border;
		border-radius: $app-radius-panel;
		grid-template-columns: 58rpx minmax(0, 1fr) minmax(0, 1fr);
		gap: 16rpx;
		background: $app-surface;
	}

	.model-row:last-child {
		border-bottom: 1px solid $app-border;
	}

	.model-row.selected {
		border-color: rgba($app-action-teal, .5);
		background: rgba($app-action-teal, .08);
	}

	.select-control {
		grid-row: 1;
		grid-column: 1;
		width: 58rpx;
	}

	.model-identity {
		grid-column: 2 / -1;
	}

	.ratio-cell {
		padding: 16rpx;
		border: 1px solid rgba($app-teal, .12);
		border-radius: 10rpx;
		background: #0b1115;
	}

	.ratio-cell:nth-child(3) {
		grid-column: 1 / 3;
	}

	.ratio-cell:nth-child(4) {
		grid-column: 3;
	}

	.mobile-label {
		display: block;
		margin-bottom: 5rpx;
		color: $app-muted;
		font-size: 24rpx;
	}

	.capability-cell {
		grid-column: 1 / -1;
	}

	.capability-chip:nth-child(n + 4) {
		display: none;
	}

	.capability-cell .mobile-capability-more {
		display: inline-flex;
	}

	.status-cell {
		grid-column: 1 / 2;
		min-height: 88rpx;
	}

	.row-actions {
		grid-column: 2 / -1;
	}

	.pagination {
		display: grid;
		grid-template-columns: 1fr auto 1fr;
	}

	.pagination .admin-action-button {
		min-width: 0;
		width: 100%;
	}

	.page-position {
		min-width: 92rpx;
	}

	.center-state {
		min-height: 280rpx;
		border: 1px solid $app-border;
		border-radius: $app-radius-panel;
		background: $app-surface;
	}

	.skeleton-row {
		display: grid;
	}

	.skeleton-select {
		grid-column: 1;
		grid-row: 1;
	}

	.skeleton-stack {
		grid-column: 2 / -1;
	}

	.skeleton-row > .skeleton-ratio {
		min-height: 68rpx;
		width: 100%;
	}

	.skeleton-capability,
	.skeleton-status,
	.skeleton-actions {
		grid-column: 1 / -1;
	}

	.mobile-batch-bar {
		position: fixed;
		z-index: 60;
		left: 16rpx;
		right: 16rpx;
		bottom: max(16rpx, env(safe-area-inset-bottom));
		min-height: 118rpx;
		padding: 16rpx;
		border: 1px solid rgba($app-action-teal, .3);
		border-radius: 16rpx;
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 14rpx;
		background: rgba(12, 18, 22, .96);
		color: $app-text;
		font-size: 24rpx;
	}

	.mobile-batch-actions {
		flex: 1;
		justify-content: flex-end;
	}

	.filter-drawer-layer {
		position: fixed;
		z-index: 80;
		inset: 0;
		padding: 20rpx;
		display: flex;
		align-items: flex-end;
		background: rgba(0, 0, 0, .64);
	}

	.filter-drawer {
		width: 100%;
		max-height: calc(100vh - 80rpx);
		padding: 16rpx 22rpx calc(22rpx + env(safe-area-inset-bottom));
		border: 1px solid rgba($app-action-teal, .26);
		border-radius: 20rpx 20rpx 12rpx 12rpx;
		box-sizing: border-box;
		overflow-y: auto;
		background: rgba(15, 22, 26, .98);
		animation: drawer-enter 200ms ease-out;
	}

	.drawer-handle {
		width: 72rpx;
		height: 7rpx;
		margin: 0 auto 18rpx;
		border-radius: 999rpx;
		background: rgba($app-muted, .42);
	}

	.drawer-heading {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 18rpx;
	}

	.drawer-title,
	.drawer-copy {
		display: block;
	}

	.drawer-title {
		color: $app-text;
		font-size: 30rpx;
		font-weight: 780;
	}

	.drawer-copy {
		margin-top: 6rpx;
		color: $app-muted;
		font-size: 24rpx;
	}

	.drawer-filter-group {
		margin-top: 24rpx;
	}

	.drawer-actions {
		margin-top: 28rpx;
	}

	.drawer-actions .admin-action-button {
		flex: 1;
	}

	@supports ((backdrop-filter: blur(12px)) or (-webkit-backdrop-filter: blur(12px))) {
		.mobile-batch-bar,
		.filter-drawer {
			background: rgba(15, 22, 26, .82);
			-webkit-backdrop-filter: blur(18px) saturate(130%);
			backdrop-filter: blur(18px) saturate(130%);
		}
	}
}

@media (hover: hover) and (pointer: fine) {
	.model-row:hover {
		background: rgba($app-teal, .035);
	}

	.model-identity:hover .model-name {
		color: #dfffff;
	}

	.segmented button:hover:not(.active) {
		background: rgba($app-muted, .08);
		color: $app-text;
	}

	.row-menu button:hover {
		background: rgba($app-muted, .1);
	}
}

.catalog-shell > .admin-feedback-banner {
	margin-top: $app-space-3;
}

.query-panel {
	@include admin-glass-chrome;
	border-color: $app-border-soft;
}

.result-strip,
.catalog-panel {
	@include admin-solid-panel;
}

.result-strip {
	margin-top: $app-space-3;
	border-top: 1px solid $app-border-soft;
	border-bottom: 1px solid $app-border-soft;
}

.control-label,
.search-control input,
.segmented button,
.result-label,
.result-meta,
.list-head,
.state-copy {
	font-size: 24rpx;
}

.filter-drawer {
	@include admin-glass-chrome(true);
	box-shadow: $app-shadow-sheet;
}

@media (prefers-reduced-motion: reduce) {
	* {
		scroll-behavior: auto !important;
		transition-duration: .01ms !important;
	}

	.segmented button:active {
		transform: none;
	}

	.skeleton-block {
		animation: none;
		background: rgba($app-raised, .86);
	}

	.filter-drawer {
		animation: none;
	}
}

@media (prefers-reduced-transparency: reduce) {
	.query-panel,
	.filter-drawer,
	.mobile-batch-bar,
	.row-menu {
		background: $app-surface-elevated;
		-webkit-backdrop-filter: none;
		backdrop-filter: none;
	}
}

@media (prefers-contrast: more) {
	.query-panel,
	.result-strip,
	.catalog-panel,
	.filter-drawer,
	.row-menu {
		border: 2px solid $app-text;
		background: $app-canvas;
	}
}

@keyframes skeleton-shift {
	to {
		background-position: -220% 0;
	}
}

@keyframes drawer-enter {
	from {
		opacity: 0;
		transform: translateY(18rpx);
	}
	to {
		opacity: 1;
		transform: translateY(0);
	}
}
</style>

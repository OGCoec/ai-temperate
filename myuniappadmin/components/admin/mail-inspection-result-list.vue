<template>
	<view v-if="results.length" class="results-section">
		<view class="results-heading">
			<view>
				<text class="results-title">检查结果</text>
				<text class="results-copy">{{ resultGuidance }}</text>
			</view>
			<view v-if="retryCount" class="retry-actions">
				<admin-action-button tone="orange" size="compact" @click="$emit('copy-retry')">
					复制原始重试凭证
				</admin-action-button>
				<admin-action-button tone="amber" size="compact" @click="$emit('retry')">
					重试 {{ retryCount }} 项
				</admin-action-button>
			</view>
		</view>

		<scroll-view class="filter-strip" scroll-x>
			<view class="filter-row" role="group" aria-label="结果分类筛选">
				<button
					v-for="option in groupOptions"
					:key="option.value"
					type="button"
					:class="{ active: activeGroup === option.value }"
					:aria-pressed="activeGroup === option.value"
					@tap="selectGroup(option.value)"
					@keydown.enter.prevent="selectGroup(option.value)"
					@keydown.space.prevent="selectGroup(option.value)"
				>{{ option.label }} {{ groupCounts[option.value] || 0 }}</button>
			</view>
		</scroll-view>

		<slot name="sensitive-business-panel"></slot>

		<view v-if="!compactLayout && filteredResults.length" class="desktop-results">
			<view class="result-head">
				<text>行</text>
				<text>邮箱</text>
				<text>状态</text>
				<text>证据摘要</text>
				<text>尝试</text>
				<text>操作</text>
			</view>
			<scroll-view
				class="result-viewport desktop-result-viewport"
				scroll-y
				:scroll-top="virtualScrollTop"
				aria-label="邮箱检查结果虚拟列表"
				@scroll="onResultScroll"
			>
				<view class="virtual-spacer" :style="topSpacerStyle"></view>
				<view
					v-for="(result, index) in visibleResults"
					:key="result.lineNumber"
					class="result-row"
					role="row"
					:aria-posinset="windowStart + index + 1"
					:aria-setsize="filteredResults.length"
				>
					<text class="numeric">{{ result.lineNumber }}</text>
					<text class="email">{{ result.email || '—' }}</text>
					<view><text class="status-chip" :class="`tone-${result.tone}`">{{ result.label }}</text></view>
					<text class="evidence">{{ result.evidenceSummary }}</text>
					<text class="attempts">{{ result.attemptLabel }}</text>
					<button
						type="button"
						class="detail-button"
						:aria-expanded="expanded(result.lineNumber)"
						@click="toggle(result.lineNumber)"
					>{{ expanded(result.lineNumber) ? '收起' : '详情' }}</button>
				</view>
				<view class="virtual-spacer" :style="bottomSpacerStyle"></view>
			</scroll-view>
		</view>

		<view v-else-if="filteredResults.length" class="mobile-results">
			<scroll-view
				class="result-viewport mobile-result-viewport"
				scroll-y
				:scroll-top="virtualScrollTop"
				aria-label="邮箱检查结果虚拟列表"
				@scroll="onResultScroll"
			>
				<view class="virtual-spacer" :style="topSpacerStyle"></view>
				<view
					v-for="(result, index) in visibleResults"
					:key="result.lineNumber"
					class="result-card"
					role="listitem"
					:aria-posinset="windowStart + index + 1"
					:aria-setsize="filteredResults.length"
				>
					<button
						type="button"
						class="card-summary"
						:aria-expanded="expanded(result.lineNumber)"
						@click="toggle(result.lineNumber)"
					>
						<view class="card-identity">
							<text class="line-number">#{{ result.lineNumber }}</text>
							<text class="email">{{ result.email || '无有效邮箱' }}</text>
						</view>
						<text class="status-chip" :class="`tone-${result.tone}`">{{ result.label }}</text>
					</button>
				</view>
				<view class="virtual-spacer" :style="bottomSpacerStyle"></view>
			</scroll-view>
		</view>

		<view v-if="selectedResult" class="result-detail selected-result-detail" aria-live="polite">
			<text>{{ selectedResult.evidenceSummary }}</text>
			<text>{{ selectedResult.attemptLabel }}</text>
			<text v-if="selectedResult.folderName">文件夹：{{ selectedResult.folderName }}</text>
			<text v-if="selectedResult.sender">发件方：{{ selectedResult.sender }}</text>
			<text v-if="selectedResult.subject">主题：{{ selectedResult.subject }}</text>
			<text v-if="selectedResult.receivedAt">接收时间：{{ dateLabel(selectedResult.receivedAt) }}</text>
			<text v-if="selectedResult.imapRoute">IMAP 路由：{{ selectedResult.imapRoute }}</text>
			<view v-if="selectedResult.verifyUrl" class="sensitive-result">
				<text class="sensitive-value">{{ selectedResult.verifyUrl }}</text>
				<admin-action-button tone="teal" size="compact" @click="$emit('copy-value', selectedResult.verifyUrl, '验证 URL')">复制 URL</admin-action-button>
			</view>
			<view v-if="selectedResult.verifyToken" class="sensitive-result">
				<text class="sensitive-value">{{ selectedResult.verifyToken }}</text>
				<admin-action-button tone="teal" size="compact" @click="$emit('copy-value', selectedResult.verifyToken, '验证 Token')">复制 Token</admin-action-button>
			</view>
		</view>
		<text v-if="filteredResults.length" class="virtual-scroll-status" aria-live="polite">{{ virtualStatus }}</text>

		<view v-if="!filteredResults.length" class="empty-filter">当前分类没有结果。</view>
	</view>
</template>

<script>
import AdminActionButton from './admin-action-button.vue'
import {
	mailInspectionResultGroupOptions,
	countMailInspectionGroups
} from '@/common/admin/mail-inspection-presenter.js'

const VIRTUAL_WINDOW_SIZE = 40
const VIRTUAL_WINDOW_RADIUS = 20

function compactLayoutNow() {
	if (typeof uni !== 'undefined' && typeof uni.getSystemInfoSync === 'function') {
		try {
			return Number(uni.getSystemInfoSync()?.windowWidth) <= 767
		} catch (error) {
			// 某些 Android WebView 在初始化早期无法读取系统信息，此时继续使用窗口宽度判定。
		}
	}
	return typeof window !== 'undefined' && window.innerWidth <= 767
}

function rpxToPixels(value) {
	if (typeof uni !== 'undefined' && typeof uni.upx2px === 'function') {
		return Math.max(1, Number(uni.upx2px(value)) || value)
	}
	return value
}

export default {
	name: 'MailInspectionResultList',
	components: { AdminActionButton },
	emits: [
		'retry',
		'copy-retry',
		'copy-value',
		'select-business-category',
		'request-unregistered-reveal'
	],
	props: {
		results: { type: Array, default: () => [] },
		retryCount: { type: Number, default: 0 },
		inspectionType: { type: String, required: true },
		activeBusinessCategory: { type: String, default: '' }
	},
	data() {
		return {
			activeGroup: 'ALL',
			selectedLineNumber: null,
			windowStart: 0,
			virtualScrollTop: 0,
			compactLayout: compactLayoutNow()
		}
	},
	computed: {
		resultGuidance() {
			if (this.inspectionType === 'IP2LOCATION_VERIFY_LINK') {
				return '未找到验证链接表示扫描已完成但未提取到链接，不等于未注册或网络失败。'
			}
			return '未注册表示扫描成功但未找到候选邮件，不等同于网络失败。'
		},
		groupOptions() {
			return mailInspectionResultGroupOptions(this.inspectionType)
		},
		businessOptionValues() {
			return new Set(this.groupOptions
				.filter(option => ![
					'ALL',
					'AUTH_ERROR',
					'INPUT_ERROR',
					'RETRY_EXHAUSTED',
					'INTERNAL_ERROR'
				].includes(option.value))
				.map(option => option.value))
		},
		groupCounts() {
			return countMailInspectionGroups(this.results)
		},
		filteredResults() {
			let filtered
			if (this.activeGroup === 'ALL') {
				filtered = this.results
			} else if (this.businessOptionValues.has(this.activeGroup)) {
				filtered = this.results.filter(
					result => result.businessCategory === this.activeGroup)
			} else {
				filtered = this.results.filter(result => result.group === this.activeGroup)
			}
			return [...filtered].sort((left, right) => left.lineNumber - right.lineNumber)
		},
		rowHeightPixels() {
			return rpxToPixels(this.compactLayout ? 118 : 102)
		},
		visibleResults() {
			return this.filteredResults.slice(
				this.windowStart,
				this.windowStart + VIRTUAL_WINDOW_SIZE)
		},
		topSpacerStyle() {
			return { height: `${this.windowStart * this.rowHeightPixels}px` }
		},
		bottomSpacerStyle() {
			const hidden = Math.max(
				0,
				this.filteredResults.length - this.windowStart - this.visibleResults.length)
			return { height: `${hidden * this.rowHeightPixels}px` }
		},
		selectedResult() {
			return this.filteredResults.find(
				result => result.lineNumber === this.selectedLineNumber) || null
		},
		virtualStatus() {
			if (!this.filteredResults.length) return ''
			const first = this.windowStart + 1
			const last = this.windowStart + this.visibleResults.length
			return `当前显示第 ${first}～${last} 项，共 ${this.filteredResults.length} 项。`
		}
	},
	watch: {
		results() {
			this.normalizeWindow()
			this.ensureSelectedResult()
		},
		inspectionType() {
			this.selectGroup('ALL')
		},
		activeBusinessCategory(value) {
			if (value && this.businessOptionValues.has(value)) {
				if (this.activeGroup !== value) this.selectGroup(value)
				return
			}
			if (!value && this.businessOptionValues.has(this.activeGroup)) {
				this.activeGroup = 'ALL'
				this.selectedLineNumber = null
				this.resetWindow()
			}
		},
		compactLayout() {
			this.resetWindow()
		}
	},
	mounted() {
		if (typeof window !== 'undefined') {
			window.addEventListener('resize', this.handleWindowResize)
		}
	},
	beforeDestroy() {
		if (typeof window !== 'undefined') {
			window.removeEventListener('resize', this.handleWindowResize)
		}
	},
	beforeUnmount() {
		if (typeof window !== 'undefined') {
			window.removeEventListener('resize', this.handleWindowResize)
		}
	},
	methods: {
		selectGroup(value) {
			this.activeGroup = value
			this.selectedLineNumber = null
			this.resetWindow()
			const businessCategory = this.businessOptionValues.has(value) ? value : ''
			this.$emit('select-business-category', businessCategory)
			if (value === 'UNREGISTERED') this.$emit('request-unregistered-reveal')
		},
		expanded(lineNumber) {
			return this.selectedLineNumber === lineNumber
		},
		toggle(lineNumber) {
			this.selectedLineNumber = this.expanded(lineNumber) ? null : lineNumber
		},
		onResultScroll(event) {
			const scrollTop = Math.max(0, Number(event?.detail?.scrollTop) || 0)
			const anchor = Math.floor(scrollTop / this.rowHeightPixels)
			const maxStart = Math.max(0, this.filteredResults.length - VIRTUAL_WINDOW_SIZE)
			this.virtualScrollTop = scrollTop
			this.windowStart = Math.min(
				maxStart,
				Math.max(0, anchor - VIRTUAL_WINDOW_RADIUS))
		},
		resetWindow() {
			this.windowStart = 0
			this.virtualScrollTop = 0
		},
		normalizeWindow() {
			const maxStart = Math.max(0, this.filteredResults.length - VIRTUAL_WINDOW_SIZE)
			this.windowStart = Math.min(this.windowStart, maxStart)
		},
		ensureSelectedResult() {
			if (this.selectedLineNumber !== null && !this.selectedResult) {
				this.selectedLineNumber = null
			}
		},
		handleWindowResize() {
			this.compactLayout = compactLayoutNow()
		},
		dateLabel(value) {
			const date = new Date(value)
			return Number.isNaN(date.getTime()) ? String(value || '') : date.toLocaleString()
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.results-section {
	margin-top: 26rpx;
}

.results-heading {
	display: flex;
	align-items: flex-end;
	justify-content: space-between;
	gap: 20rpx;
}

.results-title,
.results-copy {
	display: block;
}

.results-title {
	font-size: 30rpx;
	font-weight: 800;
}

.results-copy {
	margin-top: 7rpx;
	color: $app-muted;
	font-size: 24rpx;
	line-height: 1.5;
}

.retry-actions {
	display: flex;
	gap: 10rpx;
}

.filter-strip {
	margin-top: 18rpx;
	white-space: nowrap;
}

.filter-row {
	padding-bottom: 8rpx;
	display: inline-flex;
	gap: 8rpx;
}

.filter-row button {
	min-height: 72rpx;
	margin: 0;
	padding: 0 18rpx;
	border: 1px solid $app-border;
	border-radius: $app-radius-control;
	background: $app-raised;
	color: $app-muted;
	font-size: 24rpx;
	transition: background-color 180ms ease, border-color 180ms ease, color 180ms ease;
}

.filter-row button.active {
	border-color: rgba($app-action-teal, .48);
	background: rgba($app-action-teal, .14);
	color: #dfffff;
}

.filter-row button::after,
.detail-button::after,
.card-summary::after {
	border: 0;
}

.desktop-results {
	margin-top: 12rpx;
	@include admin-solid-panel;
	overflow: hidden;
}

.result-viewport {
	width: 100%;
	box-sizing: border-box;
}

.desktop-result-viewport {
	height: 62vh;
	min-height: 420rpx;
	max-height: 1100rpx;
}

.virtual-spacer {
	width: 1px;
	pointer-events: none;
}

.result-head,
.result-row {
	display: grid;
	grid-template-columns: 54rpx minmax(190rpx, .9fr) minmax(190rpx, .9fr) minmax(260rpx, 1.4fr) 170rpx 74rpx;
	gap: 14rpx;
	align-items: center;
}

.result-head {
	min-height: 68rpx;
	padding: 0 18rpx;
	border-bottom: 1px solid $app-border;
	color: $app-muted;
	font-size: 24rpx;
	font-weight: 700;
}

.result-row {
	height: 102rpx;
	min-height: 102rpx;
	padding: 12rpx 18rpx;
	border-bottom: 1px solid rgba($app-muted, .12);
	box-sizing: border-box;
	font-size: 24rpx;
	overflow: hidden;
}

.result-row:last-child {
	border-bottom: 0;
}

.email,
.evidence {
	min-width: 0;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.email {
	font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.evidence,
.attempts {
	color: $app-muted;
}

.attempts {
	font-size: 24rpx;
}

.numeric {
	font-variant-numeric: tabular-nums;
}

.status-chip {
	display: inline-flex;
	min-height: 48rpx;
	padding: 0 12rpx;
	border: 1px solid $app-border;
	border-radius: 9rpx;
	align-items: center;
	font-size: 24rpx;
	line-height: 1.25;
}

.tone-success {
	border-color: rgba($app-action-lime, .36);
	background: rgba($app-action-lime, .1);
	color: #dfffb0;
}

.tone-warning {
	border-color: rgba($app-warning, .4);
	background: rgba($app-warning, .1);
	color: #f8d89a;
}

.tone-danger {
	border-color: rgba($app-danger, .42);
	background: rgba($app-danger, .1);
	color: $app-danger-text;
}

.detail-button {
	min-height: 72rpx;
	margin: 0;
	padding: 0;
	border: 0;
	background: transparent;
	color: $app-action-teal;
	font-size: 24rpx;
}

.result-detail {
	padding: 16rpx;
	border: 1px solid rgba($app-teal, .14);
	border-radius: 10rpx;
	display: grid;
	gap: 8rpx;
	background: #0b1115;
	color: $app-muted;
	line-height: 1.45;
}

.selected-result-detail {
	margin-top: 12rpx;
}

.virtual-scroll-status {
	display: block;
	margin-top: 8rpx;
	color: $app-muted;
	font-size: 24rpx;
}

.sensitive-result {
	margin-top: 6rpx;
	padding: 12rpx;
	border: 1px solid rgba($app-action-amber, .22);
	border-radius: 9rpx;
	display: flex;
	align-items: center;
	gap: 12rpx;
	background: rgba($app-action-amber, .05);
}

.sensitive-value {
	min-width: 0;
	flex: 1;
	overflow-wrap: anywhere;
	font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
	color: $app-text;
}

.mobile-results {
	display: none;
}

.empty-filter {
	min-height: 180rpx;
	margin-top: 12rpx;
	border: 1px solid $app-border;
	border-radius: $app-radius-panel;
	display: grid;
	place-items: center;
	color: $app-muted;
}

button:focus-visible {
	outline: 2px solid $app-focus;
	outline-offset: 2px;
}

@media (max-width: 767px) {
	.results-heading {
		align-items: flex-start;
		flex-direction: column;
	}

	.results-copy {
		display: none;
	}

	.retry-actions {
		width: 100%;
	}

	.retry-actions .admin-action-button {
		flex: 1;
	}

	.desktop-results {
		display: none;
	}

	.mobile-results {
		margin-top: 10rpx;
		display: block;
	}

	.mobile-result-viewport {
		height: 64vh;
		min-height: 640rpx;
	}

	.result-card {
		height: 104rpx;
		margin-bottom: 14rpx;
		border: 1px solid $app-border;
		border-radius: $app-radius-panel;
		box-sizing: border-box;
		overflow: hidden;
		background: $app-surface;
	}

	.card-summary {
		width: 100%;
		height: 104rpx;
		min-height: 104rpx;
		margin: 0;
		padding: 16rpx 18rpx;
		border: 0;
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 14rpx;
		background: transparent;
		color: $app-text;
		text-align: left;
	}

	.card-identity {
		min-width: 0;
	}

	.line-number {
		display: block;
		color: $app-action-teal;
		font-size: 24rpx;
		font-weight: 780;
	}

	.card-identity .email {
		display: block;
		margin-top: 5rpx;
		font-size: 24rpx;
	}

	.card-summary .status-chip {
		max-width: 45%;
		flex: 0 0 auto;
		text-align: center;
	}

	.sensitive-result {
		align-items: stretch;
		flex-direction: column;
	}

	.filter-row button {
		min-height: 88rpx;
	}
}

@media (prefers-reduced-motion: reduce) {
	.filter-row button {
		transition: none;
	}
}
</style>

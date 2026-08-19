<template>
	<view class="api-key-usage-page" :class="{ 'is-android-client': androidClient }">
		<scroll-view class="api-key-usage-scroll" scroll-y>
			<view class="api-key-usage-shell" :aria-busy="loading || appendLoading">
				<view class="api-key-usage-toolbar">
					<button class="api-key-usage-back" type="button" aria-label="返回 API Key 列表" @click="$emit('back')">
						<uni-icons type="back" size="18" color="#dce5e0" aria-hidden="true" />
						<text>返回 API Key 列表</text>
					</button>
					<view class="api-key-usage-heading">
						<text class="api-key-usage-kicker">REQUEST AUDIT</text>
						<text class="api-key-usage-title">API Key 调用记录</text>
						<view class="api-key-usage-keyline">
							<text class="api-key-usage-key">{{ selectedKey.maskedKey || '未知 Key' }}</text>
							<text class="api-key-usage-key-status" :class="`is-${keyStatusTone}`">{{ keyStatusText }}</text>
						</view>
					</view>
				</view>

				<view class="api-key-usage-query-card">
					<view class="api-key-usage-query-copy">
						<text class="api-key-usage-query-title">查询时间</text>
						<text class="api-key-usage-query-description">选择条件后不会自动请求；请手动点击“查询记录”。</text>
					</view>
					<view class="api-key-usage-range" role="radiogroup" aria-label="调用记录时间范围">
						<button
							v-for="option in rangeOptions"
							:key="option.value"
							type="button"
							:class="{ active: rangeMode === option.value }"
							:aria-pressed="String(rangeMode === option.value)"
							:disabled="loading"
							@click="selectRange(option.value)"
						>
							{{ option.label }}
						</button>
					</view>

					<view v-if="rangeMode === 'CUSTOM'" class="api-key-usage-custom-range">
						<text class="api-key-usage-custom-label">开始与结束时间</text>
						<uni-datetime-picker
							ref="rangePicker"
							v-model="customRange"
							type="datetimerange"
							return-type="timestamp"
							:clear-icon="true"
							:disabled="loading"
							@show="pickerOpen = true"
							@maskClick="pickerOpen = false"
							@change="applyCustomRange"
						/>
						<text v-if="customRangeError" class="api-key-usage-field-error" role="alert">{{ customRangeError }}</text>
					</view>

					<view v-if="filterDirty" class="api-key-usage-filter-notice" role="status">
						筛选条件已更改；下方仍是上一次查询结果，点击“查询记录”后生效。
					</view>

					<view class="api-key-usage-query-actions">
						<button class="api-key-usage-query" type="button" :disabled="loading || !canQuery" @click="queryRecords">
							{{ loading ? '正在查询…' : '查询记录' }}
						</button>
						<button v-if="queried" class="api-key-usage-refresh" type="button" :disabled="loading" @click="refreshRecords">刷新</button>
					</view>
				</view>

				<view v-if="!queried && !loading" class="api-key-usage-initial" role="status">
					<view class="api-key-usage-initial-icon" aria-hidden="true">↗</view>
					<text class="api-key-usage-initial-title">尚未查询调用记录</text>
					<text>默认已选择最近1小时。点击“查询记录”后才会向服务器发送请求。</text>
				</view>

				<view v-else-if="loading && !summary" class="api-key-usage-state" role="status">正在读取调用记录…</view>

				<view v-else-if="loadError && !summary" class="api-key-usage-state is-error" role="alert">
					<text>{{ loadError }}</text>
					<button type="button" @click="queryRecords">重新查询</button>
				</view>

				<template v-else-if="summary">
					<view class="api-key-usage-result-heading">
						<view>
							<text class="api-key-usage-result-title">查询结果</text>
							<text class="api-key-usage-period">{{ periodText }}</text>
						</view>
						<text class="api-key-usage-result-count">当前显示 {{ items.length }} 条</text>
					</view>

					<view class="api-key-usage-summary" aria-label="时间段调用汇总">
						<view><text>调用数</text><text>{{ summary.requestCount }}</text></view>
						<view><text>输入 Token</text><text>{{ summary.promptTokens }}</text></view>
						<view><text>缓存输入</text><text>{{ summary.cachedPromptTokens }}</text></view>
						<view><text>未缓存输入</text><text>{{ summary.uncachedPromptTokens }}</text></view>
						<view><text>输出 Token</text><text>{{ summary.completionTokens }}</text></view>
						<view class="is-accent"><text>总扣除额度</text><text>{{ quotaText(summary.chargedQuotaMinor) }}</text></view>
					</view>

					<view v-if="summary.pendingRequestCount !== '0'" class="api-key-usage-pending" role="status">
						<text>{{ summary.pendingRequestCount }} 条调用仍在结算</text>
						<text>预扣额度 {{ quotaText(summary.pendingReservedQuotaMinor) }}</text>
					</view>

					<view v-if="loadError" class="api-key-usage-inline-error" role="alert">
						<text>{{ loadError }}</text>
						<button type="button" @click="refreshRecords">重新查询</button>
					</view>

					<view v-if="items.length" class="api-key-usage-audit" aria-label="单次调用记录">
						<view class="api-key-usage-audit-header" aria-hidden="true">
							<text>时间</text><text>模型</text><text>输入</text><text>缓存输入</text><text>输出</text><text>计费状态</text><text>本次额度</text><text>操作</text>
						</view>
						<view v-for="(item, index) in items" :key="rowKey(item, index)" class="api-key-usage-audit-row">
							<view class="api-key-usage-audit-main">
								<view class="audit-cell is-time" data-label="时间"><text>{{ timeText(item.createdAt) }}</text></view>
								<view class="audit-cell is-model" data-label="模型">
									<text class="audit-model-name">{{ item.modelName }}</text>
									<text class="audit-model-meta">{{ item.vendor }} · {{ item.stream ? '流式' : '非流式' }}</text>
								</view>
								<view class="audit-cell is-number" data-label="输入"><text>{{ item.promptTokens }}</text></view>
								<view class="audit-cell is-number" data-label="缓存输入"><text>{{ item.cachedPromptTokens }}</text></view>
								<view class="audit-cell is-number" data-label="输出"><text>{{ item.completionTokens }}</text></view>
								<view class="audit-cell is-status" data-label="计费状态">
									<text class="api-key-usage-status" :class="`is-${item.billingStatus.toLowerCase()}`">{{ statusText(item.billingStatus) }}</text>
								</view>
								<view class="audit-cell is-charge" data-label="本次额度"><text>{{ chargeValue(item) }}</text></view>
								<view class="audit-cell is-action">
									<button type="button" :aria-expanded="String(isExpanded(item, index))" @click="toggleDetails(item, index)">{{ isExpanded(item, index) ? '收起详情' : '查看详情' }}</button>
								</view>
							</view>

							<view v-if="isExpanded(item, index)" class="api-key-usage-details">
								<view><text>未缓存输入 Token</text><text>{{ item.uncachedPromptTokens }}</text></view>
								<view><text>模型公共 ID</text><text>{{ item.modelPublicId }}</text></view>
								<view><text>响应方式</text><text>{{ item.stream ? '流式' : '非流式' }}</text></view>
								<view><text>创建时间</text><text>{{ timeText(item.createdAt) }}</text></view>
								<view><text>结算时间</text><text>{{ optionalTimeText(item.settledAt) }}</text></view>
								<view><text>结束原因</text><text>{{ item.finishReason || '—' }}</text></view>
								<view><text>失败代码</text><text>{{ item.failureCode || '—' }}</text></view>
								<view><text>预扣额度</text><text>{{ quotaText(item.reservedQuotaMinor) }}</text></view>
								<view><text>{{ chargeLabel(item) }}</text><text>{{ chargeValue(item) }}</text></view>
								<view class="is-wide"><text>计费说明</text><text>{{ billingExplanation(item) }}</text></view>
							</view>
						</view>
					</view>

					<view v-else class="api-key-usage-empty" role="status">所选时间段内没有调用记录。</view>

					<view v-if="appendError" class="api-key-usage-append-error" role="alert">
						<text>{{ appendError }}</text>
						<button type="button" @click="loadMore">重试加载</button>
					</view>
					<button v-if="nextCursor" class="api-key-usage-load-more" type="button" :disabled="appendLoading" @click="loadMore">{{ appendLoading ? '正在加载…' : '加载更多' }}</button>
				</template>
			</view>
		</scroll-view>
	</view>
</template>

<script>
	import { formatLocalDateTimeZhCn } from '@/common/platform/date-time.js'
	import { apiKeyUsageApi, formatQuotaMinor } from '@/common/user/api-key-usage-api.js'

	const RANGE_HOURS = Object.freeze({ DAY: 24, WEEK: 24 * 7 })

	function readableError(error) {
		if (error?.statusCode === 404 || error?.code === 'API_KEY_NOT_FOUND') return 'API Key 不存在或已经撤销。'
		if (error?.statusCode === 503
			|| error?.code === 'USAGE_QUERY_UNAVAILABLE'
			|| error?.code === 'USAGE_DATA_INVALID'
			|| error?.code === 'HTTP_503') return '调用记录暂时不可用，请稍后重试。'
		if (error?.code === 'API_KEY_USAGE_RESPONSE_INVALID') return '调用记录响应无效，请重新查询。'
		return error?.message || '调用记录查询失败，请稍后重试。'
	}

	export default {
		props: {
			selectedKey: { type: Object, default: () => ({}) },
			androidClient: { type: Boolean, default: false }
		},
		data() {
			return {
				rangeMode: 'HOUR',
				customRange: [],
				customRangeError: '',
				filterDirty: false,
				queried: false,
				period: null,
				summary: null,
				items: [],
				nextCursor: null,
				loading: false,
				appendLoading: false,
				loadError: '',
				appendError: '',
				pickerOpen: false,
				expandedRows: [],
				requestGeneration: 0
			}
		},
		computed: {
			rangeOptions() {
				return [
					{ value: 'HOUR', label: '最近1小时' },
					{ value: 'DAY', label: '最近24小时' },
					{ value: 'WEEK', label: '最近7天' },
					{ value: 'CUSTOM', label: '自定义' }
				]
			},
			apiKeyPublicId() { return String(this.selectedKey?.id || '').trim() },
			canQuery() {
				if (!this.apiKeyPublicId) return false
				return this.rangeMode !== 'CUSTOM' || this.validCustomRange() !== null
			},
			periodText() {
				if (!this.period) return ''
				return `${this.timeText(this.period.from)} 至 ${this.timeText(this.period.to)}`
			},
			keyStatusText() { return this.selectedKey?.status === 'DISABLED' ? '已停用' : '已启用' },
			keyStatusTone() { return this.selectedKey?.status === 'DISABLED' ? 'disabled' : 'enabled' }
		},
		watch: {
			apiKeyPublicId(value, previous) {
				if (value !== previous) this.resetForKey()
			}
		},
		beforeDestroy() { this.invalidateRequests(true) },
		beforeUnmount() { this.invalidateRequests(true) },
		methods: {
			closeIfOpen() {
				if (!this.pickerOpen) return false
				this.$refs.rangePicker?.close?.()
				this.pickerOpen = false
				return true
			},
			handlePageHide() { this.invalidateRequests(false) },
			handlePageShow() {},
			resetForKey() {
				this.invalidateRequests(true)
				this.rangeMode = 'HOUR'
				this.customRange = []
				this.customRangeError = ''
				this.filterDirty = false
				this.queried = false
			},
			invalidateRequests(clear) {
				this.requestGeneration += 1
				this.loading = false
				this.appendLoading = false
				this.pickerOpen = false
				if (!clear) return
				this.period = null
				this.summary = null
				this.items = []
				this.nextCursor = null
				this.loadError = ''
				this.appendError = ''
				this.expandedRows = []
			},
			selectRange(mode) {
				if (mode === this.rangeMode) return
				this.invalidateRequests(false)
				this.rangeMode = mode
				this.customRangeError = ''
				this.filterDirty = this.queried
			},
			applyCustomRange(event) {
				this.invalidateRequests(false)
				const value = Array.isArray(event) ? event : event?.detail?.value
				this.customRange = Array.isArray(value) ? value : []
				this.customRangeError = this.validCustomRange() ? '' : '请选择不超过31天的完整时间范围。'
				this.filterDirty = this.queried
			},
			validCustomRange() {
				if (!Array.isArray(this.customRange) || this.customRange.length !== 2) return null
				const from = Number(this.customRange[0])
				const to = Number(this.customRange[1])
				if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from) return null
				if (to - from > 31 * 24 * 60 * 60 * 1000) return null
				return { from: new Date(from).toISOString(), to: new Date(to).toISOString() }
			},
			queryRecords() {
				if (!this.canQuery || this.loading) return
				if (this.rangeMode === 'HOUR') return this.loadFirst({})
				if (this.rangeMode === 'DAY' || this.rangeMode === 'WEEK') return this.loadPreset(this.rangeMode)
				const range = this.validCustomRange()
				if (!range) {
					this.customRangeError = '请选择不超过31天的完整时间范围。'
					return
				}
				return this.loadFirst(range)
			},
			refreshRecords() { return this.queryRecords() },
			beginFirstLoad() {
				const generation = ++this.requestGeneration
				this.loading = true
				this.appendLoading = false
				this.loadError = ''
				this.appendError = ''
				this.expandedRows = []
				return generation
			},
			async loadPreset(mode) {
				const generation = this.beginFirstLoad()
				try {
					// 手动查询后先取得服务器时间锚点，避免客户端时间偏差制造未来时间范围。
					const anchor = await apiKeyUsageApi.query(this.apiKeyPublicId, { pageSize: 1 })
					if (generation !== this.requestGeneration) return
					const to = anchor.period.to
					const from = new Date(Date.parse(to) - RANGE_HOURS[mode] * 60 * 60 * 1000).toISOString()
					const page = await apiKeyUsageApi.query(this.apiKeyPublicId, { from, to, pageSize: 20 })
					if (generation !== this.requestGeneration) return
					this.applyPage(page)
				} catch (error) {
					if (generation === this.requestGeneration) this.recordFailure(error, 'loadError')
				} finally {
					if (generation === this.requestGeneration) this.loading = false
				}
			},
			async loadFirst(options) {
				const generation = this.beginFirstLoad()
				try {
					const page = await apiKeyUsageApi.query(this.apiKeyPublicId, { ...options, pageSize: 20 })
					if (generation !== this.requestGeneration) return
					this.applyPage(page)
				} catch (error) {
					if (generation === this.requestGeneration) this.recordFailure(error, 'loadError')
				} finally {
					if (generation === this.requestGeneration) this.loading = false
				}
			},
			applyPage(page) {
				this.period = page.period
				this.summary = page.summary
				this.items = page.items
				this.nextCursor = page.nextCursor
				this.queried = true
				this.filterDirty = false
				this.loadError = ''
				this.appendError = ''
			},
			async loadMore() {
				if (!this.nextCursor || !this.period || this.appendLoading) return
				const generation = this.requestGeneration
				this.appendLoading = true
				this.appendError = ''
				try {
					const page = await apiKeyUsageApi.query(this.apiKeyPublicId, {
						from: this.period.from,
						to: this.period.to,
						cursor: this.nextCursor,
						pageSize: 20
					})
					if (generation !== this.requestGeneration) return
					this.items = [...this.items, ...page.items]
					this.nextCursor = page.nextCursor
				} catch (error) {
					if (generation === this.requestGeneration) this.recordFailure(error, 'appendError')
				} finally {
					if (generation === this.requestGeneration) this.appendLoading = false
				}
			},
			recordFailure(error, target) {
				this.queried = true
				if (error?.statusCode === 404 || error?.code === 'API_KEY_NOT_FOUND') {
					this.requestGeneration += 1
					this.$emit('not-found', this.apiKeyPublicId)
				}
				this[target] = readableError(error)
			},
			rowKey(item, index) { return `${item.createdAt}|${item.modelPublicId}|${index}` },
			isExpanded(item, index) { return this.expandedRows.includes(this.rowKey(item, index)) },
			toggleDetails(item, index) {
				const key = this.rowKey(item, index)
				this.expandedRows = this.expandedRows.includes(key)
					? this.expandedRows.filter(value => value !== key)
					: [...this.expandedRows, key]
			},
			statusText(status) {
				return {
					SETTLED: '已结算', RESERVED: '结算中', FAILED_REFUNDED: '失败已退款',
					RECONCILE_REQUIRED: '待对账', REFUNDED: '已退款'
				}[status] || status
			},
			chargeLabel(item) {
				if (item.billingStatus === 'RESERVED') return '预扣额度'
				if (item.billingStatus === 'RECONCILE_REQUIRED') return '当前已扣额度（待对账）'
				return '本次实际扣除额度'
			},
			chargeValue(item) { return this.quotaText(item.billingStatus === 'RESERVED' ? item.reservedQuotaMinor : item.chargedQuotaMinor) },
			billingExplanation(item) {
				if (item.billingStatus === 'RESERVED') return '请求仍在结算，当前只显示预扣额度。'
				if (item.billingStatus === 'FAILED_REFUNDED') return '请求失败，预扣额度已经退回。'
				if (item.billingStatus === 'REFUNDED') return '本次调用额度已经退款。'
				if (item.billingStatus === 'RECONCILE_REQUIRED') return '当前额度尚未最终确认，需要后台对账。'
				return '显示数据库记录的权威实际扣费。'
			},
			quotaText(value) { return value == null ? '—' : `${formatQuotaMinor(value)} 额度` },
			timeText(value) { return formatLocalDateTimeZhCn(value) || '时间无效' },
			optionalTimeText(value) { return value ? this.timeText(value) : '尚未结算' }
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';
	.api-key-usage-page { width: 100%; min-width: 0; height: 100%; display: flex; flex-direction: column; background: #0b0d0c; color: #f3f5f4; }
	.api-key-usage-scroll { width: 100%; min-width: 0; min-height: 0; flex: 1; }
	.api-key-usage-shell { width: 100%; max-width: 1480px; min-height: 100%; margin: 0 auto; padding: 34px var(--workspace-content-gutter, 24px) calc(56px + env(safe-area-inset-bottom)); box-sizing: border-box; }
	.api-key-usage-toolbar { display: flex; align-items: flex-start; gap: 22px; }
	.api-key-usage-back, .api-key-usage-query, .api-key-usage-refresh, .api-key-usage-state button, .api-key-usage-load-more, .api-key-usage-inline-error button, .api-key-usage-append-error button { @include user-frosted-control; min-height: 48px; margin: 0; border-radius: 12px; color: #dce5e0; }
	.api-key-usage-back { display: flex; align-items: center; gap: 8px; padding: 0 14px; flex: 0 0 auto; }
	.api-key-usage-heading { min-width: 0; display: flex; flex-direction: column; }
	.api-key-usage-kicker { color: #43d6a1; font-size: 12px; font-weight: 800; letter-spacing: 1.8px; }
	.api-key-usage-title { margin-top: 8px; font-size: clamp(27px, 4vw, 38px); font-weight: 790; line-height: 1.15; }
	.api-key-usage-keyline { display: flex; align-items: center; gap: 9px; margin-top: 10px; }
	.api-key-usage-key { overflow-wrap: anywhere; color: #9ca8a2; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 13px; }
	.api-key-usage-key-status { padding: 4px 8px; border-radius: 999px; background: rgba(55, 211, 154, .12); color: #75dfb7; font-size: 10px; font-weight: 760; }
	.api-key-usage-key-status.is-disabled { background: rgba(151, 170, 160, .12); color: #a8b3ad; }
	.api-key-usage-query-card { @include user-frosted-surface; margin-top: 26px; padding: 18px; border-radius: 16px; }
	.api-key-usage-query-title, .api-key-usage-query-description { display: block; }
	.api-key-usage-query-title { color: #e4ebe7; font-size: 15px; font-weight: 730; }
	.api-key-usage-query-description { margin-top: 5px; color: #87938d; font-size: 12px; line-height: 1.55; }
	.api-key-usage-range { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-top: 15px; }
	.api-key-usage-range button { min-width: 0; min-height: 46px; display: flex; align-items: center; justify-content: center; margin: 0; padding: 0 10px; border: 1px solid rgba(151, 170, 160, .18); border-radius: 11px; background: #151916; color: #9ca8a2; font-size: 12px; text-align: center; }
	.api-key-usage-range button.active { border-color: rgba(55, 211, 154, .48); background: rgba(55, 211, 154, .09); color: #75dfb7; }
	.api-key-usage-custom-range { margin-top: 14px; }
	.api-key-usage-custom-label { display: block; margin-bottom: 8px; color: #87938d; font-size: 11px; }
	.api-key-usage-field-error { display: block; margin-top: 8px; color: #efb0aa; font-size: 12px; }
	.api-key-usage-filter-notice { margin-top: 12px; padding: 10px 12px; border: 1px solid rgba(222, 157, 80, .25); border-radius: 10px; background: rgba(201, 130, 47, .06); color: #dcb27d; font-size: 11px; line-height: 1.5; }
	.api-key-usage-query-actions { display: flex; justify-content: flex-end; gap: 9px; margin-top: 15px; }
	.api-key-usage-query { min-width: 126px; display: flex; align-items: center; justify-content: center; border-color: rgba(55, 211, 154, .48); background: rgba(55, 211, 154, .11); color: #75dfb7; text-align: center; }
	.api-key-usage-refresh { min-width: 78px; }
	.api-key-usage-initial, .api-key-usage-state, .api-key-usage-empty { min-height: 210px; margin-top: 18px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 9px; padding: 28px; box-sizing: border-box; border: 1px dashed rgba(151, 170, 160, .2); border-radius: 16px; color: #87938d; font-size: 12px; line-height: 1.6; text-align: center; }
	.api-key-usage-initial-icon { width: 45px; height: 45px; display: flex; align-items: center; justify-content: center; border: 1px solid rgba(55, 211, 154, .26); border-radius: 14px; background: rgba(55, 211, 154, .07); color: #75dfb7; font-size: 21px; }
	.api-key-usage-initial-title { color: #e4ebe7; font-size: 16px; font-weight: 720; }
	.api-key-usage-state.is-error { color: #efb0aa; }
	.api-key-usage-result-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; margin-top: 26px; }
	.api-key-usage-result-title, .api-key-usage-period { display: block; }
	.api-key-usage-result-title { color: #e4ebe7; font-size: 16px; font-weight: 740; }
	.api-key-usage-period, .api-key-usage-result-count { margin-top: 5px; color: #7f8b85; font-size: 11px; font-variant-numeric: tabular-nums; }
	.api-key-usage-summary { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 9px; margin-top: 13px; }
	.api-key-usage-summary > view { @include user-frosted-surface; min-width: 0; padding: 13px; border-radius: 13px; }
	.api-key-usage-summary text { display: block; overflow-wrap: anywhere; }
	.api-key-usage-summary text:first-child { color: #87938d; font-size: 10px; }
	.api-key-usage-summary text:last-child { margin-top: 7px; color: #e3e9e6; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 13px; font-weight: 700; }
	.api-key-usage-summary .is-accent text:last-child { color: #75dfb7; }
	.api-key-usage-pending { display: flex; justify-content: space-between; gap: 12px; margin-top: 10px; padding: 11px 13px; border: 1px solid rgba(222, 157, 80, .28); border-radius: 12px; background: rgba(201, 130, 47, .07); color: #efc18a; font-size: 12px; }
	.api-key-usage-inline-error, .api-key-usage-append-error { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 12px; color: #efb0aa; font-size: 12px; }
	.api-key-usage-inline-error button, .api-key-usage-append-error button { padding: 0 14px; flex: 0 0 auto; }
	.api-key-usage-audit { margin-top: 14px; overflow: hidden; border: 1px solid rgba(151, 170, 160, .17); border-radius: 15px; background: rgba(18, 22, 20, .82); }
	.api-key-usage-audit-header, .api-key-usage-audit-main { display: grid; grid-template-columns: minmax(132px, 1.15fr) minmax(175px, 1.45fr) minmax(76px, .72fr) minmax(86px, .78fr) minmax(76px, .72fr) minmax(98px, .82fr) minmax(98px, .82fr) minmax(92px, .7fr); align-items: center; }
	.api-key-usage-audit-header { min-height: 42px; padding: 0 13px; border-bottom: 1px solid rgba(151, 170, 160, .15); background: rgba(255, 255, 255, .025); color: #76827c; font-size: 10px; font-weight: 700; }
	.api-key-usage-audit-header text:nth-child(n+3):not(:last-child) { text-align: right; }
	.api-key-usage-audit-row { border-bottom: 1px solid rgba(151, 170, 160, .11); }
	.api-key-usage-audit-row:last-child { border-bottom: 0; }
	.api-key-usage-audit-main { min-height: 70px; padding: 0 13px; }
	.audit-cell { min-width: 0; padding: 10px 7px 10px 0; color: #aeb9b3; font-size: 11px; overflow-wrap: anywhere; }
	.audit-cell.is-number, .audit-cell.is-charge { color: #cdd6d1; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; text-align: right; }
	.audit-cell.is-charge { color: #75dfb7; }
	.audit-cell.is-time { color: #8c9892; font-variant-numeric: tabular-nums; }
	.audit-model-name, .audit-model-meta { display: block; }
	.audit-model-name { color: #e1e8e4; font-size: 12px; font-weight: 700; }
	.audit-model-meta { margin-top: 4px; color: #75817b; font-size: 10px; }
	.api-key-usage-status { display: inline-block; padding: 4px 7px; border: 1px solid rgba(55, 211, 154, .28); border-radius: 999px; color: #75dfb7; font-size: 9px; white-space: nowrap; }
	.api-key-usage-status.is-reserved { border-color: rgba(222, 157, 80, .28); color: #efc18a; }
	.api-key-usage-status.is-failed_refunded, .api-key-usage-status.is-refunded { border-color: rgba(126, 166, 215, .28); color: #a9c9ef; }
	.api-key-usage-status.is-reconcile_required { border-color: rgba(222, 112, 95, .34); color: #ef9e92; }
	.audit-cell.is-action { padding-right: 0; text-align: right; }
	.audit-cell.is-action button { min-height: 38px; margin: 0; padding: 0 9px; border: 1px solid rgba(151, 170, 160, .18); border-radius: 9px; background: #171b18; color: #b9c4be; font-size: 10px; }
	.api-key-usage-details { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 1px; padding: 1px 13px 13px; background: rgba(55, 211, 154, .025); }
	.api-key-usage-details > view { min-width: 0; padding: 11px; background: rgba(255, 255, 255, .022); }
	.api-key-usage-details > view.is-wide { grid-column: span 5; }
	.api-key-usage-details text { display: block; overflow-wrap: anywhere; }
	.api-key-usage-details text:first-child { color: #75817b; font-size: 9px; }
	.api-key-usage-details text:last-child { margin-top: 5px; color: #c5cec9; font-size: 11px; }
	.api-key-usage-load-more { width: 100%; margin-top: 12px; border-color: rgba(55, 211, 154, .32); color: #75dfb7; }
	.api-key-usage-page button:focus-visible { outline: 2px solid rgba(55, 211, 154, .76); outline-offset: 2px; }
	@media screen and (max-width: 1050px) { .api-key-usage-summary { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
	@media screen and (max-width: 760px) {
		.api-key-usage-shell { padding: 20px 14px calc(36px + env(safe-area-inset-bottom)); }
		.api-key-usage-toolbar { flex-direction: column; gap: 16px; }
		.api-key-usage-range { grid-template-columns: repeat(2, minmax(0, 1fr)); }
		.api-key-usage-query-actions { align-items: stretch; flex-direction: column; }
		.api-key-usage-query, .api-key-usage-refresh { width: 100%; }
		.api-key-usage-result-heading, .api-key-usage-pending, .api-key-usage-inline-error, .api-key-usage-append-error { align-items: stretch; flex-direction: column; }
		.api-key-usage-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
		.api-key-usage-audit { overflow: visible; border: 0; background: transparent; }
		.api-key-usage-audit-header { display: none; }
		.api-key-usage-audit-row { margin-top: 10px; overflow: hidden; border: 1px solid rgba(151, 170, 160, .17); border-radius: 14px; background: rgba(18, 22, 20, .86); }
		.api-key-usage-audit-row:first-child { margin-top: 0; }
		.api-key-usage-audit-main { min-height: 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0; padding: 12px; }
		.audit-cell { padding: 8px; }
		.audit-cell::before { content: attr(data-label); display: block; margin-bottom: 5px; color: #75817b; font-family: inherit; font-size: 9px; text-align: left; }
		.audit-cell.is-time, .audit-cell.is-model { grid-column: span 2; }
		.audit-cell.is-number, .audit-cell.is-charge { text-align: left; }
		.audit-cell.is-status { display: flex; flex-direction: column; align-items: flex-start; }
		.audit-cell.is-action { grid-column: span 2; padding-top: 10px; text-align: left; }
		.audit-cell.is-action::before { display: none; }
		.audit-cell.is-action button { width: 100%; min-height: 48px; }
		.api-key-usage-details { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 1px 12px 12px; }
		.api-key-usage-details > view.is-wide { grid-column: span 2; }
	}
	.api-key-usage-page.is-android-client .api-key-usage-shell { max-width: none; padding-top: max(14px, env(safe-area-inset-top)); padding-bottom: calc(38px + env(safe-area-inset-bottom)); }
	.api-key-usage-page.is-android-client button { min-height: 48px; }
	.api-key-usage-page.is-android-client .api-key-usage-audit { overflow: visible; border: 0; background: transparent; }
	.api-key-usage-page.is-android-client .api-key-usage-audit-header { display: none; }
	.api-key-usage-page.is-android-client .api-key-usage-audit-row { margin-top: 10px; overflow: hidden; border: 1px solid rgba(151, 170, 160, .17); border-radius: 14px; background: rgba(18, 22, 20, .86); }
	.api-key-usage-page.is-android-client .api-key-usage-audit-row:first-child { margin-top: 0; }
	.api-key-usage-page.is-android-client .api-key-usage-audit-main { min-height: 0; grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 12px; }
	.api-key-usage-page.is-android-client .audit-cell { padding: 8px; }
	.api-key-usage-page.is-android-client .audit-cell::before { content: attr(data-label); display: block; margin-bottom: 5px; color: #75817b; font-family: inherit; font-size: 9px; text-align: left; }
	.api-key-usage-page.is-android-client .audit-cell.is-time, .api-key-usage-page.is-android-client .audit-cell.is-model { grid-column: span 2; }
	.api-key-usage-page.is-android-client .audit-cell.is-number, .api-key-usage-page.is-android-client .audit-cell.is-charge { text-align: left; }
	.api-key-usage-page.is-android-client .audit-cell.is-status { display: flex; flex-direction: column; align-items: flex-start; }
	.api-key-usage-page.is-android-client .audit-cell.is-action { grid-column: span 2; padding-top: 10px; text-align: left; }
	.api-key-usage-page.is-android-client .audit-cell.is-action::before { display: none; }
	.api-key-usage-page.is-android-client .audit-cell.is-action button { width: 100%; }
	.api-key-usage-page.is-android-client .api-key-usage-details { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 1px 12px 12px; }
	.api-key-usage-page.is-android-client .api-key-usage-details > view.is-wide { grid-column: span 2; }
	@media (prefers-reduced-motion: reduce) { .api-key-usage-page button { transition: none; } }
</style>

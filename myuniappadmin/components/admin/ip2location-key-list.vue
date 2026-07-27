<template>
	<view class="key-list-shell">
		<view v-if="loading" class="skeleton-list" aria-live="polite" aria-label="正在加载凭据">
			<view v-for="index in 5" :key="index" class="skeleton-row" />
		</view>

		<view v-else-if="!items.length" class="empty-state">
			<text class="empty-title">还没有可用凭据</text>
			<text class="empty-copy">导入后只会显示脱敏标识、套餐、剩余额度和有效期。</text>
			<button class="empty-action" type="button" @click="$emit('open-import')">导入凭据</button>
		</view>

		<template v-else>
			<view class="selection-bar" :class="{ visible: selectedIds.length }" aria-live="polite">
				<text>已选择 {{ selectedIds.length }} 条</text>
				<view class="selection-actions">
					<button type="button" @click="clearSelection">取消选择</button>
					<button class="danger-text" type="button" @click="$emit('delete-selected', [...selectedIds])">批量删除</button>
				</view>
			</view>

			<view class="desktop-table" role="table" aria-label="IP2Location 凭据列表">
				<view class="table-row table-head" role="row">
					<text role="columnheader">选择</text>
					<text role="columnheader">凭据</text>
					<text role="columnheader">套餐</text>
					<text role="columnheader">剩余额度</text>
					<text role="columnheader">有效期</text>
					<text role="columnheader">状态</text>
					<text role="columnheader" class="right-column">操作</text>
				</view>
				<view v-for="item in items" :key="item.keyId" class="table-row" role="row">
					<view role="cell">
						<button class="select-button" type="button" :aria-pressed="String(isSelected(item.keyId))" :aria-label="`${isSelected(item.keyId) ? '取消选择' : '选择'} ${item.maskedKey}`" @click="toggle(item.keyId)">
							<view class="select-mark" :class="{ selected: isSelected(item.keyId) }" />
						</button>
					</view>
					<text role="cell" class="masked-key">{{ item.maskedKey }}</text>
					<text role="cell" class="plan-label">{{ planLabel(item.planType) }}</text>
					<text role="cell" class="numeric">{{ formatQuota(item.remainingQuota) }}</text>
					<view role="cell" class="expiry-cell">
						<text>{{ item.relativeExpiry }}</text>
						<text class="secondary-line numeric">{{ item.exactExpiry }}</text>
					</view>
					<view role="cell"><text class="status-label" :class="`status-${item.status.code.toLowerCase()}`">{{ item.status.label }}</text></view>
					<view role="cell" class="right-column"><button class="row-delete" type="button" :aria-label="`删除 ${item.maskedKey}`" @click="$emit('delete-one', item)">删除</button></view>
				</view>
			</view>

			<view class="mobile-list" aria-label="IP2Location 凭据列表">
				<view v-for="item in items" :key="item.keyId" class="mobile-row">
					<view class="mobile-primary">
						<button class="select-button" type="button" :aria-pressed="String(isSelected(item.keyId))" :aria-label="`${isSelected(item.keyId) ? '取消选择' : '选择'} ${item.maskedKey}`" @click="toggle(item.keyId)">
							<view class="select-mark" :class="{ selected: isSelected(item.keyId) }" />
						</button>
						<text class="masked-key">{{ item.maskedKey }}</text>
						<text class="plan-label">{{ planLabel(item.planType) }}</text>
					</view>
					<view class="mobile-metrics">
						<text class="numeric">剩余 {{ formatQuota(item.remainingQuota) }}</text>
						<text>{{ item.relativeExpiry }}</text>
					</view>
					<view class="mobile-secondary">
						<text class="status-label" :class="`status-${item.status.code.toLowerCase()}`">{{ item.status.label }}</text>
						<text class="secondary-line numeric">{{ item.exactExpiry }}</text>
						<button class="row-delete" type="button" :aria-label="`删除 ${item.maskedKey}`" @click="$emit('delete-one', item)">删除</button>
					</view>
				</view>
			</view>

			<view class="pagination" aria-label="分页">
				<button type="button" :disabled="page <= 1" @click="$emit('page-change', page - 1)">上一页</button>
				<text class="numeric">{{ page }} / {{ pageCount }}</text>
				<button type="button" :disabled="page >= pageCount" @click="$emit('page-change', page + 1)">下一页</button>
				<text class="page-size">20 条 / 页</text>
			</view>
		</template>
	</view>
</template>

<script>
const planLabels = {
	FREE: 'Free', STARTER: 'Starter', PLUS: 'Plus', SECURITY: 'Security',
	SECURITY_TRIAL: 'Security Trial', CUSTOM: 'Custom'
}

export default {
	name: 'Ip2LocationKeyList',
	props: {
		items: { type: Array, default: () => [] },
		page: { type: Number, default: 1 },
		pageCount: { type: Number, default: 1 },
		loading: { type: Boolean, default: false }
	},
	emits: ['open-import', 'page-change', 'delete-one', 'delete-selected'],
	data() { return { selectedIds: [] } },
	watch: {
		items() {
			const visible = new Set(this.items.map(item => item.keyId))
			this.selectedIds = this.selectedIds.filter(keyId => visible.has(keyId))
		}
	},
	methods: {
		planLabel(planType) { return planLabels[planType] || planType || '未知' },
		formatQuota(value) { return Math.max(0, Number(value) || 0).toLocaleString() },
		isSelected(keyId) { return this.selectedIds.includes(keyId) },
		toggle(keyId) {
			this.selectedIds = this.isSelected(keyId)
				? this.selectedIds.filter(value => value !== keyId)
				: [...this.selectedIds, keyId]
		},
		clearSelection() { this.selectedIds = [] }
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.key-list-shell { min-height: 420rpx; }
.skeleton-list { display: grid; gap: 2rpx; }
.skeleton-row { height: 94rpx; background: linear-gradient(90deg, rgba($app-raised, .8), rgba($app-border, .5), rgba($app-raised, .8)); background-size: 220% 100%; animation: skeleton 1.4s linear infinite; }
.empty-state { min-height: 420rpx; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
.empty-title { color: $app-text; font-size: 31rpx; font-weight: 740; }
.empty-copy { max-width: 560rpx; margin-top: 12rpx; color: $app-muted; font-size: 23rpx; line-height: 1.6; }
.empty-action { min-width: 220rpx; min-height: 84rpx; margin-top: 28rpx; border: 0; border-radius: $app-radius-control; background: $app-lime; color: #171306; font-weight: 760; }
.selection-bar { min-height: 0; max-height: 0; opacity: 0; overflow: hidden; display: flex; align-items: center; justify-content: space-between; color: $app-text; transition: max-height 180ms ease, opacity 180ms ease; }
.selection-bar.visible { min-height: 76rpx; max-height: 76rpx; opacity: 1; }
.selection-actions { display: flex; gap: 12rpx; }
.selection-actions button { min-height: 64rpx; border: 0; background: transparent; color: $app-green; font-size: 22rpx; }
.selection-actions .danger-text { color: $app-danger-text; }
.desktop-table { border-top: 1px solid $app-border; }
.table-row { min-height: 92rpx; display: grid; grid-template-columns: 64rpx minmax(170rpx, 1.35fr) minmax(100rpx, .7fr) minmax(100rpx, .7fr) minmax(190rpx, 1.2fr) minmax(110rpx, .75fr) 70rpx; gap: 14rpx; align-items: center; padding: 0 20rpx; border-bottom: 1px solid rgba($app-border, .72); color: $app-text; font-size: 22rpx; }
.table-head { min-height: 70rpx; color: $app-muted; font-size: 20rpx; text-transform: uppercase; letter-spacing: .04em; }
.right-column { text-align: right; justify-self: end; }
.select-button, .row-delete { min-width: 64rpx; min-height: 64rpx; padding: 0; border: 0; background: transparent; }
.select-mark { width: 30rpx; height: 30rpx; box-sizing: border-box; border: 1px solid $app-border; border-radius: 7rpx; background: $app-raised; }
.select-mark.selected { border-color: $app-green; background: $app-green; box-shadow: inset 0 0 0 7rpx $app-raised; }
.masked-key { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.plan-label { color: $app-teal; font-weight: 700; }
.numeric { font-variant-numeric: tabular-nums; }
.expiry-cell { display: flex; flex-direction: column; gap: 4rpx; }
.secondary-line { color: $app-muted; font-size: 19rpx; }
.status-label { display: inline-flex; min-height: 44rpx; align-items: center; padding: 0 13rpx; border-radius: 999rpx; font-size: 19rpx; font-weight: 720; }
.status-active { background: rgba($app-green, .1); color: $app-green; }
.status-expiring { background: rgba($app-warning, .12); color: $app-warning; }
.status-exhausted, .status-expired, .status-invalid { background: rgba($app-danger, .1); color: $app-danger-text; }
.row-delete { color: $app-danger-text; font-size: 21rpx; }
.mobile-list { display: none; }
.pagination { min-height: 92rpx; display: flex; align-items: center; justify-content: center; gap: 18rpx; color: $app-muted; }
.pagination button { min-width: 104rpx; min-height: 72rpx; border: 1px solid $app-border; border-radius: 10rpx; background: $app-raised; color: $app-text; font-size: 22rpx; }
.pagination button[disabled] { opacity: .38; }
.page-size { margin-left: 18rpx; font-size: 20rpx; }
button::after { border: 0; }
button:focus-visible { outline: 2px solid $app-focus; outline-offset: 2px; }

@keyframes skeleton { to { background-position: -220% 0; } }

@media (max-width: 760px) {
	.desktop-table { display: none; }
	.mobile-list { display: block; border-top: 1px solid $app-border; }
	.mobile-row { padding: 22rpx 4rpx; border-bottom: 1px solid rgba($app-border, .72); }
	.mobile-primary { min-height: 60rpx; display: grid; grid-template-columns: 64rpx minmax(0, 1fr) auto; gap: 10rpx; align-items: center; }
	.mobile-metrics, .mobile-secondary { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; padding-left: 74rpx; }
	.mobile-metrics { margin-top: 10rpx; color: $app-text; font-size: 22rpx; }
	.mobile-secondary { min-height: 74rpx; margin-top: 10rpx; }
	.mobile-secondary .secondary-line { flex: 1; }
	.select-button, .row-delete, .pagination button, .empty-action { min-height: 96rpx; }
	.selection-bar.visible { min-height: 96rpx; max-height: 96rpx; }
	.page-size { display: none; }
}

@media (prefers-reduced-motion: reduce) { .selection-bar { transition: none; } .skeleton-row { animation: none; } }
</style>

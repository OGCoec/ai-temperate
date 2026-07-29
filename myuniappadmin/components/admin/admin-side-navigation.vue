<template>
	<view class="admin-side-navigation">
		<view class="navigation-brand">
			<view class="brand-mark" aria-hidden="true"><view class="brand-mark-core" /></view>
			<view class="brand-copy">
				<text class="brand-title">AI Temperate</text>
				<text class="brand-subtitle">管理员工作台</text>
			</view>
		</view>

		<view class="navigation-scroll">
			<view v-for="group in groups" :key="group.label" class="navigation-group">
				<text class="group-label">{{ group.label }}</text>
				<button
					v-for="item in group.items"
					:key="item.view"
					class="navigation-item"
					:class="{ active: isActive(item.view) }"
					type="button"
					:disabled="busy"
					:aria-current="isActive(item.view) ? 'page' : undefined"
					@click="$emit('navigate', item.location || { view: item.view })"
				>
					<text class="item-symbol" aria-hidden="true">{{ item.symbol }}</text>
					<view class="item-copy">
						<text class="item-label">{{ item.label }}</text>
						<text class="item-description">{{ item.description }}</text>
					</view>
					<text class="item-arrow" aria-hidden="true">›</text>
				</button>
			</view>
		</view>

		<view class="navigation-foot">
			<view class="security-pulse" aria-hidden="true" />
			<text>受保护的管理员会话</text>
		</view>
	</view>
</template>

<script>
const MODEL_VIEWS = new Set(['ai-models', 'ai-model-create', 'ai-model-detail'])

export default {
	name: 'AdminSideNavigation',
	emits: ['navigate'],
	props: {
		activeView: { type: String, default: 'dashboard' },
		busy: { type: Boolean, default: false }
	},
	data() {
		return {
			groups: [
				{ label: '概览', items: [
					{ view: 'dashboard', symbol: '⌂', label: '控制台', description: '会话与操作入口' }
				] },
				{ label: '资源', items: [
					{ view: 'ai-models', symbol: 'AI', label: '模型目录', description: '配置与状态管理' },
					{ view: 'ai-model-discovery', symbol: '↻', label: '网关模型', description: '只读发现与匹配' },
					{ view: 'ai-model-icons', symbol: '◇', label: '图标资源', description: '上传与复用' },
					{ view: 'ip2location-keys', symbol: 'IP', label: 'IP 凭据', description: '风险数据访问' }
				] },
				{ label: '证据检查', items: [
					{ view: 'mail-openai', symbol: '@', label: 'OpenAI', description: '邮件状态证据' },
					{ view: 'mail-kiro', symbol: 'K', label: 'Kiro', description: 'AWS 邮件证据' },
					{
						view: 'mail-ip2location', symbol: 'L', label: 'IP2Location', description: '注册与验证链接',
						location: { view: 'mail-ip2location', mode: 'registration' }
					}
				] }
			]
		}
	},
	methods: {
		isActive(view) {
			if (view === 'ai-models') return MODEL_VIEWS.has(this.activeView)
			return this.activeView === view
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.admin-side-navigation {
	height: 100%;
	min-height: 0;
	padding: $app-space-4 $app-space-3 calc($app-space-3 + env(safe-area-inset-bottom));
	box-sizing: border-box;
	display: flex;
	flex-direction: column;
	color: $app-text;
	@include admin-glass-chrome(true);
}

.navigation-brand {
	min-height: 88rpx;
	padding: 0 $app-space-2 $app-space-3;
	display: flex;
	align-items: center;
	gap: $app-space-2;
}

.brand-mark {
	width: 64rpx;
	height: 64rpx;
	flex: 0 0 auto;
	border-radius: 20rpx;
	display: grid;
	place-items: center;
	background: rgba($app-green, .12);
}

.brand-mark-core {
	width: 24rpx;
	height: 24rpx;
	border: 6rpx solid $app-green;
	border-radius: 50%;
}

.brand-copy,
.item-copy {
	min-width: 0;
	display: flex;
	flex-direction: column;
}

.brand-title { font-size: 28rpx; font-weight: 760; }
.brand-subtitle,
.item-description,
.group-label,
.navigation-foot { color: $app-muted; font-size: $app-font-size-caption; }
.brand-subtitle { margin-top: 2rpx; }

.navigation-scroll {
	min-height: 0;
	flex: 1;
	overflow-y: auto;
	scrollbar-width: none;
}

.navigation-scroll::-webkit-scrollbar { display: none; }
.navigation-group + .navigation-group { margin-top: $app-space-4; }
.group-label { display: block; padding: 0 $app-space-2 $app-space-1; font-weight: 650; }

.navigation-item {
	width: 100%;
	min-height: 48px;
	margin: 0;
	padding: $app-space-2;
	border: 0;
	border-radius: 18rpx;
	box-sizing: border-box;
	display: flex;
	align-items: center;
	gap: $app-space-2;
	background: transparent;
	color: $app-text;
	text-align: left;
	transition: transform $app-motion-micro $app-ease-out, background-color $app-motion-state ease, opacity $app-motion-state ease;
}

.navigation-item::after { border: 0; }
.navigation-item.active { background: rgba($app-green, .12); }

.item-symbol {
	width: 48rpx;
	height: 48rpx;
	flex: 0 0 auto;
	border-radius: 14rpx;
	display: grid;
	place-items: center;
	background: rgba($app-muted, .1);
	color: $app-muted;
	font-size: 20rpx;
	font-weight: 780;
}

.navigation-item.active .item-symbol { background: rgba($app-green, .18); color: $app-green; }
.item-label { font-size: 27rpx; font-weight: 690; line-height: 1.25; }
.item-description { margin-top: 3rpx; line-height: 1.3; }
.item-arrow { margin-left: auto; color: $app-muted; font-size: 34rpx; }
.navigation-item:focus-visible { @include admin-focus-ring; }
.navigation-item:active:not(:disabled) { transform: scale(.98); }
.navigation-item:disabled { opacity: .45; }

.navigation-foot {
	min-height: 64rpx;
	padding: $app-space-2;
	display: flex;
	align-items: center;
	gap: 12rpx;
}

.security-pulse {
	width: 12rpx;
	height: 12rpx;
	border-radius: 50%;
	background: $app-green;
	box-shadow: 0 0 0 7rpx rgba($app-green, .1);
}

@media (hover: hover) and (pointer: fine) {
	.navigation-item:not(:disabled):hover { background: rgba($app-muted, .08); cursor: pointer; }
	.navigation-item.active:not(:disabled):hover { background: rgba($app-green, .15); }
}

@media (min-width: 768px) and (max-width: 1023px) {
	.admin-side-navigation { padding-right: 12px; padding-left: 12px; }
	.navigation-brand { justify-content: center; padding-right: 0; padding-left: 0; }
	.brand-copy,
	.item-description,
	.item-arrow,
	.navigation-foot { display: none; }
	.group-label { padding: 0 0 8px; text-align: center; font-size: 11px; }
	.navigation-item { min-height: 76px; padding: 10px 4px; flex-direction: column; justify-content: center; gap: 6px; text-align: center; }
	.item-symbol { width: 34px; height: 34px; }
	.item-label { font-size: 12px; }
}

@media (max-width: 767px) {
	.admin-side-navigation { padding: 18px 14px calc(18px + env(safe-area-inset-bottom)); background: $app-surface-soft; }
	.navigation-brand { min-height: 72px; padding: 0 10px 14px; }
	.navigation-item { min-height: 56px; margin-bottom: 8px; }
	.item-label { font-size: 16px; }
}

@media (prefers-reduced-motion: reduce) {
	.navigation-item { transition: none; }
	.navigation-item:active:not(:disabled) { transform: none; }
}
</style>

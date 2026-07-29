<template>
	<view class="admin-side-navigation" aria-label="管理员功能导航">
		<view class="navigation-brand">
			<view class="brand-mark" aria-hidden="true">
				<view class="brand-mark-core" />
			</view>
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
					:key="item.path"
					class="navigation-item"
					:class="{ active: isActive(item.path) }"
					type="button"
					:disabled="busy"
					:aria-current="isActive(item.path) ? 'page' : undefined"
					@click="$emit('navigate', item.path)"
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
const normalizePath = value => String(value || '').split('?')[0].replace(/\/+$/, '')

export default {
	name: 'AdminSideNavigation',
	emits: ['navigate'],
	props: {
		activePath: { type: String, default: '/pages/index/index' },
		busy: { type: Boolean, default: false }
	},
	data() {
		return {
			groups: [
				{
					label: '概览',
					items: [
						{
							path: '/pages/index/index',
							symbol: '⌂',
							label: '控制台',
							description: '会话与操作入口'
						}
					]
				},
				{
					label: '资源',
					items: [
						{
							path: '/pages/ai-models/index',
							symbol: 'AI',
							label: '模型目录',
							description: '配置与状态管理'
						},
						{
							path: '/pages/ai-model-icons/index',
							symbol: '◇',
							label: '图标资源',
							description: '上传与复用'
						},
						{
							path: '/pages/risk/ip2location-keys',
							symbol: 'IP',
							label: 'IP 凭据',
							description: '风险数据访问'
						}
					]
				},
				{
					label: '证据检查',
					items: [
						{
							path: '/pages/mail-inspection/openai/index',
							symbol: '@',
							label: 'OpenAI',
							description: '邮件状态证据'
						},
						{
							path: '/pages/mail-inspection/kiro/index',
							symbol: 'K',
							label: 'Kiro',
							description: 'AWS 邮件证据'
						},
						{
							path: '/pages/mail-inspection/ip2location/index',
							symbol: 'L',
							label: 'IP2Location',
							description: '注册与验证链接'
						}
					]
				}
			]
		}
	},
	methods: {
		isActive(path) {
			const active = normalizePath(this.activePath)
			const candidate = normalizePath(path)
			if (candidate === '/pages/ai-models/index') {
				return active.startsWith('/pages/ai-models/')
			}
			return active === candidate
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.admin-side-navigation {
	height: 100%;
	min-height: 0;
	padding: $app-space-4 $app-space-3;
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
	display: flex;
	align-items: center;
	justify-content: center;
	background: rgba($app-green, .12);
}

.brand-mark-core {
	width: 24rpx;
	height: 24rpx;
	border: 6rpx solid $app-green;
	border-radius: 50%;
	box-shadow: inset 0 0 0 4rpx $app-surface-soft;
}

.brand-copy,
.item-copy {
	min-width: 0;
	display: flex;
	flex-direction: column;
}

.brand-title {
	font-size: 28rpx;
	font-weight: 760;
	letter-spacing: -.015em;
}

.brand-subtitle,
.item-description,
.group-label,
.navigation-foot {
	color: $app-muted;
	font-size: $app-font-size-caption;
}

.brand-subtitle {
	margin-top: 2rpx;
}

.navigation-scroll {
	min-height: 0;
	flex: 1;
	overflow-y: auto;
	scrollbar-width: none;
}

.navigation-scroll::-webkit-scrollbar {
	display: none;
}

.navigation-group + .navigation-group {
	margin-top: $app-space-4;
}

.group-label {
	display: block;
	padding: 0 $app-space-2 $app-space-1;
	font-weight: 650;
}

.navigation-item {
	width: 100%;
	min-height: 94rpx;
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
	transition:
		transform $app-motion-micro $app-ease-out,
		background-color $app-motion-state ease,
		opacity $app-motion-state ease;
}

.navigation-item::after {
	border: 0;
}

.navigation-item.active {
	background: rgba($app-green, .12);
}

.item-symbol {
	width: 48rpx;
	height: 48rpx;
	flex: 0 0 auto;
	border-radius: 14rpx;
	display: flex;
	align-items: center;
	justify-content: center;
	background: rgba($app-muted, .1);
	color: $app-muted;
	font-size: 20rpx;
	font-weight: 780;
}

.navigation-item.active .item-symbol {
	background: rgba($app-green, .18);
	color: $app-green;
}

.item-label {
	font-size: 27rpx;
	font-weight: 690;
	line-height: 1.25;
}

.item-description {
	margin-top: 3rpx;
	line-height: 1.3;
}

.item-arrow {
	margin-left: auto;
	color: $app-muted;
	font-size: 34rpx;
}

.navigation-item:focus-visible {
	@include admin-focus-ring;
}

.navigation-item:active:not(:disabled) {
	transform: scale(.98);
}

.navigation-item:disabled {
	opacity: .45;
}

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
	.navigation-item:not(:disabled):hover {
		background: rgba($app-muted, .08);
		cursor: pointer;
	}

	.navigation-item.active:not(:disabled):hover {
		background: rgba($app-green, .15);
	}
}

@media (max-width: 767px) {
	.admin-side-navigation {
		padding: 16rpx 20rpx;
		display: block;
		overflow: hidden;
		background: rgba($app-surface-soft, .94);
	}

	.navigation-brand,
	.group-label,
	.item-description,
	.item-arrow,
	.navigation-foot {
		display: none;
	}

	.navigation-scroll {
		display: flex;
		overflow-x: auto;
	}

	.navigation-group {
		display: flex;
		gap: 8rpx;
	}

	.navigation-group + .navigation-group {
		margin: 0 0 0 8rpx;
	}

	.navigation-item {
		width: auto;
		min-width: max-content;
		min-height: 76rpx;
		padding: 12rpx 16rpx;
	}

	.item-symbol {
		width: 40rpx;
		height: 40rpx;
	}

	.item-label {
		font-size: 24rpx;
	}
}

@media (prefers-reduced-motion: reduce) {
	.navigation-item {
		transition: opacity 80ms linear, background-color 80ms linear;
	}

	.navigation-item:active:not(:disabled) {
		transform: none;
	}
}

@media (prefers-reduced-transparency: reduce) {
	.admin-side-navigation {
		background: $app-surface-soft;
		-webkit-backdrop-filter: none;
		backdrop-filter: none;
	}
}

@media (prefers-contrast: more) {
	.admin-side-navigation {
		background: $app-canvas;
	}

	.navigation-item.active {
		outline: 2px solid $app-green;
	}
}
</style>

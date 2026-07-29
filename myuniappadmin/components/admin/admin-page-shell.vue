<template>
	<view class="admin-page-shell">
		<view class="shell-navigation" role="navigation" aria-label="管理员功能">
			<admin-side-navigation
				:active-path="currentPath"
				:busy="busy"
				@navigate="$emit('navigate', $event)"
			/>
		</view>
		<view class="shell-main" role="main">
			<view class="shell-content">
				<admin-page-header
					v-if="showHeader"
					:title="title"
					:description="description"
					:kicker="kicker"
				>
					<template v-if="$slots.meta" #meta>
						<slot name="meta" />
					</template>
					<template v-if="$slots.actions" #actions>
						<slot name="actions" />
					</template>
				</admin-page-header>
				<view v-if="loading" class="shell-loading" role="status" aria-live="polite">
					<view class="loading-orbit" aria-hidden="true" />
					<text>{{ loadingLabel }}</text>
				</view>
				<slot v-else />
			</view>
		</view>
	</view>
</template>

<script>
import AdminPageHeader from './admin-page-header.vue'
import AdminSideNavigation from './admin-side-navigation.vue'

export default {
	name: 'AdminPageShell',
	components: { AdminPageHeader, AdminSideNavigation },
	emits: ['navigate'],
	props: {
		currentPath: { type: String, required: true },
		title: { type: String, default: '' },
		description: { type: String, default: '' },
		kicker: { type: String, default: '' },
		busy: { type: Boolean, default: false },
		loading: { type: Boolean, default: false },
		loadingLabel: { type: String, default: '正在准备管理员工作区…' },
		showHeader: { type: Boolean, default: true }
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.admin-page-shell {
	min-height: 100vh;
	display: grid;
	grid-template-columns: 292px minmax(0, 1fr);
	background:
		radial-gradient(circle at 88% -12%, rgba($app-green, .075), transparent 31%),
		$app-canvas;
	color: $app-text;
	font-family: $app-font-family;
}

.shell-navigation {
	position: sticky;
	top: 0;
	height: 100vh;
	min-height: 0;
	z-index: 30;
}

.shell-main {
	min-width: 0;
}

.shell-content {
	width: min(1560px, 100%);
	min-height: 100vh;
	margin: 0 auto;
	padding: 52rpx 48rpx calc(64rpx + env(safe-area-inset-bottom));
	box-sizing: border-box;
}

.shell-loading {
	min-height: 480rpx;
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
	gap: $app-space-3;
	color: $app-muted;
	font-size: $app-font-size-body;
}

.loading-orbit {
	width: 56rpx;
	height: 56rpx;
	border: 5rpx solid rgba($app-green, .16);
	border-top-color: $app-green;
	border-radius: 50%;
	animation: shell-orbit .8s linear infinite;
}

@keyframes shell-orbit {
	to { transform: rotate(360deg); }
}

@media (max-width: 1023px) {
	.admin-page-shell {
		grid-template-columns: 238px minmax(0, 1fr);
	}

	.shell-content {
		padding-right: 32rpx;
		padding-left: 32rpx;
	}
}

@media (max-width: 767px) {
	.admin-page-shell {
		display: block;
		padding-top: calc(108rpx + env(safe-area-inset-top));
	}

	.shell-navigation {
		position: fixed;
		inset: 0 0 auto;
		height: calc(108rpx + env(safe-area-inset-top));
		z-index: 30;
	}

	.shell-content {
		min-height: calc(100vh - 108rpx);
		padding: 32rpx 20rpx calc(48rpx + env(safe-area-inset-bottom));
	}
}

@media (prefers-reduced-motion: reduce) {
	.loading-orbit {
		animation: none;
	}
}

@media (prefers-reduced-transparency: reduce) {
	.admin-page-shell {
		background: $app-canvas;
	}
}

@media (prefers-contrast: more) {
	.admin-page-shell {
		background: #000;
	}
}
</style>

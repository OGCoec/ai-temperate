<template>
	<view class="admin-page-shell" @keyup.esc="$emit('close-drawer')">
		<view class="mobile-app-bar" role="banner">
			<button
				ref="menuButton"
				class="menu-button"
				type="button"
				aria-label="打开管理员导航"
				aria-controls="admin-workspace-navigation"
				:aria-expanded="drawerOpen ? 'true' : 'false'"
				@click="$emit('open-drawer')"
			>
				<text aria-hidden="true">☰</text>
			</button>
			<view class="mobile-brand">
				<text>AI Temperate</text>
				<text>{{ activeLabel }}</text>
			</view>
			<view class="session-indicator" :class="sessionState.toLowerCase()" aria-hidden="true" />
		</view>

		<button
			v-if="drawerOpen"
			class="drawer-scrim"
			type="button"
			aria-label="关闭管理员导航"
			@click="$emit('close-drawer')"
		/>

		<view
			id="admin-workspace-navigation"
			class="shell-navigation"
			:class="{ 'drawer-open': drawerOpen }"
			role="navigation"
			aria-label="管理员功能"
			:aria-hidden="mobileNavigation && !drawerOpen ? 'true' : undefined"
			:inert="mobileNavigation && !drawerOpen ? '' : undefined"
		>
			<admin-side-navigation
				:active-view="currentView"
				:busy="busy"
				@navigate="handleNavigation"
			/>
		</view>

		<view class="shell-main" role="main">
			<view class="shell-content">
				<slot />
			</view>
		</view>
	</view>
</template>

<script>
import AdminSideNavigation from './admin-side-navigation.vue'

const LABELS = Object.freeze({
	dashboard: '控制台',
	'ai-models': '模型目录',
	'ai-model-discovery': '网关模型',
	'ai-model-create': '新增模型',
	'ai-model-detail': '模型详情',
	'ai-model-icons': '图标资源',
	'ip2location-keys': 'IP 凭据',
	'mail-openai': 'OpenAI 邮件检查',
	'mail-kiro': 'Kiro 邮件检查',
	'mail-ip2location': 'IP2Location 邮件检查'
})

export default {
	name: 'AdminPageShell',
	components: { AdminSideNavigation },
	emits: ['close-drawer', 'navigate', 'open-drawer'],
	props: {
		currentView: { type: String, default: 'dashboard' },
		busy: { type: Boolean, default: false },
		drawerOpen: { type: Boolean, default: false },
		sessionState: { type: String, default: 'VERIFYING_SESSION' }
	},
	data() {
		return { mobileNavigation: false }
	},
	computed: {
		activeLabel() {
			return LABELS[this.currentView] || '管理员工作台'
		}
	},
	watch: {
		drawerOpen(open, previous) {
			// #ifdef H5
			this.$nextTick(() => {
				if (open) {
					document.querySelector('#admin-workspace-navigation button:not(:disabled)')?.focus()
				} else if (previous) {
					this.$refs.menuButton?.focus?.()
				}
			})
			// #endif
		}
	},
	mounted() {
		this.syncNavigationMode()
		// #ifdef H5
		window.addEventListener('resize', this.syncNavigationMode)
		// #endif
	},
	beforeUnmount() {
		// #ifdef H5
		window.removeEventListener('resize', this.syncNavigationMode)
		// #endif
	},
	methods: {
		syncNavigationMode() {
		// #ifdef H5
		this.mobileNavigation = window.matchMedia('(max-width: 767px)').matches
		// #endif
		// #ifndef H5
		this.mobileNavigation = Number(uni.getSystemInfoSync?.().windowWidth || 0) < 768
		// #endif
	},
		handleNavigation(location) {
			this.$emit('navigate', location)
			this.$emit('close-drawer')
		}
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
	z-index: 40;
}

.shell-main {
	min-width: 0;
	height: 100vh;
	overflow-y: auto;
	overscroll-behavior: contain;
}

.shell-content {
	width: min(1560px, 100%);
	min-height: 100%;
	margin: 0 auto;
	padding: 52rpx 48rpx calc(64rpx + env(safe-area-inset-bottom));
	box-sizing: border-box;
}

.mobile-app-bar,
.drawer-scrim {
	display: none;
}

@media (min-width: 768px) and (max-width: 1023px) {
	.admin-page-shell {
		grid-template-columns: 132px minmax(0, 1fr);
	}

	.shell-content {
		padding-right: 32rpx;
		padding-left: 32rpx;
	}
}

@media (max-width: 767px) {
	.admin-page-shell {
		display: block;
		padding-top: calc(56px + env(safe-area-inset-top));
	}

	.mobile-app-bar {
		position: fixed;
		inset: 0 0 auto;
		z-index: 45;
		height: calc(56px + env(safe-area-inset-top));
		padding: env(safe-area-inset-top) max(16px, env(safe-area-inset-right)) 0 max(16px, env(safe-area-inset-left));
		box-sizing: border-box;
		display: grid;
		grid-template-columns: 48px minmax(0, 1fr) 48px;
		align-items: center;
		gap: 8px;
		@include admin-glass-chrome(true);
	}

	.menu-button {
		width: 48px;
		height: 48px;
		margin: 0;
		padding: 0;
		border: 0;
		border-radius: 14px;
		display: grid;
		place-items: center;
		background: rgba($app-muted, .1);
		color: $app-text;
		font-size: 22px;
	}

	.menu-button::after,
	.drawer-scrim::after {
		border: 0;
	}

	.menu-button:focus-visible {
		@include admin-focus-ring;
	}

	.mobile-brand {
		min-width: 0;
		display: flex;
		flex-direction: column;
	}

	.mobile-brand text:first-child {
		font-size: 15px;
		font-weight: 760;
	}

	.mobile-brand text:last-child {
		margin-top: 1px;
		color: $app-muted;
		font-size: 12px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.session-indicator {
		justify-self: center;
		width: 10px;
		height: 10px;
		border-radius: 50%;
		background: $app-muted;
		box-shadow: 0 0 0 6px rgba($app-muted, .08);
	}

	.session-indicator.ready {
		background: $app-green;
		box-shadow: 0 0 0 6px rgba($app-green, .1);
	}

	.session-indicator.transient_failure {
		background: $app-warning;
	}

	.shell-navigation {
		position: fixed;
		inset: 0 auto 0 0;
		z-index: 60;
		width: 86vw;
		max-width: 320px;
		height: 100dvh;
		padding-top: env(safe-area-inset-top);
		box-sizing: border-box;
		transform: translate3d(-102%, 0, 0);
		transition: transform 170ms $app-ease-out;
	}

	.shell-navigation.drawer-open {
		transform: translate3d(0, 0, 0);
	}

	.drawer-scrim {
		position: fixed;
		inset: 0;
		z-index: 55;
		width: 100%;
		height: 100%;
		margin: 0;
		padding: 0;
		border: 0;
		display: block;
		background: rgba(0, 0, 0, .52);
	}

	.shell-main {
		height: auto;
		min-height: calc(100vh - 56px);
		overflow: visible;
	}

	.shell-content {
		min-height: calc(100vh - 56px);
		padding: 28rpx max(20rpx, env(safe-area-inset-right)) calc(48rpx + env(safe-area-inset-bottom)) max(20rpx, env(safe-area-inset-left));
	}
}

@media (prefers-reduced-motion: reduce) {
	.shell-navigation {
		transition: none;
	}
}

@media (prefers-reduced-transparency: reduce) {
	.mobile-app-bar {
		background: $app-surface-soft;
		-webkit-backdrop-filter: none;
		backdrop-filter: none;
	}
}
</style>

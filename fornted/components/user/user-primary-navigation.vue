<template>
	<view
		class="user-primary-navigation"
		:class="{ 'is-chat-sidebar': variant === 'chat-sidebar' }"
		role="navigation"
		aria-label="主要导航"
	>
		<view class="user-primary-navigation-before">
			<slot name="before-items" />
		</view>
		<view class="user-primary-navigation-inner">
			<button
				class="user-primary-navigation-item"
				:class="{ 'is-active': activeDestination === 'chat' }"
				type="button"
				:aria-current="activeDestination === 'chat' ? 'page' : undefined"
				@click="navigate('chat')"
			>
				<uni-icons type="chat" size="22" :color="iconColor('chat')" aria-hidden="true" />
				<text>聊天</text>
			</button>
			<button
				class="user-primary-navigation-item"
				:class="{ 'is-active': activeDestination === 'models' }"
				type="button"
				:aria-current="activeDestination === 'models' ? 'page' : undefined"
				@click="navigate('models')"
			>
				<uni-icons type="list" size="22" :color="iconColor('models')" aria-hidden="true" />
				<text>模型</text>
			</button>
			<button
				class="user-primary-navigation-item"
				:class="{ 'is-active': activeDestination === 'profile' }"
				type="button"
				:aria-current="activeDestination === 'profile' ? 'page' : undefined"
				@click="navigate('profile')"
			>
				<uni-icons type="person" size="22" :color="iconColor('profile')" aria-hidden="true" />
				<text>个人</text>
			</button>
		</view>
		<view class="user-primary-navigation-after">
			<slot name="after-items" />
		</view>
	</view>
</template>

<script>
	const DESTINATIONS = Object.freeze(['chat', 'models', 'profile'])

	export default {
		props: {
			activeDestination: {
				type: String,
				required: true,
				validator: value => DESTINATIONS.includes(value)
			},
			variant: {
				type: String,
				default: 'default',
				validator: value => ['default', 'chat-sidebar'].includes(value)
			}
		},
		methods: {
			iconColor(destination) {
				return this.activeDestination === destination ? '#37d39a' : '#9ba6a0'
			},
			navigate(destination) {
				this.$emit('destination-click', destination)
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.user-primary-navigation {
		@include user-frosted-navigation;
		position: fixed;
		right: 0;
		bottom: 0;
		left: 0;
		z-index: 20;
		box-sizing: border-box;
		padding: 8px 16px calc(10px + env(safe-area-inset-bottom));
		border-top: 1px solid rgba(151, 170, 160, .18);
	}

	.user-primary-navigation-before,
	.user-primary-navigation-after {
		display: none;
	}

	.user-primary-navigation-inner {
		max-width: 420px;
		margin: 0 auto;
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 6px;
	}

	.user-primary-navigation-item {
		min-height: 52px;
		margin: 0;
		padding: 6px 12px;
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 8px;
		border: 1px solid transparent;
		border-radius: 12px;
		background: transparent;
		color: #9ba6a0;
		font-size: 14px;
		font-weight: 650;
		line-height: 1.2;
		text-align: center;
		box-sizing: border-box;
		transition: transform 90ms ease-out, background-color 140ms ease-out, color 140ms ease-out;
	}

	.user-primary-navigation-item::after { border: 0; }
	.user-primary-navigation-item.is-active {
		background: rgba(55, 211, 154, .13);
		color: #dff8ed;
	}
	.user-primary-navigation-item:active { transform: scale(.985); }
	.user-primary-navigation-item:focus-visible {
		outline: 3px solid rgba(55, 211, 154, .32);
		outline-offset: 2px;
	}

	@media (hover: hover) and (pointer: fine) {
		.user-primary-navigation-item:hover:not(.is-active) {
			background: rgba(243, 245, 244, .06);
			color: #dce5e0;
		}
	}

	@media screen and (min-width: 768px) {
		.user-primary-navigation {
			position: static;
		width: 232px;
			min-height: 100%;
		padding: 20px 12px;
			flex-shrink: 0;
			border-top: 0;
		border-right: 1px solid rgba(151, 170, 160, .18);
		}

		.user-primary-navigation-inner {
			max-width: none;
			margin: 0;
			grid-template-columns: 1fr;
		}

		.user-primary-navigation-item {
			min-height: 48px;
			justify-content: flex-start;
			padding: 8px 12px;
		}

		.user-primary-navigation.is-chat-sidebar {
			--sidebar-inline-padding: 12px;
			width: 240px;
			height: 100dvh;
			min-height: 0;
			display: flex;
			flex-direction: column;
			overflow: hidden;
		}

		.is-chat-sidebar .user-primary-navigation-before {
			display: block;
		}

		.is-chat-sidebar .user-primary-navigation-after {
			display: flex;
			flex-direction: column;
		}

		.is-chat-sidebar .user-primary-navigation-before,
		.is-chat-sidebar .user-primary-navigation-inner {
			flex-shrink: 0;
		}

		.is-chat-sidebar .user-primary-navigation-after {
			min-height: 0;
			flex: 1;
			margin-right: calc(-1 * var(--sidebar-inline-padding));
			overflow: hidden;
		}

		.is-chat-sidebar .user-primary-navigation-item.is-active {
			position: relative;
			background: rgba(243, 245, 244, .055);
			color: #e5ece8;
		}

		.is-chat-sidebar .user-primary-navigation-item.is-active::before {
			width: 3px;
			height: 20px;
			position: absolute;
			left: 0;
			border-radius: 0 3px 3px 0;
			background: #37d39a;
			content: '';
		}
	}

	@media screen and (min-width: 1100px) {
		.user-primary-navigation {
			padding-right: 16px;
			padding-left: 16px;
		}

		.user-primary-navigation-item {
			padding-right: 14px;
			padding-left: 14px;
		}

		.user-primary-navigation.is-chat-sidebar {
			--sidebar-inline-padding: 16px;
			width: 272px;
		}
	}

	@media (prefers-reduced-motion: reduce) {
		.user-primary-navigation-item { transition: none; }
	}
</style>

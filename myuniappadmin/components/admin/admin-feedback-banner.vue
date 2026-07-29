<template>
	<view
		v-if="message || $slots.default"
		class="admin-feedback-banner"
		:class="`tone-${tone}`"
		:role="tone === 'danger' || tone === 'warning' ? 'alert' : 'status'"
	>
		<view class="banner-symbol" aria-hidden="true">{{ symbol }}</view>
		<view class="banner-copy">
			<text v-if="title" class="banner-title">{{ title }}</text>
			<text v-if="message" class="banner-message">{{ message }}</text>
			<slot />
		</view>
		<button
			v-if="dismissible"
			class="banner-dismiss"
			type="button"
			aria-label="关闭通知"
			@click="$emit('dismiss')"
		>×</button>
	</view>
</template>

<script>
const TONES = ['info', 'success', 'warning', 'danger']

export default {
	name: 'AdminFeedbackBanner',
	emits: ['dismiss'],
	props: {
		tone: {
			type: String,
			default: 'info',
			validator: value => TONES.includes(value)
		},
		title: { type: String, default: '' },
		message: { type: String, default: '' },
		dismissible: { type: Boolean, default: false }
	},
	computed: {
		symbol() {
			return {
				info: 'i',
				success: '✓',
				warning: '!',
				danger: '×'
			}[this.tone]
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.admin-feedback-banner {
	min-height: 80rpx;
	padding: 16rpx 18rpx;
	border-radius: $app-radius-control;
	box-sizing: border-box;
	display: flex;
	align-items: flex-start;
	gap: 14rpx;
	background: rgba($app-teal, .08);
	color: $app-text;
	animation: feedback-arrive $app-motion-surface $app-ease-out both;
}

.banner-symbol {
	width: 40rpx;
	height: 40rpx;
	flex: 0 0 auto;
	border-radius: 50%;
	display: flex;
	align-items: center;
	justify-content: center;
	background: rgba($app-teal, .18);
	color: $app-teal;
	font-size: 24rpx;
	font-weight: 800;
}

.banner-copy {
	min-width: 0;
	flex: 1;
}

.banner-title,
.banner-message {
	display: block;
}

.banner-title {
	font-size: 26rpx;
	font-weight: 720;
}

.banner-message {
	color: inherit;
	font-size: 25rpx;
	line-height: 1.5;
	overflow-wrap: anywhere;
}

.banner-title + .banner-message {
	margin-top: 4rpx;
}

.tone-success {
	background: rgba($app-action-lime, .09);
	color: #e3ffbb;
}

.tone-success .banner-symbol {
	background: rgba($app-action-lime, .18);
	color: $app-action-lime;
}

.tone-warning {
	background: rgba($app-warning, .1);
	color: #ffe6b9;
}

.tone-warning .banner-symbol {
	background: rgba($app-warning, .19);
	color: $app-warning;
}

.tone-danger {
	background: rgba($app-danger, .11);
	color: $app-danger-text;
}

.tone-danger .banner-symbol {
	background: rgba($app-danger, .2);
	color: $app-danger-text;
}

.banner-dismiss {
	width: 54rpx;
	height: 54rpx;
	min-height: 54rpx;
	margin: -7rpx -7rpx -7rpx 0;
	padding: 0;
	border: 0;
	border-radius: 50%;
	display: flex;
	align-items: center;
	justify-content: center;
	background: transparent;
	color: currentColor;
	font-size: 34rpx;
	line-height: 1;
	opacity: .78;
	transition: transform $app-motion-micro $app-ease-out, background-color $app-motion-state ease;
}

.banner-dismiss::after {
	border: 0;
}

.banner-dismiss:focus-visible {
	@include admin-focus-ring;
}

.banner-dismiss:active {
	transform: scale(.98);
}

@keyframes feedback-arrive {
	from {
		opacity: 0;
		transform: translate3d(0, 10rpx, 0);
	}
	to {
		opacity: 1;
		transform: translate3d(0, 0, 0);
	}
}

@media (hover: hover) and (pointer: fine) {
	.banner-dismiss:hover {
		background: rgba(255, 255, 255, .08);
		cursor: pointer;
	}
}

@media (prefers-reduced-motion: reduce) {
	.admin-feedback-banner {
		animation: feedback-fade 80ms linear both;
	}

	.banner-dismiss {
		transition: opacity 80ms linear;
	}

	.banner-dismiss:active {
		transform: none;
	}
}

@keyframes feedback-fade {
	from { opacity: 0; }
	to { opacity: 1; }
}
</style>

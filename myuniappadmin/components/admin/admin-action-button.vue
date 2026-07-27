<template>
	<button
		class="admin-action-button"
		:class="[`tone-${tone}`, `size-${size}`, { block, loading }]"
		:type="type"
		:disabled="disabled || loading"
		:aria-busy="loading ? 'true' : 'false'"
		@click="handleClick"
	>
		<view v-if="loading" class="loading-indicator" aria-hidden="true" />
		<view class="button-content">
			<slot />
		</view>
	</button>
</template>

<script>
const TONES = ['neutral', 'teal', 'amber', 'lime', 'orange', 'danger']
const SIZES = ['compact', 'regular', 'large']

export default {
	name: 'AdminActionButton',
	emits: ['click'],
	props: {
		tone: {
			type: String,
			default: 'neutral',
			validator: value => TONES.includes(value)
		},
		size: {
			type: String,
			default: 'regular',
			validator: value => SIZES.includes(value)
		},
		type: {
			type: String,
			default: 'button'
		},
		block: {
			type: Boolean,
			default: false
		},
		loading: {
			type: Boolean,
			default: false
		},
		disabled: {
			type: Boolean,
			default: false
		}
	},
	methods: {
		handleClick(event) {
			if (this.disabled || this.loading) return
			this.$emit('click', event)
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.admin-action-button {
	min-height: 88rpx;
	margin: 0;
	padding: 0 26rpx;
	border: 1px solid transparent;
	border-radius: $app-radius-control;
	box-sizing: border-box;
	display: inline-flex;
	align-items: center;
	justify-content: center;
	gap: 12rpx;
	color: $app-text;
	font-size: 22rpx;
	font-weight: 720;
	line-height: 1;
	text-align: center;
	white-space: nowrap;
	touch-action: manipulation;
	transition:
		transform 120ms ease-out,
		background-color 180ms ease,
		border-color 180ms ease,
		color 180ms ease,
		opacity 180ms ease;
}

.admin-action-button::after {
	border: 0;
}

.button-content {
	min-width: 0;
	display: inline-flex;
	align-items: center;
	justify-content: center;
	line-height: 1;
}

.admin-action-button.block {
	width: 100%;
	display: flex;
}

.admin-action-button.size-compact {
	min-height: 72rpx;
	padding: 0 20rpx;
	font-size: 20rpx;
}

.admin-action-button.size-large {
	min-height: 96rpx;
	padding: 0 32rpx;
	font-size: 23rpx;
}

.tone-neutral {
	border-color: $app-border;
	background: $app-raised;
	color: $app-text;
}

.tone-teal {
	border-color: rgba($app-action-teal, .5);
	background: rgba($app-action-teal, .14);
	color: #dfffff;
}

.tone-amber {
	background: $app-action-amber;
	color: #1a1408;
}

.tone-lime {
	background: $app-action-lime;
	color: #101706;
}

.tone-orange {
	border-color: rgba($app-action-orange, .62);
	background: rgba($app-action-orange, .16);
	color: #ffd8ad;
}

.tone-danger {
	border-color: rgba($app-danger, .62);
	background: rgba($app-danger, .16);
	color: $app-danger-text;
}

.admin-action-button:active:not(:disabled) {
	transform: scale(.98);
}

.admin-action-button:focus-visible {
	outline: 2px solid $app-focus;
	outline-offset: 3px;
}

.admin-action-button:disabled {
	opacity: .46;
	cursor: not-allowed;
}

.admin-action-button.loading:disabled {
	opacity: .78;
}

.loading-indicator {
	width: 22rpx;
	height: 22rpx;
	border: 3rpx solid currentColor;
	border-right-color: transparent;
	border-radius: 50%;
	box-sizing: border-box;
	animation: action-spin .7s linear infinite;
}

@media (hover: hover) and (pointer: fine) {
	.admin-action-button:not(:disabled) {
		cursor: pointer;
	}

	.tone-neutral:hover {
		border-color: rgba($app-action-teal, .38);
		background: #19242a;
	}

	.tone-teal:hover {
		background: rgba($app-action-teal, .22);
	}

	.tone-amber:hover {
		background: #ffd177;
	}

	.tone-lime:hover {
		background: #b9eb5c;
	}

	.tone-orange:hover {
		background: rgba($app-action-orange, .24);
	}

	.tone-danger:hover {
		background: rgba($app-danger, .24);
	}
}

@media (pointer: coarse) {
	.admin-action-button {
		min-height: 96rpx;
	}

	.admin-action-button.size-compact {
		min-height: 88rpx;
	}
}

@media (prefers-reduced-motion: reduce) {
	.admin-action-button {
		transition: none;
	}

	.admin-action-button:active:not(:disabled) {
		transform: none;
	}

	.loading-indicator {
		animation-duration: 1.4s;
	}
}

@keyframes action-spin {
	to {
		transform: rotate(360deg);
	}
}
</style>

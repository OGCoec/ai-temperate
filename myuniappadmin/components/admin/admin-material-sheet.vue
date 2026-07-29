<template>
	<view
		v-if="rendered"
		class="material-sheet-layer"
		:class="[
			`placement-${placement}`,
			{ open: modelValue, 'css-motion-fallback': !springMotion }
		]"
		:style="layerStyle"
	>
		<button
			class="sheet-backdrop"
			type="button"
			aria-label="关闭面板"
			:style="backdropStyle"
			@click="requestBackdropClose"
		/>
		<view
			class="material-sheet"
			:style="sheetStyle"
			role="dialog"
			aria-modal="true"
			:aria-labelledby="titleId"
		>
			<view class="sheet-handle" aria-hidden="true" />
			<view class="sheet-header">
				<view class="sheet-heading">
					<text :id="titleId" class="sheet-title">{{ title }}</text>
					<text v-if="description" class="sheet-description">{{ description }}</text>
				</view>
				<button class="sheet-close" type="button" aria-label="关闭面板" @click="requestClose">×</button>
			</view>
			<scroll-view class="sheet-body" scroll-y>
				<slot />
			</scroll-view>
			<view v-if="$slots.footer" class="sheet-footer">
				<slot name="footer" />
			</view>
		</view>
	</view>
</template>

<script>
import {
	ADMIN_MOTION_PRESETS,
	adminSupportsSpringMotion,
	animateAdminSpring,
	cancelAdminMotion
} from '@/common/admin/admin-motion.js'

let nextSheetId = 1

export default {
	name: 'AdminMaterialSheet',
	emits: ['update:modelValue', 'close'],
	props: {
		modelValue: { type: Boolean, default: false },
		title: { type: String, required: true },
		description: { type: String, default: '' },
		placement: {
			type: String,
			default: 'responsive',
			validator: value => ['responsive', 'right', 'bottom'].includes(value)
		},
		closeOnBackdrop: { type: Boolean, default: true }
	},
	data() {
		return {
			rendered: this.modelValue,
			motionProgress: this.modelValue ? 1 : 0,
			compactViewport: false,
			springMotion: adminSupportsSpringMotion(),
			fallbackTimer: null,
			titleId: `admin-material-sheet-title-${nextSheetId++}`
		}
	},
	computed: {
		layerStyle() {
			return {
				pointerEvents: this.motionProgress > 0 ? 'auto' : 'none'
			}
		},
		backdropStyle() {
			return { opacity: String(this.motionProgress) }
		},
		sheetStyle() {
			const progress = Math.max(0, Math.min(1, this.motionProgress))
			const offset = (1 - progress) * 100
			const bottom = this.placement === 'bottom'
				|| (this.placement === 'responsive' && this.compactViewport)
			return {
				opacity: String(.84 + progress * .16),
				transform: bottom
					? `translate3d(0, ${offset}%, 0) scale(${.985 + progress * .015})`
					: `translate3d(${offset}%, 0, 0) scale(${.985 + progress * .015})`
			}
		}
	},
	watch: {
		modelValue(value) {
			this.animateVisibility(value)
		}
	},
	mounted() {
		this.updateViewportMode()
		if (typeof document !== 'undefined') document.addEventListener('keydown', this.handleKeydown)
		if (typeof window !== 'undefined') window.addEventListener('resize', this.updateViewportMode, { passive: true })
		if (this.modelValue) this.animateVisibility(true)
	},
	beforeDestroy() {
		this.teardown()
	},
	beforeUnmount() {
		this.teardown()
	},
	methods: {
		updateViewportMode() {
			if (typeof window !== 'undefined') {
				this.compactViewport = window.innerWidth <= 767
				return
			}
			try {
				this.compactViewport = Number(uni.getSystemInfoSync()?.windowWidth || 0) <= 767
			} catch (error) {
				this.compactViewport = false
			}
		},
		animateVisibility(open) {
			if (open) this.rendered = true
			if (this.fallbackTimer) {
				clearTimeout(this.fallbackTimer)
				this.fallbackTimer = null
			}
			if (!this.springMotion) {
				this.$nextTick(() => {
					this.motionProgress = open ? 1 : 0
					if (!open) {
						this.fallbackTimer = setTimeout(() => {
							this.rendered = false
							this.fallbackTimer = null
						}, 320)
					}
				})
				return
			}
			this.$nextTick(() => {
				animateAdminSpring({
					owner: this,
					from: this.motionProgress,
					to: open ? 1 : 0,
					preset: open ? ADMIN_MOTION_PRESETS.sheet : ADMIN_MOTION_PRESETS.quiet,
					precision: .002,
					onUpdate: value => {
						this.motionProgress = Math.max(0, Math.min(1, value))
					},
					onComplete: () => {
						this.motionProgress = open ? 1 : 0
						if (!open) this.rendered = false
					}
				})
			})
		},
		requestBackdropClose() {
			if (this.closeOnBackdrop) this.requestClose()
		},
		requestClose() {
			this.$emit('update:modelValue', false)
			this.$emit('close')
		},
		handleKeydown(event) {
			if (this.modelValue && event?.key === 'Escape') this.requestClose()
		},
		teardown() {
			cancelAdminMotion(this)
			if (this.fallbackTimer) clearTimeout(this.fallbackTimer)
			this.fallbackTimer = null
			if (typeof document !== 'undefined') document.removeEventListener('keydown', this.handleKeydown)
			if (typeof window !== 'undefined') window.removeEventListener('resize', this.updateViewportMode)
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.material-sheet-layer {
	position: fixed;
	inset: 0;
	z-index: 80;
	display: flex;
	align-items: stretch;
	justify-content: flex-end;
}

.sheet-backdrop {
	position: absolute;
	inset: 0;
	width: 100%;
	height: 100%;
	margin: 0;
	padding: 0;
	border: 0;
	border-radius: 0;
	background: $app-scrim;
}

.sheet-backdrop::after {
	border: 0;
}

.material-sheet {
	position: relative;
	z-index: 1;
	width: min(760rpx, 92vw);
	height: 100%;
	padding: calc(24rpx + env(safe-area-inset-top)) 28rpx calc(28rpx + env(safe-area-inset-bottom));
	box-sizing: border-box;
	display: flex;
	flex-direction: column;
	color: $app-text;
	@include admin-glass-chrome(true);
	-webkit-backdrop-filter: blur($app-blur-sheet) saturate(150%);
	backdrop-filter: blur($app-blur-sheet) saturate(150%);
	box-shadow: $app-shadow-sheet;
	will-change: transform, opacity;
}

.css-motion-fallback .material-sheet {
	transition:
		transform $app-motion-surface $app-ease-out,
		opacity $app-motion-state ease;
}

.css-motion-fallback .sheet-backdrop {
	transition: opacity $app-motion-state ease;
}

.sheet-handle {
	display: none;
}

.sheet-header {
	padding-bottom: $app-space-3;
	display: flex;
	align-items: flex-start;
	gap: $app-space-3;
}

.sheet-heading {
	min-width: 0;
	flex: 1;
}

.sheet-title,
.sheet-description {
	display: block;
}

.sheet-title {
	font-size: 38rpx;
	font-weight: 760;
	letter-spacing: -.02em;
}

.sheet-description {
	margin-top: 8rpx;
	color: $app-muted;
	font-size: $app-font-size-body;
	line-height: 1.5;
}

.sheet-close {
	width: 68rpx;
	height: 68rpx;
	min-height: 68rpx;
	margin: 0;
	padding: 0;
	border: 0;
	border-radius: 50%;
	display: flex;
	align-items: center;
	justify-content: center;
	background: rgba($app-muted, .1);
	color: $app-text;
	font-size: 38rpx;
	transition: transform $app-motion-micro $app-ease-out, background-color $app-motion-state ease;
}

.sheet-close::after {
	border: 0;
}

.sheet-close:focus-visible {
	@include admin-focus-ring;
}

.sheet-close:active {
	transform: scale(.98);
}

.sheet-body {
	min-height: 0;
	flex: 1;
}

.sheet-footer {
	padding-top: $app-space-3;
	display: flex;
	justify-content: flex-end;
	gap: $app-space-2;
}

.placement-bottom {
	align-items: flex-end;
}

.placement-bottom .material-sheet {
	width: 100%;
	height: min(82vh, 1100rpx);
	border-radius: $app-radius-sheet $app-radius-sheet 0 0;
}

.placement-bottom .sheet-handle {
	width: 72rpx;
	height: 8rpx;
	margin: -6rpx auto 22rpx;
	border-radius: 999px;
	display: block;
	background: rgba($app-muted, .38);
}

@media (hover: hover) and (pointer: fine) {
	.sheet-close:hover {
		background: rgba($app-muted, .18);
		cursor: pointer;
	}
}

@media (max-width: 767px) {
	.placement-responsive {
		align-items: flex-end;
	}

	.placement-responsive .material-sheet {
		width: 100%;
		height: min(86vh, 1200rpx);
		border-radius: $app-radius-sheet $app-radius-sheet 0 0;
	}

	.placement-responsive .sheet-handle {
		width: 72rpx;
		height: 8rpx;
		margin: -6rpx auto 22rpx;
		border-radius: 999px;
		display: block;
		background: rgba($app-muted, .38);
	}
}

@media (prefers-reduced-motion: reduce) {
	.material-sheet,
	.sheet-backdrop {
		transition: opacity 80ms linear;
		transform: none !important;
	}

	.sheet-close {
		transition: opacity 80ms linear;
	}

	.sheet-close:active {
		transform: none;
	}
}

@media (prefers-reduced-transparency: reduce) {
	.material-sheet {
		background: $app-surface-elevated;
		-webkit-backdrop-filter: none;
		backdrop-filter: none;
	}
}

@media (prefers-contrast: more) {
	.material-sheet {
		background: $app-canvas;
		border: 2px solid $app-text;
	}
}
</style>

<template>
	<view
		class="user-thinking-orb"
		:class="{ 'is-medium': normalizedSize === 40, 'is-large': normalizedSize === 64, 'is-reduced': effectiveMotionReduced }"
		:style="orbSizeStyle"
		:aria-label="ariaLabel || defaultLabel"
		role="img"
		:orb-config="orbConfig"
		:change:orb-config="orbRender.update"
	>
		<canvas
			class="user-thinking-orb-canvas"
			:hidpi="false"
			:style="orbSizeStyle"
			aria-hidden="true"
		></canvas>
	</view>
</template>

<script>
	import {
		createAiMotionPreferenceController,
		AI_MOTION_PREFERENCES
	} from '@/common/ui/ai-motion-preference.js'

	const LABELS = Object.freeze({
		working: '正在准备回答',
		searching: '正在联网搜索',
		solving: '正在思考',
		listening: '正在聆听',
		connecting: '正在连接',
		weaving: '正在整理',
		composing: '正在生成',
		breathing: '正在等待',
		shaping: '正在完成'
	})

	export default {
		name: 'UserThinkingOrb',
		props: {
			state: { type: String, default: 'working' },
			size: { type: Number, default: 20 },
			speed: { type: Number, default: 1 },
			paused: { type: Boolean, default: false },
			ariaLabel: { type: String, default: '' },
			reduced: { type: Boolean, default: null }
		},
		data() {
			return {
				localMotionReduced: false,
				motionPreference: AI_MOTION_PREFERENCES.SYSTEM,
				motionController: null
			}
		},
		computed: {
			defaultLabel() { return LABELS[this.state] || LABELS.working },
			normalizedSize() {
				return this.size === 64 ? 64 : this.size === 40 ? 40 : 20
			},
			orbSizeStyle() {
				const size = `${this.normalizedSize}px`
				return {
					width: size,
					height: size,
					minWidth: size,
					minHeight: size,
					maxWidth: size,
					maxHeight: size,
					flexBasis: size
				}
			},
			effectiveMotionReduced() {
				return this.reduced === null ? this.localMotionReduced : this.reduced
			},
			orbConfig() {
				return {
					state: this.state,
					size: this.normalizedSize,
					speed: Number.isFinite(Number(this.speed)) ? Number(this.speed) : 1,
					paused: Boolean(this.paused),
					reduced: this.effectiveMotionReduced,
					dark: true
				}
			}
		},
		mounted() {
			if (this.reduced !== null) return
			this.motionController = createAiMotionPreferenceController(snapshot => {
				this.localMotionReduced = snapshot.reduced
				this.motionPreference = snapshot.preference
			})
		},
		beforeUnmount() {
			this.motionController?.destroy?.()
			this.motionController = null
		}
	}
</script>

<script module="orbRender" lang="renderjs">
	import orbRender from './user-thinking-orb-render.js'
	export default orbRender
</script>

<style lang="scss">
	.user-thinking-orb { width: 20px; min-width: 20px; height: 20px; min-height: 20px; padding: 0; flex: 0 0 20px; flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; overflow: visible; border: 0; box-sizing: content-box; line-height: 0; }
	.user-thinking-orb.is-medium { width: 40px; min-width: 40px; height: 40px; min-height: 40px; flex-basis: 40px; }
	.user-thinking-orb.is-large { width: 64px; min-width: 64px; height: 64px; min-height: 64px; flex-basis: 64px; }
	.user-thinking-orb-canvas { width: 20px; min-width: 20px; height: 20px; min-height: 20px; padding: 0; flex: 0 0 20px; flex-shrink: 0; display: block; overflow: visible; border: 0; box-sizing: content-box; line-height: 0; pointer-events: none; }
	.user-thinking-orb.is-medium .user-thinking-orb-canvas { width: 40px; min-width: 40px; height: 40px; min-height: 40px; flex-basis: 40px; }
	.user-thinking-orb.is-large .user-thinking-orb-canvas { width: 64px; min-width: 64px; height: 64px; min-height: 64px; flex-basis: 64px; }
</style>

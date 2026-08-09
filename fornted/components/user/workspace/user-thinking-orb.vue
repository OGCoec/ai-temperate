<template>
	<view
		class="user-thinking-orb"
		:class="{ 'is-large': size === 64, 'is-reduced': effectiveMotionReduced }"
		:aria-label="ariaLabel || defaultLabel"
		role="img"
		:orb-config="orbConfig"
		:change:orb-config="orbRender.update"
	>
		<canvas class="user-thinking-orb-canvas" aria-hidden="true"></canvas>
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
			effectiveMotionReduced() {
				return this.reduced === null ? this.localMotionReduced : this.reduced
			},
			orbConfig() {
				return {
					state: this.state,
					size: this.size === 64 ? 64 : 20,
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
	.user-thinking-orb { width: 20px; height: 20px; flex: 0 0 20px; display: inline-flex; align-items: center; justify-content: center; overflow: visible; }
	.user-thinking-orb.is-large { width: 64px; height: 64px; flex-basis: 64px; }
	.user-thinking-orb-canvas { width: 20px; height: 20px; display: block; pointer-events: none; }
	.user-thinking-orb.is-large .user-thinking-orb-canvas { width: 64px; height: 64px; }
</style>

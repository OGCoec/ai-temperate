<template>
	<view
		class="user-voice-waveform"
		:waveform-config="waveformConfig"
		:change:waveform-config="waveformRender.update"
	>
		<!-- #ifdef H5 -->
		<view
			class="user-voice-waveform-native-host"
			aria-hidden="true"
		></view>
		<!-- #endif -->
		<!-- #ifdef APP-PLUS -->
		<canvas
			class="user-voice-waveform-canvas"
			:hidpi="false"
			aria-hidden="true"
		></canvas>
		<!-- #endif -->
	</view>
</template>

<script>
	import { isVoiceWaveformDebugEnabled } from './user-voice-waveform-debug.js'

	let H5_VOICE_WAVEFORM_DEBUG_ENABLED = false
	// #ifdef H5
	H5_VOICE_WAVEFORM_DEBUG_ENABLED = isVoiceWaveformDebugEnabled(
		typeof window !== 'undefined' ? window.location?.search : '')
	// #endif

	export default {
		name: 'UserVoiceWaveform',
		props: {
			state: { type: String, default: 'IDLE' },
			sessionEpoch: { type: Number, default: 0 },
			packet: { type: Object, default: null },
			reduced: { type: Boolean, default: false }
		},
		computed: {
			waveformConfig() {
				const levels = Array.isArray(this.packet?.levels)
					? this.packet.levels.slice(0, 5).map(value =>
						Math.max(0, Math.min(1, Number(value) || 0)))
					: []
				// 通过条件编译决定平台 profile，不暴露为公共 Prop。
				let profile = 'h5'
				// #ifdef APP-PLUS
				profile = 'android'
				// #endif
				return {
					state: String(this.state || 'IDLE').toUpperCase(),
					sessionEpoch: Number(this.sessionEpoch),
					reduced: Boolean(this.reduced),
					debug: H5_VOICE_WAVEFORM_DEBUG_ENABLED,
					profile,
					packet: levels.length ? {
						epoch: Number(this.packet.epoch),
						sequence: Number(this.packet.sequence),
						publishedAtMs: Number(this.packet.publishedAtMs),
						levels
					} : null
				}
			}
		}
	}
</script>

<script module="waveformRender" lang="renderjs">
	import waveformRender from './user-voice-waveform-render.js'
	export default waveformRender
</script>

<style lang="scss">
	.user-voice-waveform {
		width: 100%;
		min-width: 48px;
		height: 24px;
		min-height: 24px;
		display: block;
		overflow: hidden;
		line-height: 0;
		pointer-events: none;
	}

	.user-voice-waveform-native-host,
	.user-voice-waveform-native-canvas,
	.user-voice-waveform-canvas {
		width: 100%;
		height: 24px;
		min-height: 24px;
		display: block;
	}
</style>

<template>
	<view
		class="user-voice-waveform"
		:waveform-config="waveformConfig"
		:change:waveform-config="waveformRender.update"
	>
		<canvas
			class="user-voice-waveform-canvas"
			:hidpi="false"
			aria-hidden="true"
		></canvas>
	</view>
</template>

<script>
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
				return {
					state: String(this.state || 'IDLE').toUpperCase(),
					sessionEpoch: Number(this.sessionEpoch),
					reduced: Boolean(this.reduced),
					packet: levels.length ? {
						epoch: Number(this.packet.epoch),
						sequence: Number(this.packet.sequence),
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

	.user-voice-waveform-canvas {
		width: 100%;
		height: 24px;
		min-height: 24px;
		display: block;
	}
</style>

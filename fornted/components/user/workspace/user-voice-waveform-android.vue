<template>
	<view
		class="user-voice-waveform-android"
		:class="{ 'is-reduced': reduced }"
		:style="waveformStyle"
		aria-hidden="true"
	>
		<view
			class="user-voice-waveform-android-viewport"
		>
			<view
				class="user-voice-waveform-android-track"
				:class="{ 'is-advancing': trackAdvancing && !reduced }"
			>
				<view
					v-for="bar in renderedBars"
					:key="bar.id"
					class="user-voice-waveform-android-bar"
					:class="{ 'is-pending': bar.pending }"
					:style="{
						height: `${bar.height}px`,
						backgroundColor: bar.color
					}"
				></view>
			</view>
		</view>
	</view>
</template>

<script>
	import { markRaw } from 'vue'
	import {
		createAndroidVoiceWaveformController,
		presentAndroidVoiceWaveformBar,
		resolveAndroidVoiceWaveformCapacity
	} from '@/common/voice/voice-waveform-android-controller.js'
	import {
		VOICE_WAVEFORM_BAR_GAP,
		VOICE_WAVEFORM_BAR_PITCH,
		VOICE_WAVEFORM_BAR_WIDTH,
		VOICE_WAVEFORM_HEIGHT
	} from '@/common/voice/voice-waveform-presentation.js'

	export default {
		name: 'UserVoiceWaveformAndroid',
		props: {
			state: { type: String, default: 'IDLE' },
			sessionEpoch: { type: Number, default: 0 },
			packet: { type: Object, default: null },
			reduced: { type: Boolean, default: false }
		},
		data() {
			return {
				controller: null,
				controllerEpoch: -1,
				controllerRunning: false,
				renderedBars: [],
				latestSnapshot: null,
				trackAdvancing: false,
				animationRevision: 0,
				visibleCapacity: 192,
				windowResizeHandler: null
			}
		},
		computed: {
			waveformStyle() {
				return {
					'--voice-waveform-height': `${VOICE_WAVEFORM_HEIGHT}px`,
					'--voice-waveform-bar-width': `${VOICE_WAVEFORM_BAR_WIDTH}px`,
					'--voice-waveform-bar-gap': `${VOICE_WAVEFORM_BAR_GAP}px`,
					'--voice-waveform-bar-pitch': `${VOICE_WAVEFORM_BAR_PITCH}px`,
					'--voice-waveform-visible-width': `${this.visibleCapacity * VOICE_WAVEFORM_BAR_PITCH}px`
				}
			}
		},
		watch: {
			state: {
				immediate: true,
				handler() { this.syncControllerState() }
			},
			sessionEpoch() {
				this.syncControllerState()
			},
			packet(packet) {
				if (!this.controllerRunning) return
				try {
					this.controller?.accept?.(packet)
				} catch (_) {
					this.stopVisualization()
				}
			},
			reduced() {
				if (this.latestSnapshot) this.applySnapshot(this.latestSnapshot)
			}
		},
		mounted() {
			this.measureVisibleCapacity()
			if (typeof uni !== 'undefined' && typeof uni.onWindowResize === 'function') {
				this.windowResizeHandler = () => this.measureVisibleCapacity()
				uni.onWindowResize(this.windowResizeHandler)
			}
		},
		beforeUnmount() {
			try { this.controller?.dispose?.() } catch (_) {}
			this.controller = null
			this.controllerRunning = false
			this.animationRevision += 1
			this.trackAdvancing = false
			if (this.windowResizeHandler
				&& typeof uni !== 'undefined' && typeof uni.offWindowResize === 'function') {
				uni.offWindowResize(this.windowResizeHandler)
			}
			this.windowResizeHandler = null
		},
		methods: {
			ensureController() {
				if (this.controller) return this.controller
				this.controller = markRaw(createAndroidVoiceWaveformController({
					capacity: this.visibleCapacity,
					onSnapshot: snapshot => this.applySnapshot(snapshot)
				}))
				return this.controller
			},

			applySnapshot(snapshot) {
				try {
					if (!snapshot || !Array.isArray(snapshot.settledBars)
						|| !Array.isArray(snapshot.movingBars)) return
					this.latestSnapshot = snapshot
					const revision = ++this.animationRevision
					this.trackAdvancing = false
					const source = this.reduced ? snapshot.settledBars : snapshot.movingBars
					const pendingBarId = this.reduced
						? null
						: snapshot.movingBars[snapshot.movingBars.length - 1]?.id
					this.renderedBars = source.map(bar => ({
						...presentAndroidVoiceWaveformBar(bar),
						pending: bar.id === pendingBarId
					}))
					if (this.reduced
						|| String(this.state || '').toUpperCase() !== 'RECORDING') return
					this.$nextTick(() => {
						if (revision !== this.animationRevision
							|| !this.controllerRunning || this.reduced) return
						this.trackAdvancing = true
					})
				} catch (_) {
					this.stopVisualization()
				}
			},

			stopVisualization() {
				try { this.controller?.stop?.() } catch (_) {}
				this.controllerRunning = false
				this.animationRevision += 1
				this.trackAdvancing = false
			},

			syncControllerState() {
				try {
					const controller = this.ensureController()
					const recording = String(this.state || '').toUpperCase() === 'RECORDING'
					const epoch = Number(this.sessionEpoch)
					if (recording) {
						if (!this.controllerRunning || this.controllerEpoch !== epoch) {
							this.controllerEpoch = epoch
							this.controllerRunning = controller.start(epoch) === true
							if (this.controllerRunning && this.packet) controller.accept(this.packet)
						}
						return
					}
					if (this.controllerRunning) controller.stop()
					else controller.reset(epoch)
					this.controllerEpoch = epoch
					this.controllerRunning = false
					this.animationRevision += 1
					this.trackAdvancing = false
				} catch (_) {
					this.stopVisualization()
				}
			},

			measureVisibleCapacity() {
				if (typeof uni === 'undefined' || typeof uni.createSelectorQuery !== 'function') return
				try {
					const query = uni.createSelectorQuery().in(this)
					query.select('.user-voice-waveform-android').boundingClientRect(rect => {
						if (!(Number(rect?.width) > 0)) return
						this.visibleCapacity = resolveAndroidVoiceWaveformCapacity(rect.width)
						this.controller?.setCapacity?.(this.visibleCapacity)
					}).exec()
				} catch (_) {
					// 尺寸测量失败时保持当前容量，可视反馈不会影响录音主链路。
				}
			}
		}
	}
</script>

<style scoped>
	.user-voice-waveform-android {
		width: 100%;
		min-width: 0;
		height: var(--voice-waveform-height);
		min-height: var(--voice-waveform-height);
		overflow: hidden;
		pointer-events: none;
	}

	.user-voice-waveform-android-viewport {
		width: var(--voice-waveform-visible-width);
		max-width: 100%;
		height: var(--voice-waveform-height);
		overflow: hidden;
	}

	.user-voice-waveform-android-track {
		width: max-content;
		min-width: 100%;
		height: var(--voice-waveform-height);
		display: flex;
		align-items: center;
		justify-content: flex-start;
		transform: translate3d(0, 0, 0);
		transition-property: transform;
		transition-duration: 0ms;
		transition-timing-function: linear;
		will-change: auto;
	}

	.user-voice-waveform-android-track.is-advancing {
		transform: translate3d(calc(-1 * var(--voice-waveform-bar-pitch)), 0, 0);
		transition-duration: 300ms;
		will-change: transform;
	}

	.user-voice-waveform-android-bar {
		width: var(--voice-waveform-bar-width);
		min-width: var(--voice-waveform-bar-width);
		max-height: 20px;
		margin-right: var(--voice-waveform-bar-gap);
		flex: 0 0 var(--voice-waveform-bar-width);
		border-radius: 999px;
	}

	.user-voice-waveform-android-bar.is-pending {
		visibility: hidden;
	}

	.user-voice-waveform-android.is-reduced .user-voice-waveform-android-track {
		transform: translate3d(0, 0, 0);
		transition-duration: 0ms;
		will-change: auto;
	}
</style>

<template>
	<view class="probe-page" :bridge-seed="bridgeSeed" :change:bridge-seed="webRtcBridge.install">
		<web-view
			id="webrtcProbe"
			src="/hybrid/html/webrtc-probe.html"
			:update-title="false"
			@message="handleMessage"
		/>
	</view>
</template>

<script>
	import {
		cancelAndroidWebRtcProbe,
		completeAndroidWebRtcProbe,
		takeAndroidWebRtcProbeConfiguration
	} from '@/common/auth/webrtc-verification.js'

	export default {
		data() {
			return {
				bridgeSeed: 'install',
				configuration: null,
				accepted: false,
				started: false,
				startTimer: null,
				context: null
			}
		},
		onLoad() {
			this.configuration = takeAndroidWebRtcProbeConfiguration()
			if (!this.configuration) {
				uni.navigateBack()
			}
		},
		onReady() {
			this.context = uni.createWebviewContext('webrtcProbe', this)
			this.startTimer = setTimeout(() => this.sendConfiguration(), 350)
		},
		onUnload() {
			if (this.startTimer) clearTimeout(this.startTimer)
			if (this.configuration && !this.accepted) {
				cancelAndroidWebRtcProbe(this.configuration.nonce)
			}
		},
		methods: {
			handleMessage(event) {
				const payload = event?.detail?.data
				const messages = Array.isArray(payload) ? payload : [payload]
				messages.forEach(message => this.acceptMessage(message))
			},
			handleBridgePayload(serialized) {
				try {
					this.acceptMessage(typeof serialized === 'string'
						? JSON.parse(serialized)
						: serialized)
				} catch (_) {
					// 非法桥接消息不参与当前随机 Nonce 的首次结果竞争。
				}
			},
			acceptMessage(message) {
				if (!message || !this.configuration) return
				if (message.type === 'ready') {
					this.sendConfiguration()
					return
				}
				if (message.type !== 'result'
					|| this.accepted
					|| message.nonce !== this.configuration.nonce) return
				this.accepted = true
				if (this.startTimer) clearTimeout(this.startTimer)
				this.context?.stop?.()
				completeAndroidWebRtcProbe(
					message.nonce,
					Array.isArray(message.webRtcIps) ? message.webRtcIps : []
				)
				uni.navigateBack()
			},
			sendConfiguration() {
				if (!this.configuration || this.accepted || !this.context) return
				this.started = true
				this.context.evalJS(
					`window.startWebRtcProbe(${JSON.stringify(this.configuration)})`
				)
			}
		}
	}
</script>

<script module="webRtcBridge" lang="renderjs">
	export default {
		methods: {
			install(value, oldValue, ownerInstance) {
				window.__AIT_WEBRTC_POST_MESSAGE__ = serialized => {
					ownerInstance.callMethod('handleBridgePayload', serialized)
				}
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.probe-page {
		@include user-safe-viewport;
		background: #0b0d0c;
	}
</style>

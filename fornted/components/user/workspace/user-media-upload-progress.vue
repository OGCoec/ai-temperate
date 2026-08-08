<template>
	<view
		v-if="progress && visible"
		class="media-upload-progress"
		:class="{
			'is-failed': progress.state === 'FAILED',
			'is-fading': fading,
			'is-indeterminate': progress.percent == null && progress.state !== 'FAILED'
		}"
		:role="progress.percent == null ? 'status' : 'progressbar'"
		:aria-label="statusLabel"
		:aria-busy="progress.percent == null ? 'true' : undefined"
		:aria-valuemin="progress.percent == null ? undefined : '0'"
		:aria-valuemax="progress.percent == null ? undefined : '100'"
		:aria-valuenow="progress.percent == null ? undefined : String(progress.percent)"
	>
		<view class="media-upload-progress-track" aria-hidden="true">
			<view
				class="media-upload-progress-fill"
				:style="progress.percent == null ? null : { width: `${progress.percent}%` }"
			></view>
		</view>
		<text class="media-upload-progress-label" aria-hidden="true">{{ statusLabel }}</text>
		<text class="media-upload-progress-announcement" aria-live="polite">{{ announcement }}</text>
	</view>
</template>

<script>
	/**
	 * \u5728\u5A92\u4F53\u5361\u7247\u4E0B\u65B9\u5C55\u793A\u771F\u5B9E OSS \u4E0A\u4F20\u8FDB\u5EA6\u3002
	 */
	export default {
		props: {
			progress: { type: Object, default: null }
		},
		emits: ['dismiss'],
		data() {
			return {
				visible: true,
				fading: false,
				completionTimer: null,
				fadeTimer: null,
				announcement: '',
				announcementStateKey: ''
			}
		},
		computed: {
			statusLabel() {
				if (!this.progress) return ''
				if (this.progress.state === 'FAILED') return '\u4E0A\u4F20\u5230 OSS \u5931\u8D25'
				if (this.progress.state === 'VERIFYING') return '\u6B63\u5728\u6821\u9A8C OSS'
				if (this.progress.state === 'COMPLETED') return '\u5DF2\u4E0A\u4F20\u5230 OSS\uFF0C100%'
				const prefix = this.progress.attempt > 1
					? `\u6B63\u5728\u91CD\u8BD5\u4E0A\u4F20\uFF08${this.progress.attempt}/${this.progress.maxAttempts}\uFF09`
					: '\u6B63\u5728\u4E0A\u4F20\u5230 OSS'
				if (this.progress.percent != null) return `${prefix}\uFF1A${this.progress.percent}%`
				return `${prefix}\uFF08\u5DF2\u4E0A\u4F20 ${this.formatBytes(this.progress.transferredBytes)}\uFF09`
			}
		},
		watch: {
			progress: {
				immediate: true,
				handler(value) {
					this.clearTimers()
					this.visible = Boolean(value)
					this.fading = false
					const stateKey = value ? `${value.state}:${value.attempt}` : ''
					if (stateKey && stateKey !== this.announcementStateKey) {
						this.announcement = this.stateAnnouncement(value)
						this.announcementStateKey = stateKey
					}
					if (value?.state === 'COMPLETED') this.scheduleDismiss()
				}
			}
		},
		beforeUnmount() {
			this.clearTimers()
		},
		beforeDestroy() {
			this.clearTimers()
		},
		methods: {
			scheduleDismiss() {
				this.completionTimer = setTimeout(() => {
					if (this.prefersReducedMotion()) {
						this.visible = false
						this.$emit('dismiss')
						return
					}
					this.fading = true
					this.fadeTimer = setTimeout(() => {
						this.visible = false
						this.$emit('dismiss')
					}, 200)
				}, 1000)
			},
			clearTimers() {
				clearTimeout(this.completionTimer)
				clearTimeout(this.fadeTimer)
				this.completionTimer = null
				this.fadeTimer = null
			},
			prefersReducedMotion() {
				try {
					return Boolean(globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches)
				} catch (_) {
					return false
				}
			},
			formatBytes(value) {
				const bytes = Math.max(0, Number(value) || 0)
				return `${(bytes / (1024 * 1024)).toFixed(bytes >= 10 * 1024 * 1024 ? 0 : 1)} MiB`
			},
			stateAnnouncement(progress) {
				if (progress.state === 'FAILED') return '\u5A92\u4F53\u4E0A\u4F20\u5230 OSS \u5931\u8D25'
				if (progress.state === 'VERIFYING') return '\u5A92\u4F53\u6B63\u5728\u6821\u9A8C OSS'
				if (progress.state === 'COMPLETED') return '\u5A92\u4F53\u5DF2\u4E0A\u4F20\u5230 OSS'
				return progress.attempt > 1
					? `\u6B63\u5728\u91CD\u8BD5\u4E0A\u4F20\uFF0C\u7B2C ${progress.attempt} \u6B21`
					: '\u5A92\u4F53\u6B63\u5728\u4E0A\u4F20\u5230 OSS'
			}
		}
	}
</script>

<style scoped lang="scss">
	.media-upload-progress { padding: 9px 10px 10px; color: #a7e6c9; transition: opacity 200ms ease; }
	.media-upload-progress.is-fading { opacity: 0; }
	.media-upload-progress.is-failed { color: #ffaaa3; }
	.media-upload-progress-track { height: 5px; overflow: hidden; border-radius: 999px; background: #173e2e; }
	.media-upload-progress-fill { width: 0; height: 100%; border-radius: inherit; background: #36cf93; transition: width 180ms ease; }
	.media-upload-progress.is-failed .media-upload-progress-track { background: rgba(164, 64, 59, .32); }
	.media-upload-progress.is-failed .media-upload-progress-fill { background: #e97169; }
	.media-upload-progress.is-indeterminate .media-upload-progress-fill { width: 36%; animation: media-upload-indeterminate 1.05s ease-in-out infinite; }
	.media-upload-progress-label { display: block; margin-top: 7px; font-size: 11px; line-height: 1.35; }
	.media-upload-progress-announcement { width: 1px; height: 1px; position: absolute; overflow: hidden; clip: rect(0 0 0 0); clip-path: inset(50%); white-space: nowrap; }

	@keyframes media-upload-indeterminate {
		0% { transform: translateX(-115%); }
		100% { transform: translateX(310%); }
	}

	@media (prefers-reduced-motion: reduce) {
		.media-upload-progress, .media-upload-progress-fill { transition: none; }
		.media-upload-progress.is-indeterminate .media-upload-progress-fill { animation: none; transform: none; }
	}
</style>

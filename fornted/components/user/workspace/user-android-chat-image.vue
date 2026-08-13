<template>
	<view
		class="android-chat-image"
		:class="{ 'is-thumbnail': variant === 'THUMBNAIL', 'is-error': phase === 'ERROR' }"
		:style="frameStyle"
	>
		<image
			v-if="renderSrc && phase !== 'ERROR' && phase !== 'WAITING_REMOTE'"
			:key="revision"
			class="android-chat-image-element"
			:src="renderSrc"
			:mode="variant === 'THUMBNAIL' ? 'aspectFill' : 'widthFix'"
			@click="handlePreview"
			@load="handleLoad"
			@error="handleError"
		/>
		<view v-if="phase === 'LOADING'" class="android-image-placeholder" role="status">
			<uni-icons type="image" size="24" color="#73827a" aria-hidden="true" />
			<text>图片加载中…</text>
		</view>
		<view v-else-if="phase === 'WAITING_REMOTE'" class="android-image-placeholder" role="status">
			<uni-icons type="image" size="24" color="#8fdcbe" aria-hidden="true" />
			<text>图片已上传，正在处理</text>
		</view>
		<view v-else-if="phase === 'ERROR'" class="android-image-placeholder is-error" role="alert">
			<uni-icons type="info" size="24" color="#ff9b94" aria-hidden="true" />
			<text>图片加载失败</text>
			<button class="android-image-retry" type="button" @click.stop="retry">重新加载</button>
		</view>
	</view>
</template>

<script>
	import { createMediaDescriptor, resolveMediaAspectRatio } from '@/common/aichat/ai-media-presentation.js'

	export default {
		name: 'UserAndroidChatImage',
		props: {
			attachment: { type: Object, required: true },
			localSrc: { type: String, default: '' },
			variant: {
				type: String,
				default: 'FULL',
				validator: value => ['FULL', 'THUMBNAIL'].includes(value)
			},
			aspectRatio: { type: Number, default: null }
		},
		emits: ['state-change', 'layout-change', 'preview'],
		data() {
			return {
				phase: 'LOADING',
				revision: 1,
				renderSrc: '',
				autoRetryCount: 0,
				retryTimer: null,
				observedAspectRatio: null,
				layoutReported: false
			}
		},
		computed: {
			descriptor() {
				try {
					return createMediaDescriptor(
						this.attachment,
						null,
						{ localSrc: this.localSrc }
					)
				} catch (_) {
					return null
				}
			},
			awaitingRemote() {
				const localSource = String(this.localSrc || '').trim()
				const remoteSource = String(this.attachment?.url || '').trim()
				return Boolean(localSource) && !/^https:\/\/[^\s]+$/i.test(remoteSource)
			},
			effectiveAspectRatio() {
				const explicit = Number(this.aspectRatio)
				if (Number.isFinite(explicit) && explicit > 0) return explicit
				return this.observedAspectRatio || resolveMediaAspectRatio(this.descriptor, 1)
			},
			frameStyle() {
				return { '--android-image-aspect': String(this.effectiveAspectRatio) }
			}
		},
		watch: {
			attachment() {
				this.resetSource()
			},
			localSrc() {
				this.resetSource()
			}
		},
		mounted() {
			this.resetSource()
		},
		beforeUnmount() {
			if (this.retryTimer) clearTimeout(this.retryTimer)
			this.retryTimer = null
		},
		methods: {
			resetSource() {
				if (this.retryTimer) clearTimeout(this.retryTimer)
				this.retryTimer = null
				this.revision += 1
				this.autoRetryCount = 0
				this.observedAspectRatio = null
				this.layoutReported = false
				const descriptor = this.descriptor
				this.phase = descriptor
					? 'LOADING'
					: this.awaitingRemote ? 'WAITING_REMOTE' : 'ERROR'
				this.renderSrc = descriptor?.src || ''
				this.emitState()
			},
			handleLoad(event) {
				const width = Number(event?.detail?.width)
				const height = Number(event?.detail?.height)
				if (Number.isFinite(width) && width > 0 && Number.isFinite(height) && height > 0) {
					const ratio = width / height
					if (Math.abs(ratio - this.effectiveAspectRatio) > 0.01) this.observedAspectRatio = ratio
				}
				this.phase = 'READY'
				this.emitState()
				if (!this.layoutReported) {
					this.layoutReported = true
					this.$emit('layout-change', {
						key: this.descriptor?.key || '',
						revision: this.revision,
						aspectRatio: this.effectiveAspectRatio
					})
				}
			},
			handleError() {
				if (this.autoRetryCount < 1) {
					this.autoRetryCount += 1
					this.scheduleReload(250)
					return
				}
				this.finishLoadFailure()
			},
			finishLoadFailure() {
				this.renderSrc = ''
				this.phase = this.awaitingRemote ? 'WAITING_REMOTE' : 'ERROR'
				this.emitState()
			},
			scheduleReload(delay) {
				if (this.retryTimer) clearTimeout(this.retryTimer)
				this.renderSrc = ''
				this.phase = 'LOADING'
				this.revision += 1
				this.retryTimer = setTimeout(() => {
					this.retryTimer = null
					this.renderSrc = this.descriptor?.src || ''
					if (!this.renderSrc) {
						this.finishLoadFailure()
					}
				}, delay)
			},
			retry() {
				this.autoRetryCount = 1
				this.scheduleReload(0)
			},
			handlePreview() {
				if (this.phase === 'READY') this.$emit('preview', this.attachment)
			},
			emitState() {
				this.$emit('state-change', {
					key: this.descriptor?.key || '',
					revision: this.revision,
					phase: this.phase,
					errorCategory: this.phase === 'ERROR' ? 'LOAD_FAILED' : null
				})
			}
		}
	}
</script>

<style lang="scss" scoped>
	.android-chat-image { position: relative; width: 100%; min-height: 120px; aspect-ratio: var(--android-image-aspect, 1); overflow: hidden; border: 1px solid #313a35; border-radius: 12px; background: #101412; box-sizing: border-box; }
	.android-chat-image.is-thumbnail { height: 100%; min-height: 0; aspect-ratio: var(--android-image-aspect, 1); }
	.android-chat-image-element { width: 100%; height: auto; display: block; }
	.android-chat-image.is-thumbnail .android-chat-image-element { width: 100%; height: 100%; }
	.android-image-placeholder { position: absolute; inset: 0; min-height: 120px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 7px; background: linear-gradient(145deg, #121815, #0d110f); color: #7f8d85; font-size: 12px; }
	.android-chat-image.is-thumbnail .android-image-placeholder { min-height: 0; }
	.android-image-placeholder.is-error { color: #ffd0cc; }
	.android-image-retry { min-height: 34px; margin: 0; padding: 0 12px; border: 1px solid #4a3a38; border-radius: 8px; background: #211716; color: #ffd0cc; font-size: 12px; line-height: 32px; }
	.android-image-retry::after { border: 0; }
</style>

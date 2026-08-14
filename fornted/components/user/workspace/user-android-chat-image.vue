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
			<text>图片正在处理</text>
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
	const ANDROID_IMAGE_DIAGNOSTICS_ENABLED = process.env.NODE_ENV === 'development'

	export default {
		name: 'UserAndroidChatImage',
		props: {
			attachment: { type: Object, required: true },
			localSrc: { type: String, default: '' },
			sourceStatus: { type: String, default: '' },
			diagnosticRunId: { type: String, default: '' },
			managedLocalSource: { type: Boolean, default: false },
			variant: {
				type: String,
				default: 'FULL',
				validator: value => ['FULL', 'THUMBNAIL'].includes(value)
			},
			aspectRatio: { type: Number, default: null }
		},
		emits: ['state-change', 'layout-change', 'preview', 'retry'],
		data() {
			return {
				phase: 'LOADING',
				revision: 1,
				renderSrc: '',
				autoRetryCount: 0,
				retryTimer: null,
				observedAspectRatio: null,
				layoutReported: false,
				loggedDiagnosticKeys: []
			}
		},
		computed: {
			descriptor() {
				try {
					// 生成图片带 sourceStatus 时必须等待受控 localSrc，禁止回退到 data URL 或远端 URL 直连。
					if (this.sourceStatus && !String(this.localSrc || '').trim()) return null
					return createMediaDescriptor(
						this.attachment,
						null,
						{
							localSrc: this.localSrc,
							allowManagedFileUri: this.managedLocalSource
						}
					)
				} catch (_) {
					return null
				}
			},
			awaitingRemote() {
				if (['PREPARING_PREVIEW', 'WAITING_REMOTE', 'DOWNLOADING_FINAL']
					.includes(String(this.sourceStatus || ''))) return true
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
			},
			sourceStatus() {
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
			emitDiagnostic(phase, fields = {}, warning = false) {
				if (!ANDROID_IMAGE_DIAGNOSTICS_ENABLED || !this.diagnosticRunId) return
				const diagnosticKey = `${this.revision}:${phase}`
				if (this.loggedDiagnosticKeys.includes(diagnosticKey)) return
				this.loggedDiagnosticKeys.push(diagnosticKey)
				if (this.loggedDiagnosticKeys.length > 32) this.loggedDiagnosticKeys.shift()
				const sourceKind = !this.descriptor
					? 'NONE'
					: this.managedLocalSource ? 'FILE_URI' : this.localSrc ? 'APP_LOCAL' : 'HTTPS'
				const values = {
					event: 'image_android_view',
					phase,
					diagnosticRunId: this.diagnosticRunId,
					revision: this.revision,
					sourceStatus: String(this.sourceStatus || 'NONE').slice(0, 32),
					sourceKind,
					autoRetryCount: this.autoRetryCount,
					...fields
				}
				const line = Object.entries(values)
					.map(([key, value]) => {
						const safeValue = typeof value === 'number' || typeof value === 'boolean'
							? value
							: String(value).replace(/[\u0000-\u0020\u007f]/g, '_').slice(0, 128)
						return `${key}=${String(safeValue)}`
					})
					.join(' ')
				if (warning) console.warn(`[ait-android-image] ${line}`)
				else console.log(`[ait-android-image] ${line}`)
			},
			resetSource() {
				if (this.retryTimer) clearTimeout(this.retryTimer)
				this.retryTimer = null
				const descriptor = this.descriptor
				const nextSource = descriptor?.src || ''
				if (nextSource && nextSource === this.renderSrc
					&& ['READY', 'LOADING'].includes(this.phase)) {
					// 高清下载状态变化不能覆盖正在显示的缩略图，也不能重置它的加载重试计数。
					this.emitState()
					return
				}
				this.revision += 1
				this.autoRetryCount = 0
				this.observedAspectRatio = null
				this.layoutReported = false
				const nativeReadySource = Boolean(String(this.localSrc || '').trim())
					&& ['PREVIEW_READY', 'FINAL_READY'].includes(this.sourceStatus)
				this.phase = descriptor
					? (nativeReadySource ? 'READY' : 'LOADING')
					: this.awaitingRemote ? 'WAITING_REMOTE' : 'ERROR'
				this.renderSrc = nextSource
				this.emitDiagnostic('VIEW_SOURCE_RESET', { phaseAfter: this.phase })
				if (this.renderSrc && this.phase !== 'ERROR') {
					this.emitDiagnostic('VIEW_LOAD_STARTED', { phaseAfter: this.phase })
				}
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
				this.emitDiagnostic('VIEW_LOAD_SUCCEEDED', {
					width: Number.isFinite(width) ? width : 0,
					height: Number.isFinite(height) ? height : 0
				})
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
			handleError(event) {
				const errorCode = Number(event?.detail?.errCode ?? event?.detail?.code)
				this.emitDiagnostic('VIEW_LOAD_FAILED', {
					eventType: String(event?.type || 'error').slice(0, 32),
					errorCode: Number.isFinite(errorCode) ? errorCode : 0
				}, true)
				if (this.autoRetryCount < 1) {
					this.autoRetryCount += 1
					this.emitDiagnostic('VIEW_AUTO_RETRY_SCHEDULED', { delayMs: 250 })
					this.scheduleReload(250)
					return
				}
				this.finishLoadFailure()
			},
			finishLoadFailure() {
				this.renderSrc = ''
				this.phase = this.awaitingRemote ? 'WAITING_REMOTE' : 'ERROR'
				if (this.phase === 'ERROR') this.emitDiagnostic('VIEW_ERROR_SHOWN', {}, true)
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
					if (this.renderSrc) this.emitDiagnostic('VIEW_LOAD_STARTED', { phaseAfter: this.phase })
					if (!this.renderSrc) {
						this.finishLoadFailure()
					}
				}, delay)
			},
			retry() {
				this.emitDiagnostic('VIEW_MANUAL_RETRY')
				if (this.sourceStatus === 'ERROR' && !this.descriptor) {
					this.$emit('retry')
					return
				}
				this.autoRetryCount = 1
				this.scheduleReload(0)
			},
			handlePreview() {
				this.$emit('preview', {
					attachment: this.attachment,
					src: this.renderSrc,
					phase: this.phase
				})
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

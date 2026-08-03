<template>
	<!-- #ifdef H5 -->
	<view
		class="ai-html-preview"
		:class="['is-' + previewState, { 'is-fullscreen': fullscreen }]"
		role="document"
		aria-label="HTML 预览"
		:aria-busy="String(previewState === 'connecting' || previewState === 'rendering')"
	>
		<iframe
			v-if="frameUrl"
			ref="previewFrame"
			class="ai-html-preview-frame"
			:src="frameUrl"
			:style="frameStyle"
			sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox"
			referrerpolicy="no-referrer"
			loading="eager"
			title="HTML 安全预览"
			@load="onFrameLoad"
		></iframe>
		<view
			v-if="previewState !== 'ready'"
			class="ai-html-preview-status"
			:class="'is-' + previewState"
			role="status"
			aria-live="polite"
		>
			<view
				v-if="previewState === 'connecting' || previewState === 'rendering'"
				class="ai-html-preview-spinner"
				aria-hidden="true"
			></view>
			<text>{{ statusMessage }}</text>
		</view>
	</view>
	<!-- #endif -->
	<!-- #ifndef H5 -->
	<view class="ai-html-preview ai-html-preview-unsupported" role="status">
		<text>当前平台暂不支持 HTML 预览</text>
	</view>
	<!-- #endif -->
</template>

<script>
	import { getAiHtmlPreviewConfig } from '@/common/aichat/ai-html-preview-config.js'
	import {
		AI_HTML_PREVIEW_READY_TIMEOUT_MS,
		AI_HTML_PREVIEW_RENDER_TIMEOUT_MS,
		createAiHtmlPreviewDisposeMessage,
		createAiHtmlPreviewFrameUrl,
		createAiHtmlPreviewRenderMessage,
		createAiHtmlPreviewSecureId,
		isAiHtmlPreviewShellMessage,
		sanitizeAiHtmlPreviewRuntimeError
	} from '@/common/aichat/ai-html-preview-protocol.js'

	export default {
		name: 'UserMarkdownHtmlPreview',
		props: {
			code: { type: String, default: '' },
			fullscreen: { type: Boolean, default: false }
		},
		data() {
			const config = getAiHtmlPreviewConfig()
			return {
				previewOrigin: config.origin,
				frameUrl: '',
				channelId: '',
				renderId: '',
				previewState: config.enabled ? 'connecting' : 'error',
				statusMessage: config.enabled ? '正在连接安全预览' : config.error,
				warningKind: '',
				frameHeight: 520,
				frameBackground: '#ffffff',
				shellReady: false,
				readyTimeoutId: null,
				renderTimeoutId: null
			}
		},
		computed: {
			frameStyle() {
				return {
					height: this.fullscreen ? '100%' : `${this.frameHeight}px`,
					backgroundColor: this.frameBackground
				}
			}
		},
		watch: {
			code(nextCode, previousCode) {
				if (nextCode === previousCode || !this.shellReady) return
				this.sendRender()
			}
		},
		mounted() {
			// #ifdef H5
			window.addEventListener('message', this.onWindowMessage)
			this.initializeFrame()
			// #endif
		},
		beforeUnmount() {
			// #ifdef H5
			this.sendDispose()
			this.clearTimers()
			window.removeEventListener('message', this.onWindowMessage)
			// #endif
		},
		methods: {
			initializeFrame() {
				if (!this.previewOrigin || typeof window === 'undefined') return
				try {
					this.channelId = createAiHtmlPreviewSecureId()
					this.frameUrl = createAiHtmlPreviewFrameUrl({
						previewOrigin: this.previewOrigin,
						parentOrigin: window.location.origin,
						channelId: this.channelId
					})
					this.previewState = 'connecting'
					this.statusMessage = '正在连接安全预览'
					this.readyTimeoutId = setTimeout(() => {
						if (this.shellReady) return
						this.previewState = 'error'
						this.statusMessage = '预览服务连接超时，请返回代码视图后重试'
					}, AI_HTML_PREVIEW_READY_TIMEOUT_MS)
				} catch (error) {
					this.previewState = 'error'
					this.statusMessage = error?.message || '无法初始化 HTML 安全预览'
				}
			},
			onFrameLoad() {
				if (!this.shellReady && this.previewState !== 'error') {
					this.previewState = 'connecting'
					this.statusMessage = '正在连接安全预览'
				}
			},
			onWindowMessage(event) {
				const frame = this.$refs.previewFrame
				if (!frame || event.source !== frame.contentWindow) return
				if (event.origin !== this.previewOrigin) return
				if (!isAiHtmlPreviewShellMessage(event.data, this.channelId)) return
				if (event.data.type === 'ready') {
					this.shellReady = true
					if (this.readyTimeoutId) clearTimeout(this.readyTimeoutId)
					this.readyTimeoutId = null
					this.sendRender()
					return
				}
				if (event.data.renderId !== this.renderId) return
				if (event.data.type === 'rendered') {
					if (this.renderTimeoutId) clearTimeout(this.renderTimeoutId)
					this.renderTimeoutId = null
					this.frameHeight = Math.min(720, Math.max(280, Number(event.data.height) || 520))
					this.frameBackground = String(event.data.backgroundColor || '#ffffff').slice(0, 64)
					if (this.warningKind !== 'runtime' && this.warningKind !== 'navigation') {
						this.previewState = 'ready'
						this.statusMessage = ''
						this.warningKind = ''
					}
					return
				}
				if (event.data.type === 'runtime-error') {
					this.previewState = 'warning'
					this.warningKind = 'runtime'
					this.statusMessage = sanitizeAiHtmlPreviewRuntimeError(event.data.message)
					return
				}
				if (event.data.type === 'navigation') {
					this.previewState = 'warning'
					this.warningKind = 'navigation'
					this.statusMessage = '预览页面已在安全沙箱内导航'
				}
			},
			sendRender() {
				const frame = this.$refs.previewFrame
				if (!frame?.contentWindow || !this.shellReady) return
				this.sendDispose()
				try {
					this.renderId = createAiHtmlPreviewSecureId()
					const message = createAiHtmlPreviewRenderMessage({
						channelId: this.channelId,
						renderId: this.renderId,
						html: this.code,
						theme: 'dark'
					})
					this.previewState = 'rendering'
					this.warningKind = ''
					this.statusMessage = '正在渲染 HTML 预览'
					frame.contentWindow.postMessage(message, this.previewOrigin)
					this.renderTimeoutId = setTimeout(() => {
						if (this.previewState !== 'rendering') return
						this.previewState = 'warning'
						this.warningKind = 'slow'
						this.statusMessage = '页面仍在加载，外部资源可能响应较慢'
					}, AI_HTML_PREVIEW_RENDER_TIMEOUT_MS)
				} catch (error) {
					this.previewState = 'error'
					this.statusMessage = error?.message || '无法发送 HTML 预览内容'
				}
			},
			sendDispose() {
				const frame = this.$refs.previewFrame
				if (!frame?.contentWindow || !this.channelId || !this.renderId) return
				try {
					const message = createAiHtmlPreviewDisposeMessage({
						channelId: this.channelId,
						renderId: this.renderId
					})
					frame.contentWindow.postMessage(message, this.previewOrigin)
				} catch (_) {}
				this.renderId = ''
				if (this.renderTimeoutId) clearTimeout(this.renderTimeoutId)
				this.renderTimeoutId = null
			},
			clearTimers() {
				if (this.readyTimeoutId) clearTimeout(this.readyTimeoutId)
				if (this.renderTimeoutId) clearTimeout(this.renderTimeoutId)
				this.readyTimeoutId = null
				this.renderTimeoutId = null
			}
		}
	}
</script>

<style lang="scss">
	.ai-html-preview { position: relative; width: 100%; min-height: 280px; overflow: hidden; background: #fff; }
	.ai-html-preview.is-fullscreen { min-height: 0; height: 100%; flex: 1 1 auto; }
	.ai-html-preview-frame { width: 100%; min-height: 280px; display: block; border: 0; background: #fff; }
	.ai-html-preview.is-fullscreen .ai-html-preview-frame { min-height: 0; }
	.ai-html-preview-status { position: absolute; left: 50%; top: 14px; z-index: 2; max-width: min(560px, calc(100% - 28px)); padding: 9px 12px; display: flex; align-items: center; gap: 8px; box-sizing: border-box; border: 1px solid rgba(83, 101, 91, .2); border-radius: 10px; background: rgba(247, 249, 248, .95); box-shadow: 0 8px 28px rgba(16, 24, 20, .12); color: #34413a; font-size: 12px; line-height: 1.4; text-align: left; transform: translateX(-50%); pointer-events: none; }
	.ai-html-preview-status.is-warning { border-color: rgba(183, 126, 25, .32); background: rgba(255, 249, 232, .96); color: #76510f; }
	.ai-html-preview-status.is-error { top: 50%; transform: translate(-50%, -50%); border-color: rgba(184, 63, 63, .28); background: rgba(255, 243, 243, .97); color: #8f2e2e; }
	.ai-html-preview-spinner { width: 13px; height: 13px; flex: 0 0 13px; border: 2px solid rgba(52, 65, 58, .2); border-top-color: #3e7c65; border-radius: 50%; animation: ai-html-preview-spin 720ms linear infinite; }
	.ai-html-preview-unsupported { min-height: 120px; display: flex; align-items: center; justify-content: center; color: #66736c; }
	@keyframes ai-html-preview-spin { to { transform: rotate(360deg); } }
	@media (prefers-reduced-motion: reduce) {
		.ai-html-preview-spinner { animation: none; opacity: .65; }
	}
</style>

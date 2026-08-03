<template>
	<view
		class="ai-markdown-code-block"
		:class="{ 'is-fullscreen': isFullscreen }"
		:role="isFullscreen ? 'dialog' : 'region'"
		:aria-modal="isFullscreen ? 'true' : undefined"
		:aria-label="isFullscreen ? 'HTML 全屏代码与预览' : languageLabel + ' code'"
	>
		<view class="ai-markdown-code-toolbar">
			<text class="ai-markdown-code-language">{{ languageLabel }}</text>
			<view class="ai-markdown-code-actions">
				<!-- #ifdef H5 -->
				<view
					v-if="isHtmlPreviewable"
					class="ai-code-view-toggle"
					:class="{ 'is-preview': isPreviewMode }"
					role="group"
					aria-label="代码块视图切换"
				>
					<view class="ai-code-view-indicator" aria-hidden="true"></view>
					<button
						type="button"
						class="ai-code-icon-button ai-code-view-button"
						aria-label="显示代码"
						:aria-pressed="String(!isPreviewMode)"
						@click="setViewMode('code')"
					>
						<svg class="ai-code-toolbar-icon" viewBox="0 0 24 24" aria-hidden="true">
							<path d="m8 9-3 3 3 3M16 9l3 3-3 3M14 5l-4 14" />
						</svg>
					</button>
					<button
						type="button"
						class="ai-code-icon-button ai-code-view-button"
						aria-label="显示预览"
						:aria-pressed="String(isPreviewMode)"
						:aria-disabled="String(previewDisabled)"
						:disabled="previewDisabled"
						:title="previewDisabledReason"
						@click="setViewMode('preview')"
					>
						<svg class="ai-code-toolbar-icon" viewBox="0 0 24 24" aria-hidden="true">
							<rect x="3.5" y="4.5" width="17" height="15" rx="2" />
							<path d="M3.5 8.5h17M10 11.5l4 2.5-4 2.5z" />
						</svg>
					</button>
				</view>
				<button
					class="ai-code-icon-button"
					type="button"
					aria-label="复制代码"
					:title="copyLabel"
					:disabled="!code"
					@click="copyCode"
				>
					<svg class="ai-code-toolbar-icon" viewBox="0 0 24 24" aria-hidden="true">
						<rect x="9" y="9" width="11" height="11" rx="2" />
						<path d="M15 9V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h3" />
					</svg>
				</button>
				<button
					v-if="isPreviewMode && !isFullscreen"
					ref="fullscreenButton"
					class="ai-code-icon-button"
					type="button"
					aria-label="全屏预览"
					title="全屏预览"
					@click="openFullscreen"
				>
					<svg class="ai-code-toolbar-icon" viewBox="0 0 24 24" aria-hidden="true">
						<path d="M8 3H3v5M16 3h5v5M21 16v5h-5M8 21H3v-5" />
					</svg>
				</button>
				<button
					v-if="isFullscreen"
					class="ai-code-icon-button"
					type="button"
					aria-label="下载 HTML"
					title="下载 HTML"
					:disabled="!code"
					@click="downloadHtml"
				>
					<svg class="ai-code-toolbar-icon" viewBox="0 0 24 24" aria-hidden="true">
						<path d="M12 3v12m0 0 4-4m-4 4-4-4M5 20h14" />
					</svg>
				</button>
				<button
					v-if="isFullscreen"
					ref="closeFullscreenButton"
					class="ai-code-icon-button"
					type="button"
					aria-label="关闭全屏预览"
					title="关闭全屏预览"
					@click="closeFullscreen(true)"
				>
					<svg class="ai-code-toolbar-icon" viewBox="0 0 24 24" aria-hidden="true">
						<path d="m5 5 14 14M19 5 5 19" />
					</svg>
				</button>
				<!-- #endif -->
				<!-- #ifndef H5 -->
				<text v-if="isHtmlPreviewable" class="ai-code-preview-platform-note">HTML 预览仅支持 H5</text>
				<button
					class="ai-markdown-copy-button"
					type="button"
					aria-label="复制代码"
					:disabled="!code"
					@click="copyCode"
				>
					<text>{{ copyLabel }}</text>
				</button>
				<!-- #endif -->
			</view>
		</view>
		<!-- #ifdef H5 -->
		<text
			v-if="isHtmlPreviewable && !previewConfigured"
			class="ai-code-preview-configuration-note"
			role="status"
		>{{ previewConfigurationError }}</text>
		<!-- #endif -->
		<user-markdown-html-preview
			v-if="isPreviewMode"
			:code="code"
			:fullscreen="isFullscreen"
		/>
		<scroll-view v-else class="ai-markdown-code-scroll" scroll-x :show-scrollbar="false">
			<view class="ai-code-lines" :class="{ 'is-loading': highlightStatus === 'loading' }">
				<user-markdown-code-lines :block-key="blockKey" :lines="highlightedStableLines" />
				<user-markdown-code-lines :block-key="blockKey" :lines="highlightedUnstableLines" />
				<view v-if="highlightStatus === 'loading' && code" class="ai-code-loading-placeholder" aria-hidden="true"></view>
			</view>
		</scroll-view>
		<text v-if="copyState" class="ai-markdown-copy-status" role="status">{{ copyState }}</text>
		<!-- #ifdef H5 -->
		<button
			v-if="isFullscreen"
			class="ai-code-focus-sentinel"
			type="button"
			aria-label="返回全屏预览工具栏"
			@focus="focusFullscreenStart"
		></button>
		<!-- #endif -->
	</view>
</template>

<script>
	import { createAiCodeHighlightSession } from '@/common/aichat/ai-code-highlight-session.js'
	import { getAiHtmlPreviewConfig } from '@/common/aichat/ai-html-preview-config.js'
	import { isAiHtmlPreviewLanguage } from '@/common/aichat/ai-html-preview-document.js'
	import UserMarkdownHtmlPreview from './user-markdown-html-preview.vue'
	import UserMarkdownCodeLines from './user-markdown-code-lines.vue'

	export default {
		name: 'UserMarkdownCodeBlock',
		components: { UserMarkdownCodeLines, UserMarkdownHtmlPreview },
		props: {
			blockKey: { type: String, default: '' },
			language: { type: Object, default: () => ({ id: 'plain', label: 'Plain text' }) },
			code: { type: String, default: '' },
			streaming: { type: Boolean, default: false }
		},
		data() {
			const previewConfig = getAiHtmlPreviewConfig()
			return {
				viewMode: 'code',
				isFullscreen: false,
				previewConfigured: previewConfig.enabled,
				previewConfigurationError: previewConfig.error,
				copyState: '',
				highlightStatus: 'loading',
				highlightedLanguage: null,
				highlightedStableLines: [],
				highlightedUnstableLines: [],
				documentScrollLocked: false,
				bodyOverflowBeforeFullscreen: ''
			}
		},
		computed: {
			languageLabel() {
				return this.highlightedLanguage?.label || this.language?.label || 'Plain text'
			},
			copyLabel() {
				return this.copyState === 'Copied' ? '已复制' : '复制'
			},
			isHtmlPreviewable() {
				return isAiHtmlPreviewLanguage(this.language)
			},
			previewDisabled() {
				return this.streaming || !this.code || !this.previewConfigured
			},
			previewDisabledReason() {
				if (!this.previewConfigured) return this.previewConfigurationError
				if (this.streaming) return '代码仍在生成，完成后可预览'
				if (!this.code) return '没有可预览的 HTML'
				return '显示预览'
			},
			isPreviewMode() {
				return this.isHtmlPreviewable && !this.previewDisabled && this.viewMode === 'preview'
			}
		},
		watch: {
			code(nextCode, previousCode) {
				this.viewMode = 'code'
				this.closeFullscreen(false)
				if (!this.highlightSession) return
				const operation = this.streaming
					? this.highlightSession.update({ code: nextCode, previousCode, streaming: true })
					: this.highlightSession.complete({ finalCode: nextCode })
				void operation
			},
			streaming(nextStreaming, previousStreaming) {
				if (nextStreaming) {
					this.viewMode = 'code'
					this.closeFullscreen(false)
				}
				if (!nextStreaming && previousStreaming && this.highlightSession) {
					void this.highlightSession.complete({ finalCode: this.code })
				}
			},
			'language.id'() {
				this.viewMode = 'code'
				this.closeFullscreen(false)
				this.startHighlightSession()
			}
		},
		mounted() {
			this.startHighlightSession()
		},
		beforeUnmount() {
			this.closeFullscreen(false)
			this.highlightSession?.close()
			this.highlightSession = null
		},
		methods: {
			setViewMode(mode) {
				if (mode === 'preview' && (!this.isHtmlPreviewable || this.previewDisabled)) return
				this.viewMode = mode === 'preview' ? 'preview' : 'code'
			},
			startHighlightSession() {
				this.viewMode = 'code'
				this.highlightSession?.close()
				this.highlightStatus = 'loading'
				this.highlightedLanguage = null
				this.highlightedStableLines = []
				this.highlightedUnstableLines = []
				this.highlightSession = createAiCodeHighlightSession({
					blockKey: this.blockKey,
					language: this.language,
					onSnapshot: snapshot => {
						this.highlightStatus = snapshot.status
						this.highlightedLanguage = snapshot.language
						this.highlightedStableLines = snapshot.stableLines
						this.highlightedUnstableLines = snapshot.unstableLines
					},
					onError: () => {}
				})
				const operation = this.streaming
					? this.highlightSession.update({ code: this.code, previousCode: '', streaming: true })
					: this.highlightSession.complete({ finalCode: this.code })
				void operation
			},
			setCopyState(value) {
				this.copyState = value
				if (!value) return
				setTimeout(() => {
					if (this.copyState === value) this.copyState = ''
				}, 2200)
			},
			copyCode() {
				const value = String(this.code || '')
				if (!value) return
				if (typeof uni !== 'undefined' && typeof uni.setClipboardData === 'function') {
					uni.setClipboardData({
						data: value,
						success: () => this.setCopyState('Copied'),
						fail: () => this.setCopyState('复制失败')
					})
					return
				}
				if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
					navigator.clipboard.writeText(value)
						.then(() => this.setCopyState('Copied'))
						.catch(() => this.setCopyState('复制失败'))
					return
				}
				this.setCopyState('剪贴板不可用')
			},
			openFullscreen() {
				if (!this.isPreviewMode || this.isFullscreen) return
				this.isFullscreen = true
				this.lockDocumentScroll()
				document.addEventListener('keydown', this.onFullscreenKeydown)
				this.$nextTick(() => this.focusElement(this.$refs.closeFullscreenButton))
			},
			closeFullscreen(restoreFocus = true) {
				if (!this.isFullscreen && !this.documentScrollLocked) return
				this.isFullscreen = false
				if (typeof document !== 'undefined') {
					document.removeEventListener('keydown', this.onFullscreenKeydown)
				}
				this.restoreDocumentScroll()
				if (restoreFocus) {
					this.$nextTick(() => this.focusElement(this.$refs.fullscreenButton))
				}
			},
			onFullscreenKeydown(event) {
				if (event.key === 'Escape') {
					event.preventDefault()
					this.closeFullscreen(true)
					return
				}
				if (event.key === 'Tab') this.trapFullscreenFocus(event)
			},
			trapFullscreenFocus(event) {
				if (!this.isFullscreen || !this.$el?.querySelectorAll) return
				const focusable = Array.from(this.$el.querySelectorAll(
					'button:not([disabled]):not(.ai-code-focus-sentinel), iframe'
				)).filter(element => element.getClientRects().length > 0)
				if (!focusable.length) {
					event.preventDefault()
					return
				}
				const first = focusable[0]
				const last = focusable[focusable.length - 1]
				if (event.shiftKey && document.activeElement === first) {
					event.preventDefault()
					last.focus()
				} else if (!event.shiftKey && document.activeElement === last) {
					event.preventDefault()
					first.focus()
				}
			},
			focusFullscreenStart() {
				this.focusElement(this.$refs.closeFullscreenButton)
			},
			focusElement(reference) {
				if (typeof reference?.focus === 'function') {
					reference.focus()
					return
				}
				if (typeof reference?.$el?.focus === 'function') reference.$el.focus()
			},
			lockDocumentScroll() {
				if (typeof document === 'undefined' || this.documentScrollLocked) return
				this.bodyOverflowBeforeFullscreen = document.body.style.overflow
				document.body.style.overflow = 'hidden'
				this.documentScrollLocked = true
			},
			restoreDocumentScroll() {
				if (typeof document === 'undefined' || !this.documentScrollLocked) return
				document.body.style.overflow = this.bodyOverflowBeforeFullscreen
				this.bodyOverflowBeforeFullscreen = ''
				this.documentScrollLocked = false
			},
			downloadHtml() {
				const value = String(this.code || '')
				if (!value || typeof document === 'undefined' || typeof URL === 'undefined') return
				try {
					const url = URL.createObjectURL(new Blob([value], { type: 'text/html;charset=utf-8' }))
					const link = document.createElement('a')
					const stamp = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14)
					link.href = url
					link.download = `html-preview-${stamp}.html`
					link.click()
					setTimeout(() => URL.revokeObjectURL(url), 0)
				} catch (_) {
					this.setCopyState('下载不可用')
				}
			}
		}
	}
</script>

<style lang="scss">
	.ai-markdown-code-block { margin: 14px 0; overflow: hidden; border: 1px solid rgba(120, 145, 132, .35); border-radius: 14px; background: #1f1f1f; box-shadow: 0 8px 28px rgba(0, 0, 0, .18); }
	.ai-markdown-code-block.is-fullscreen { position: fixed; inset: 0; z-index: 10000; margin: 0; display: flex; flex-direction: column; border: 0; border-radius: 0; background: #1f1f1f; box-shadow: none; }
	.ai-markdown-code-toolbar { min-height: 48px; padding: 0 6px 0 20px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-bottom: 1px solid rgba(120, 145, 132, .25); background: rgba(255, 255, 255, .035); }
	.ai-markdown-code-language { min-width: 0; overflow: hidden; color: #b9c7bf; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }
	.ai-markdown-code-actions { display: flex; align-items: center; gap: 2px; }
	.ai-code-view-toggle { position: relative; width: 74px; height: 36px; display: flex; align-items: center; gap: 2px; }
	.ai-code-view-indicator { position: absolute; left: 0; top: 0; width: 36px; height: 36px; border-radius: 50%; background: rgba(255, 255, 255, .08); transform: translateX(0); transition: transform 200ms cubic-bezier(0.4, 0, 0.2, 1), background-color 150ms ease; pointer-events: none; }
	.ai-code-view-toggle.is-preview .ai-code-view-indicator { transform: translateX(38px); }
	.ai-code-icon-button { position: relative; z-index: 1; width: 36px; min-width: 36px; height: 36px; min-height: 36px; margin: 0; padding: 0; display: flex; align-items: center; justify-content: center; border: 0; border-radius: 50%; background: transparent; color: #d4d4d4; cursor: pointer; transition: color 150ms ease, background-color 150ms ease, transform 120ms cubic-bezier(0.23, 1, 0.32, 1); }
	.ai-code-icon-button:active { transform: scale(.97); }
	.ai-code-icon-button:focus-visible { outline: 2px solid #8fe8c4; outline-offset: 2px; }
	.ai-code-icon-button[disabled] { opacity: .42; cursor: default; transform: none; }
	.ai-code-toolbar-icon { width: 20px; height: 20px; overflow: visible; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; pointer-events: none; }
	.ai-code-preview-platform-note, .ai-code-preview-configuration-note { color: #9ba9a1; font-size: 11px; }
	.ai-code-preview-configuration-note { padding: 8px 14px; display: block; border-bottom: 1px solid rgba(120, 145, 132, .2); }
	.ai-markdown-copy-button { min-width: 44px; min-height: 44px; margin: 0; padding: 0 10px; border: 0; border-radius: 9px; background: transparent; color: #8fe8c4; font-size: 12px; cursor: pointer; }
	.ai-markdown-copy-button:active { background: rgba(143, 232, 196, .14); }
	.ai-markdown-copy-button:focus-visible { outline: 2px solid #8fe8c4; outline-offset: 2px; }
	.ai-markdown-copy-button[disabled] { opacity: .45; }
	.ai-markdown-code-scroll { width: 100%; box-sizing: border-box; background: #1f1f1f; }
	.ai-markdown-code-block.is-fullscreen .ai-markdown-code-scroll { flex: 1 1 auto; min-height: 0; }
	.ai-code-lines { min-width: 100%; min-height: 53px; padding: 16px; display: inline-block; box-sizing: border-box; color: #d4d4d4; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; line-height: 1.62; white-space: pre; word-break: normal; }
	.ai-markdown-code-block.is-fullscreen .ai-code-lines { min-height: 100%; }
	.ai-code-loading-placeholder { width: 38%; height: 1.1em; margin: .25em 0; border-radius: 4px; background: rgba(212, 212, 212, .08); }
	.ai-markdown-copy-status { display: block; padding: 0 14px 10px; color: #8fe8c4; font-size: 11px; }
	.ai-markdown-code-block.is-fullscreen .ai-markdown-copy-status { position: fixed; right: 12px; bottom: 12px; z-index: 10001; padding: 8px 10px; border-radius: 8px; background: rgba(27, 31, 29, .92); }
	.ai-code-focus-sentinel { position: fixed; width: 1px; min-width: 1px; height: 1px; min-height: 1px; margin: 0; padding: 0; overflow: hidden; border: 0; clip-path: inset(50%); white-space: nowrap; }
	@media (hover: hover) and (pointer: fine) {
		.ai-code-icon-button:not([disabled]):hover { background: rgba(255, 255, 255, .08); color: #ffffff; }
	}
	@media (prefers-reduced-motion: reduce) {
		.ai-markdown-copy-button, .ai-code-view-indicator, .ai-code-icon-button { transition: none; }
		.ai-code-icon-button:active { transform: none; }
	}
</style>

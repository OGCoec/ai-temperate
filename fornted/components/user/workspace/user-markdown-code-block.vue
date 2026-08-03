<template>
	<view class="ai-markdown-code-block" role="region" :aria-label="languageLabel + ' code'">
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
						class="ai-code-view-button"
						aria-label="代码"
						:aria-pressed="String(!isPreviewMode)"
						@click="setViewMode('code')"
					>
						<text class="ai-code-view-icon" aria-hidden="true">&lt;/&gt;</text>
					</button>
					<button
						type="button"
						class="ai-code-view-button"
						aria-label="预览"
						:aria-pressed="String(isPreviewMode)"
						:aria-disabled="String(previewDisabled)"
						:disabled="previewDisabled"
						@click="setViewMode('preview')"
					>
						<text class="ai-code-view-icon ai-code-preview-icon" aria-hidden="true">▶</text>
					</button>
				</view>
				<!-- #endif -->
				<!-- #ifndef H5 -->
				<text v-if="isHtmlPreviewable" class="ai-code-preview-platform-note">HTML 预览仅支持 H5</text>
				<!-- #endif -->
				<button
					class="ai-markdown-copy-button"
					type="button"
					aria-label="Copy code"
					:disabled="!code"
					@click="copyCode"
				>
					<text>{{ copyLabel }}</text>
				</button>
			</view>
		</view>
		<user-markdown-html-preview v-if="isPreviewMode" :code="code" />
		<scroll-view v-else class="ai-markdown-code-scroll" scroll-x :show-scrollbar="false">
			<view class="ai-code-lines" :class="{ 'is-loading': highlightStatus === 'loading' }">
				<user-markdown-code-lines :block-key="blockKey" :lines="highlightedStableLines" />
				<user-markdown-code-lines :block-key="blockKey" :lines="highlightedUnstableLines" />
				<view v-if="highlightStatus === 'loading' && code" class="ai-code-loading-placeholder" aria-hidden="true"></view>
			</view>
		</scroll-view>
		<text v-if="copyState" class="ai-markdown-copy-status" role="status">{{ copyState }}</text>
	</view>
</template>

<script>
	import { isAiHtmlPreviewLanguage } from '@/common/aichat/ai-html-preview-document.js'
	import { createAiCodeHighlightSession } from '@/common/aichat/ai-code-highlight-session.js'
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
			return {
				viewMode: 'code',
				copyState: '',
				highlightStatus: 'loading',
				highlightedLanguage: null,
				highlightedStableLines: [],
				highlightedUnstableLines: []
			}
		},
		computed: {
			languageLabel() {
				return this.highlightedLanguage?.label || this.language?.label || 'Plain text'
			},
			copyLabel() {
				return this.copyState === 'Copied' ? 'Copied' : 'Copy'
			},
			isHtmlPreviewable() {
				return isAiHtmlPreviewLanguage(this.language)
			},
			previewDisabled() {
				return this.streaming || !this.code
			},
			isPreviewMode() {
				return this.isHtmlPreviewable && !this.previewDisabled && this.viewMode === 'preview'
			}
		},
		watch: {
			code(nextCode, previousCode) {
				this.viewMode = 'code'
				if (!this.highlightSession) return
				const operation = this.streaming
					? this.highlightSession.update({ code: nextCode, previousCode, streaming: true })
					: this.highlightSession.complete({ finalCode: nextCode })
				void operation
			},
			streaming(nextStreaming, previousStreaming) {
				if (nextStreaming) this.viewMode = 'code'
				if (!nextStreaming && previousStreaming && this.highlightSession) {
					void this.highlightSession.complete({ finalCode: this.code })
				}
			},
			'language.id'() {
				this.viewMode = 'code'
				this.startHighlightSession()
			}
		},
		mounted() {
			this.startHighlightSession()
		},
		beforeUnmount() {
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
						fail: () => this.setCopyState('Copy failed')
					})
					return
				}
				if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
					navigator.clipboard.writeText(value)
						.then(() => this.setCopyState('Copied'))
						.catch(() => this.setCopyState('Copy failed'))
					return
				}
				this.setCopyState('Clipboard unavailable')
			}
		}
	}
</script>

<style lang="scss">
	.ai-markdown-code-block { margin: 14px 0; overflow: hidden; border: 1px solid rgba(120, 145, 132, .35); border-radius: 14px; background: #1f1f1f; box-shadow: 0 8px 28px rgba(0, 0, 0, .18); }
	.ai-markdown-code-toolbar { min-height: 44px; padding: 0 10px 0 14px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-bottom: 1px solid rgba(120, 145, 132, .25); background: rgba(255, 255, 255, .035); }
	.ai-markdown-code-language { color: #b9c7bf; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; font-weight: 700; }
	.ai-markdown-code-actions { display: flex; align-items: center; gap: 6px; }
	.ai-code-view-toggle { position: relative; display: flex; align-items: center; gap: 2px; }
	.ai-code-view-indicator { position: absolute; left: 0; top: 0; width: 36px; height: 36px; border-radius: 50%; background: rgba(255, 255, 255, .08); transform: translateX(0); transition: transform 200ms ease-in-out; pointer-events: none; }
	.ai-code-view-toggle.is-preview .ai-code-view-indicator { transform: translateX(38px); }
	.ai-code-view-button { position: relative; z-index: 1; width: 36px; min-width: 36px; height: 36px; min-height: 36px; margin: 0; padding: 0; display: flex; align-items: center; justify-content: center; border: 0; border-radius: 50%; background: transparent; color: #d4d4d4; cursor: pointer; }
	.ai-code-view-button:focus-visible { outline: 2px solid #8fe8c4; outline-offset: 2px; }
	.ai-code-view-button[disabled] { opacity: .42; cursor: default; }
	.ai-code-view-icon { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; line-height: 1; }
	.ai-code-preview-icon { font-size: 14px; }
	.ai-code-preview-platform-note { color: #9ba9a1; font-size: 11px; }
	.ai-markdown-copy-button { min-width: 44px; min-height: 44px; margin: 0; padding: 0 10px; border: 0; border-radius: 9px; background: transparent; color: #8fe8c4; font-size: 12px; }
	.ai-markdown-copy-button:active { background: rgba(143, 232, 196, .14); }
	.ai-markdown-copy-button:focus-visible { outline: 2px solid #8fe8c4; outline-offset: 2px; }
	.ai-markdown-copy-button { cursor: pointer; }
	.ai-markdown-copy-button[disabled] { opacity: .45; }
	.ai-markdown-code-scroll { width: 100%; box-sizing: border-box; background: #1f1f1f; }
	.ai-code-lines { min-width: 100%; min-height: 53px; padding: 16px; display: inline-block; box-sizing: border-box; color: #d4d4d4; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; line-height: 1.62; white-space: pre; word-break: normal; }
	.ai-code-loading-placeholder { width: 38%; height: 1.1em; margin: .25em 0; border-radius: 4px; background: rgba(212, 212, 212, .08); }
	.ai-markdown-copy-status { display: block; padding: 0 14px 10px; color: #8fe8c4; font-size: 11px; }
	@media (prefers-reduced-motion: reduce) {
		.ai-markdown-copy-button, .ai-code-view-indicator { transition: none; }
	}
</style>

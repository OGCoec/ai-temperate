<template>
	<view class="ai-markdown-code-block" role="region" :aria-label="languageLabel + ' code'">
		<view class="ai-markdown-code-toolbar">
			<text class="ai-markdown-code-language">{{ languageLabel }}</text>
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
		<scroll-view class="ai-markdown-code-scroll" scroll-x :show-scrollbar="false">
			<text class="ai-markdown-code-content" selectable>{{ code }}</text>
		</scroll-view>
		<text v-if="copyState" class="ai-markdown-copy-status" role="status">{{ copyState }}</text>
	</view>
</template>

<script>
	export default {
		name: 'UserMarkdownCodeBlock',
		props: {
			language: { type: Object, default: () => ({ id: 'plain', label: 'Plain text' }) },
			code: { type: String, default: '' },
			streaming: { type: Boolean, default: false }
		},
		data() {
			return { copyState: '' }
		},
		computed: {
			languageLabel() {
				return this.language?.label || 'Plain text'
			},
			copyLabel() {
				return this.copyState === 'Copied' ? 'Copied' : 'Copy'
			}
		},
		methods: {
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
	.ai-markdown-code-block { margin: 14px 0; overflow: hidden; border: 1px solid rgba(120, 145, 132, .35); border-radius: 14px; background: #101512; box-shadow: 0 8px 28px rgba(0, 0, 0, .18); }
	.ai-markdown-code-toolbar { min-height: 44px; padding: 0 10px 0 14px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-bottom: 1px solid rgba(120, 145, 132, .25); background: rgba(255, 255, 255, .035); }
	.ai-markdown-code-language { color: #b9c7bf; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; font-weight: 700; }
	.ai-markdown-copy-button { min-width: 44px; min-height: 44px; margin: 0; padding: 0 10px; border: 0; border-radius: 9px; background: transparent; color: #8fe8c4; font-size: 12px; }
	.ai-markdown-copy-button:active { background: rgba(143, 232, 196, .14); }
	.ai-markdown-copy-button:focus-visible { outline: 2px solid #8fe8c4; outline-offset: 2px; }
	.ai-markdown-copy-button { cursor: pointer; }
	.ai-markdown-copy-button[disabled] { opacity: .45; }
	.ai-markdown-code-scroll { width: 100%; box-sizing: border-box; }
	.ai-markdown-code-content { min-width: 100%; padding: 16px; display: block; box-sizing: border-box; color: #edf6f0; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; line-height: 1.62; white-space: pre; word-break: normal; }
	.ai-markdown-copy-status { display: block; padding: 0 14px 10px; color: #8fe8c4; font-size: 11px; }
	@media (prefers-reduced-motion: reduce) {
		.ai-markdown-copy-button { transition: none; }
	}
</style>

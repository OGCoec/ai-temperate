<template>
	<!-- #ifdef H5 -->
	<view class="ai-html-preview" role="document" aria-label="HTML 预览">
		<iframe
			v-if="previewUrl"
			class="ai-html-preview-frame"
			:src="previewUrl"
			sandbox="allow-scripts"
			referrerpolicy="no-referrer"
			title="HTML 预览"
		></iframe>
		<text v-else class="ai-html-preview-status" role="status">无法创建 HTML 预览</text>
	</view>
	<!-- #endif -->
	<!-- #ifndef H5 -->
	<view class="ai-html-preview ai-html-preview-unsupported" role="status">
		<text>当前平台暂不支持 HTML 预览</text>
	</view>
	<!-- #endif -->
</template>

<script>
	import { createAiHtmlPreviewDocument } from '@/common/aichat/ai-html-preview-document.js'

	export default {
		name: 'UserMarkdownHtmlPreview',
		props: {
			code: { type: String, default: '' }
		},
		data() {
			return { previewUrl: '' }
		},
		watch: {
			code: {
				immediate: true,
				handler() {
					this.refreshPreview()
				}
			}
		},
		beforeUnmount() {
			this.releasePreview()
		},
		methods: {
			refreshPreview() {
				this.releasePreview()
				// #ifdef H5
				if (typeof Blob === 'undefined' || typeof URL === 'undefined') return
				try {
					const documentText = createAiHtmlPreviewDocument(this.code)
					this.previewUrl = URL.createObjectURL(new Blob([documentText], {
						type: 'text/html;charset=utf-8'
					}))
				} catch (_) {
					this.previewUrl = ''
				}
				// #endif
			},
			releasePreview() {
				// #ifdef H5
				if (this.previewUrl && typeof URL !== 'undefined') {
					URL.revokeObjectURL(this.previewUrl)
				}
				// #endif
				this.previewUrl = ''
			}
		}
	}
</script>

<style lang="scss">
	.ai-html-preview { width: 100%; min-height: 280px; overflow: hidden; background: #fff; }
	.ai-html-preview-frame { width: 100%; height: min(56vh, 520px); min-height: 280px; display: block; border: 0; background: #fff; }
	.ai-html-preview-status { padding: 24px; display: block; color: #66736c; text-align: center; }
	.ai-html-preview-unsupported { min-height: 120px; display: flex; align-items: center; justify-content: center; }
</style>

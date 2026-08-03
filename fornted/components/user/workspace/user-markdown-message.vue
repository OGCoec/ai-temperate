<template>
	<view class="ai-markdown-message" :class="{ 'is-streaming': streaming, 'is-compact': compact }">
		<user-markdown-node :node="ast" :path="[]" :message-key="messageKey" />
	</view>
</template>

<script>
	import { parseAiResponse } from '@/common/aichat/ai-response-parser.js'
	import { decorateAiMarkdownSources } from '@/common/aichat/ai-conversation-source-presentation.js'
	import UserMarkdownNode from './user-markdown-node.vue'

	export default {
		name: 'UserMarkdownMessage',
		components: { UserMarkdownNode },
		props: {
			text: { type: String, default: '' },
			streaming: { type: Boolean, default: false },
			messageKey: { type: String, default: '' },
			sources: { type: Array, default: () => [] },
			compact: { type: Boolean, default: false }
		},
		computed: {
			ast() {
				return decorateAiMarkdownSources(
					parseAiResponse(this.text, { streaming: this.streaming }),
					this.sources)
			}
		}
	}
</script>

<style lang="scss">
	.ai-markdown-message { min-width: 0; }
	.ai-markdown-message.is-streaming { contain: content; }
	.ai-markdown-message.is-compact .ai-markdown-document { color: inherit; font-size: 12px; line-height: 1.6; }
	.ai-markdown-message.is-compact .ai-markdown-list { margin-bottom: 0; }
	.ai-markdown-message.is-compact .ai-markdown-list-item .ai-markdown-paragraph { margin-bottom: 3px; }
</style>

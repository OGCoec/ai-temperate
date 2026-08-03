<template>
	<view class="ai-markdown-message" :class="{ 'is-streaming': streaming }">
		<user-markdown-node :node="ast" :path="[]" :message-key="messageKey" />
	</view>
</template>

<script>
	import { parseAiMarkdown } from '@/common/aichat/ai-markdown-parser.js'
	import UserMarkdownNode from './user-markdown-node.vue'

	export default {
		name: 'UserMarkdownMessage',
		components: { UserMarkdownNode },
		props: {
			text: { type: String, default: '' },
			streaming: { type: Boolean, default: false },
			messageKey: { type: String, default: '' }
		},
		computed: {
			ast() {
				return parseAiMarkdown(this.text, { streaming: this.streaming })
			}
		}
	}
</script>

<style lang="scss">
	.ai-markdown-message { min-width: 0; }
	.ai-markdown-message.is-streaming { contain: content; }
</style>

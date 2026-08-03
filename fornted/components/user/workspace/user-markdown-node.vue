<template>
	<view v-if="node?.type === 'document'" class="ai-markdown-document">
		<user-markdown-node
			v-for="(child, index) in children(node)"
			:key="nodeKey(child, path.concat(String(index)))"
			:node="child"
			:path="path.concat(String(index))"
			:message-key="messageKey"
		/>
	</view>
	<view v-else-if="node?.type === 'paragraph'" class="ai-markdown-paragraph">
		<user-markdown-inline
			v-for="(child, index) in children(node)"
			:key="nodeKey(child, path.concat(String(index)))"
			:node="child"
			:path="path.concat(String(index))"
			:message-key="messageKey"
		/>
	</view>
	<view
		v-else-if="node?.type === 'heading'"
		class="ai-markdown-heading"
		:class="headingClass(node.level)"
		role="heading"
		:aria-level="String(node.level)"
	>
		<user-markdown-inline
			v-for="(child, index) in children(node)"
			:key="nodeKey(child, path.concat(String(index)))"
			:node="child"
			:path="path.concat(String(index))"
			:message-key="messageKey"
		/>
	</view>
	<view v-else-if="node?.type === 'blockquote'" class="ai-markdown-blockquote" role="blockquote">
		<user-markdown-node
			v-for="(child, index) in children(node)"
			:key="nodeKey(child, path.concat(String(index)))"
			:node="child"
			:path="path.concat(String(index))"
			:message-key="messageKey"
		/>
	</view>
	<view v-else-if="node?.type === 'thematicBreak'" class="ai-markdown-thematic-break" role="separator"></view>
	<view v-else-if="node?.type === 'unorderedList'" class="ai-markdown-list ai-markdown-unordered-list" role="list">
		<view
			v-for="(item, index) in children(node)"
			:key="nodeKey(item, path.concat(String(index)))"
			class="ai-markdown-list-row"
			role="listitem"
		>
			<view v-if="item.type === 'taskItem'" class="ai-markdown-task-marker" :class="{ checked: item.checked }" role="checkbox" :aria-checked="String(item.checked)" aria-disabled="true"></view>
			<text v-else class="ai-markdown-list-marker" aria-hidden="true">•</text>
			<view class="ai-markdown-list-content">
				<user-markdown-node :node="item" :path="path.concat(String(index))" :message-key="messageKey" />
			</view>
		</view>
	</view>
	<view v-else-if="node?.type === 'orderedList'" class="ai-markdown-list ai-markdown-ordered-list" role="list">
		<view
			v-for="(item, index) in children(node)"
			:key="nodeKey(item, path.concat(String(index)))"
			class="ai-markdown-list-row"
			role="listitem"
		>
			<view v-if="item.type === 'taskItem'" class="ai-markdown-task-marker" :class="{ checked: item.checked }" role="checkbox" :aria-checked="String(item.checked)" aria-disabled="true"></view>
			<text v-else class="ai-markdown-list-marker" aria-hidden="true">{{ Number(node.start || 1) + index }}.</text>
			<view class="ai-markdown-list-content">
				<user-markdown-node :node="item" :path="path.concat(String(index))" :message-key="messageKey" />
			</view>
		</view>
	</view>
	<view v-else-if="node?.type === 'listItem' || node?.type === 'taskItem'" class="ai-markdown-list-item">
		<user-markdown-node
			v-for="(child, index) in children(node)"
			:key="nodeKey(child, path.concat(String(index)))"
			:node="child"
			:path="path.concat(String(index))"
			:message-key="messageKey"
		/>
	</view>
	<user-markdown-code-block
		v-else-if="node?.type === 'codeBlock'"
		:block-key="messageKey + ':' + path.join('.')"
		:language="node.language"
		:code="node.code"
		:streaming="Boolean(node.streaming)"
	/>
	<user-markdown-table
		v-else-if="node?.type === 'table'"
		:headers="node.headers"
		:rows="node.rows"
		:alignments="node.alignments"
		:message-key="messageKey + ':' + path.join('.')"
	/>
	<text v-else class="ai-markdown-fallback">{{ fallbackText(node) }}</text>
</template>

<script>
	import UserMarkdownInline from './user-markdown-inline.vue'
	import UserMarkdownCodeBlock from './user-markdown-code-block.vue'
	import UserMarkdownTable from './user-markdown-table.vue'

	export default {
		name: 'UserMarkdownNode',
		components: {
			UserMarkdownInline,
			UserMarkdownCodeBlock,
			UserMarkdownTable
		},
		props: {
			node: { type: Object, default: () => ({ type: 'document', children: [] }) },
			path: { type: Array, default: () => [] },
			messageKey: { type: String, default: '' }
		},
		methods: {
			children(node) {
				return Array.isArray(node?.children) ? node.children : []
			},
			nodeKey(node, nodePath) {
				return this.messageKey + ':' + nodePath.join('.') + ':' + (node?.type || 'unknown')
			},
			headingClass(level) {
				const safeLevel = Math.min(6, Math.max(1, Number(level) || 1))
				return 'ai-markdown-heading-' + safeLevel
			},
			fallbackText(node) {
				if (node?.type === 'text') return String(node.value || '')
				return this.children(node).map(child => this.fallbackText(child)).join('')
			}
		}
	}
</script>

<style lang="scss">
	.ai-markdown-document { min-width: 0; color: #edf3f0; font-size: 15px; line-height: 1.72; word-break: break-word; }
	.ai-markdown-paragraph { margin: 0 0 13px; }
	.ai-markdown-heading { margin: 20px 0 10px; color: #f5faf7; line-height: 1.3; }
	.ai-markdown-heading-1 { font-size: 25px; font-weight: 780; }
	.ai-markdown-heading-2 { font-size: 21px; font-weight: 760; }
	.ai-markdown-heading-3 { font-size: 18px; font-weight: 730; }
	.ai-markdown-heading-4, .ai-markdown-heading-5, .ai-markdown-heading-6 { font-size: 16px; font-weight: 700; }
	.ai-markdown-blockquote { min-height: 64px; margin: 14px 0; padding: 2px 0 2px 14px; box-sizing: border-box; display: flex; flex-direction: column; justify-content: center; border-left: 3px solid #37d39a; color: #b9c7bf; text-align: left; }
	.ai-markdown-blockquote > .ai-markdown-paragraph { margin-bottom: 0; }
	.ai-markdown-thematic-break { height: 1px; margin: 20px 0; background: rgba(151, 171, 160, .45); }
	.ai-markdown-list { margin: 0 0 13px; padding: 0; }
	.ai-markdown-list-row { min-width: 0; display: flex; align-items: flex-start; gap: 9px; }
	.ai-markdown-list-marker { min-width: 19px; color: #8fe8c4; line-height: 1.72; text-align: right; }
	.ai-markdown-list-content { min-width: 0; flex: 1; }
	.ai-markdown-list-item .ai-markdown-paragraph { margin-bottom: 7px; }
	.ai-markdown-task-marker { width: 15px; height: 15px; margin-top: 6px; flex: 0 0 15px; box-sizing: border-box; border: 1px solid #7f9287; border-radius: 4px; }
	.ai-markdown-task-marker.checked { border-color: #37d39a; background: #37d39a; box-shadow: inset 0 0 0 3px #17241e; }
	.ai-markdown-fallback { white-space: pre-wrap; }
</style>

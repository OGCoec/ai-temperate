<template>
	<text v-if="node?.type === 'text'" class="ai-markdown-text">{{ node.value }}</text>
	<text v-else-if="node?.type === 'inlineCode'" class="ai-markdown-inline-code">{{ node.value }}</text>
	<text v-else-if="node?.type === 'strong'" class="ai-markdown-strong">
		<user-markdown-inline
			v-for="(child, index) in children(node)"
			:key="nodeKey(child, path.concat(String(index)))"
			:node="child"
			:path="path.concat(String(index))"
			:message-key="messageKey"
		/>
	</text>
	<text v-else-if="node?.type === 'emphasis'" class="ai-markdown-emphasis">
		<user-markdown-inline
			v-for="(child, index) in children(node)"
			:key="nodeKey(child, path.concat(String(index)))"
			:node="child"
			:path="path.concat(String(index))"
			:message-key="messageKey"
		/>
	</text>
	<text v-else-if="node?.type === 'deletion'" class="ai-markdown-deletion">
		<user-markdown-inline
			v-for="(child, index) in children(node)"
			:key="nodeKey(child, path.concat(String(index)))"
			:node="child"
			:path="path.concat(String(index))"
			:message-key="messageKey"
		/>
	</text>
	<navigator
		v-else-if="node?.type === 'link' && node.safe"
		class="ai-markdown-link"
		:url="node.href"
		target="_blank"
	>
		<user-markdown-inline
			v-for="(child, index) in children(node)"
			:key="nodeKey(child, path.concat(String(index)))"
			:node="child"
			:path="path.concat(String(index))"
			:message-key="messageKey"
		/>
	</navigator>
	<text v-else-if="node?.type === 'link'">
		<user-markdown-inline
			v-for="(child, index) in children(node)"
			:key="nodeKey(child, path.concat(String(index)))"
			:node="child"
			:path="path.concat(String(index))"
			:message-key="messageKey"
		/>
	</text>
	<text v-else>{{ fallbackText(node) }}</text>
</template>

<script>
	const INLINE_TYPES = new Set([
		'text',
		'inlineCode',
		'strong',
		'emphasis',
		'deletion',
		'link'
	])

	export default {
		name: 'UserMarkdownInline',
		props: {
			node: { type: Object, default: () => ({ type: 'text', value: '' }) },
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
			fallbackText(node) {
				if (node?.type === 'text') return String(node.value || '')
				if (INLINE_TYPES.has(node?.type)) {
					return this.children(node).map(child => this.fallbackText(child)).join('')
				}
				return ''
			}
		}
	}
</script>

<style lang="scss">
	.ai-markdown-text { color: inherit; }
	.ai-markdown-inline-code { padding: 2px 5px; border-radius: 5px; background: rgba(255, 255, 255, .09); color: #b7f3db; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .92em; }
	.ai-markdown-strong { font-weight: 700; }
	.ai-markdown-emphasis { font-style: italic; }
	.ai-markdown-deletion { text-decoration: line-through; opacity: .82; }
	.ai-markdown-link { color: #7de7bb; text-decoration: underline; text-decoration-thickness: 1px; text-underline-offset: 2px; }
</style>

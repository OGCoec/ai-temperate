<template>
	<view class="ai-code-line-group">
		<user-markdown-code-line
			v-for="line in lines"
			:key="blockKey + ':line:' + line.index"
			:block-key="blockKey"
			:line="line"
		/>
	</view>
</template>

<script>
	import UserMarkdownCodeLine from './user-markdown-code-line.vue'

	export default {
		name: 'UserMarkdownCodeLines',
		components: { UserMarkdownCodeLine },
		props: {
			blockKey: { type: String, default: '' },
			lines: { type: Array, default: () => [] }
		}
	}
</script>

<style lang="scss">
	.ai-code-line-group { display: block; }
</style>

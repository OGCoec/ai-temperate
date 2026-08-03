<template>
	<view class="ai-code-line">
		<text
			v-for="token in line.tokens"
			:key="blockKey + ':' + line.index + ':' + token.index"
			:class="tokenClasses(token)"
			selectable
		>{{ token.content }}</text>
	</view>
</template>

<script>
	const ALLOWED_COLOR_ROLES = new Set([
		'foreground', 'comment', 'keyword', 'control', 'type', 'function',
		'variable', 'constant', 'string', 'number', 'regexp', 'invalid',
		'accent-gold', 'regexp-muted', 'markup-list', 'tag-punctuation',
		'label', 'header', 'bracket-level-1', 'bracket-level-2',
		'bracket-level-3', 'bracket-error'
	])
	const ALLOWED_FONT_STYLES = new Set(['italic', 'bold', 'underline'])

	export default {
		name: 'UserMarkdownCodeLine',
		props: {
			blockKey: { type: String, default: '' },
			line: { type: Object, default: () => ({ index: 0, tokens: [] }) }
		},
		methods: {
			tokenClasses(token) {
				const colorRole = ALLOWED_COLOR_ROLES.has(token?.colorRole)
					? token.colorRole
					: 'foreground'
				const classes = ['ai-code-token', 'ai-code-color-' + colorRole]
				for (const fontStyle of token?.fontStyles || []) {
					if (ALLOWED_FONT_STYLES.has(fontStyle)) classes.push('is-' + fontStyle)
				}
				return classes
			}
		}
	}
</script>

<style lang="scss">
	.ai-code-line { min-height: 1.62em; display: block; white-space: pre; }
	.ai-code-token { font-family: inherit; font-size: inherit; line-height: inherit; white-space: pre; }
	.ai-code-token.is-italic { font-style: italic; }
	.ai-code-token.is-bold { font-weight: 700; }
	.ai-code-token.is-underline { text-decoration: underline; }
	.ai-code-color-foreground { color: #d4d4d4; }
	.ai-code-color-comment { color: #6a9955; }
	.ai-code-color-keyword { color: #569cd6; }
	.ai-code-color-control { color: #c586c0; }
	.ai-code-color-type { color: #4ec9b0; }
	.ai-code-color-function { color: #dcdcaa; }
	.ai-code-color-variable { color: #9cdcfe; }
	.ai-code-color-constant { color: #4fc1ff; }
	.ai-code-color-string { color: #ce9178; }
	.ai-code-color-number { color: #b5cea8; }
	.ai-code-color-regexp { color: #d16969; }
	.ai-code-color-invalid { color: #f44747; }
	.ai-code-color-accent-gold { color: #d7ba7d; }
	.ai-code-color-regexp-muted { color: #646695; }
	.ai-code-color-markup-list { color: #6796e6; }
	.ai-code-color-tag-punctuation { color: #808080; }
	.ai-code-color-label { color: #c8c8c8; }
	.ai-code-color-header { color: #000080; }
	.ai-code-color-bracket-level-1 { color: #ffd700; }
	.ai-code-color-bracket-level-2 { color: #da70d6; }
	.ai-code-color-bracket-level-3 { color: #179fff; }
	.ai-code-color-bracket-error { color: rgba(255, 18, 18, .8); }
</style>

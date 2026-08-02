<template>
	<view class="ai-markdown-table-region" role="region" aria-label="Markdown table">
		<view class="ai-markdown-table-toolbar">
			<text class="ai-markdown-table-label">Table</text>
			<button class="ai-markdown-table-copy" type="button" aria-label="Copy table as TSV" @click="copyTable">
				<text>{{ copyLabel }}</text>
			</button>
		</view>
		<scroll-view class="ai-markdown-table-scroll" scroll-x :show-scrollbar="false">
			<view class="ai-markdown-table" role="table">
				<view class="ai-markdown-table-row ai-markdown-table-head" role="row">
					<view
						v-for="(cell, index) in headers"
						:key="cellKey(cell, index, 'header')"
						class="ai-markdown-table-cell"
						role="columnheader"
						:style="cellStyle(index)"
					>
						<user-markdown-inline
							v-for="(child, childIndex) in cellChildren(cell)"
							:key="cellKey(child, childIndex, 'header-child')"
							:node="child"
							:path="[index, childIndex]"
							:message-key="messageKey"
						/>
					</view>
				</view>
				<view
					v-for="(row, rowIndex) in rows"
					:key="rowKey(row, rowIndex)"
					class="ai-markdown-table-row"
					role="row"
				>
					<view
						v-for="(cell, cellIndex) in row"
						:key="cellKey(cell, cellIndex, 'row-' + rowIndex)"
						class="ai-markdown-table-cell"
						role="cell"
						:style="cellStyle(cellIndex)"
					>
						<user-markdown-inline
							v-for="(child, childIndex) in cellChildren(cell)"
							:key="cellKey(child, childIndex, 'row-child-' + rowIndex)"
							:node="child"
							:path="[rowIndex, cellIndex, childIndex]"
							:message-key="messageKey"
						/>
					</view>
				</view>
			</view>
		</scroll-view>
		<text v-if="copyState" class="ai-markdown-table-status" role="status">{{ copyState }}</text>
	</view>
</template>

<script>
	import UserMarkdownInline from './user-markdown-inline.vue'

	export default {
		name: 'UserMarkdownTable',
		components: { UserMarkdownInline },
		props: {
			headers: { type: Array, default: () => [] },
			rows: { type: Array, default: () => [] },
			alignments: { type: Array, default: () => [] },
			messageKey: { type: String, default: '' }
		},
		data() {
			return { copyState: '' }
		},
		computed: {
			copyLabel() {
				return this.copyState === 'Copied' ? 'Copied' : 'Copy TSV'
			}
		},
		methods: {
			cellChildren(cell) {
				return Array.isArray(cell?.children) ? cell.children : []
			},
			cellKey(cell, index, scope) {
				return this.messageKey + ':' + scope + ':' + index + ':' + (cell?.type || 'cell')
			},
			rowKey(row, index) {
				return this.messageKey + ':row:' + index + ':' + (row?.length || 0)
			},
			cellStyle(index) {
				const alignment = this.alignments[index]
				return alignment ? { textAlign: alignment } : {}
			},
			cellText(cell) {
				if (!cell) return ''
				if (cell.type === 'text' || cell.type === 'inlineCode') return String(cell.value || '')
				return this.cellChildren(cell).map(child => this.cellText(child)).join('')
			},
			tableAsTsv() {
				const lines = [
					this.headers.map(cell => this.cellText(cell)).join('\t')
				]
				for (const row of this.rows) {
					lines.push(row.map(cell => this.cellText(cell)).join('\t'))
				}
				return lines.join('\n')
			},
			setCopyState(value) {
				this.copyState = value
				if (!value) return
				setTimeout(() => {
					if (this.copyState === value) this.copyState = ''
				}, 2200)
			},
			copyTable() {
				const value = this.tableAsTsv()
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
	.ai-markdown-table-region { margin: 14px 0; overflow: hidden; border: 1px solid rgba(120, 145, 132, .35); border-radius: 14px; background: #121815; }
	.ai-markdown-table-toolbar { min-height: 42px; padding: 0 10px 0 14px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid rgba(120, 145, 132, .25); }
	.ai-markdown-table-label { color: #b9c7bf; font-size: 12px; font-weight: 700; }
	.ai-markdown-table-copy { min-width: 44px; min-height: 44px; margin: 0; padding: 0 10px; border: 0; border-radius: 9px; background: transparent; color: #8fe8c4; font-size: 12px; }
	.ai-markdown-table-copy:active { background: rgba(143, 232, 196, .14); }
	.ai-markdown-table-copy:focus-visible { outline: 2px solid #8fe8c4; outline-offset: 2px; }
	.ai-markdown-table-copy { cursor: pointer; }
	.ai-markdown-table-scroll { width: 100%; }
	.ai-markdown-table { min-width: 100%; display: table; border-collapse: collapse; }
	.ai-markdown-table-row { display: table-row; }
	.ai-markdown-table-head { background: rgba(255, 255, 255, .045); }
	.ai-markdown-table-cell { min-width: 120px; padding: 10px 12px; display: table-cell; border-bottom: 1px solid rgba(120, 145, 132, .18); color: #e5eee9; font-size: 13px; line-height: 1.55; vertical-align: top; }
	.ai-markdown-table-head .ai-markdown-table-cell { color: #b9f0d5; font-weight: 700; }
	.ai-markdown-table-status { display: block; padding: 0 14px 10px; color: #8fe8c4; font-size: 11px; }
</style>

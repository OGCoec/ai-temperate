<template>
	<view class="ai-markdown-table-region" role="region" aria-label="Markdown 表格">
		<view class="ai-markdown-table-toolbar">
			<text class="ai-markdown-table-label">Table</text>
			<button class="ai-markdown-table-copy" type="button" aria-label="Copy table as TSV" @click="copyTable">
				<text>{{ copyLabel }}</text>
			</button>
		</view>
		<scroll-view
			class="ai-markdown-table-scroll"
			scroll-x
			:show-scrollbar="true"
			tabindex="0"
			aria-label="表格可左右滑动查看更多内容"
		>
			<view class="ai-markdown-table" role="table" :style="tableStyle">
				<view class="ai-markdown-table-row ai-markdown-table-head" role="row">
					<view
						v-for="(cell, index) in headers"
						:key="cellKey(cell, index, 'header')"
						class="ai-markdown-table-cell"
						:class="cellClass(cell, index, true)"
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
						:class="cellClass(cell, cellIndex, false)"
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
	import {
		AI_MARKDOWN_TABLE_COLUMN_WIDTH_STEPS,
		aiMarkdownTableAsTsv,
		aiMarkdownTableCellText,
		aiMarkdownTableCellNeedsTokenBreak,
		createAiMarkdownTableLayout
	} from '@/common/aichat/ai-markdown-table-layout.js'
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
			},
			tableLayout() {
				return createAiMarkdownTableLayout(this.headers, this.rows, this.alignments)
			},
			columnProfiles() {
				return this.tableLayout.columnProfiles
			},
			tableMinWidth() {
				return this.tableLayout.tableMinWidth
			},
			tableStyle() {
				return {
					width: '100%',
					minWidth: this.tableMinWidth + 'px'
				}
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
			cellText(cell) {
				return aiMarkdownTableCellText(cell)
			},
			cellClass(cell, index, header) {
				const profile = this.columnProfiles[index] || {}
				return {
					'is-numeric': Boolean(profile.numeric && !header),
					'is-breakable': aiMarkdownTableCellNeedsTokenBreak(cell)
				}
			},
			cellStyle(index) {
				const profile = this.columnProfiles[index] || {
					width: AI_MARKDOWN_TABLE_COLUMN_WIDTH_STEPS[0],
					alignment: 'left'
				}
				const width = profile.width + 'px'
				return {
					width,
					minWidth: width,
					maxWidth: width,
					textAlign: profile.alignment
				}
			},
			tableAsTsv() {
				return aiMarkdownTableAsTsv(this.headers, this.rows)
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
	.ai-markdown-table-region { width: 100%; max-width: 100%; min-width: 0; margin: 14px 0; overflow: hidden; border: 1px solid rgba(120, 145, 132, .35); border-radius: 14px; background: #121815; box-sizing: border-box; }
	.ai-markdown-table-toolbar { min-height: 42px; padding: 0 10px 0 14px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid rgba(120, 145, 132, .25); }
	.ai-markdown-table-label { color: #b9c7bf; font-size: 12px; font-weight: 700; }
	.ai-markdown-table-copy { min-width: 44px; min-height: 44px; flex: 0 0 auto; margin: 0; padding: 0 10px; border: 0; border-radius: 9px; background: transparent; color: #8fe8c4; font-size: 12px; }
	.ai-markdown-table-copy:active { background: rgba(143, 232, 196, .14); }
	.ai-markdown-table-copy:focus-visible { outline: 2px solid #8fe8c4; outline-offset: 2px; }
	.ai-markdown-table-copy { cursor: pointer; }
	.ai-markdown-table-scroll { width: 100%; max-width: 100%; min-width: 0; overflow-x: auto; overscroll-behavior-x: contain; scrollbar-width: thin; scrollbar-color: rgba(143, 232, 196, .45) rgba(255, 255, 255, .04); -webkit-overflow-scrolling: touch; }
	.ai-markdown-table-scroll::-webkit-scrollbar { height: 8px; }
	.ai-markdown-table-scroll::-webkit-scrollbar-track { background: rgba(255, 255, 255, .04); }
	.ai-markdown-table-scroll::-webkit-scrollbar-thumb { border-radius: 999px; background: rgba(143, 232, 196, .42); }
	.ai-markdown-table { display: table; border-collapse: collapse; table-layout: fixed; }
	.ai-markdown-table-row { display: table-row; }
	.ai-markdown-table-head { background: rgba(255, 255, 255, .045); }
	.ai-markdown-table-cell { padding: 10px 12px; display: table-cell; border-bottom: 1px solid rgba(120, 145, 132, .18); color: #e5eee9; font-size: 13px; line-height: 1.55; vertical-align: top; white-space: normal; overflow-wrap: anywhere; word-break: break-word; box-sizing: border-box; }
	.ai-markdown-table-cell.is-breakable { word-break: break-all; }
	.ai-markdown-table-cell.is-numeric { white-space: nowrap; overflow-wrap: normal; word-break: normal; font-variant-numeric: tabular-nums; }
	.ai-markdown-table-cell .ai-markdown-inline-code,
	.ai-markdown-table-cell .ai-markdown-link { max-width: 100%; white-space: normal; overflow-wrap: anywhere; word-break: break-all; }
	.ai-markdown-table-head .ai-markdown-table-cell { color: #b9f0d5; font-weight: 700; }
	.ai-markdown-table-status { display: block; padding: 0 14px 10px; color: #8fe8c4; font-size: 11px; }
</style>

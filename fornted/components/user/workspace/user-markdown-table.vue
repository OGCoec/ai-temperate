<template>
	<view class="ai-markdown-table-region" :class="{ 'is-h5-table': h5TableLayout }" role="region" aria-label="Markdown 表格">
		<view class="ai-markdown-table-toolbar">
			<text class="ai-markdown-table-label">Table</text>
			<view class="ai-markdown-table-actions">
				<!-- #ifdef H5 -->
				<view class="ai-markdown-table-view-toggle" role="group" aria-label="表格显示方式">
					<button
						class="ai-markdown-table-view-button"
						:class="{ 'is-active': resolvedViewMode === 'cards' }"
						type="button"
						:aria-pressed="String(resolvedViewMode === 'cards')"
						@click="tableViewMode = 'cards'"
					>卡片</button>
					<button
						class="ai-markdown-table-view-button"
						:class="{ 'is-active': resolvedViewMode === 'table' }"
						type="button"
						:aria-pressed="String(resolvedViewMode === 'table')"
						@click="tableViewMode = 'table'"
					>表格</button>
				</view>
				<!-- #endif -->
				<button class="ai-markdown-table-copy" type="button" aria-label="Copy table as TSV" @click="copyTable">
					<text>{{ copyLabel }}</text>
				</button>
			</view>
		</view>
		<!-- #ifdef H5 -->
		<view v-if="resolvedViewMode === 'cards'" class="ai-markdown-table-cards" role="list">
			<view
				v-for="(row, rowIndex) in rows"
				:key="rowKey(row, rowIndex)"
				class="ai-markdown-table-card"
				role="listitem"
			>
				<view class="ai-markdown-table-card-title">
					<user-markdown-inline
						v-for="(child, childIndex) in cellChildren(row[0])"
						:key="cellKey(child, childIndex, 'card-title-' + rowIndex)"
						:node="child"
						:path="[rowIndex, 0, childIndex]"
						:message-key="messageKey"
					/>
					<text v-if="!cellChildren(row[0]).length">第 {{ rowIndex + 1 }} 项</text>
				</view>
				<view
					v-for="(cell, cellIndex) in row.slice(1)"
					:key="cellKey(cell, cellIndex + 1, 'card-row-' + rowIndex)"
					class="ai-markdown-table-card-field"
				>
					<text class="ai-markdown-table-card-label">{{ cellText(headers[cellIndex + 1]) || `列 ${cellIndex + 2}` }}</text>
					<view class="ai-markdown-table-card-value" :class="cellClass(cell, cellIndex + 1, false)">
						<user-markdown-inline
							v-for="(child, childIndex) in cellChildren(cell)"
							:key="cellKey(child, childIndex, 'card-value-' + rowIndex + '-' + cellIndex)"
							:node="child"
							:path="[rowIndex, cellIndex + 1, childIndex]"
							:message-key="messageKey"
						/>
					</view>
				</view>
			</view>
		</view>
		<!-- #endif -->
		<scroll-view
			class="ai-markdown-table-scroll"
			:class="{ 'is-view-hidden': h5TableLayout && resolvedViewMode !== 'table' }"
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
		resolveAiMarkdownTableViewMode,
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
			let h5TableLayout = false
			let compactViewport = false
			// #ifdef H5
			h5TableLayout = true
			compactViewport = typeof window !== 'undefined'
				&& typeof window.matchMedia === 'function'
				&& window.matchMedia('(max-width: 767px)').matches
			// #endif
			return {
				copyState: '',
				tableViewMode: 'auto',
				h5TableLayout,
				compactViewport,
				tableMediaQuery: null
			}
		},
		mounted() {
			// #ifdef H5
			if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
			this.tableMediaQuery = window.matchMedia('(max-width: 767px)')
			this.compactViewport = this.tableMediaQuery.matches
			this.tableMediaQuery.addEventListener?.('change', this.handleTableViewportChange)
			// #endif
		},
		beforeUnmount() {
			// #ifdef H5
			this.tableMediaQuery?.removeEventListener?.('change', this.handleTableViewportChange)
			this.tableMediaQuery = null
			// #endif
		},
		beforeDestroy() {
			// #ifdef H5
			this.tableMediaQuery?.removeEventListener?.('change', this.handleTableViewportChange)
			this.tableMediaQuery = null
			// #endif
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
			},
			resolvedViewMode() {
				if (!this.h5TableLayout) return 'table'
				return resolveAiMarkdownTableViewMode(
					this.tableViewMode,
					this.columnProfiles.length,
					this.compactViewport ? 767 : 768
				)
			}
		},
		methods: {
			handleTableViewportChange(event) {
				this.compactViewport = Boolean(event?.matches)
			},
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
	.ai-markdown-table-region { width: 100%; max-width: 100%; min-width: 0; margin: 16px 0; overflow: hidden; border: 1px solid rgba(151, 170, 160, .26); border-radius: 12px; background: #151816; box-sizing: border-box; }
	.ai-markdown-table-toolbar { min-height: 40px; padding: 0 8px 0 12px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid rgba(151, 170, 160, .18); }
	.ai-markdown-table-region.is-h5-table .ai-markdown-table-toolbar { min-height: 56px; padding-right: 6px; gap: 8px; }
	.ai-markdown-table-label { color: #b9c7bf; font-size: 12px; font-weight: 700; }
	.ai-markdown-table-actions, .ai-markdown-table-view-toggle { min-width: 0; display: flex; align-items: center; gap: 8px; }
	.ai-markdown-table-view-toggle { padding: 3px; border: 1px solid rgba(151, 170, 160, .18); border-radius: 10px; background: rgba(255, 255, 255, .025); }
	.ai-markdown-table-view-button { min-width: 44px; min-height: 44px; margin: 0; padding: 0 9px; border: 0; border-radius: 8px; background: transparent; color: #8f9a94; font-size: 11px; cursor: pointer; }
	.ai-markdown-table-view-button.is-active { background: rgba(55, 211, 154, .12); color: #b9f0d5; }
	.ai-markdown-table-view-button:active { transform: scale(.97); }
	.ai-markdown-table-view-button:focus-visible { outline: 2px solid #8fe8c4; outline-offset: 2px; }
	.ai-markdown-table-copy { min-width: 44px; min-height: 44px; flex: 0 0 auto; margin: 0; padding: 0 10px; border: 0; border-radius: 9px; background: transparent; color: #8fe8c4; font-size: 12px; }
	.ai-markdown-table-copy:active { background: rgba(143, 232, 196, .14); }
	.ai-markdown-table-copy:focus-visible { outline: 2px solid #8fe8c4; outline-offset: 2px; }
	.ai-markdown-table-copy { cursor: pointer; }
	.ai-markdown-table-scroll { width: 100%; max-width: 100%; min-width: 0; overflow-x: auto; overscroll-behavior-x: contain; scrollbar-width: thin; scrollbar-color: rgba(143, 232, 196, .45) rgba(255, 255, 255, .04); -webkit-overflow-scrolling: touch; }
	.ai-markdown-table-scroll.is-view-hidden { display: none; }
	.ai-markdown-table-scroll::-webkit-scrollbar { height: 8px; }
	.ai-markdown-table-scroll::-webkit-scrollbar-track { background: rgba(255, 255, 255, .04); }
	.ai-markdown-table-scroll::-webkit-scrollbar-thumb { border-radius: 999px; background: rgba(143, 232, 196, .42); }
	.ai-markdown-table { display: table; border-collapse: collapse; table-layout: fixed; }
	.ai-markdown-table-row { display: table-row; }
	.ai-markdown-table-head { background: rgba(255, 255, 255, .045); }
	.ai-markdown-table-cell { padding: 8px 10px; display: table-cell; border-bottom: 1px solid rgba(151, 170, 160, .14); color: #e5eee9; font-size: 13px; line-height: 1.55; vertical-align: top; white-space: normal; overflow-wrap: anywhere; word-break: break-word; box-sizing: border-box; }
	.ai-markdown-table-cell.is-breakable { word-break: break-all; }
	.ai-markdown-table-cell.is-numeric { white-space: nowrap; overflow-wrap: normal; word-break: normal; font-variant-numeric: tabular-nums; }
	.ai-markdown-table-cell .ai-markdown-inline-code,
	.ai-markdown-table-cell .ai-markdown-link { max-width: 100%; white-space: normal; overflow-wrap: anywhere; word-break: break-all; }
	.ai-markdown-table-head .ai-markdown-table-cell { color: #b9f0d5; font-weight: 700; }
	.ai-markdown-table-cards { padding: 10px; display: grid; gap: 10px; }
	.ai-markdown-table-card { min-width: 0; padding: 12px; border: 1px solid rgba(151, 170, 160, .16); border-radius: 11px; background: rgba(255, 255, 255, .025); }
	.ai-markdown-table-card-title { padding-bottom: 10px; color: #dff8ed; font-size: 14px; font-weight: 720; line-height: 1.45; overflow-wrap: anywhere; }
	.ai-markdown-table-card-field { min-width: 0; padding: 8px 0; display: grid; grid-template-columns: minmax(88px, .38fr) minmax(0, 1fr); gap: 12px; border-top: 1px solid rgba(151, 170, 160, .12); }
	.ai-markdown-table-card-label { color: #92a099; font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
	.ai-markdown-table-card-value { min-width: 0; color: #e5eee9; font-size: 13px; line-height: 1.55; overflow-wrap: anywhere; word-break: break-word; }
	.ai-markdown-table-card-value.is-breakable { word-break: break-all; }
	.ai-markdown-table-card-value.is-numeric { font-variant-numeric: tabular-nums; }
	.ai-markdown-table-status { display: block; padding: 0 14px 10px; color: #8fe8c4; font-size: 11px; }
	@media (prefers-reduced-motion: reduce) { .ai-markdown-table-view-button:active { transform: none; } }
</style>

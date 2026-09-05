<template>
	<view
		class="recent-conversations"
		:class="{ 'is-compact': compact }"
	>
		<button
			class="recent-toggle"
			type="button"
			:aria-expanded="String(expanded)"
			:aria-controls="contentId"
			@click="$emit('toggle')"
		>
			<text>最近</text>
			<uni-icons
				:type="expanded ? 'down' : 'right'"
				size="15"
				color="#89958f"
				aria-hidden="true"
			/>
		</button>

		<view v-if="expanded" :id="contentId" class="recent-content">
			<scroll-view
				ref="recentList"
				class="recent-list"
				scroll-y
				:scroll-top="recentScrollTarget"
				:show-scrollbar="false"
				@scroll="handleRecentScroll"
				:lower-threshold="96"
				@scrolltolower="requestLoadMore"
			>
				<view class="recent-scroll-content">
					<view
						v-for="conversation in conversations"
						:key="conversation.conversationPublicId"
						class="conversation-row"
						:class="{ 'is-active': currentConversationPublicId === conversation.conversationPublicId }"
					>
						<button
							class="conversation-open"
							type="button"
							@click="$emit('open', conversation.conversationPublicId)"
						>
							<text>{{ conversation.title || '未命名对话' }}</text>
						</button>
						<button
							class="conversation-copy"
							type="button"
							aria-label="复制会话 ID"
							@click.stop="$emit('copy', conversation.conversationPublicId)"
						>
							<uni-icons type="paperclip" size="15" color="#89958f" aria-hidden="true" />
						</button>
					</view>

					<view v-if="loading && !conversations.length" class="recent-status" role="status">
						<text>正在加载…</text>
					</view>
					<view v-else-if="loaded && !conversations.length && !error" class="recent-status">
						<text>暂无最近会话</text>
					</view>
					<view v-if="error" class="recent-error" role="alert">
						<text>{{ error }}</text>
						<button type="button" @click="$emit('retry')">重试</button>
					</view>
					<view v-if="loading && conversations.length" class="recent-status" role="status">
						<text>正在加载更多…</text>
					</view>
					<view
						v-else-if="loaded && conversations.length && !hasMore && !error"
						class="recent-status"
					>
						<text>已加载全部会话</text>
					</view>
				</view>
			</scroll-view>
			<view v-if="recentScrollbarVisible" class="recent-scrollbar">
				<button
					class="recent-scrollbar-thumb"
					:class="{ 'is-dragging': recentScrollbarDragging }"
					type="button"
					role="scrollbar"
					aria-orientation="vertical"
					:aria-controls="contentId"
					:aria-label="recentScrollbarLabel"
					:aria-valuemin="0"
					:aria-valuemax="Math.round(recentMaxScrollTop)"
					:aria-valuenow="Math.round(recentScrollTop)"
					:style="recentScrollbarThumbStyle"
					@mousedown.stop.prevent="startRecentScrollbarDrag"
					@touchstart.stop.prevent="startRecentScrollbarDrag"
					@touchmove.stop.prevent="moveRecentScrollbarDrag"
					@touchend.stop="finishRecentScrollbarDrag"
					@touchcancel.stop="finishRecentScrollbarDrag"
					@keydown="handleRecentScrollbarKeydown"
				></button>
			</view>
		</view>
	</view>
</template>

<script>
	const RECENT_SCROLLBAR_MIN_THUMB_PX = 40
	const RECENT_SCROLLBAR_KEY_STEP_PX = 48
	const RECENT_SCROLLBAR_TRACK_INSET_PX = 4
	const RECENT_LOAD_MORE_THRESHOLD_PX = 96

	export default {
		data() {
			return {
				recentScrollTop: 0,
				recentScrollTarget: 0,
				recentViewportHeight: 0,
				recentContentHeight: 0,
				recentBottomZoneEntered: false,
				recentScrollbarDragging: false,
				recentScrollbarDragStartY: null,
				recentScrollbarDragStartTop: 0
			}
		},
		props: {
			contentId: {
				type: String,
				required: true
			},
			expanded: {
				type: Boolean,
				default: false
			},
			compact: {
				type: Boolean,
				default: false
			},
			conversations: {
				type: Array,
				default: () => []
			},
			currentConversationPublicId: {
				type: String,
				default: ''
			},
			loaded: {
				type: Boolean,
				default: false
			},
			loading: {
				type: Boolean,
				default: false
			},
			error: {
				type: String,
				default: ''
			},
			hasMore: {
				type: Boolean,
				default: false
			}
		},
		computed: {
			recentMaxScrollTop() {
				return Math.max(0, this.recentContentHeight - this.recentViewportHeight)
			},
			recentScrollbarTrackHeight() {
				return Math.max(
					0,
					this.recentViewportHeight - RECENT_SCROLLBAR_TRACK_INSET_PX * 2
				)
			},
			recentScrollbarVisible() {
				return this.recentViewportHeight > 0 && this.recentMaxScrollTop > 1
			},
			recentScrollbarThumbHeight() {
				if (!this.recentScrollbarVisible) return 0
				const proportionalHeight = this.recentScrollbarTrackHeight
					* (this.recentViewportHeight / this.recentContentHeight)
				return Math.min(
					this.recentScrollbarTrackHeight,
					Math.max(RECENT_SCROLLBAR_MIN_THUMB_PX, Math.round(proportionalHeight))
				)
			},
			recentScrollbarThumbOffset() {
				const trackTravel = Math.max(
					0,
					this.recentScrollbarTrackHeight - this.recentScrollbarThumbHeight
				)
				if (!trackTravel || !this.recentMaxScrollTop) return 0
				return Math.round((this.recentScrollTop / this.recentMaxScrollTop) * trackTravel)
			},
			recentScrollbarThumbStyle() {
				return {
					height: `${this.recentScrollbarThumbHeight}px`,
					transform: `translate3d(0, ${this.recentScrollbarThumbOffset}px, 0)`
				}
			},
			recentScrollbarLabel() {
				if (!this.recentMaxScrollTop) return '最近会话滚动条'
				const progress = Math.round((this.recentScrollTop / this.recentMaxScrollTop) * 100)
				return `最近会话滚动条，当前位置 ${progress}%`
			}
		},
		watch: {
			expanded(expanded) {
				if (!expanded) {
					this.finishRecentScrollbarDrag()
					this.resetRecentScrollbarMetrics()
					return
				}
				this.scheduleRecentScrollbarMeasurement()
			},
			conversations: {
				handler() {
					this.recentBottomZoneEntered = false
					this.scheduleRecentScrollbarMeasurement()
				},
				deep: true
			},
			loading(loading) {
				if (!loading) {
					this.recentBottomZoneEntered = false
				}
				this.scheduleRecentScrollbarMeasurement()
			},
			error() {
				this.scheduleRecentScrollbarMeasurement()
			}
		},
		mounted() {
			this.scheduleRecentScrollbarMeasurement()
			// #ifdef H5
			window.addEventListener('resize', this.scheduleRecentScrollbarMeasurement)
			window.addEventListener('mousemove', this.moveRecentScrollbarDrag)
			window.addEventListener('mouseup', this.finishRecentScrollbarDrag)
			// #endif
		},
		beforeDestroy() {
			this.releaseRecentScrollbar()
		},
		beforeUnmount() {
			this.releaseRecentScrollbar()
		},
		methods: {
			requestLoadMore() {
				if (this.recentBottomZoneEntered || !this.hasMore || this.loading) return
				this.recentBottomZoneEntered = true
				this.$emit('load-more')
			},
			scheduleRecentScrollbarMeasurement() {
				if (!this.expanded) return
				this.$nextTick(() => this.measureRecentScrollbar())
			},
			measureRecentScrollbar() {
				if (!this.expanded) return
				const query = uni.createSelectorQuery().in(this)
				query.select('.recent-list').boundingClientRect()
				query.select('.recent-scroll-content').boundingClientRect()
				query.exec(rects => {
					const listRect = rects?.[0]
					const contentRect = rects?.[1]
					const viewportHeight = Number(listRect?.height)
					const contentHeight = Number(contentRect?.height)
					if (Number.isFinite(viewportHeight) && viewportHeight >= 0) {
						this.recentViewportHeight = viewportHeight
					}
					if (Number.isFinite(contentHeight) && contentHeight >= 0) {
						this.recentContentHeight = contentHeight
					}
					this.setRecentScrollTop(this.recentScrollTop)
				})
			},
			handleRecentScroll(event) {
				const detail = event?.detail || {}
				const scrollTop = Number(detail.scrollTop)
				const scrollHeight = Number(detail.scrollHeight)
				if (Number.isFinite(scrollTop)) {
					this.recentScrollTop = Math.max(0, scrollTop)
					this.recentScrollTarget = this.recentScrollTop
				}
				if (Number.isFinite(scrollHeight) && scrollHeight >= 0) {
					this.recentContentHeight = scrollHeight
				}
				if (this.recentViewportHeight > 0) {
					const distanceToBottom = Math.max(
						0,
						this.recentContentHeight
							- this.recentViewportHeight
							- this.recentScrollTop
					)
					if (distanceToBottom > RECENT_LOAD_MORE_THRESHOLD_PX) {
						this.recentBottomZoneEntered = false
					}
				}
			},
			setRecentScrollTop(value) {
				const nextTop = Math.min(
					this.recentMaxScrollTop,
					Math.max(0, Number.isFinite(value) ? value : 0)
				)
				this.recentScrollTop = nextTop
				this.recentScrollTarget = nextTop
			},
			recentPointerY(event) {
				const touch = event?.touches?.[0] || event?.changedTouches?.[0]
				const pointerY = Number(touch?.clientY ?? event?.clientY)
				return Number.isFinite(pointerY) ? pointerY : null
			},
			startRecentScrollbarDrag(event) {
				if (!this.recentScrollbarVisible) return
				const pointerY = this.recentPointerY(event)
				if (pointerY == null) return
				this.recentScrollbarDragging = true
				this.recentScrollbarDragStartY = pointerY
				this.recentScrollbarDragStartTop = this.recentScrollTop
			},
			moveRecentScrollbarDrag(event) {
				if (!this.recentScrollbarDragging) return
				const pointerY = this.recentPointerY(event)
				const trackTravel = this.recentScrollbarTrackHeight
					- this.recentScrollbarThumbHeight
				if (pointerY == null || trackTravel <= 0 || !this.recentMaxScrollTop) return
				const deltaY = pointerY - this.recentScrollbarDragStartY
				const nextTop = this.recentScrollbarDragStartTop
					+ (deltaY / trackTravel) * this.recentMaxScrollTop
				this.setRecentScrollTop(nextTop)
			},
			finishRecentScrollbarDrag() {
				this.recentScrollbarDragging = false
				this.recentScrollbarDragStartY = null
				this.recentScrollbarDragStartTop = this.recentScrollTop
			},
			handleRecentScrollbarKeydown(event) {
				const pageStep = Math.max(RECENT_SCROLLBAR_KEY_STEP_PX, this.recentViewportHeight * 0.9)
				let targetTop = null
				switch (event.key) {
					case 'ArrowDown':
						targetTop = this.recentScrollTop + RECENT_SCROLLBAR_KEY_STEP_PX
						break
					case 'ArrowUp':
						targetTop = this.recentScrollTop - RECENT_SCROLLBAR_KEY_STEP_PX
						break
					case 'PageDown':
						targetTop = this.recentScrollTop + pageStep
						break
					case 'PageUp':
						targetTop = this.recentScrollTop - pageStep
						break
					case 'Home':
						targetTop = 0
						break
					case 'End':
						targetTop = this.recentMaxScrollTop
						break
					default:
						return
				}
				event.preventDefault()
				this.setRecentScrollTop(targetTop)
			},
			resetRecentScrollbarMetrics() {
				this.recentScrollTop = 0
				this.recentScrollTarget = 0
				this.recentViewportHeight = 0
				this.recentContentHeight = 0
				this.recentBottomZoneEntered = false
			},
			releaseRecentScrollbar() {
				this.finishRecentScrollbarDrag()
				// #ifdef H5
				window.removeEventListener('resize', this.scheduleRecentScrollbarMeasurement)
				window.removeEventListener('mousemove', this.moveRecentScrollbarDrag)
				window.removeEventListener('mouseup', this.finishRecentScrollbarDrag)
				// #endif
			}
		}
	}
</script>

<style lang="scss" scoped>
	.recent-conversations {
		min-height: 0;
		flex: 1;
		display: flex;
		flex-direction: column;
	}

	.recent-toggle,
	.conversation-open,
	.conversation-copy,
	.recent-error button {
		margin: 0;
		border: 0;
		background: transparent;
		box-sizing: border-box;
	}

	.recent-toggle::after,
	.conversation-open::after,
	.conversation-copy::after,
	.recent-error button::after {
		border: 0;
	}

	.recent-toggle {
		width: 100%;
		min-height: 44px;
		padding: 8px 12px;
		display: flex;
		align-items: center;
		justify-content: space-between;
		color: #c7d0cb;
		font-size: 13px;
		font-weight: 700;
		text-align: left;
	}

	.recent-content {
		width: 100%;
		min-width: 0;
		min-height: 0;
		position: relative;
		flex: 1;
		display: flex;
		overflow: hidden;
	}

	.recent-list {
		width: 100%;
		min-width: 0;
		min-height: 0;
		height: 100%;
		flex: 1;
		box-sizing: border-box;
	}

	.recent-scroll-content {
		min-height: 100%;
		padding-right: 13px;
		box-sizing: border-box;
	}

	.recent-scrollbar {
		width: 12px;
		position: absolute;
		top: 4px;
		right: 0;
		bottom: 4px;
		z-index: 2;
		display: flex;
		justify-content: center;
		pointer-events: none;
	}

	.recent-scrollbar-thumb {
		width: 12px;
		min-width: 12px;
		min-height: 40px;
		position: relative;
		margin: 0;
		padding: 0;
		border: 0;
		border-radius: 999px;
		background: transparent;
		box-shadow: none;
		cursor: grab;
		pointer-events: auto;
		touch-action: none;
		box-sizing: border-box;
	}

	.recent-scrollbar-thumb::before {
		content: '';
		position: absolute;
		inset: 0 3px;
		border-radius: 999px;
		background: rgba(151, 165, 157, .62);
		box-shadow: 0 0 0 1px rgba(8, 12, 10, .28);
		transition: background-color 120ms ease-out, box-shadow 120ms ease-out;
	}

	.recent-scrollbar-thumb::after {
		border: 0;
	}

	.recent-scrollbar-thumb.is-dragging::before {
		background: rgba(218, 230, 223, .9);
		box-shadow: 0 0 0 2px rgba(55, 211, 154, .32);
	}

	.recent-scrollbar-thumb.is-dragging {
		cursor: grabbing;
	}

	.conversation-row {
		min-width: 0;
		margin: 2px 0 2px 6px;
		display: flex;
		align-items: center;
		border-radius: 10px;
		background: transparent;
		transition: background-color 140ms ease-out;
	}

	.conversation-row.is-active {
		background: rgba(238, 242, 240, .075);
	}

	.conversation-open {
		min-width: 0;
		min-height: 44px;
		padding: 8px;
		flex: 1;
		display: flex;
		align-items: center;
		color: #aeb8b2;
		font-size: 13px;
		text-align: left;
	}

	.conversation-open text {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.conversation-copy {
		width: 34px;
		height: 34px;
		min-height: 34px;
		padding: 0;
		flex: 0 0 34px;
		display: flex;
		align-items: center;
		justify-content: center;
		border-radius: 8px;
		opacity: .55;
	}

	.recent-conversations.is-compact .recent-toggle {
		min-height: 40px;
		padding: 6px;
		font-size: 12px;
	}

	.recent-conversations.is-compact .recent-toggle :deep(uni-icons) {
		display: none;
	}

	.recent-conversations.is-compact .conversation-row {
		height: 40px;
		min-height: 40px;
		margin: 0;
		border-radius: 8px;
	}

	.recent-conversations.is-compact .conversation-open {
		height: 40px;
		min-height: 40px;
		padding: 6px;
		font-size: 12px;
	}

	.recent-conversations.is-compact .conversation-copy {
		width: 40px;
		height: 40px;
		min-height: 40px;
		flex-basis: 40px;
		border-radius: 8px;
	}

	.recent-status,
	.recent-error {
		padding: 10px 12px;
		color: #7f8a84;
		font-size: 12px;
		line-height: 1.45;
	}

	.recent-error {
		display: flex;
		align-items: center;
		gap: 8px;
		color: #d99a56;
	}

	.recent-error text {
		min-width: 0;
		flex: 1;
	}

	.recent-error button {
		min-height: 34px;
		padding: 4px 8px;
		color: #b7c2bc;
		font-size: 12px;
	}

	@media screen and (min-width: 768px) {
		.recent-toggle {
			padding-right: calc(12px + var(--sidebar-inline-padding, 0px));
		}

		.recent-status,
		.recent-error {
			padding-right: calc(12px + var(--sidebar-inline-padding, 0px));
		}

	}

	.recent-toggle:focus-visible,
	.conversation-open:focus-visible,
	.conversation-copy:focus-visible,
	.recent-error button:focus-visible {
		outline: 2px solid rgba(55, 211, 154, .42);
		outline-offset: -2px;
	}

	.recent-scrollbar-thumb:focus-visible {
		outline: 2px solid rgba(55, 211, 154, .72);
		outline-offset: 2px;
	}

	@media (hover: hover) and (pointer: fine) {
		.conversation-row:hover {
			background: rgba(238, 242, 240, .045);
		}

		.conversation-row.is-active:hover {
			background: rgba(238, 242, 240, .085);
		}

		.conversation-row:hover .conversation-copy,
		.conversation-copy:focus-visible {
			opacity: 1;
		}

		.recent-scrollbar-thumb:hover::before {
			background: rgba(191, 204, 196, .82);
		}
	}

	@media (prefers-reduced-motion: reduce) {
		.conversation-row,
		.recent-scrollbar-thumb {
			transition: none;
		}
	}
</style>

<template>
	<view class="recent-conversations">
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
			<scroll-view class="recent-list" scroll-y>
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
				<button
					v-if="hasMore"
					class="recent-more"
					type="button"
					:disabled="loading"
					@click="$emit('load-more')"
				>
					{{ loading ? '加载中…' : '加载更多' }}
				</button>
			</scroll-view>
		</view>
	</view>
</template>

<script>
	export default {
		props: {
			contentId: {
				type: String,
				required: true
			},
			expanded: {
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
	.recent-error button,
	.recent-more {
		margin: 0;
		border: 0;
		background: transparent;
		box-sizing: border-box;
	}

	.recent-toggle::after,
	.conversation-open::after,
	.conversation-copy::after,
	.recent-error button::after,
	.recent-more::after {
		border: 0;
	}

	.recent-toggle {
		width: 100%;
		min-height: 42px;
		padding: 7px 12px;
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
		flex: 1;
		display: flex;
	}

	.recent-list {
		width: 100%;
		min-width: 0;
		min-height: 0;
		height: 100%;
		flex: 1;
	}

	.conversation-row {
		min-width: 0;
		margin: 1px 4px;
		display: flex;
		align-items: center;
		border-radius: 9px;
		background: transparent;
		transition: background-color 150ms ease-out;
	}

	.conversation-row.is-active {
		background: rgba(238, 242, 240, .075);
	}

	.conversation-open {
		min-width: 0;
		min-height: 40px;
		padding: 7px 8px;
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

	.recent-error button,
	.recent-more {
		min-height: 34px;
		padding: 4px 8px;
		color: #b7c2bc;
		font-size: 12px;
	}

	.recent-more {
		width: 100%;
	}

	@media screen and (min-width: 768px) {
		.recent-toggle {
			padding-right: calc(12px + var(--sidebar-inline-padding, 0px));
		}

		.conversation-row {
			margin-right: calc(4px + var(--sidebar-inline-padding, 0px));
		}

		.recent-status,
		.recent-error {
			padding-right: calc(12px + var(--sidebar-inline-padding, 0px));
		}

		.recent-more {
			padding-right: calc(8px + var(--sidebar-inline-padding, 0px));
		}
	}

	.recent-toggle:focus-visible,
	.conversation-open:focus-visible,
	.conversation-copy:focus-visible,
	.recent-error button:focus-visible,
	.recent-more:focus-visible {
		outline: 2px solid rgba(55, 211, 154, .42);
		outline-offset: -2px;
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
	}

	@media (prefers-reduced-motion: reduce) {
		.conversation-row {
			transition: none;
		}
	}
</style>

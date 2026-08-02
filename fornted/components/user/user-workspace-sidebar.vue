<template>
	<view class="workspace-navigation-layer">
		<user-primary-navigation
			:active-destination="activeDestination"
			variant="chat-sidebar"
			@destination-click="$emit('destination-click', $event)"
		>
			<template #before-items>
				<button class="workspace-new-chat" type="button" @click="$emit('new-chat')">
					<uni-icons type="compose" size="20" color="#37d39a" aria-hidden="true" />
					<text>新聊天</text>
				</button>
			</template>
			<template #after-items>
				<user-recent-conversations
					content-id="workspace-desktop-recent"
					:expanded="recentExpanded"
					:conversations="conversations"
					:current-conversation-public-id="currentConversationPublicId"
					:loaded="conversationsLoaded"
					:loading="conversationLoading"
					:error="conversationError"
					:has-more="hasMoreConversations"
					@toggle="$emit('toggle-recent')"
					@open="$emit('open-conversation', $event)"
					@copy="$emit('copy-conversation', $event)"
					@retry="$emit('retry-conversations')"
					@load-more="$emit('load-more-conversations')"
				/>
			</template>
		</user-primary-navigation>

		<view
			v-if="drawerOpen"
			class="workspace-drawer-backdrop"
			aria-hidden="true"
			@click="$emit('close-drawer')"
		></view>
		<view
			v-if="drawerOpen"
			ref="mobileDrawer"
			class="workspace-history-drawer is-open"
			role="complementary"
			aria-label="聊天会话"
			tabindex="-1"
			@keydown.esc.stop="$emit('close-drawer')"
		>
			<view class="workspace-drawer-heading">
				<text class="workspace-drawer-title">聊天</text>
				<button
					class="workspace-icon-button"
					type="button"
					aria-label="关闭会话列表"
					@click="$emit('close-drawer')"
				>
					<uni-icons type="closeempty" size="22" color="#dce5e0" aria-hidden="true" />
				</button>
			</view>
			<button class="workspace-new-chat" type="button" @click="$emit('new-chat')">
				<uni-icons type="compose" size="20" color="#37d39a" aria-hidden="true" />
				<text>新聊天</text>
			</button>
			<user-recent-conversations
				content-id="workspace-mobile-recent"
				:expanded="recentExpanded"
				:conversations="conversations"
				:current-conversation-public-id="currentConversationPublicId"
				:loaded="conversationsLoaded"
				:loading="conversationLoading"
				:error="conversationError"
				:has-more="hasMoreConversations"
				@toggle="$emit('toggle-recent')"
				@open="$emit('open-conversation', $event)"
				@copy="$emit('copy-conversation', $event)"
				@retry="$emit('retry-conversations')"
				@load-more="$emit('load-more-conversations')"
			/>
		</view>
	</view>
</template>

<script>
	import UserPrimaryNavigation from './user-primary-navigation.vue'
	import UserRecentConversations from './user-recent-conversations.vue'

	export default {
		components: {
			UserPrimaryNavigation,
			UserRecentConversations
		},
		props: {
			activeDestination: {
				type: String,
				required: true
			},
			recentExpanded: {
				type: Boolean,
				default: false
			},
			drawerOpen: {
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
			conversationsLoaded: {
				type: Boolean,
				default: false
			},
			conversationLoading: {
				type: Boolean,
				default: false
			},
			conversationError: {
				type: String,
				default: ''
			},
			hasMoreConversations: {
				type: Boolean,
				default: false
			}
		},
		watch: {
			drawerOpen(value) {
				if (!value) return
				this.$nextTick(() => {
					const drawer = this.$refs.mobileDrawer
					const element = drawer?.$el || drawer
					element?.focus?.()
				})
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.workspace-navigation-layer {
		flex-shrink: 0;
	}

	.workspace-new-chat {
		width: calc(100% - 8px);
		min-height: 44px;
		margin: 0 4px 8px;
		padding: 7px 10px;
		display: flex;
		align-items: center;
		justify-content: flex-start;
		gap: 9px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: #dce5e0;
		font-size: 14px;
		font-weight: 680;
		text-align: left;
		box-sizing: border-box;
	}

	.workspace-new-chat::after,
	.workspace-icon-button::after {
		border: 0;
	}

	.workspace-new-chat:focus-visible,
	.workspace-icon-button:focus-visible {
		outline: 2px solid rgba(55, 211, 154, .42);
		outline-offset: -2px;
	}

	.workspace-history-drawer {
		position: fixed;
		inset: 0 auto 0 0;
		z-index: 35;
		width: min(86vw, 320px);
		padding: 18px 12px calc(92px + env(safe-area-inset-bottom));
		display: flex;
		flex-direction: column;
		box-sizing: border-box;
		border-right: 1px solid rgba(86, 101, 93, .44);
		background: rgba(18, 22, 20, .96);
		backdrop-filter: blur(20px) saturate(115%);
		transform: translateX(-105%);
		transition: transform 180ms ease-out;
	}

	.workspace-history-drawer.is-open {
		transform: translateX(0);
	}

	.workspace-drawer-backdrop {
		position: fixed;
		inset: 0;
		z-index: 34;
		background: rgba(0, 0, 0, .58);
	}

	.workspace-drawer-heading {
		min-height: 48px;
		padding: 0 4px;
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.workspace-drawer-title {
		font-size: 18px;
		font-weight: 720;
	}

	.workspace-icon-button {
		@include user-frosted-control;
		width: 48px;
		height: 48px;
		min-height: 48px;
		margin: 0;
		padding: 0;
		border-radius: 14px;
		box-sizing: border-box;
	}

	@media screen and (min-width: 1024px) {
		.workspace-history-drawer,
		.workspace-drawer-backdrop {
			display: none !important;
		}
	}

	@media (hover: hover) and (pointer: fine) {
		.workspace-new-chat:hover {
			background: rgba(243, 245, 244, .05);
		}
	}

	@media (prefers-reduced-motion: reduce) {
		.workspace-history-drawer {
			transition: none;
		}
	}
</style>

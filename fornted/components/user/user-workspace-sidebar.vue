<template>
	<view class="workspace-navigation-layer">
		<user-primary-navigation
			v-if="!androidClient"
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
			:class="{ 'is-android-drawer': androidClient }"
			aria-hidden="true"
			@click="$emit('close-drawer')"
		></view>
		<view
			v-if="drawerOpen"
			ref="mobileDrawer"
			class="workspace-history-drawer is-open"
			:class="{ 'is-android-drawer': androidClient }"
			role="complementary"
			aria-label="聊天会话"
			tabindex="-1"
			@keydown.esc.stop="$emit('close-drawer')"
		>
			<view class="workspace-drawer-heading">
				<text class="workspace-drawer-title">{{ androidClient ? 'AI Temperate' : '聊天' }}</text>
				<button
					class="workspace-icon-button"
					type="button"
					aria-label="关闭会话列表"
					@click="$emit('close-drawer')"
				>
					<uni-icons type="closeempty" size="22" color="#dce5e0" aria-hidden="true" />
				</button>
			</view>
			<button
				v-if="androidClient"
				class="workspace-drawer-account"
				type="button"
				@click="selectDrawerDestination('profile')"
			>
				<image
					v-if="drawerProfile?.avatarUrl"
					class="workspace-drawer-avatar is-image"
					:src="drawerProfile.avatarUrl"
					mode="aspectFill"
				/>
				<view v-else class="workspace-drawer-avatar" aria-hidden="true">
					<text>{{ drawerAvatarText }}</text>
				</view>
				<view class="workspace-drawer-account-copy">
					<text class="workspace-drawer-account-name">{{ drawerDisplayName }}</text>
					<text class="workspace-drawer-account-hint">账号管理</text>
				</view>
				<uni-icons type="right" size="16" color="#718078" aria-hidden="true" />
			</button>
			<button class="workspace-new-chat" type="button" @click="startDrawerNewChat">
				<uni-icons type="compose" size="18" color="#37d39a" aria-hidden="true" />
				<text>新聊天</text>
			</button>
			<user-primary-navigation
				v-if="androidClient"
				:active-destination="activeDestination"
				:show-api-keys="androidClient"
				variant="drawer"
				@destination-click="selectDrawerDestination"
			/>
			<view v-if="androidClient" class="workspace-drawer-divider" aria-hidden="true"></view>
			<user-recent-conversations
				content-id="workspace-mobile-recent"
				:compact="androidClient"
				:expanded="recentExpanded"
				:conversations="conversations"
				:current-conversation-public-id="currentConversationPublicId"
				:loaded="conversationsLoaded"
				:loading="conversationLoading"
				:error="conversationError"
				:has-more="hasMoreConversations"
				@toggle="$emit('toggle-recent')"
				@open="openDrawerConversation"
				@copy="$emit('copy-conversation', $event)"
				@retry="$emit('retry-conversations')"
				@load-more="$emit('load-more-conversations')"
			/>
		</view>
	</view>
</template>

<script>
	import { clientPlatform } from '@/common/auth/config.js'
	import { getCurrentUserProfile } from '@/common/user/current-user-profile.js'
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
		data() {
			return {
				drawerProfile: getCurrentUserProfile()
			}
		},
		computed: {
			androidClient() {
				return clientPlatform() === 'ANDROID'
			},
			drawerDisplayName() {
				return String(this.drawerProfile?.displayName || '当前用户').trim()
			},
			drawerAvatarText() {
				return this.drawerDisplayName.slice(0, 1).toUpperCase() || 'U'
			}
		},
		watch: {
			drawerOpen(value) {
				if (!value) return
				this.drawerProfile = getCurrentUserProfile()
				this.$nextTick(() => {
					const drawer = this.$refs.mobileDrawer
					const element = drawer?.$el || drawer
					element?.focus?.()
				})
			}
		},
		methods: {
			selectDrawerDestination(destination) {
				this.$emit('destination-click', destination)
				this.$emit('close-drawer')
			},
			startDrawerNewChat() {
				this.$emit('new-chat')
				this.$emit('close-drawer')
			},
			openDrawerConversation(conversationPublicId) {
				this.$emit('open-conversation', conversationPublicId)
				this.$emit('close-drawer')
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
		width: calc(100% - 12px);
		min-height: 48px;
		margin: 0 6px 12px;
		padding: 8px 12px;
		display: flex;
		align-items: center;
		justify-content: flex-start;
		gap: 9px;
		border: 0;
		border-radius: 12px;
		background: transparent;
		color: #dce5e0;
		font-size: 14px;
		font-weight: 680;
		text-align: left;
		box-sizing: border-box;
	}

	.workspace-drawer-account {
		width: 100%;
		min-height: 50px;
		margin: 0 0 6px;
		padding: 6px;
		display: flex;
		align-items: center;
		gap: 8px;
		border: 0;
		border-radius: 10px;
		background: rgba(243, 245, 244, .035);
		color: #eef4f1;
		text-align: left;
		box-sizing: border-box;
	}

	.workspace-drawer-account::after { border: 0; }
	.workspace-drawer-avatar {
		width: 32px;
		height: 32px;
		flex: 0 0 32px;
		display: flex;
		align-items: center;
		justify-content: center;
		overflow: hidden;
		border-radius: 10px;
		background: #37d39a;
		color: #08130e;
		font-size: 13px;
		font-weight: 800;
	}
	.workspace-drawer-avatar.is-image { display: block; background: #202520; }
	.workspace-drawer-account-copy { min-width: 0; flex: 1; display: flex; flex-direction: column; }
	.workspace-drawer-account-name { overflow: hidden; font-size: 13px; font-weight: 720; text-overflow: ellipsis; white-space: nowrap; }
	.workspace-drawer-account-hint { margin-top: 1px; color: #89948e; font-size: 10px; }

	.workspace-new-chat::after,
	.workspace-icon-button::after {
		border: 0;
	}

	.workspace-drawer-account:focus-visible,
	.workspace-new-chat:focus-visible,
	.workspace-icon-button:focus-visible {
		outline: 2px solid rgba(55, 211, 154, .42);
		outline-offset: -2px;
	}

	.workspace-history-drawer {
		position: fixed;
		inset: 0 auto 0 0;
		z-index: 35;
		width: min(88vw, 360px);
		padding: 16px 12px calc(92px + env(safe-area-inset-bottom));
		display: flex;
		flex-direction: column;
		box-sizing: border-box;
		border-right: 1px solid rgba(151, 170, 160, .22);
		background: rgba(21, 24, 22, .96);
		backdrop-filter: blur(16px) saturate(112%);
		transform: translateX(-105%);
		transition: transform 240ms cubic-bezier(.2, .8, .2, 1);
	}

	.workspace-history-drawer.is-android-drawer {
		width: min(70vw, 288px);
		padding: max(8px, env(safe-area-inset-top)) 8px calc(12px + env(safe-area-inset-bottom));
	}
	.is-android-drawer .workspace-icon-button { @include user-android-compact-control(30px, 30px, 9px); width: 44px; height: 44px; min-height: 44px; }
	.is-android-drawer .workspace-drawer-heading { min-height: 44px; padding: 0 2px; }
	.is-android-drawer .workspace-drawer-title { font-size: 15px; }
	.is-android-drawer .workspace-drawer-account:active,
	.is-android-drawer .workspace-new-chat:active { background: rgba(243, 245, 244, .075); }
	.is-android-drawer .workspace-new-chat {
		width: 100%;
		min-height: 44px;
		margin: 0 0 4px;
		padding: 6px 8px;
		gap: 7px;
		border-radius: 10px;
		font-size: 13px;
	}
	.workspace-drawer-divider {
		width: 100%;
		height: 1px;
		margin: 4px 0;
		flex: 0 0 1px;
		background: rgba(151, 170, 160, .14);
	}

	.workspace-history-drawer.is-open {
		transform: translateX(0);
	}

	.workspace-drawer-backdrop {
		position: fixed;
		inset: 0;
		z-index: 34;
		background: rgba(0, 0, 0, .56);
	}

	.workspace-drawer-heading {
		min-height: 48px;
		padding: 0 4px;
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.workspace-drawer-title {
		font-size: 17px;
		font-weight: 740;
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

	@media screen and (min-width: 768px) {
		.workspace-history-drawer:not(.is-android-drawer),
		.workspace-drawer-backdrop:not(.is-android-drawer) {
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

	@media (prefers-reduced-transparency: reduce), (prefers-contrast: more) {
		.workspace-history-drawer {
			background: #151816;
			backdrop-filter: none;
			-webkit-backdrop-filter: none;
		}
	}
</style>

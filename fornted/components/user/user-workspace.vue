<template>
	<view class="user-workspace">
		<user-workspace-sidebar
			:active-destination="activeDestination"
			:recent-expanded="recentExpanded"
			:drawer-open="drawerOpen"
			:conversations="conversations"
			:current-conversation-public-id="currentConversationPublicId || ''"
			:conversations-loaded="conversationsLoaded"
			:conversation-loading="conversationLoading"
			:conversation-error="conversationError"
			:has-more-conversations="hasMoreConversations"
			@destination-click="selectDestination"
			@new-chat="startNewChat"
			@toggle-recent="toggleRecentConversations"
			@open-conversation="openConversation"
			@copy-conversation="copyConversationId"
			@retry-conversations="refreshConversations"
			@load-more-conversations="loadMoreConversations"
			@close-drawer="drawerOpen = false"
		/>

		<view class="user-workspace-content">
			<user-chat-panel
				ref="chatPanel"
				v-if="visitedDestinations.chat"
				v-show="activeDestination === 'chat'"
				@open-conversation-drawer="openConversationDrawer"
				@new-chat="startNewChat"
				@conversation-state-change="applyConversationState"
				@conversation-completed="refreshConversations"
			/>
			<user-model-panel
				ref="modelPanel"
				v-if="visitedDestinations.models"
				v-show="activeDestination === 'models'"
				:authenticated="authenticated"
				:model-public-id="activeModelPublicId"
				@open-model="openModelDetail"
				@close-model="closeModelDetail"
				@open-conversation-drawer="openConversationDrawer"
			/>
			<user-profile-panel
				ref="profilePanel"
				v-if="visitedDestinations.profile"
				v-show="activeDestination === 'profile'"
				:authenticated="authenticated"
				@open-conversation-drawer="openConversationDrawer"
			/>
		</view>
	</view>
</template>

<script>
	import { aiConversationApi } from '@/common/aichat/ai-conversation-api.js'
	import {
		readAiConversationStore,
		setConversationError,
		setConversationLoading,
		setConversationPage
	} from '@/common/aichat/ai-conversation-store.js'
	import UserWorkspaceSidebar from './user-workspace-sidebar.vue'
	import UserChatPanel from './workspace/user-chat-panel.vue'
	import UserModelPanel from './workspace/user-model-panel.vue'
	import UserProfilePanel from './workspace/user-profile-panel.vue'

	const DESTINATIONS = Object.freeze(['chat', 'models', 'profile'])
	const DESKTOP_SIDEBAR_MIN_WIDTH = 768

	export default {
		components: {
			UserWorkspaceSidebar,
			UserChatPanel,
			UserModelPanel,
			UserProfilePanel
		},
		props: {
			initialDestination: {
				type: String,
				default: 'chat',
				validator: value => DESTINATIONS.includes(value)
			},
			initialModelPublicId: {
				type: String,
				default: ''
			},
			authenticated: {
				type: Boolean,
				default: false
			}
		},
		data() {
			const activeDestination = DESTINATIONS.includes(this.initialDestination)
				? this.initialDestination
				: 'chat'
			return {
				...readAiConversationStore(),
				activeDestination,
				visitedDestinations: {
					chat: activeDestination === 'chat',
					models: activeDestination === 'models',
					profile: activeDestination === 'profile'
				},
				recentExpanded: false,
				drawerOpen: false,
				activeModelPublicId: String(this.initialModelPublicId || '').trim()
			}
		},
		watch: {
			authenticated(value) {
				if (!value) return
				this.handleAuthenticated()
			},
			initialModelPublicId(value) {
				this.activeModelPublicId = String(value || '').trim()
			}
		},
		mounted() {
			// #ifdef H5
			document.body?.classList?.add('ait-workspace-active')
			// #endif
			if (this.authenticated) {
				this.handleAuthenticated()
			}
		},
		beforeDestroy() {
			this.releaseWorkspaceBody()
		},
		beforeUnmount() {
			this.releaseWorkspaceBody()
		},
		methods: {
			releaseWorkspaceBody() {
				// #ifdef H5
				document.body?.classList?.remove('ait-workspace-active')
				// #endif
			},
			applyConversationState(value) {
				Object.assign(this, value)
			},
			syncChatStore() {
				this.$nextTick(() => {
					this.$refs.chatPanel?.syncStore()
				})
			},
			selectDestination(destination) {
				if (!DESTINATIONS.includes(destination)) return
				if (destination === this.activeDestination) {
					if (destination === 'chat') {
						const windowWidth = Number(
							uni.getSystemInfoSync().windowWidth || 0)
						if (windowWidth < DESKTOP_SIDEBAR_MIN_WIDTH) {
							this.openConversationDrawer()
						} else {
							this.toggleRecentConversations()
						}
					}
					return
				}
				this.visitedDestinations[destination] = true
				this.activeDestination = destination
				this.drawerOpen = false
				if (destination === 'chat' && this.authenticated) {
					this.handleAuthenticated()
				}
			},
			startNewChat() {
				this.visitedDestinations.chat = true
				this.activeDestination = 'chat'
				this.drawerOpen = false
				this.$nextTick(() => {
					const chatPanel = this.$refs.chatPanel
					if (this.authenticated) {
						chatPanel?.onAuthenticatedPageReady()
					}
					chatPanel?.newChat()
				})
			},
			openConversation(conversationPublicId) {
				this.visitedDestinations.chat = true
				this.activeDestination = 'chat'
				this.drawerOpen = false
				this.$nextTick(() => {
					const chatPanel = this.$refs.chatPanel
					if (this.authenticated) {
						chatPanel?.onAuthenticatedPageReady()
					}
					chatPanel?.openConversation(conversationPublicId)
				})
			},
			openConversationDrawer() {
				this.recentExpanded = true
				this.drawerOpen = true
				this.ensureRecentConversations()
			},
			toggleRecentConversations() {
				this.recentExpanded = !this.recentExpanded
				if (this.recentExpanded) this.ensureRecentConversations()
			},
			ensureRecentConversations() {
				if (this.conversationsLoaded || this.conversationLoading) {
					return true
				}
				return this.refreshConversations()
			},
			async refreshConversations() {
				this.applyConversationState(setConversationLoading(true))
				try {
					this.applyConversationState(setConversationPage(
						await aiConversationApi.listConversations(),
						false
					))
					this.syncChatStore()
					return true
				} catch (error) {
					this.applyConversationState(setConversationError(
						'会话列表暂时无法加载，请重试。'
					))
					this.syncChatStore()
					return false
				}
			},
			async loadMoreConversations() {
				if (!this.nextCursor || this.conversationLoading) return
				this.applyConversationState(setConversationLoading(true))
				try {
					this.applyConversationState(setConversationPage(
						await aiConversationApi.listConversations({
							cursor: this.nextCursor
						}),
						true
					))
				} catch (error) {
					this.applyConversationState(setConversationError(
						'更多会话暂时无法加载，请重试。'
					))
				}
				this.syncChatStore()
			},
			copyConversationId(conversationPublicId) {
				uni.setClipboardData({
					data: conversationPublicId,
					success: () => {
						uni.showToast({
							title: '会话 ID 已复制',
							icon: 'none'
						})
					},
					fail: () => {
						uni.showToast({
							title: '复制失败，请重试',
							icon: 'none'
						})
					}
				})
			},
			openModelDetail(modelPublicId) {
				this.activeModelPublicId = String(modelPublicId || '').trim()
			},
			closeModelDetail() {
				this.activeModelPublicId = ''
			},
			handleAuthenticated() {
				if (!this.authenticated) return
				this.$nextTick(() => {
					this.$refs.chatPanel?.onAuthenticatedPageReady()
				})
			},
			handlePageShow() {
				if (!this.authenticated) return
				this.$refs.chatPanel?.handlePageShow()
			},
			handlePageHide() {
				this.$refs.chatPanel?.handlePageHide()
			},
			handlePageUnload() {
				this.$refs.chatPanel?.handlePageUnload()
				this.releaseWorkspaceBody()
			},
			handleBackPress() {
				const activePanel = this.activeDestination === 'chat'
					? this.$refs.chatPanel
					: this.activeDestination === 'models'
						? this.$refs.modelPanel
						: this.$refs.profilePanel
				if (typeof activePanel?.closeIfOpen === 'function'
					&& activePanel.closeIfOpen()) return true
				if (this.drawerOpen) {
					this.drawerOpen = false
					return true
				}
				return false
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.user-workspace {
		@include user-safe-viewport;
		width: 100%;
		min-width: 0;
		max-width: 100%;
		height: 100dvh;
		display: flex;
		flex-direction: row;
		overflow: hidden;
		background: #0b0d0c;
		color: #f3f5f4;
	}

	.user-workspace-content {
		width: 0;
		max-width: 100%;
		min-width: 0;
		min-height: 0;
		height: 100%;
		flex: 1 1 0%;
		overflow: hidden;
		background: #0b0d0c;
	}
</style>

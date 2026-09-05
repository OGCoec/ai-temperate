<template>
	<view class="user-workspace" :class="workspaceClass" :style="workspaceStyle">
		<!-- #ifdef H5 -->
		<user-h5-workspace-sidebar
			:active-destination="activeNavigationDestination"
			:recent-expanded="recentExpanded"
			:open="effectiveSidebarOpen"
			:mode="sidebarMode"
			:presentation="sidebarPresentation"
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
			@retry-conversations="ensureRecentConversations"
			@load-more-conversations="loadMoreConversations"
			@close="closeSidebar"
		/>
		<button
			v-if="!effectiveSidebarOpen"
			ref="sidebarToggle"
			class="workspace-sidebar-toggle"
			type="button"
			aria-label="打开会话边栏"
			aria-controls="workspace-conversation-sidebar"
			:aria-expanded="String(effectiveSidebarOpen)"
			@click="toggleSidebar"
		>
			<uni-icons type="bars" size="21" color="#dce5e0" aria-hidden="true" />
		</button>
		<!-- #endif -->
		<!-- #ifndef H5 -->
		<user-workspace-sidebar
			:active-destination="activeNavigationDestination"
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
			@retry-conversations="ensureRecentConversations"
			@load-more-conversations="loadMoreConversations"
			@close-drawer="drawerOpen = false"
		/>
		<!-- #endif -->

		<view class="user-workspace-content">
			<user-chat-panel
				ref="chatPanel"
				v-if="visitedDestinations.chat"
				v-show="activeDestination === 'chat'"
				@open-conversation-drawer="openConversationDrawer"
				@new-chat="startNewChat"
				@open-account="selectDestination('profile')"
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
				@open-api-keys="selectDestination('apiKeys')"
				@open-models="selectDestination('models')"
			/>
			<user-api-key-panel
				ref="apiKeyPanel"
				v-if="visitedDestinations.apiKeys"
				v-show="activeDestination === 'apiKeys'"
				:authenticated="authenticated"
				@open-conversation-drawer="openConversationDrawer"
				@open-usage="openApiKeyUsage"
			/>
			<user-api-key-usage-panel
				ref="apiKeyUsagePanel"
				v-if="visitedDestinations.apiKeyUsage"
				v-show="activeDestination === 'apiKeyUsage'"
				:selected-key="selectedApiKey"
				:android-client="androidClient"
				@back="backFromApiKeyUsage"
				@not-found="handleApiKeyUsageNotFound"
			/>
		</view>
	</view>
</template>

<script>
	import {
		aiConversationApi,
		CONVERSATION_LIST_PAGE_SIZE
	} from '@/common/aichat/ai-conversation-api.js'
	import {
		readAiConversationStore,
		setConversationError,
		setConversationLoading,
		setConversationPage
	} from '@/common/aichat/ai-conversation-store.js'
	import {
		defaultH5SidebarOpen,
		resolveH5SidebarOpen,
		resolveH5SidebarMode,
		resolveH5SidebarWidth
	} from '@/common/ui/h5-workspace-layout.js'
	import UserH5WorkspaceSidebar from './user-h5-workspace-sidebar.vue'
	import UserWorkspaceSidebar from './user-workspace-sidebar.vue'
	import UserChatPanel from './workspace/user-chat-panel.vue'
	import UserModelPanel from './workspace/user-model-panel.vue'
	import UserProfilePanel from './workspace/user-profile-panel.vue'
	import UserApiKeyPanel from './workspace/user-api-key-panel.vue'
	import UserApiKeyUsagePanel from './workspace/user-api-key-usage-panel.vue'
	import { clientPlatform } from '@/common/auth/config.js'
	import {
		recordAuthDiagnosticEvent,
		renewAuthDiagnosticPage
	} from '@/common/auth/auth-diagnostics.js'

	const DESTINATIONS = Object.freeze(['chat', 'models', 'profile', 'apiKeys', 'apiKeyUsage'])
	export default {
		components: {
			UserH5WorkspaceSidebar,
			UserWorkspaceSidebar,
			UserChatPanel,
			UserModelPanel,
			UserProfilePanel,
			UserApiKeyPanel,
			UserApiKeyUsagePanel
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
			const initialWindowWidth = Number(uni.getSystemInfoSync().windowWidth || 0)
			let recentExpanded = false
			// #ifdef H5
			recentExpanded = true
			// #endif
			return {
				...readAiConversationStore(),
				activeDestination,
				visitedDestinations: {
					chat: activeDestination === 'chat',
					models: activeDestination === 'models',
					profile: activeDestination === 'profile',
					apiKeys: activeDestination === 'apiKeys',
					apiKeyUsage: activeDestination === 'apiKeyUsage'
				},
				recentExpanded,
				drawerOpen: false,
				sidebarOpen: defaultH5SidebarOpen(initialWindowWidth),
				sidebarMode: resolveH5SidebarMode(initialWindowWidth),
				sidebarWidth: resolveH5SidebarWidth(initialWindowWidth),
				sidebarPreferenceTouched: false,
				workspaceResizeListener: null,
				activeModelPublicId: String(this.initialModelPublicId || '').trim(),
				selectedApiKey: Object.freeze({})
			}
		},
		computed: {
			androidClient() { return clientPlatform() === 'ANDROID' },
			activeNavigationDestination() {
				return this.activeDestination === 'apiKeyUsage' ? 'apiKeys' : this.activeDestination
			},
			sidebarPresentation() {
				return this.sidebarMode === 'push'
					&& (['profile', 'apiKeys'].includes(this.activeDestination) || this.activeDestination === 'apiKeyUsage')
					? 'rail'
					: 'full'
			},
			effectiveSidebarOpen() {
				return this.sidebarPresentation === 'rail' || this.sidebarOpen
			},
			effectiveSidebarWidth() {
				return this.sidebarPresentation === 'rail' ? 72 : this.sidebarWidth
			},
			workspaceClass() {
				// #ifdef H5
				return {
					'is-h5-workspace': true,
					'is-sidebar-open': this.effectiveSidebarOpen,
					'is-sidebar-overlay': this.sidebarMode === 'overlay',
					'is-sidebar-push': this.sidebarMode === 'push',
					'is-sidebar-rail': this.sidebarPresentation === 'rail'
				}
				// #endif
				return {}
			},
			workspaceStyle() {
				// #ifdef H5
				return { '--workspace-sidebar-width': `${this.effectiveSidebarWidth}px` }
				// #endif
				return null
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
			renewAuthDiagnosticPage(
				'/pages/ai-chat/index',
				'user_workspace_mounted')
			recordAuthDiagnosticEvent('USER_WORKSPACE_MOUNTED', {
				source: 'user_workspace_mounted',
				authReady: this.authenticated,
				route: '/pages/ai-chat/index'
			})
			// #ifdef H5
			document.body?.classList?.add('ait-workspace-active')
			this.workspaceResizeListener = event => this.handleWorkspaceResize(
				event?.size?.windowWidth
			)
			if (typeof uni.onWindowResize === 'function') {
				uni.onWindowResize(this.workspaceResizeListener)
			}
			if (this.sidebarOpen) this.ensureRecentConversations()
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
				if (this.workspaceResizeListener && typeof uni.offWindowResize === 'function') {
					uni.offWindowResize(this.workspaceResizeListener)
				}
				this.workspaceResizeListener = null
				// #endif
			},
			handleWorkspaceResize(value) {
				const width = Number(value ?? (uni.getSystemInfoSync().windowWidth || 0))
				this.sidebarMode = resolveH5SidebarMode(width)
				this.sidebarWidth = resolveH5SidebarWidth(width)
				this.sidebarOpen = resolveH5SidebarOpen(
					this.sidebarOpen,
					this.sidebarPreferenceTouched,
					width
				)
				if (!this.sidebarPreferenceTouched) {
					if (this.sidebarOpen) this.ensureRecentConversations()
				}
			},
			toggleSidebar() {
				this.sidebarPreferenceTouched = true
				if (this.sidebarOpen) {
					this.closeSidebar()
					return
				}
				this.sidebarOpen = true
				this.ensureRecentConversations()
			},
			closeSidebar() {
				this.sidebarPreferenceTouched = true
				this.sidebarOpen = false
				this.$nextTick(() => {
					const toggle = this.$refs.sidebarToggle
					const element = toggle?.$el || toggle
					element?.focus?.({ preventScroll: true })
				})
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
				if (this.activeDestination === 'apiKeyUsage' && destination !== 'apiKeyUsage') {
					if (this.$refs.apiKeyUsagePanel?.closeIfOpen?.()) return
					this.$refs.apiKeyUsagePanel?.handlePageHide?.()
					this.selectedApiKey = Object.freeze({})
				}
				if (this.activeDestination === 'apiKeys'
					&& destination !== 'apiKeys'
					&& this.$refs.apiKeyPanel?.closeIfOpen()) {
					return
				}
				if (destination === this.activeDestination) {
					if (destination === 'chat') {
						// #ifdef H5
						this.toggleSidebar()
						// #endif
						// #ifndef H5
						const windowWidth = Number(
							uni.getSystemInfoSync().windowWidth || 0)
						if (windowWidth < 768) {
							this.openConversationDrawer()
						} else {
							this.toggleRecentConversations()
						}
						// #endif
					}
					return
				}
				this.visitedDestinations[destination] = true
				this.activeDestination = destination
				this.drawerOpen = false
				// #ifdef H5
				if (this.sidebarMode === 'overlay') this.sidebarOpen = false
				// #endif
				if (destination === 'chat' && this.authenticated) {
					this.handleAuthenticated()
				}
			},
			startNewChat() {
				this.visitedDestinations.chat = true
				this.activeDestination = 'chat'
				this.drawerOpen = false
				// #ifdef H5
				if (this.sidebarMode === 'overlay') this.sidebarOpen = false
				// #endif
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
				// #ifdef H5
				if (this.sidebarMode === 'overlay') this.sidebarOpen = false
				// #endif
				this.$nextTick(() => {
					const chatPanel = this.$refs.chatPanel
					if (this.authenticated) {
						chatPanel?.onAuthenticatedPageReady()
					}
					chatPanel?.openConversation(conversationPublicId)
				})
			},
			openConversationDrawer() {
				// #ifdef H5
				this.sidebarPreferenceTouched = true
				this.sidebarOpen = true
				this.ensureRecentConversations()
				return
				// #endif
				this.recentExpanded = true
				this.drawerOpen = true
				this.ensureRecentConversations()
			},
			toggleRecentConversations() {
				this.recentExpanded = !this.recentExpanded
				if (this.recentExpanded) this.ensureRecentConversations()
			},
			ensureRecentConversations() {
				if (!this.authenticated) {
					recordAuthDiagnosticEvent('CONVERSATION_LIST_SKIPPED', {
						source: 'user_workspace_recent_conversations',
						authReady: false,
						path: '/api/ai/conversations',
						outcome: 'authentication_pending'
					})
					return false
				}
				if (this.conversationsLoaded || this.conversationLoading) {
					return true
				}
				recordAuthDiagnosticEvent('CONVERSATION_LIST_TRIGGERED', {
					source: 'user_workspace_recent_conversations',
					authReady: this.authenticated,
					path: '/api/ai/conversations'
				})
				return this.refreshConversations()
			},
			async refreshConversations() {
				if (!this.authenticated) return false
				this.applyConversationState(setConversationLoading(true))
				try {
					this.applyConversationState(setConversationPage(
						await aiConversationApi.listConversations({
							pageSize: CONVERSATION_LIST_PAGE_SIZE
						}),
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
				const requestedCursor = this.nextCursor
				if (!requestedCursor || this.conversationLoading) return
				this.applyConversationState(setConversationLoading(true))
				try {
					const page = await aiConversationApi.listConversations({
						cursor: requestedCursor,
						pageSize: CONVERSATION_LIST_PAGE_SIZE
					})
					if (this.nextCursor !== requestedCursor) return
					this.applyConversationState(setConversationPage(
						page,
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
			openApiKeyUsage(key) {
				if (!key?.id) return
				this.selectedApiKey = Object.freeze({
					id: key.id,
					maskedKey: key.maskedKey,
					status: key.status
				})
				this.visitedDestinations.apiKeyUsage = true
				this.activeDestination = 'apiKeyUsage'
				this.drawerOpen = false
				// #ifdef H5
				if (this.sidebarMode === 'overlay') this.sidebarOpen = false
				// #endif
			},
			backFromApiKeyUsage() {
				this.$refs.apiKeyUsagePanel?.handlePageHide?.()
				this.selectedApiKey = Object.freeze({})
				this.visitedDestinations.apiKeys = true
				this.activeDestination = 'apiKeys'
			},
			handleApiKeyUsageNotFound(publicId) {
				this.$refs.apiKeyPanel?.applyEditorRemoval?.(publicId)
				this.backFromApiKeyUsage()
			},
			handleAuthenticated() {
				if (!this.authenticated) return
				this.$nextTick(() => {
					this.$refs.chatPanel?.onAuthenticatedPageReady()
					if (this.sidebarOpen && this.recentExpanded) {
						this.ensureRecentConversations()
					}
					if (this.activeDestination === 'apiKeys') {
						this.$refs.apiKeyPanel?.onAuthenticatedPageReady()
					}
				})
			},
			handlePageShow() {
				if (!this.authenticated) return
				if (this.activeDestination === 'apiKeys') this.$refs.apiKeyPanel?.handlePageShow()
				else if (this.activeDestination === 'apiKeyUsage') this.$refs.apiKeyUsagePanel?.handlePageShow()
				else this.$refs.chatPanel?.handlePageShow()
			},
			handlePageHide() {
				this.$refs.chatPanel?.handlePageHide()
				this.$refs.apiKeyUsagePanel?.handlePageHide()
			},
			handlePageUnload() {
				this.$refs.chatPanel?.handlePageUnload()
				this.$refs.apiKeyPanel?.handlePageUnload()
				this.$refs.apiKeyUsagePanel?.invalidateRequests?.(true)
				this.releaseWorkspaceBody()
			},
			handleBackPress() {
				if (this.drawerOpen) {
					this.drawerOpen = false
					return true
				}
				const activePanel = this.activeDestination === 'chat'
					? this.$refs.chatPanel
					: this.activeDestination === 'models'
						? this.$refs.modelPanel
						: this.activeDestination === 'apiKeys'
							? this.$refs.apiKeyPanel
							: this.activeDestination === 'apiKeyUsage'
								? this.$refs.apiKeyUsagePanel
								: this.$refs.profilePanel
				if (typeof activePanel?.closeIfOpen === 'function'
					&& activePanel.closeIfOpen()) return true
				if (this.activeDestination === 'apiKeys') {
					this.selectDestination('profile')
					return true
				}
				if (this.activeDestination === 'apiKeyUsage') {
					this.backFromApiKeyUsage()
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

	.user-workspace.is-h5-workspace {
		--workspace-content-gutter: clamp(16px, 1.5vw, 40px);
		--workspace-layout-gap: clamp(16px, 1.25vw, 24px);
		--workspace-sidebar-track: 0px;
		--ait-h5-canvas: #0b0d0c;
		--ait-h5-sidebar: #101310;
		--ait-h5-surface: #151916;
		--ait-h5-border: rgba(151, 170, 160, .18);
		--ait-h5-accent: #37d39a;
		--ait-h5-text: #eef3f0;
		--ait-h5-muted: #98a39d;
		display: grid;
		grid-template-columns: var(--workspace-sidebar-track) minmax(0, 1fr);
		background: var(--ait-h5-canvas);
		transition: grid-template-columns 190ms cubic-bezier(.4, 0, 1, 1);
	}

	.user-workspace.is-h5-workspace.is-sidebar-open.is-sidebar-push {
		--workspace-sidebar-track: var(--workspace-sidebar-width);
		transition-duration: 230ms;
		transition-timing-function: cubic-bezier(.2, .8, .2, 1);
	}

	.is-h5-workspace .user-workspace-content {
		width: auto;
		grid-column: 2;
	}

	.workspace-sidebar-toggle {
		@include user-frosted-control;
		width: 44px;
		height: 44px;
		min-height: 44px;
		position: fixed;
		top: max(10px, env(safe-area-inset-top));
		left: 12px;
		z-index: 32;
		margin: 0;
		padding: 0;
		border-radius: 12px;
		box-sizing: border-box;
		transition: transform 100ms ease-out, background-color 160ms ease-out;
	}

	.workspace-sidebar-toggle::after { border: 0; }
	.workspace-sidebar-toggle:active { transform: scale(.96); }
	.workspace-sidebar-toggle:focus-visible { outline: 2px solid rgba(55, 211, 154, .82); outline-offset: 2px; }

	@media (prefers-reduced-motion: reduce) {
		.user-workspace.is-h5-workspace { transition: none; }
		.workspace-sidebar-toggle { transition: background-color 100ms ease-out; }
		.workspace-sidebar-toggle:active { transform: none; }
	}
</style>

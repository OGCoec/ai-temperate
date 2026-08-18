<template>
	<view class="workspace-sidebar-layer" :class="layerClass">
		<view
			v-if="mode === 'overlay'"
			class="workspace-sidebar-backdrop"
			aria-hidden="true"
			@click="requestClose"
		></view>
		<view
			id="workspace-conversation-sidebar"
			ref="sidebar"
			class="workspace-sidebar"
			:class="sidebarClass"
			role="complementary"
			:aria-label="presentation === 'rail' ? '账户导航轨道' : '会话边栏'"
			:aria-hidden="String(!open)"
			:inert="open ? undefined : true"
			tabindex="-1"
			@keydown.esc.stop="handleEscape"
			@keydown.tab="trapSidebarFocus"
		>
			<view class="workspace-sidebar-header">
				<view class="workspace-brand" aria-label="AI Temperate">
					<view class="workspace-brand-mark" aria-hidden="true">AI</view>
					<text v-if="presentation === 'full'" class="workspace-brand-name">AI Temperate</text>
				</view>
				<button
					v-if="presentation === 'full'"
					class="workspace-sidebar-close"
					type="button"
					aria-label="关闭会话边栏"
					aria-controls="workspace-conversation-sidebar"
					:aria-expanded="String(open)"
					@click="requestClose"
				>
					<uni-icons type="bars" size="21" color="#dce5e0" aria-hidden="true" />
				</button>
			</view>

			<button class="workspace-new-chat" type="button" aria-label="新聊天" title="新聊天" @click="$emit('new-chat')">
				<uni-icons type="compose" size="20" color="#37d39a" aria-hidden="true" />
				<text v-if="presentation === 'full'">新聊天</text>
			</button>

			<button
				v-if="presentation === 'rail'"
				class="workspace-rail-chat"
				type="button"
				aria-label="返回聊天并打开会话"
				title="返回聊天"
				@click="$emit('destination-click', 'chat')"
			>
				<uni-icons type="chat" size="20" color="#aeb9b3" aria-hidden="true" />
			</button>

			<view v-if="presentation === 'full'" class="workspace-sidebar-conversations">
				<user-recent-conversations
					content-id="workspace-h5-recent"
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

			<view class="workspace-sidebar-footer">
				<button
					class="workspace-account-action"
					type="button"
					aria-label="账户与设置"
					title="账户与设置"
					:aria-current="['profile', 'apiKeys'].includes(activeDestination) ? 'page' : undefined"
					@click="$emit('destination-click', 'profile')"
				>
					<uni-icons type="person" size="20" :color="presentation === 'rail' ? '#37d39a' : '#aeb9b3'" aria-hidden="true" />
					<text v-if="presentation === 'full'">账户与设置</text>
				</button>
			</view>
		</view>
	</view>
</template>

<script>
	import UserRecentConversations from './user-recent-conversations.vue'

	export default {
		name: 'UserH5WorkspaceSidebar',
		components: { UserRecentConversations },
		data() {
			return { previousBodyOverflow: null }
		},
		props: {
			activeDestination: { type: String, required: true },
			recentExpanded: { type: Boolean, default: true },
			open: { type: Boolean, default: false },
			mode: {
				type: String,
				default: 'overlay',
				validator: value => ['overlay', 'push'].includes(value)
			},
			presentation: {
				type: String,
				default: 'full',
				validator: value => ['full', 'rail'].includes(value)
			},
			conversations: { type: Array, default: () => [] },
			currentConversationPublicId: { type: String, default: '' },
			conversationsLoaded: { type: Boolean, default: false },
			conversationLoading: { type: Boolean, default: false },
			conversationError: { type: String, default: '' },
			hasMoreConversations: { type: Boolean, default: false }
		},
		computed: {
			layerClass() {
				return {
					'is-open': this.open,
					'is-overlay': this.mode === 'overlay',
					'is-push': this.mode === 'push',
					'is-rail': this.presentation === 'rail'
				}
			},
			sidebarClass() {
				return {
					'is-open': this.open,
					'is-overlay': this.mode === 'overlay',
					'is-push': this.mode === 'push',
					'is-rail': this.presentation === 'rail'
				}
			}
		},
		watch: {
			open(value) {
				this.handleOpenState(value)
			},
			mode() {
				this.syncBodyScrollLock()
			}
		},
		mounted() {
			this.handleOpenState(this.open)
		},
		beforeUnmount() {
			this.releaseBodyScrollLock()
		},
		beforeDestroy() {
			this.releaseBodyScrollLock()
		},
		methods: {
			requestClose() {
				this.$emit('close')
			},
			handleEscape() {
				if (this.presentation === 'full') this.requestClose()
			},
			handleOpenState(value) {
				this.syncBodyScrollLock()
				if (!value || this.presentation === 'rail') return
				this.$nextTick(() => {
					const sidebar = this.$refs.sidebar
					const element = sidebar?.$el || sidebar
					element?.focus?.({ preventScroll: true })
				})
			},
			syncBodyScrollLock() {
				if (typeof document === 'undefined') return
				const shouldLock = this.open && this.mode === 'overlay'
				if (shouldLock && this.previousBodyOverflow == null) {
					this.previousBodyOverflow = document.body.style.overflow
					document.body.style.overflow = 'hidden'
				} else if (!shouldLock) {
					this.releaseBodyScrollLock()
				}
			},
			releaseBodyScrollLock() {
				if (typeof document === 'undefined' || this.previousBodyOverflow == null) return
				document.body.style.overflow = this.previousBodyOverflow
				this.previousBodyOverflow = null
			},
			trapSidebarFocus(event) {
				if (this.mode !== 'overlay') return
				const sidebar = this.$refs.sidebar?.$el || this.$refs.sidebar
				const focusable = Array.from(sidebar?.querySelectorAll?.(
					'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
				) || [])
				if (!focusable.length) {
					event.preventDefault()
					sidebar?.focus?.()
					return
				}
				const first = focusable[0]
				const last = focusable[focusable.length - 1]
				if (event.shiftKey && document.activeElement === first) {
					event.preventDefault()
					last.focus()
				} else if (!event.shiftKey && document.activeElement === last) {
					event.preventDefault()
					first.focus()
				}
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.workspace-sidebar-layer { width: 100%; min-width: 0; height: 100%; position: relative; z-index: 40; overflow: visible; }
	.workspace-sidebar { width: var(--workspace-sidebar-width, 240px); height: 100dvh; min-height: 0; position: relative; z-index: 1; padding: 12px 12px max(12px, env(safe-area-inset-bottom)); display: flex; flex-direction: column; overflow: hidden; border-right: 1px solid var(--ait-h5-border, rgba(151, 170, 160, .18)); background: var(--ait-h5-sidebar, #101310); box-sizing: border-box; transform: translateX(-102%); visibility: hidden; pointer-events: none; transition: transform 190ms cubic-bezier(.4, 0, 1, 1), visibility 0s linear 190ms; }
	.workspace-sidebar.is-open { transform: translateX(0); visibility: visible; pointer-events: auto; transition: transform 230ms cubic-bezier(.2, .8, .2, 1), visibility 0s linear 0s; }
	.workspace-sidebar-header { min-height: 52px; display: flex; align-items: center; justify-content: space-between; gap: 8px; }
	.workspace-brand { min-width: 0; display: flex; align-items: center; gap: 10px; }
	.workspace-brand-mark { width: 30px; height: 30px; display: flex; align-items: center; justify-content: center; flex: 0 0 30px; border: 1px solid rgba(55, 211, 154, .28); border-radius: 9px; background: rgba(55, 211, 154, .08); color: #75dfb7; font-size: 11px; font-weight: 800; }
	.workspace-brand-name { overflow: hidden; color: var(--ait-h5-text, #eef3f0); font-size: 14px; font-weight: 720; letter-spacing: -.1px; text-overflow: ellipsis; white-space: nowrap; }
	.workspace-sidebar-close, .workspace-new-chat, .workspace-rail-chat, .workspace-account-action { @include user-frosted-control; box-sizing: border-box; }
	.workspace-sidebar-close { width: 44px; height: 44px; min-height: 44px; margin: 0; padding: 0; flex: 0 0 44px; border-radius: 12px; }
	.workspace-new-chat, .workspace-account-action { width: 100%; min-height: 48px; margin: 8px 0 0; padding: 0 12px; display: flex; align-items: center; justify-content: flex-start; gap: 10px; border-radius: 12px; color: #dce5e0; font-size: 14px; font-weight: 680; text-align: left; }
	.workspace-rail-chat { display: flex; align-items: center; justify-content: center; }
	.workspace-sidebar-conversations { min-height: 0; margin: 10px -12px 0 0; flex: 1; display: flex; flex-direction: column; overflow: hidden; --sidebar-inline-padding: 12px; }
	.workspace-sidebar-conversations :deep(.conversation-copy) { width: 44px; height: 44px; min-height: 44px; flex-basis: 44px; }
	.workspace-sidebar-conversations :deep(.recent-error button),
	.workspace-sidebar-conversations :deep(.recent-more) { min-height: 44px; }
	.workspace-sidebar-footer { padding-top: 10px; border-top: 1px solid rgba(151, 170, 160, .14); }
	.workspace-account-action { margin-top: 0; color: #aeb9b3; }
	.workspace-sidebar-close:focus-visible, .workspace-new-chat:focus-visible, .workspace-rail-chat:focus-visible, .workspace-account-action:focus-visible { outline: 2px solid rgba(55, 211, 154, .78); outline-offset: 2px; }
	.workspace-sidebar-close:active, .workspace-new-chat:active, .workspace-rail-chat:active, .workspace-account-action:active { transform: scale(.98); }
	.workspace-sidebar.is-rail { width: 72px; padding: 12px 10px max(12px, env(safe-area-inset-bottom)); align-items: center; }
	.workspace-sidebar.is-rail .workspace-sidebar-header { width: 100%; justify-content: center; }
	.workspace-sidebar.is-rail .workspace-brand { justify-content: center; }
	.workspace-sidebar.is-rail .workspace-new-chat,
	.workspace-sidebar.is-rail .workspace-rail-chat,
	.workspace-sidebar.is-rail .workspace-account-action { width: 44px; height: 44px; min-height: 44px; margin: 8px auto 0; padding: 0; justify-content: center; border-radius: 12px; }
	.workspace-sidebar.is-rail .workspace-sidebar-footer { width: 100%; margin-top: auto; padding-top: 0; border-top: 0; }
	.workspace-sidebar-backdrop { position: fixed; inset: 0; z-index: 0; background: rgba(0, 0, 0, .58); opacity: 0; pointer-events: none; transition: opacity 180ms ease-out; }
	.workspace-sidebar-layer.is-open .workspace-sidebar-backdrop { opacity: 1; pointer-events: auto; transition-duration: 220ms; }
	.workspace-sidebar.is-overlay { width: min(88vw, 360px); position: fixed; inset: 0 auto 0 0; z-index: 1; box-shadow: 18px 0 48px rgba(0, 0, 0, .32); }
	@media (hover: hover) and (pointer: fine) { .workspace-new-chat:hover, .workspace-rail-chat:hover, .workspace-account-action:hover, .workspace-sidebar-close:hover { background: rgba(243, 245, 244, .07); } }
	@media (prefers-reduced-motion: reduce) { .workspace-sidebar, .workspace-sidebar-backdrop { transition: none; } .workspace-sidebar-close:active, .workspace-new-chat:active, .workspace-rail-chat:active, .workspace-account-action:active { transform: none; } }
	@media (prefers-reduced-transparency: reduce), (prefers-contrast: more) { .workspace-sidebar { background: #101310; } .workspace-sidebar-backdrop { background: rgba(0, 0, 0, .72); } }
</style>

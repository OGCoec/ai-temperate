<template>
	<admin-page-shell
		:current-view="location.view"
		:busy="sessionState === 'VERIFYING_SESSION'"
		:drawer-open="drawerOpen"
		:session-state="sessionState"
		@navigate="navigate"
		@open-drawer="openDrawer"
		@close-drawer="closeDrawer"
	>
		<admin-feedback-banner
			v-if="routeNotice"
			tone="warning"
			:message="routeNotice"
			:dismissible="true"
			@dismiss="routeNotice = ''"
		/>

		<view v-if="sessionState === 'VERIFYING_SESSION'" class="workspace-skeleton" role="status" aria-live="polite">
			<view class="skeleton-heading" /><view class="skeleton-copy" />
			<view class="skeleton-grid"><view v-for="item in 4" :key="item" /></view>
			<text>正在验证管理员会话…</text>
		</view>

		<view v-else-if="sessionState === 'TRANSIENT_FAILURE'" class="workspace-session-error" role="alert">
			<text class="session-error-title">管理员会话暂时无法确认</text>
			<text class="session-error-copy">导航框架会保持显示。请检查网络后重试，系统不会把临时故障误判为退出登录。</text>
			<admin-action-button tone="teal" :loading="retrying" @click="retrySession">重新验证</admin-action-button>
		</view>

		<transition
			v-else-if="sessionState === 'READY'"
			name="workspace-content"
			mode="out-in"
			@after-enter="handlePanelAfterEnter"
		>
			<component
				:is="panelComponent"
				:key="panelKey"
				ref="activePanel"
				v-bind="panelProps"
				@request-navigation="navigate"
				@session-invalid="handleSessionInvalid"
				@update:ip2-mode="changeIp2Mode"
			/>
		</transition>
	</admin-page-shell>
</template>

<script>
import AdminActionButton from '@/components/admin/admin-action-button.vue'
import AdminFeedbackBanner from '@/components/admin/admin-feedback-banner.vue'
import AdminPageShell from '@/components/admin/admin-page-shell.vue'
import DashboardPanel from '@/components/admin/workspace/dashboard-panel.vue'
import AiModelListPanel from '@/components/admin/workspace/ai-model-list-panel.vue'
import AiModelDiscoveryPanel from '@/components/admin/workspace/ai-model-discovery-panel.vue'
import AiModelCreatePanel from '@/components/admin/workspace/ai-model-create-panel.vue'
import AiModelDetailPanel from '@/components/admin/workspace/ai-model-detail-panel.vue'
import AiModelIconsPanel from '@/components/admin/workspace/ai-model-icons-panel.vue'
import Ip2locationKeysPanel from '@/components/admin/workspace/ip2location-keys-panel.vue'
import MailInspectionPanel from '@/components/admin/workspace/mail-inspection-panel.vue'
import { createAdminWorkspaceController } from '@/common/admin/admin-workspace-controller.js'
import { createAdminWorkspaceHistoryApp } from '@/common/admin/admin-workspace-history-app.js'
// #ifdef H5
import { createAdminWorkspaceHistoryH5 } from '@/common/admin/admin-workspace-history-h5.js'
// #endif
import {
	buildAdminWorkspaceUrl,
	normalizeAdminWorkspaceLocation
} from '@/common/admin/admin-workspace-route.js'
import {
	ensureAdminSession,
	invalidateAdminSessionValidation,
	shouldRevalidateAdminSession
} from '@/common/admin/admin-route-guard-runtime.js'
import { handleAdminSessionInvalid } from '@/common/admin/admin-session-expiry-navigation.js'

const PANELS = Object.freeze({
	dashboard: DashboardPanel,
	'ai-models': AiModelListPanel,
	'ai-model-discovery': AiModelDiscoveryPanel,
	'ai-model-create': AiModelCreatePanel,
	'ai-model-detail': AiModelDetailPanel,
	'ai-model-icons': AiModelIconsPanel,
	'ip2location-keys': Ip2locationKeysPanel,
	'mail-openai': MailInspectionPanel,
	'mail-kiro': MailInspectionPanel,
	'mail-ip2location': MailInspectionPanel
})

function sanitizeDiscoveryPrefill(value) {
	if (!value || typeof value !== 'object') return null
	const modelId = String(value.modelId || '').trim()
	const owner = String(value.owner || '').trim()
	if (!modelId || modelId.length > 128 || owner.length > 128) return null
	return { modelId, owner }
}

export default {
	components: { AdminActionButton, AdminFeedbackBanner, AdminPageShell },
	data() {
		return {
			controller: null,
			historyAdapter: null,
			location: normalizeAdminWorkspaceLocation({ view: 'dashboard' }),
			activePanelLocationKey: '',
			lastActivatedPanelKey: '',
			sessionState: 'VERIFYING_SESSION',
			drawerOpen: false,
			retrying: false,
			routeNotice: '',
			activationSerial: 0,
			workspaceHidden: false,
			createdModelPublicId: '',
			pendingModelPrefill: null
		}
	},
	computed: {
		panelComponent() { return PANELS[this.location.view] || DashboardPanel },
		panelKey() {
			if (this.location.view === 'mail-ip2location') return `${this.location.view}:${this.location.mode}`
			if (this.location.view === 'ai-model-detail') return `${this.location.view}:${this.location.publicId}`
			if (this.location.view === 'ai-model-create') {
				return `${this.location.view}:${this.pendingModelPrefill?.modelId || 'blank'}`
			}
			return this.location.view
		},
		panelProps() {
			if (this.location.view === 'ai-model-create') {
				return { discoveryPrefill: this.pendingModelPrefill }
			}
			if (this.location.view === 'ai-model-detail') {
				return {
					workspacePublicId: this.location.publicId,
					created: this.createdModelPublicId === this.location.publicId
				}
			}
			if (this.location.view === 'mail-openai') {
				return {
					inspectionType: 'OPENAI_STATUS', activeBusiness: 'OPENAI', eyebrow: 'MAIL EVIDENCE · OPENAI',
					title: 'OpenAI 邮件证据检查',
					description: '通过 Microsoft OAuth 与 Outlook IMAP 搜索 OpenAI / ChatGPT 邮件，区分正常证据、限制证据、无注册证据和无法分类。'
				}
			}
			if (this.location.view === 'mail-kiro') {
				return {
					inspectionType: 'KIRO_STATUS', activeBusiness: 'KIRO', eyebrow: 'MAIL EVIDENCE · KIRO',
					title: 'Kiro 邮件证据检查',
					description: '通过 Microsoft OAuth 与 Outlook IMAP 搜索 Kiro / AWS 邮件，区分正常证据、限制证据、无注册证据和无法分类。'
				}
			}
			if (this.location.view === 'mail-ip2location') {
				const verify = this.location.mode === 'verify-link'
				return {
					inspectionType: verify ? 'IP2LOCATION_VERIFY_LINK' : 'IP2LOCATION_REGISTRATION',
					activeBusiness: 'IP2LOCATION',
					eyebrow: verify ? 'VERIFY LINK · IP2LOCATION' : 'MAIL EVIDENCE · IP2LOCATION',
					title: verify ? 'IP2Location 验证链接提取' : 'IP2Location 注册邮件检查',
					description: verify
						? '从 IP2Location 邮件中提取规范验证 URL 与单独 verifyToken。'
						: '扫描 IP2Location 注册候选邮件，明确区分已找到与扫描完成后未找到。',
					showIp2Modes: true,
					ip2Mode: verify ? 'IP2LOCATION_VERIFY_LINK' : 'IP2LOCATION_REGISTRATION'
				}
			}
			return {}
		}
	},
	onLoad(options) {
		this.location = normalizeAdminWorkspaceLocation(options || {})
		this.routeNotice = this.location.notice
	},
	async mounted() {
		this.createHistoryAdapter()
		this.controller = createAdminWorkspaceController({
			initialLocation: this.location,
			historyAdapter: this.historyAdapter,
			resolvePanel: location => buildAdminWorkspaceUrl(location) === this.activePanelLocationKey
				? this.$refs.activePanel
				: null,
				onChange: state => {
					this.location = state.location
					this.drawerOpen = state.drawerOpen
					if (state.location.view !== 'ai-model-create') {
						this.pendingModelPrefill = null
					}
					if (state.location.notice) this.routeNotice = state.location.notice
				}
		})
		this.historyAdapter?.start?.(this.location, buildAdminWorkspaceUrl(this.location))
		if (this.location.corrected) this.historyAdapter?.replace?.(this.location, buildAdminWorkspaceUrl(this.location))
		await this.verifySession(false)
	},
	onShow() {
		if (this.sessionState !== 'READY') return
		if (shouldRevalidateAdminSession()) {
			void this.verifySession(true)
		} else if (this.workspaceHidden) {
			this.workspaceHidden = false
			void this.activateCurrentPanel(true)
		}
	},
	onHide() {
		this.workspaceHidden = true
		this.$refs.activePanel?.onWorkspaceDeactivated?.()
	},
	onUnload() {
		this.$refs.activePanel?.onWorkspaceDeactivated?.()
		this.historyAdapter?.destroy?.()
	},
	onBackPress() {
		if (!this.controller) return false
		const state = this.controller.snapshot()
		const capturesBack = state.drawerOpen
			|| state.historyDepth > 0
			|| ['ai-model-create', 'ai-model-detail'].includes(state.location.view)
			|| Boolean(this.$refs.activePanel?.hasWorkspaceOverlay?.())
		if (!capturesBack) return false
		void this.controller.back()
		return true
	},
	methods: {
		createHistoryAdapter() {
			const wrap = adapter => ({
				start: (location, url) => adapter.start?.(location, url),
				push: location => adapter.push(location, buildAdminWorkspaceUrl(location)),
				replace: location => adapter.replace(location, buildAdminWorkspaceUrl(location)),
				releaseToSystem: () => adapter.releaseToSystem?.(),
				destroy: () => adapter.destroy?.()
			})
			// #ifdef H5
			this.historyAdapter = wrap(createAdminWorkspaceHistoryH5({
				windowObject: window,
				onPop: location => {
					const accepted = this.controller?.acceptPlatformLocation(location)
					if (!accepted) return
					void accepted.then(changed => {
						if (!changed) this.historyAdapter?.replace?.(this.location)
					})
				}
			}))
			// #endif
			// #ifndef H5
			const appHistory = createAdminWorkspaceHistoryApp()
			appHistory.replace(this.location)
			this.historyAdapter = wrap(appHistory)
			// #endif
		},
		async verifySession(force) {
			this.sessionState = 'VERIFYING_SESSION'
			try {
				const allowed = await ensureAdminSession({ force })
				if (!allowed) {
					this.sessionState = 'INVALID_SESSION'
					return
				}
				this.sessionState = 'READY'
				this.workspaceHidden = false
				await this.activateCurrentPanel(true)
			} catch (_error) {
				this.sessionState = 'TRANSIENT_FAILURE'
			}
		},
		async retrySession() {
			if (this.retrying) return
			this.retrying = true
			try { await this.verifySession(true) } finally { this.retrying = false }
		},
		async navigate(next, options = {}) {
			if (!this.controller || this.sessionState !== 'READY') return false
			const previousPrefill = this.pendingModelPrefill
			const nextPrefill = next?.view === 'ai-model-create'
				? sanitizeDiscoveryPrefill(next.prefill)
				: null
			const navigatingFromCreate = this.location.view === 'ai-model-create'
			// 当前新增页可能正在等待“放弃草稿”确认；确认完成前修改 key 会重挂载组件并丢失草稿。
			if (!navigatingFromCreate) this.pendingModelPrefill = nextPrefill
			if (next?.view === 'ai-model-detail' && next?.created === true) {
				this.createdModelPublicId = String(next.publicId || '')
			} else if (next?.view !== 'ai-model-detail') {
				this.createdModelPublicId = ''
			}
			try {
				const changed = await this.controller.navigate(next, options)
				if (!changed) {
					this.pendingModelPrefill = previousPrefill
					return false
				}
				this.pendingModelPrefill = nextPrefill
				return true
			} catch (error) {
				this.pendingModelPrefill = previousPrefill
				throw error
			}
		},
		async activateCurrentPanel(force = false) {
			const serial = ++this.activationSerial
			await this.$nextTick()
			if (serial !== this.activationSerial || this.sessionState !== 'READY') return
			const panel = this.$refs.activePanel
			if (!panel) return
			const locationKey = buildAdminWorkspaceUrl(this.location)
			if (!force && this.lastActivatedPanelKey === locationKey) return
			this.activePanelLocationKey = locationKey
			this.lastActivatedPanelKey = locationKey
			await panel.onWorkspaceActivated?.()
		},
		handlePanelAfterEnter() {
			void this.activateCurrentPanel(false)
		},
		openDrawer() { this.controller?.openDrawer() },
		closeDrawer() { this.controller?.closeDrawer() },
		changeIp2Mode(value) {
			const mode = value === 'IP2LOCATION_VERIFY_LINK' ? 'verify-link' : 'registration'
			void this.navigate({ view: 'mail-ip2location', mode })
		},
		handleSessionInvalid(error) {
			this.sessionState = 'INVALID_SESSION'
			invalidateAdminSessionValidation()
			handleAdminSessionInvalid(error || { code: 'ADMIN_SESSION_INVALID' }, { forceRedirect: true })
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.workspace-skeleton,
.workspace-session-error {
	min-height: 540rpx;
	padding: 36rpx;
	box-sizing: border-box;
	@include admin-solid-panel;
}

.workspace-skeleton { position: relative; overflow: hidden; }
.workspace-skeleton::after { content: ''; position: absolute; inset: 0; transform: translateX(-100%); background: linear-gradient(90deg, transparent, rgba(255, 255, 255, .045), transparent); animation: skeleton-sweep 1.3s infinite; }
.skeleton-heading { width: min(420rpx, 60%); height: 58rpx; border-radius: 14rpx; background: rgba($app-muted, .12); }
.skeleton-copy { width: min(720rpx, 82%); height: 28rpx; margin-top: 22rpx; border-radius: 10rpx; background: rgba($app-muted, .08); }
.skeleton-grid { margin-top: 56rpx; display: grid; grid-template-columns: repeat(2, 1fr); gap: 20rpx; }
.skeleton-grid view { min-height: 180rpx; border-radius: $app-radius-panel; background: rgba($app-muted, .065); }
.workspace-skeleton text { display: block; margin-top: 28rpx; color: $app-muted; }
.workspace-session-error { display: flex; flex-direction: column; align-items: flex-start; justify-content: center; }
.session-error-title { font-size: 32rpx; font-weight: 760; }
.session-error-copy { max-width: 720rpx; margin: 14rpx 0 28rpx; color: $app-muted; line-height: 1.6; }
.workspace-content-enter-active,
.workspace-content-leave-active { transition: opacity 170ms ease, transform 170ms $app-ease-out; }
.workspace-content-enter-from { opacity: 0; transform: translate3d(8px, 0, 0); }
.workspace-content-leave-to { opacity: 0; transform: translate3d(-5px, 0, 0); }

@keyframes skeleton-sweep { to { transform: translateX(100%); } }

@media (max-width: 600px) {
	.workspace-skeleton,
	.workspace-session-error { min-height: 420rpx; padding: 26rpx; }
	.skeleton-grid { grid-template-columns: 1fr; }
}

@media (prefers-reduced-motion: reduce) {
	.workspace-content-enter-active,
	.workspace-content-leave-active { transition: none; }
	.workspace-skeleton::after { animation: none; }
}
</style>

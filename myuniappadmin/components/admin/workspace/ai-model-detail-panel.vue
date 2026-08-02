<template>
	<view class="workspace-panel">
		<admin-page-header
			kicker="模型运营"
			:title="model?.modelName || '模型详情'"
			:description="`公共 ID ${publicId || '—'} · 字段更新由强 ETag 防止并发覆盖。`"
		>
		<template v-if="model" #meta>
			<view class="header-state">
				<view class="state-dot" :class="{ enabled: model.enabled }" aria-hidden="true" />
				<text>{{ model.enabled ? '已启用' : '已停用' }}</text>
				<text class="version-badge numeric">v{{ model.rowVersion }}</text>
			</view>
		</template>
		<template #actions>
			<admin-action-button tone="neutral" size="compact" aria-label="返回模型目录" @click="requestLeave">
				返回目录
			</admin-action-button>
		</template>
		</admin-page-header>
		<view class="detail-shell">
			<admin-feedback-banner
				v-if="createdNotice"
				tone="success"
				message="模型已建立。请在启用前再次确认倍率与能力。"
				:dismissible="true"
				@dismiss="createdNotice = false"
			/>
			<admin-feedback-banner v-if="serverError" tone="danger" :message="serverError" />
			<view v-if="conflict" class="conflict-panel" role="alert">
				<view>
					<text class="conflict-title">检测到并发修改</text>
					<text class="conflict-copy">服务器版本已变化。当前草稿仍保留，查看最新内容会放弃这份草稿。</text>
				</view>
				<admin-action-button tone="amber" @click="confirmLoadLatest">查看最新内容</admin-action-button>
			</view>

			<view v-if="loading" class="center-state" role="status">
				<view class="loading-mark" aria-hidden="true" />
				<text>正在读取模型详情…</text>
			</view>
			<view v-else-if="loadError" class="center-state" role="alert">
				<text class="state-title">模型详情未能加载</text>
				<text class="state-copy">{{ loadError }}</text>
				<admin-action-button tone="teal" @click="loadDetail">重新加载</admin-action-button>
			</view>
			<template v-else-if="model">
				<view class="record-strip">
					<view>
						<text class="record-label">创建日期</text>
						<text class="record-value numeric">{{ model.createdAt || '—' }}</text>
					</view>
					<view>
						<text class="record-label">最近更新</text>
						<text class="record-value numeric">{{ model.updatedAt || '—' }}</text>
					</view>
					<view>
						<text class="record-label">并发版本</text>
						<text class="record-value numeric">{{ etag || '—' }}</text>
					</view>
					<view>
						<text class="record-label">能力数量</text>
						<text class="record-value numeric">{{ model.capabilities?.length || 0 }}</text>
					</view>
				</view>
				<view class="capacity-strip" aria-label="模型上下文与生成限制">
					<view>
						<text class="record-label">最大上下文窗口</text>
						<text class="record-value numeric">{{ tokenLimitDetail(model.contextWindowK, model.contextWindowTokens) }}</text>
					</view>
					<view>
						<text class="record-label">单次最大输出</text>
						<text class="record-value numeric">{{ tokenLimitDetail(model.maxOutputK, model.maxOutputTokens) }}</text>
					</view>
				</view>

				<ai-model-form
					ref="modelForm"
					v-model="draft"
					:errors="errors"
					:readonly="!editing"
					:busy="saving || statusWriting"
					:icon-options="iconOptions"
					:icon-loading="iconLoading"
					@manage-icons="manageIcons"
				/>

				<view class="status-panel">
					<view>
						<text class="status-title">运行状态独立管理</text>
						<text class="status-copy">状态不会写入字段 Merge Patch。启用前必须完整配置上下文与输出限制；每次启停都会递增版本，并在需要时刷新可用模型快照。</text>
					</view>
					<admin-action-button
						v-if="model.enabled"
						tone="orange"
						:disabled="saving || statusWriting"
						:loading="statusWriting"
						@click="confirmStatus"
					>停用此模型</admin-action-button>
					<admin-action-button
						v-else
						tone="lime"
						:disabled="saving || statusWriting"
						:loading="statusWriting"
						@click="confirmStatus"
					>启用此模型</admin-action-button>
				</view>
			</template>

			<view v-if="model && !loading" class="action-dock">
				<view class="dock-copy">
					<text class="dock-title">{{ editing ? '正在编辑受版本保护的字段' : '模型详情为只读状态' }}</text>
					<text>{{ editing ? '保存只提交发生变化的字段。' : '启用编辑后才会开放字段输入。' }}</text>
				</view>
				<view v-if="editing" class="dock-actions">
					<admin-action-button tone="neutral" :disabled="saving" @click="cancelEdit">取消编辑</admin-action-button>
					<admin-action-button tone="amber" :disabled="!tokenLimitsComplete" :loading="saving" @click="save">保存更改</admin-action-button>
				</view>
				<view v-else class="dock-actions">
					<admin-action-button tone="teal" :disabled="statusWriting" @click="loadDetail">刷新详情</admin-action-button>
					<admin-action-button tone="amber" :disabled="statusWriting" @click="startEdit">编辑字段</admin-action-button>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
import AdminActionButton from '@/components/admin/admin-action-button.vue'
import AdminFeedbackBanner from '@/components/admin/admin-feedback-banner.vue'
import AdminPageHeader from '@/components/admin/admin-page-header.vue'
import AiModelForm from '@/components/admin/ai-model-form.vue'
import { adminAiModelApi } from '@/common/admin/admin-ai-model-api.js'
import { adminAiModelIconApi } from '@/common/admin/admin-ai-model-icon-api.js'
import { isAdminRequestAborted } from '@/common/admin/admin-http.js'
import { createAdminRequestScope } from '@/common/admin/admin-request-scope.js'
import {
	aiModelFormChanged,
	cloneAiModelForm,
	createEmptyAiModelForm,
	createMergePatch,
	modelToAiModelForm,
	validateAiModelForm
} from '@/common/admin/admin-ai-model-form.js'

const PUBLIC_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/

function statusFailureMessage(error) {
	if (error?.code === 'AI_MODEL_TOKEN_LIMIT_REQUIRED') {
		return '启用失败：请先配置最大上下文窗口和单次最大输出。'
	}
	return error?.message || '模型状态修改失败。'
}

export default {
	name: 'AiModelDetailPanel',
	components: { AdminActionButton, AdminFeedbackBanner, AdminPageHeader, AiModelForm },
	emits: ['request-navigation'],
	props: {
		workspacePublicId: { type: String, required: true },
		created: { type: Boolean, default: false }
	},
	computed: {
		tokenLimitsComplete() {
			return String(this.draft?.contextWindowK ?? '').trim().length > 0
				&& String(this.draft?.maxOutputK ?? '').trim().length > 0
		}
	},
	data() {
		const empty = createEmptyAiModelForm()
		return {
			publicId: '',
			model: null,
			etag: '',
			snapshot: cloneAiModelForm(empty),
			draft: cloneAiModelForm(empty),
			errors: {},
			loading: false,
			requestScope: null,
			requestGeneration: 0,
			saving: false,
			statusWriting: false,
			iconOptions: [],
			iconLoading: false,
			editing: false,
			conflict: false,
			loadError: '',
			serverError: '',
			createdNotice: false,
			exitAllowed: false
		}
	},
	beforeUnmount() {
		this.cancelReadRequests()
	},
	methods: {
		onWorkspaceActivated() {
			const publicId = String(this.workspacePublicId || '').trim()
			if (!PUBLIC_ID_PATTERN.test(publicId)) return
			this.cancelReadRequests()
			this.ensureReadScope()
			const changed = this.publicId !== publicId
			this.publicId = publicId
			if (changed || !this.model) {
				this.createdNotice = this.created === true
				this.loadDetail()
			}
			return this.loadIcons()
		},
		onWorkspaceDeactivated() {
			this.cancelReadRequests()
		},
		ensureReadScope() {
			if (this.requestScope?.isActive()) return this.requestScope
			this.requestScope = createAdminRequestScope()
			this.requestGeneration += 1
			return this.requestScope
		},
		cancelReadRequests() {
			this.requestGeneration += 1
			this.requestScope?.abortAll()
			this.requestScope = null
			this.loading = false
			this.iconLoading = false
		},
		beforeWorkspaceLeave() {
			if (this.exitAllowed) return true
			if (this.saving || this.statusWriting) return false
			if (!this.editing || !aiModelFormChanged(this.snapshot, this.draft)) return true
			return new Promise(resolve => {
				uni.showModal({
					title: '离开并放弃草稿？',
					content: '当前字段修改尚未保存。',
					confirmText: '放弃并离开',
					confirmColor: '#d9686b',
					success: result => resolve(result.confirm === true),
					fail: () => resolve(false)
				})
			})
		},
		async loadIcons() {
			if (this.iconLoading) return
			const scope = this.ensureReadScope()
			const generation = this.requestGeneration
			this.iconLoading = true
			try {
				const options = await adminAiModelIconApi.listAll({ scope })
				if (!scope.isActive() || generation !== this.requestGeneration) return
				this.iconOptions = options
			} catch (error) {
				if (isAdminRequestAborted(error) || generation !== this.requestGeneration) return
				this.serverError = error?.message || '模型图标资源暂时无法加载。'
			} finally {
				if (generation === this.requestGeneration) this.iconLoading = false
			}
		},
		manageIcons() {
			if (this.saving || this.statusWriting) return
			this.$emit('request-navigation', { view: 'ai-model-icons' })
		},
		applyServerDetail(result) {
			this.model = result.model
			this.etag = result.etag
			this.snapshot = modelToAiModelForm(result.model)
			this.draft = cloneAiModelForm(this.snapshot)
			this.errors = {}
			this.editing = false
			this.conflict = false
			this.serverError = ''
		},
		async loadDetail() {
			if (!this.publicId || this.loading) return
			const scope = this.ensureReadScope()
			const generation = this.requestGeneration
			this.loading = true
			this.loadError = ''
			try {
				const result = await adminAiModelApi.detail(this.publicId, { scope })
				if (!scope.isActive() || generation !== this.requestGeneration) return
				this.applyServerDetail(result)
			} catch (error) {
				if (isAdminRequestAborted(error) || generation !== this.requestGeneration) return
				this.loadError = error?.message || '模型详情接口暂时不可用。'
			} finally {
				if (generation === this.requestGeneration) this.loading = false
			}
		},
		startEdit() {
			this.draft = cloneAiModelForm(this.snapshot)
			this.errors = {}
			this.serverError = ''
			this.conflict = false
			this.editing = true
		},
		cancelEdit() {
			if (!aiModelFormChanged(this.snapshot, this.draft)) {
				this.editing = false
				return
			}
			uni.showModal({
				title: '放弃字段更改？',
				content: '未保存的模型字段将被恢复为当前服务器版本。',
				confirmText: '放弃更改',
				confirmColor: '#d9686b',
				success: result => {
					if (!result.confirm) return
					this.draft = cloneAiModelForm(this.snapshot)
					this.errors = {}
					this.editing = false
					this.conflict = false
				}
			})
		},
		tokenLimitDetail(kValue, tokenValue) {
			if (kValue == null || tokenValue == null) return '未配置'
			return `${kValue} K · ${tokenValue} Token`
		},
		async save() {
			if (!this.editing || this.saving) return
			const validation = validateAiModelForm(this.draft)
			this.errors = validation.errors
			this.serverError = ''
			if (!validation.valid) {
				this.serverError = '请修正标记字段后再保存。'
				this.$nextTick(() => this.$refs.modelForm?.focusFirstInvalid(validation.errors))
				return
			}
			const patch = createMergePatch(this.snapshot, this.draft)
			if (!Object.keys(patch).length) {
				this.editing = false
				return
			}
			this.saving = true
			try {
				this.applyServerDetail(await adminAiModelApi.patch(this.publicId, this.etag, patch))
				this.createdNotice = false
			} catch (error) {
				if (error?.code === 'AI_MODEL_VERSION_CONFLICT') {
					this.conflict = true
					this.serverError = '保存未覆盖服务器的新版本，当前草稿仍保留。'
				} else {
					this.serverError = error?.message || '模型字段保存失败，当前草稿仍保留。'
				}
			} finally {
				this.saving = false
			}
		},
		confirmLoadLatest() {
			uni.showModal({
				title: '放弃草稿并加载最新版本？',
				content: '此操作不会自动合并当前草稿。',
				confirmText: '加载最新内容',
				confirmColor: '#39d6d2',
				success: result => {
					if (result.confirm) this.loadDetail()
				}
			})
		},
		confirmStatus() {
			if (!this.model || this.statusWriting) return
			const enabled = !this.model.enabled
			uni.showModal({
				title: enabled ? '启用模型' : '停用模型',
				content: enabled
					? '启用后模型将进入可用模型缓存。'
					: '停用只改变可用状态，模型主记录与能力历史仍会保留。',
				confirmText: enabled ? '启用' : '停用',
				confirmColor: enabled ? '#a8dc4a' : '#e89a4a',
				success: result => {
					if (result.confirm) this.setStatus(enabled)
				}
			})
		},
		async setStatus(enabled) {
			this.statusWriting = true
			this.serverError = ''
			try {
				await adminAiModelApi.setEnabled(this.publicId, enabled)
				await this.loadDetail()
			} catch (error) {
				this.serverError = statusFailureMessage(error)
			} finally {
				this.statusWriting = false
			}
		},
		requestLeave() {
			if (this.saving || this.statusWriting) return
			this.$emit('request-navigation', { view: 'ai-models' })
		},
		leave() {
			this.$emit('request-navigation', { view: 'ai-models' })
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.detail-page { min-height: 100vh; box-sizing: border-box; padding: 34rpx 34rpx calc(190rpx + env(safe-area-inset-bottom)); background: $app-bg; color: $app-text; }
.detail-shell { width: min(1320px, 100%); margin: 0 auto; padding-bottom: 150rpx; }
.context-bar { min-height: 164rpx; display: grid; grid-template-columns: 112rpx minmax(0, 1fr) auto; gap: 28rpx; align-items: center; }
.back-button { min-width: 104rpx; }
.eyebrow { display: block; color: $app-green; font-size: 24rpx; font-weight: 780; letter-spacing: .12em; }
.page-title { display: block; max-width: 800rpx; margin-top: 8rpx; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 54rpx; line-height: 1.04; font-weight: 800; letter-spacing: -.035em; }
.page-copy { display: block; margin-top: 12rpx; color: $app-muted; font-size: 24rpx; line-height: 1.5; }
.header-state { min-height: 72rpx; padding: 0 18rpx; border: 1px solid $app-border; border-radius: $app-radius-control; display: flex; align-items: center; gap: 10rpx; color: $app-muted; font-size: 24rpx; }
.state-dot { width: 14rpx; height: 14rpx; border-radius: 50%; background: $app-action-orange; }
.state-dot.enabled { background: $app-action-lime; }
.version-badge { margin-left: 8rpx; padding-left: 14rpx; border-left: 1px solid $app-border; color: $app-text; }
.admin-feedback-banner { margin: 12rpx 0 20rpx; }
.conflict-panel { margin: 12rpx 0 20rpx; padding: 22rpx 24rpx; border: 1px solid rgba($app-warning, .36); border-radius: $app-radius-panel; display: flex; align-items: center; justify-content: space-between; gap: 24rpx; background: rgba($app-warning, .08); }
.conflict-title, .conflict-copy { display: block; }
.conflict-title { color: #ffe0a5; font-size: 24rpx; font-weight: 760; }
.conflict-copy { margin-top: 6rpx; color: $app-muted; font-size: 24rpx; line-height: 1.45; }
.conflict-panel .admin-action-button { min-width: 188rpx; }
.center-state { min-height: 440rpx; padding: 40rpx; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; color: $app-muted; }
.loading-mark { width: 34rpx; height: 34rpx; margin-bottom: 18rpx; border: 4rpx solid rgba($app-green, .2); border-top-color: $app-green; border-radius: 50%; animation: spin .7s linear infinite; }
.state-title { color: $app-text; font-size: 28rpx; font-weight: 760; }
.state-copy { max-width: 620rpx; margin: 10rpx 0 20rpx; font-size: 24rpx; line-height: 1.5; }
.record-strip { min-height: 100rpx; margin: 10rpx 0 24rpx; @include admin-solid-panel; display: grid; grid-template-columns: repeat(4, 1fr); overflow: hidden; }
.record-strip > view { padding: 18rpx 24rpx; border-right: 1px solid $app-border; display: flex; flex-direction: column; justify-content: center; }
.record-strip > view:last-child { border-right: 0; }
.capacity-strip { min-height: 100rpx; margin: -10rpx 0 24rpx; @include admin-solid-panel; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); overflow: hidden; }
.capacity-strip > view { padding: 18rpx 24rpx; border-right: 1px solid $app-border; display: flex; flex-direction: column; justify-content: center; }
.capacity-strip > view:last-child { border-right: 0; }
.record-label { color: $app-muted; font-size: 24rpx; }
.record-value { margin-top: 6rpx; color: $app-text; font-size: 24rpx; font-weight: 700; }
.status-panel { margin-top: 24rpx; padding: 24rpx 28rpx; @include admin-solid-panel; display: grid; grid-template-columns: 1fr auto; gap: 28rpx; align-items: center; }
.status-title, .status-copy { display: block; }
.status-title { font-size: 24rpx; font-weight: 760; }
.status-copy { margin-top: 6rpx; color: $app-muted; font-size: 24rpx; line-height: 1.45; }
.status-panel .admin-action-button { min-width: 210rpx; }
.action-dock { position: fixed; z-index: 20; left: 292px; right: 0; bottom: 0; min-height: 132rpx; padding: 18rpx max(34rpx, calc((100vw - 1320px) / 2 + 34rpx)) calc(18rpx + env(safe-area-inset-bottom)); display: flex; align-items: center; justify-content: space-between; gap: 24rpx; @include admin-glass-chrome(true); box-shadow: 0 -8rpx 16rpx rgba(0, 0, 0, .18); }
.dock-title, .dock-copy > text { display: block; }
.dock-title { color: $app-text; font-size: 24rpx; font-weight: 740; }
.dock-copy > text:last-child { margin-top: 4rpx; color: $app-muted; font-size: 24rpx; }
.dock-actions { display: flex; gap: 12rpx; }
.dock-actions .admin-action-button { min-width: 190rpx; }
.numeric { font-variant-numeric: tabular-nums; }
button::after { border: 0; }
button:focus-visible { outline: 2px solid $app-focus; outline-offset: 2px; }
@keyframes spin { to { transform: rotate(360deg); } }

@supports ((backdrop-filter: blur(12px)) or (-webkit-backdrop-filter: blur(12px))) {
	.action-dock {
		background: rgba(8, 11, 13, .84);
		-webkit-backdrop-filter: blur(18px) saturate(130%);
		backdrop-filter: blur(18px) saturate(130%);
	}
}

@media (max-width: 1023px) and (min-width: 768px) {
	.action-dock { left: 132px; }
}

@media (max-width: 767px) {
	.detail-shell { padding-bottom: 178rpx; }
	.context-bar { padding: 0 22rpx; min-height: auto; grid-template-columns: 92rpx minmax(0, 1fr); gap: 10rpx; }
	.back-button { min-width: 88rpx; }
	.page-title { font-size: 40rpx; }
	.eyebrow, .page-copy { display: none; }
	.header-state { grid-column: 1 / -1; justify-content: center; min-height: 82rpx; }
	.admin-feedback-banner, .conflict-panel { margin-left: 0; margin-right: 0; }
	.conflict-panel { align-items: stretch; flex-direction: column; }
	.conflict-panel .admin-action-button { width: 100%; min-width: 0; }
	.record-strip { margin: 18rpx 22rpx; grid-template-columns: 1fr 1fr; }
	.capacity-strip { margin: 18rpx 22rpx; grid-template-columns: 1fr; }
	.capacity-strip > view { border-right: 0; }
	.capacity-strip > view:first-child { border-bottom: 1px solid $app-border; }
	.record-strip > view:nth-child(2) { border-right: 0; }
	.record-strip > view:nth-child(-n + 2) { border-bottom: 1px solid $app-border; }
	.status-panel { margin: 22rpx; padding: 22rpx; grid-template-columns: 1fr; }
	.status-panel .admin-action-button { width: 100%; min-width: 0; }
	.action-dock { left: 0; padding: 16rpx 22rpx calc(16rpx + env(safe-area-inset-bottom)); }
	.dock-copy { display: none; }
	.dock-actions { width: 100%; }
	.dock-actions .admin-action-button { min-width: 0; flex: 1; }
}

@media (prefers-reduced-motion: reduce) {
	.loading-mark { animation: none; }
	* { transition-duration: .01ms !important; }
}

@media (prefers-reduced-transparency: reduce) {
	.action-dock {
		background: $app-surface-elevated;
		-webkit-backdrop-filter: none;
		backdrop-filter: none;
	}
}

@media (prefers-contrast: more) {
	.record-strip,
	.capacity-strip,
	.status-panel,
	.action-dock {
		border: 2px solid $app-text;
		background: $app-canvas;
	}
}
</style>

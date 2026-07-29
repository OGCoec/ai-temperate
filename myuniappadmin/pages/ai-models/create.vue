<template>
	<admin-page-shell
		v-if="adminRouteReady"
		current-path="/pages/ai-models/create"
		kicker="模型运营"
		title="新增 AI 模型"
		description="先建立完整模型记录，再按需启用。新模型默认停用，不会立即进入可用快照。"
		:busy="saving"
		@navigate="navigateProtected"
	>
		<template #meta>
			<view class="header-state">
				<view class="state-dot" :class="{ enabled }" aria-hidden="true" />
				<text>{{ enabled ? '创建后启用' : '安全默认：停用' }}</text>
			</view>
		</template>
		<template #actions>
			<admin-action-button tone="neutral" size="compact" aria-label="返回模型目录" @click="requestLeave">
				返回目录
			</admin-action-button>
		</template>
		<view class="editor-shell">
			<admin-feedback-banner
				v-if="serverError"
				tone="danger"
				:message="serverError"
			/>

			<view class="status-panel">
				<view>
					<text class="status-title">初始可用状态</text>
					<text class="status-copy">停用状态允许先完成字段审阅；启用后会在事务提交后刷新模型缓存。</text>
				</view>
				<view class="status-choice" role="group" aria-label="模型初始启用状态">
					<button
						type="button"
						:class="{ active: !enabled }"
						:aria-pressed="!enabled"
						:disabled="saving"
						@click="enabled = false"
					>保持停用</button>
					<button
						type="button"
						:class="{ active: enabled }"
						:aria-pressed="enabled"
						:disabled="saving"
						@click="enabled = true"
					>创建后启用</button>
				</view>
			</view>

			<ai-model-form
				ref="modelForm"
				v-model="form"
				:errors="errors"
				:busy="saving"
				:icon-options="iconOptions"
				:icon-loading="iconLoading"
				@manage-icons="manageIcons"
			/>

			<view class="action-dock">
				<view class="dock-copy">
					<text class="dock-title">确认模型字段与能力</text>
					<text>保存后可在详情页继续编辑；系统不提供模型物理删除。</text>
				</view>
				<view class="dock-actions">
					<admin-action-button tone="neutral" :disabled="saving" @click="requestLeave">取消</admin-action-button>
					<admin-action-button tone="amber" :loading="saving" @click="save">保存模型</admin-action-button>
				</view>
			</view>
		</view>
	</admin-page-shell>
</template>

<script>
import AdminActionButton from '@/components/admin/admin-action-button.vue'
import AiModelForm from '@/components/admin/ai-model-form.vue'
import { adminAiModelApi } from '@/common/admin/admin-ai-model-api.js'
import { adminAiModelIconApi } from '@/common/admin/admin-ai-model-icon-api.js'
import { createAdminPageGuardMixin } from '@/common/admin/admin-page-guard.js'
import {
	guardedAdminNavigate,
	guardedAdminRedirect
} from '@/common/admin/admin-route-guard-runtime.js'
import {
	aiModelFormChanged,
	cloneAiModelForm,
	createEmptyAiModelForm,
	validateAiModelForm
} from '@/common/admin/admin-ai-model-form.js'

export default {
	mixins: [createAdminPageGuardMixin('/pages/ai-models/create')],
	components: { AdminActionButton, AiModelForm },
	data() {
		const form = createEmptyAiModelForm()
		return {
			form,
			initialForm: cloneAiModelForm(form),
			enabled: false,
			initialEnabled: false,
			errors: {},
			serverError: '',
			saving: false,
			iconOptions: [],
			iconLoading: false,
			exitAllowed: false
		}
	},
	onShow() {
		this.runAfterAdminRouteGuard(() => this.loadIcons())
	},
	onBackPress() {
		if (this.exitAllowed) return false
		this.requestLeave()
		return true
	},
	methods: {
		navigateProtected(route) {
			if (route === '/pages/ai-models/create' || this.saving) return
			if (!this.dirty()) {
				this.exitAllowed = true
				return guardedAdminNavigate(route)
			}
			uni.showModal({
				title: '放弃未保存内容？',
				content: '当前模型字段尚未保存，离开后不会保留草稿。',
				confirmText: '放弃并离开',
				confirmColor: '#d9686b',
				success: result => {
					if (!result.confirm) return
					this.exitAllowed = true
					guardedAdminNavigate(route)
				}
			})
		},
		async loadIcons() {
			if (this.iconLoading) return
			this.iconLoading = true
			try {
				this.iconOptions = await adminAiModelIconApi.listAll()
			} catch (error) {
				this.serverError = error?.message || '模型图标资源暂时无法加载。'
			} finally {
				this.iconLoading = false
			}
		},
		manageIcons() {
			if (this.saving) return
			return guardedAdminNavigate('/pages/ai-model-icons/index')
		},
		dirty() {
			return aiModelFormChanged(this.initialForm, this.form)
				|| this.enabled !== this.initialEnabled
		},
		requestLeave() {
			if (this.saving) return
			if (!this.dirty()) {
				this.leave()
				return
			}
			uni.showModal({
				title: '放弃未保存内容？',
				content: '当前模型字段尚未保存，返回后不会保留草稿。',
				confirmText: '放弃并返回',
				confirmColor: '#d9686b',
				success: result => {
					if (result.confirm) this.leave()
				}
			})
		},
		leave() {
			this.exitAllowed = true
			uni.navigateBack({ delta: 1 })
		},
		async save() {
			if (this.saving) return
			const validation = validateAiModelForm(this.form)
			this.errors = validation.errors
			this.serverError = ''
			if (!validation.valid) {
				this.serverError = '请修正标记字段后再保存模型。'
				this.$nextTick(() => this.$refs.modelForm?.focusFirstInvalid(validation.errors))
				return
			}
			this.saving = true
			try {
				const created = await adminAiModelApi.create({
					...validation.command,
					enabled: this.enabled
				})
				if (!created?.publicId) throw new Error('新增模型响应缺少公共 ID。')
				this.exitAllowed = true
				await guardedAdminRedirect(
					`/pages/ai-models/detail?publicId=${encodeURIComponent(created.publicId)}&created=1`)
			} catch (error) {
				this.serverError = error?.message || '模型保存失败，当前输入已保留。'
			} finally {
				this.saving = false
			}
		}
	}
}
</script>

<style lang="scss" scoped>
@import '@/common/app-theme.scss';

.editor-page { min-height: 100vh; box-sizing: border-box; padding: 34rpx 34rpx calc(190rpx + env(safe-area-inset-bottom)); background: $app-bg; color: $app-text; }
.editor-shell { width: min(1320px, 100%); margin: 0 auto; padding-bottom: 150rpx; }
.context-bar { min-height: 164rpx; display: grid; grid-template-columns: 112rpx minmax(0, 1fr) auto; gap: 28rpx; align-items: center; }
.back-button { min-width: 104rpx; }
.eyebrow { display: block; color: $app-green; font-size: 24rpx; font-weight: 780; letter-spacing: .12em; }
.page-title { display: block; margin-top: 8rpx; font-size: 54rpx; line-height: 1.04; font-weight: 800; letter-spacing: -.035em; }
.page-copy { display: block; max-width: 820rpx; margin-top: 12rpx; color: $app-muted; font-size: 24rpx; line-height: 1.55; }
.header-state { min-height: 72rpx; padding: 0 18rpx; border: 1px solid $app-border; border-radius: $app-radius-control; display: flex; align-items: center; gap: 10rpx; color: $app-muted; font-size: 24rpx; }
.state-dot { width: 14rpx; height: 14rpx; border-radius: 50%; background: $app-action-orange; }
.state-dot.enabled { background: $app-action-lime; }
.admin-feedback-banner { margin: 12rpx 0 22rpx; }
.status-panel { margin: 18rpx 0 24rpx; padding: 24rpx 28rpx; @include admin-solid-panel; display: grid; grid-template-columns: 1fr auto; gap: 28rpx; align-items: center; }
.status-title, .status-copy { display: block; }
.status-title { font-size: 24rpx; font-weight: 760; }
.status-copy { margin-top: 6rpx; color: $app-muted; font-size: 24rpx; line-height: 1.45; }
.status-choice { min-height: 78rpx; padding: 5rpx; border: 1px solid $app-border; border-radius: $app-radius-control; display: flex; background: #0b1115; }
.status-choice button { min-height: 88rpx; margin: 0; padding: 0 20rpx; border: 0; border-radius: 9rpx; display: inline-flex; align-items: center; justify-content: center; background: transparent; color: $app-muted; font-size: 24rpx; line-height: 1; transition: transform 120ms ease-out, background-color 180ms ease, color 180ms ease; }
.status-choice button:first-child.active { background: rgba($app-action-orange, .15); color: #ffd8ad; }
.status-choice button:last-child.active { background: rgba($app-action-lime, .17); color: #e6ffb7; }
.status-choice button:active:not(:disabled) { transform: scale(.98); }
.action-dock { position: fixed; z-index: 20; left: 292px; right: 0; bottom: 0; min-height: 132rpx; padding: 18rpx max(34rpx, calc((100vw - 1320px) / 2 + 34rpx)) calc(18rpx + env(safe-area-inset-bottom)); display: flex; align-items: center; justify-content: space-between; gap: 24rpx; @include admin-glass-chrome(true); box-shadow: 0 -8rpx 16rpx rgba(0, 0, 0, .18); }
.dock-title, .dock-copy > text { display: block; }
.dock-title { color: $app-text; font-size: 24rpx; font-weight: 740; }
.dock-copy > text:last-child { margin-top: 4rpx; color: $app-muted; font-size: 24rpx; }
.dock-actions { display: flex; gap: 12rpx; }
.dock-actions .admin-action-button { min-width: 190rpx; }
button::after { border: 0; }
button:focus-visible { outline: 2px solid $app-focus; outline-offset: 2px; }

@supports ((backdrop-filter: blur(12px)) or (-webkit-backdrop-filter: blur(12px))) {
	.action-dock {
		background: rgba(8, 11, 13, .84);
		-webkit-backdrop-filter: blur(18px) saturate(130%);
		backdrop-filter: blur(18px) saturate(130%);
	}
}

@media (hover: hover) and (pointer: fine) {
	.status-choice button:hover:not(.active):not(:disabled) {
		background: rgba($app-muted, .08);
		color: $app-text;
	}
}

@media (pointer: coarse) {
	.status-choice button {
		min-height: 96rpx;
	}
}

@media (max-width: 1023px) and (min-width: 768px) {
	.action-dock { left: 238px; }
}

@media (max-width: 767px) {
	.editor-shell { padding-bottom: 178rpx; }
	.context-bar { padding: 0 22rpx; min-height: auto; grid-template-columns: 92rpx minmax(0, 1fr); gap: 10rpx; }
	.back-button { min-width: 88rpx; }
	.page-title { font-size: 40rpx; }
	.eyebrow, .page-copy { display: none; }
	.header-state { grid-column: 1 / -1; justify-content: center; min-height: 82rpx; }
	.status-panel { margin: 22rpx; padding: 22rpx; grid-template-columns: 1fr; }
	.status-choice button { min-height: 78rpx; flex: 1; }
	.admin-feedback-banner { margin: 18rpx 0; }
	.action-dock { left: 0; padding: 16rpx 22rpx calc(16rpx + env(safe-area-inset-bottom)); }
	.dock-copy { display: none; }
	.dock-actions { width: 100%; }
	.dock-actions .admin-action-button { min-width: 0; flex: 1; }
}

@media (prefers-reduced-motion: reduce) {
	* { transition-duration: .01ms !important; }
	.status-choice button:active:not(:disabled) { transform: none; }
}

@media (prefers-reduced-transparency: reduce) {
	.action-dock {
		background: $app-surface-elevated;
		-webkit-backdrop-filter: none;
		backdrop-filter: none;
	}
}

@media (prefers-contrast: more) {
	.status-panel,
	.action-dock {
		border: 2px solid $app-text;
		background: $app-canvas;
	}
}
</style>

<template>
	<view v-if="open" class="api-key-editor-layer" role="presentation" @click.self="requestClose" @keydown.esc.stop.prevent="requestClose" @keydown.tab="trapFocus">
		<view ref="editor" class="api-key-editor" role="dialog" aria-modal="true" aria-labelledby="api-key-editor-title" tabindex="-1">
			<view class="api-key-editor-heading">
				<view>
					<text id="api-key-editor-title" class="api-key-editor-title">管理 API Key</text>
					<text class="api-key-editor-key">{{ displayMaskedKey }}</text>
				</view>
				<button class="api-key-editor-close" type="button" aria-label="关闭 API Key 管理面板" @click="requestClose">
					<uni-icons type="closeempty" size="20" color="#dce5e0" aria-hidden="true" />
				</button>
			</view>

			<view class="api-key-editor-body">
				<view v-if="loading && !detail" class="api-key-editor-state" role="status">正在读取 API Key 详情…</view>
				<view v-else-if="loadError && !detail" class="api-key-editor-state api-key-editor-error" role="alert">
					<text>{{ loadError }}</text>
					<button type="button" @click="loadDetail">重新加载</button>
				</view>

				<template v-else-if="detail">
					<view v-if="conflict" class="api-key-conflict" role="alert">
						<text>API Key 已在其他页面发生变化。请重新加载，避免覆盖最新数据。</text>
						<button type="button" @click="reloadAfterConflict">重新加载</button>
					</view>

					<view class="api-key-editor-section">
						<text class="api-key-editor-section-title">状态与有效期</text>
						<view class="api-key-status-options" role="radiogroup" aria-label="API Key 状态">
							<button type="button" :class="{ active: lifecycleStatus === 'ENABLED' }" :aria-pressed="String(lifecycleStatus === 'ENABLED')" @click="lifecycleStatus = 'ENABLED'">启用</button>
							<button type="button" :class="{ active: lifecycleStatus === 'DISABLED' }" :aria-pressed="String(lifecycleStatus === 'DISABLED')" @click="lifecycleStatus = 'DISABLED'">停用</button>
						</view>
						<view class="api-key-expiry-editor">
							<user-api-key-expiry-picker
								:selection="expirySelection"
								:disabled="busy || conflict"
								@change="handleExpiryChange"
								@validity="handleExpiryValidity"
							/>
						</view>
						<text v-if="lifecycleError" class="api-key-section-error" role="alert">{{ lifecycleError }}</text>
						<button class="api-key-save" type="button" :disabled="lifecycleBusy || !lifecycleDirty || conflict" @click="saveLifecycle">
							{{ lifecycleBusy ? '正在保存…' : '保存设置' }}
						</button>
					</view>

					<view class="api-key-editor-section">
						<text class="api-key-editor-section-title">授权模型</text>
						<user-api-key-model-picker
							ref="modelPicker"
							:selected-ids="selectedModelIds"
							:minimum-models="0"
							:disabled-models="disabledModels"
							@change="selectedModelIds = $event"
						/>
						<text v-if="selectedModelIds.length === 0" class="api-key-empty-model-warning" role="status">保存空授权后，此 Key 将不能调用任何模型。</text>
						<text v-if="modelsError" class="api-key-section-error" role="alert">{{ modelsError }}</text>
						<button class="api-key-save" type="button" :disabled="modelsBusy || !modelsDirty || conflict" @click="saveModels">
							{{ modelsBusy ? '正在保存…' : '保存授权' }}
						</button>
					</view>

					<view class="api-key-editor-section">
						<text class="api-key-editor-section-title">使用信息</text>
						<view class="api-key-metadata">
							<view><text>最近使用</text><text>{{ formatOptionalTime(detail.lastUsedAt, '尚未使用') }}</text></view>
							<view><text>创建时间</text><text>{{ formatOptionalTime(detail.createdAt) }}</text></view>
							<view><text>更新时间</text><text>{{ formatOptionalTime(detail.updatedAt) }}</text></view>
						</view>
					</view>

					<view class="api-key-editor-section api-key-danger-zone">
						<text class="api-key-editor-section-title">危险操作</text>
						<text class="api-key-danger-copy">撤销后，这个 Key 将立即失效；历史 Usage 和计费记录仍会保留。</text>
						<button class="api-key-delete" type="button" :disabled="deleteBusy || conflict" @click="openDeleteConfirm">撤销 API Key</button>
						<text v-if="deleteError" class="api-key-section-error" role="alert">{{ deleteError }}</text>
					</view>
				</template>
			</view>
		</view>

		<view v-if="deleteConfirmOpen" class="api-key-delete-layer" role="presentation" @click.self="closeDeleteConfirm" @keydown.esc.stop.prevent="closeDeleteConfirm">
			<view ref="deleteDialog" class="api-key-delete-dialog" role="alertdialog" aria-modal="true" aria-labelledby="api-key-delete-title" tabindex="-1">
				<text id="api-key-delete-title" class="api-key-delete-title">撤销 API Key？</text>
				<text class="api-key-delete-copy">{{ displayMaskedKey }} 撤销后将立即失效，且不能恢复。</text>
				<view class="api-key-delete-actions">
					<button type="button" :disabled="deleteBusy" @click="closeDeleteConfirm">取消</button>
					<button class="confirm" type="button" :disabled="deleteBusy" @click="removeKey">{{ deleteBusy ? '正在撤销…' : '确认撤销' }}</button>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
	import { formatLocalDateTimeZhCn } from '@/common/platform/date-time.js'
	import { apiKeyApi } from '@/common/user/api-key-api.js'
	import {
		API_KEY_EXPIRY_OPTION,
		createPermanentExpirySelection,
		expiresAtFromExpirySelection,
		expirySelectionFromExpiresAt
	} from '@/common/user/api-key-expiry.js'
	import UserApiKeyExpiryPicker from './user-api-key-expiry-picker.vue'
	import UserApiKeyModelPicker from './user-api-key-model-picker.vue'

	function sameExpirySelection(left, right) {
		const leftPermanent = left?.option === API_KEY_EXPIRY_OPTION.PERMANENT
		const rightPermanent = right?.option === API_KEY_EXPIRY_OPTION.PERMANENT
		if (leftPermanent || rightPermanent) return leftPermanent === rightPermanent
		return left?.localDate === right?.localDate
	}

	function sameIds(left, right) {
		const a = [...new Set(left)].sort()
		const b = [...new Set(right)].sort()
		return a.length === b.length && a.every((value, index) => value === b[index])
	}

	export default {
		components: { UserApiKeyExpiryPicker, UserApiKeyModelPicker },
		props: {
			open: { type: Boolean, default: false },
			apiKeyPublicId: { type: String, default: '' },
			summary: { type: Object, default: null }
		},
		data() {
			return {
				detail: null,
				etag: '',
				loading: false,
				loadError: '',
				conflict: false,
				lifecycleStatus: 'ENABLED',
				expirySelection: createPermanentExpirySelection(),
				expiryValid: true,
				expiryValidationMessage: '',
				selectedModelIds: [],
				disabledModels: [],
				lifecycleBusy: false,
				modelsBusy: false,
				deleteBusy: false,
				lifecycleError: '',
				modelsError: '',
				deleteError: '',
				deleteConfirmOpen: false,
				requestGeneration: 0
			}
		},
		computed: {
			displayMaskedKey() {
				return this.detail?.maskedKey || this.summary?.maskedKey || '正在读取…'
			},
			lifecycleDirty() {
				if (!this.detail) return false
				const originalExpiry = expirySelectionFromExpiresAt(this.detail.expiresAt)
				return this.lifecycleStatus !== this.detail.status
					|| !sameExpirySelection(this.expirySelection, originalExpiry)
			},
			modelsDirty() {
				if (!this.detail) return false
				const currentEnabled = this.detail.models
					.filter(model => model.enabled)
					.map(model => model.modelPublicId)
				return this.disabledModels.length > 0 || !sameIds(currentEnabled, this.selectedModelIds)
			},
			busy() {
				return this.loading || this.lifecycleBusy || this.modelsBusy || this.deleteBusy
			}
		},
		watch: {
			open(value) {
				if (value) {
					this.loadDetail()
					this.$nextTick(() => this.focusEditor())
				}
				else this.clearSensitiveState()
			},
			apiKeyPublicId(value, previous) {
				if (this.open && value && value !== previous) this.loadDetail()
			}
		},
		beforeDestroy() {
			this.clearSensitiveState()
		},
		beforeUnmount() {
			this.clearSensitiveState()
		},
		methods: {
			closeIfOpen() {
				if (this.deleteConfirmOpen) {
					this.deleteConfirmOpen = false
					this.$nextTick(() => this.focusEditor())
					return true
				}
				if (this.busy) {
					uni.showToast({ title: '请等待当前操作完成', icon: 'none' })
					return true
				}
				this.$emit('close')
				return true
			},
			focusEditor() {
				const editor = this.$refs.editor?.$el || this.$refs.editor
				const first = editor?.querySelector?.('button:not([disabled]), input:not([disabled])')
				;(first || editor)?.focus?.({ preventScroll: true })
			},
			trapFocus(event) {
				const target = this.deleteConfirmOpen ? this.$refs.deleteDialog : this.$refs.editor
				const root = target?.$el || target
				const focusable = Array.from(root?.querySelectorAll?.('button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])') || [])
				if (!focusable.length) return
				const first = focusable[0]
				const last = focusable[focusable.length - 1]
				if (event.shiftKey && document.activeElement === first) {
					event.preventDefault(); last.focus()
				} else if (!event.shiftKey && document.activeElement === last) {
					event.preventDefault(); first.focus()
				}
			},
			openDeleteConfirm() {
				this.deleteConfirmOpen = true
				this.$nextTick(() => {
					const dialog = this.$refs.deleteDialog?.$el || this.$refs.deleteDialog
					const first = dialog?.querySelector?.('button:not([disabled])')
					;(first || dialog)?.focus?.({ preventScroll: true })
				})
			},
			closeDeleteConfirm() {
				if (this.deleteBusy) return
				this.deleteConfirmOpen = false
				this.$nextTick(() => this.focusEditor())
			},
			requestClose() {
				if (!this.busy && !this.deleteConfirmOpen) this.$emit('close')
			},
			clearSensitiveState() {
				this.requestGeneration += 1
				this.loading = false
				this.lifecycleBusy = false
				this.modelsBusy = false
				this.deleteBusy = false
				this.detail = null
				this.etag = ''
				this.expirySelection = createPermanentExpirySelection()
				this.expiryValid = true
				this.expiryValidationMessage = ''
				this.selectedModelIds = []
				this.disabledModels = []
				this.deleteConfirmOpen = false
				this.conflict = false
				this.loadError = ''
				this.lifecycleError = ''
				this.modelsError = ''
				this.deleteError = ''
			},
			async loadDetail() {
				if (!this.apiKeyPublicId || this.loading) return
				const generation = ++this.requestGeneration
				this.loading = true
				this.loadError = ''
				try {
					const result = await apiKeyApi.detail(this.apiKeyPublicId)
					if (generation !== this.requestGeneration) return
					this.applyDetail(result.value, result.etag, false, false)
					this.conflict = false
				} catch (error) {
					if (generation === this.requestGeneration) this.handleFailure(error, 'loadError')
				} finally {
					if (generation === this.requestGeneration) this.loading = false
				}
			},
			applyDetail(value, etag, preserveLifecycle, preserveModels) {
				this.detail = value
				this.etag = etag
				if (!preserveLifecycle) {
					this.lifecycleStatus = value.status
					this.expirySelection = expirySelectionFromExpiresAt(value.expiresAt)
					this.expiryValid = true
					this.expiryValidationMessage = ''
				}
				if (!preserveModels) {
					this.selectedModelIds = value.models
						.filter(model => model.enabled)
						.map(model => model.modelPublicId)
					this.disabledModels = value.models.filter(model => !model.enabled)
				}
			},
			handleExpiryChange(value) {
				this.expirySelection = value
			},
			handleExpiryValidity(result) {
				this.expiryValid = result?.valid === true
				this.expiryValidationMessage = result?.message || ''
			},
			async saveLifecycle() {
				if (!this.lifecycleDirty || this.lifecycleBusy || this.conflict) return
				this.lifecycleError = ''
				if (!this.expiryValid) {
					this.lifecycleError = this.expiryValidationMessage || '请选择有效的过期日期。'
					return
				}
				let expiresAt
				try {
					expiresAt = expiresAtFromExpirySelection(this.expirySelection, new Date())
				} catch (error) {
					this.lifecycleError = error.message
					return
				}
				this.lifecycleBusy = true
				try {
					const result = await apiKeyApi.update(this.detail.id, this.etag, {
						status: this.lifecycleStatus,
						expiresAt
					})
					this.applyDetail(result.value, result.etag, false, true)
					this.$emit('updated', result.value)
					uni.showToast({ title: 'API Key 设置已保存', icon: 'none' })
				} catch (error) {
					this.handleFailure(error, 'lifecycleError')
				} finally {
					this.lifecycleBusy = false
				}
			},
			async saveModels() {
				if (!this.modelsDirty || this.modelsBusy || this.conflict) return
				if (this.selectedModelIds.length === 0) {
					const confirmed = await new Promise(resolve => uni.showModal({
						title: '清空模型授权？',
						content: '保存后，这个 API Key 将不能调用任何模型。',
						confirmText: '确认清空',
						success: result => resolve(result.confirm === true),
						fail: () => resolve(false)
					}))
					if (!confirmed) return
				}
				this.modelsBusy = true
				this.modelsError = ''
				try {
					const result = await apiKeyApi.replaceModels(
						this.detail.id,
						this.etag,
						this.selectedModelIds)
					this.applyDetail(result.value, result.etag, true, false)
					this.$emit('updated', result.value)
					uni.showToast({ title: '模型授权已保存', icon: 'none' })
				} catch (error) {
					this.handleFailure(error, 'modelsError')
					if (error?.code === 'MODEL_NOT_FOUND_OR_DISABLED') this.$refs.modelPicker?.refresh()
				} finally {
					this.modelsBusy = false
				}
			},
			async removeKey() {
				if (this.deleteBusy || this.conflict) return
				this.deleteBusy = true
				this.deleteError = ''
				try {
					await apiKeyApi.remove(this.detail.id, this.etag)
					this.deleteConfirmOpen = false
					this.$emit('deleted', this.detail.id)
					this.$emit('close')
				} catch (error) {
					this.deleteConfirmOpen = false
					this.handleFailure(error, 'deleteError')
				} finally {
					this.deleteBusy = false
				}
			},
			handleFailure(error, target) {
				if (error?.statusCode === 412 || error?.code === 'VERSION_CONFLICT') {
					this.conflict = true
					this[target] = 'API Key 已在其他页面修改，请重新加载。'
					return
				}
				if (error?.statusCode === 404 || error?.code === 'API_KEY_NOT_FOUND') {
					const id = this.detail?.id || this.apiKeyPublicId
					this.$emit('deleted', id)
					this.$emit('close')
					return
				}
				if (error?.statusCode === 428
					|| error?.code === 'VERSION_REQUIRED'
					|| error?.code === 'VERSION_INVALID') {
					this[target] = 'API Key 版本协议异常，已停止本次操作，请刷新页面。'
					return
				}
				if (error?.statusCode === 503 || error?.code === 'HTTP_503') {
					this[target] = '服务暂时不可用，当前表单内容已保留，请稍后手动重试。'
					return
				}
				this[target] = error?.message || 'API Key 操作暂时无法完成。'
			},
			reloadAfterConflict() {
				this.conflict = false
				this.lifecycleError = ''
				this.modelsError = ''
				this.deleteError = ''
				this.loadDetail()
			},
			formatOptionalTime(value, fallback = '暂不可用') {
				return value ? (formatLocalDateTimeZhCn(value) || fallback) : fallback
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';
	.api-key-editor-layer { position: fixed; inset: 0; z-index: 45; display: flex; justify-content: flex-end; background: rgba(0, 0, 0, .68); }
	.api-key-editor { @include user-frosted-surface; width: min(680px, 100%); height: 100dvh; display: grid; grid-template-rows: auto minmax(0, 1fr); box-sizing: border-box; overflow: hidden; border-radius: 22px 0 0 22px; color: #f3f5f4; }
	.api-key-editor-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 24px 24px 18px; border-bottom: 1px solid rgba(151, 170, 160, .13); }
	.api-key-editor-title { display: block; font-size: 24px; font-weight: 760; }
	.api-key-editor-key { display: block; margin-top: 6px; color: #8f9b95; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 13px; }
	.api-key-editor-close { width: 44px; height: 44px; min-height: 44px; display: inline-flex; align-items: center; justify-content: center; flex: 0 0 44px; margin: 0; padding: 0; box-sizing: border-box; border: 1px solid rgba(151, 170, 160, .2); border-radius: 12px; background: #171b18; color: #aeb9b3; font-size: 0; line-height: 1; }
	.api-key-editor-close:focus-visible { outline: 2px solid rgba(55, 211, 154, .72); }
	.api-key-editor-body { min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 0 24px 24px; }
	.api-key-editor-state { min-height: 240px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; color: #8f9b95; text-align: center; }
	.api-key-editor-state button, .api-key-conflict button { @include user-frosted-control; margin: 0; padding: 0 16px; border-radius: 12px; color: #dce5e0; }
	.api-key-editor-error, .api-key-section-error { color: #efb0aa; }
	.api-key-conflict { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 18px; padding: 14px; border: 1px solid rgba(222, 157, 80, .32); border-radius: 14px; background: rgba(201, 130, 47, .09); color: #efc18a; font-size: 13px; }
	.api-key-editor-section { margin-top: 18px; padding-top: 18px; border-top: 1px solid rgba(151, 170, 160, .16); }
	.api-key-editor-section-title { display: block; margin-bottom: 12px; color: #cbd4cf; font-size: 14px; font-weight: 720; }
	.api-key-status-options { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
	.api-key-status-options button, .api-key-save { @include user-frosted-control; margin: 0; border-radius: 12px; color: #aeb9b3; }
	.api-key-status-options button.active { border-color: rgba(55, 211, 154, .48); background: rgba(55, 211, 154, .09); color: #75dfb7; }
	.api-key-expiry-editor { margin-top: 14px; }
	.api-key-save { width: 100%; margin-top: 14px; border-color: rgba(55, 211, 154, .4); color: #75dfb7; }
	.api-key-section-error, .api-key-empty-model-warning { display: block; margin-top: 12px; font-size: 12px; line-height: 1.5; }
	.api-key-empty-model-warning { color: #efc18a; }
	.api-key-metadata { @include user-frosted-surface; border-radius: 14px; overflow: hidden; }
	.api-key-metadata > view { min-height: 52px; padding: 12px 14px; display: flex; justify-content: space-between; gap: 12px; box-sizing: border-box; border-bottom: 1px solid rgba(151, 170, 160, .12); }
	.api-key-metadata > view:last-child { border-bottom: 0; }
	.api-key-metadata text:first-child { color: #8f9b95; font-size: 12px; }
	.api-key-metadata text:last-child { color: #dce5e0; font-size: 13px; text-align: right; }
	.api-key-danger-zone { margin-bottom: calc(20px + env(safe-area-inset-bottom)); border-color: rgba(201, 130, 47, .24); }
	.api-key-danger-copy { display: block; color: #a99783; font-size: 12px; line-height: 1.6; }
	.api-key-delete { @include user-frosted-control; width: 100%; margin: 14px 0 0; border-color: rgba(222, 112, 95, .38); border-radius: 12px; color: #ef9e92; }
	.api-key-delete-layer { position: fixed; inset: 0; z-index: 70; display: flex; align-items: center; justify-content: center; padding: 20px; background: rgba(0, 0, 0, .8); }
	.api-key-delete-dialog { @include user-frosted-surface; width: min(440px, 100%); padding: 22px; border-radius: 18px; color: #f3f5f4; }
	.api-key-delete-title { display: block; font-size: 20px; font-weight: 760; }
	.api-key-delete-copy { display: block; margin-top: 10px; color: #aeb9b3; font-size: 13px; line-height: 1.6; }
	.api-key-delete-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 20px; }
	.api-key-delete-actions button { @include user-frosted-control; margin: 0; border-radius: 12px; color: #dce5e0; }
	.api-key-delete-actions .confirm { border-color: rgba(222, 112, 95, .4); color: #ef9e92; }
	@media screen and (max-width: 680px) { .api-key-editor { width: 100%; border-radius: 0; } .api-key-editor-heading { padding: 20px 16px 16px; } .api-key-editor-body { padding: 0 16px calc(20px + env(safe-area-inset-bottom)); } .api-key-conflict { align-items: stretch; flex-direction: column; } }
</style>

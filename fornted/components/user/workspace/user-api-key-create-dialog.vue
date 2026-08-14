<template>
	<view v-if="open" class="api-key-dialog-layer" role="presentation" @click.self="requestClose" @keydown.esc.stop.prevent="requestClose" @keydown.tab="trapFocus">
		<view ref="dialog" class="api-key-dialog" role="dialog" aria-modal="true" aria-labelledby="api-key-create-title" tabindex="-1">
			<view class="api-key-dialog-heading">
				<view>
					<text id="api-key-create-title" class="api-key-dialog-title">创建 API Key</text>
					<text class="api-key-dialog-subtitle">选择有效期和允许调用的模型。</text>
				</view>
				<button class="api-key-dialog-close" type="button" :disabled="busy" aria-label="关闭创建窗口" @click="requestClose">×</button>
			</view>

			<view class="api-key-form-section">
				<text class="api-key-form-label">有效期</text>
				<user-api-key-expiry-picker
					:selection="expirySelection"
					:disabled="busy"
					@change="handleExpiryChange"
					@validity="handleExpiryValidity"
				/>
			</view>

			<view class="api-key-form-section">
				<text class="api-key-form-label">授权模型</text>
				<user-api-key-model-picker
					:selected-ids="selectedModelIds"
					:minimum-models="1"
					@change="selectedModelIds = $event"
				/>
			</view>

			<text v-if="localError || error" class="api-key-dialog-error" role="alert">{{ localError || error }}</text>
			<view class="api-key-dialog-actions">
				<button class="api-key-cancel" type="button" :disabled="busy" @click="requestClose">取消</button>
				<button class="api-key-submit" type="button" :disabled="busy" @click="submit">
					{{ busy ? '正在创建…' : '创建 API Key' }}
				</button>
			</view>
		</view>
	</view>
</template>

<script>
	import {
		createPermanentExpirySelection,
		expiresAtFromExpirySelection
	} from '@/common/user/api-key-expiry.js'
	import UserApiKeyExpiryPicker from './user-api-key-expiry-picker.vue'
	import UserApiKeyModelPicker from './user-api-key-model-picker.vue'

	export default {
		components: { UserApiKeyExpiryPicker, UserApiKeyModelPicker },
		props: {
			open: { type: Boolean, default: false },
			busy: { type: Boolean, default: false },
			error: { type: String, default: '' }
		},
		data() {
			return {
				expirySelection: createPermanentExpirySelection(),
				expiryValid: true,
				expiryValidationMessage: '',
				selectedModelIds: [],
				localError: ''
			}
		},
		watch: {
			open(value) {
				if (!value) return
				this.expirySelection = createPermanentExpirySelection()
				this.expiryValid = true
				this.expiryValidationMessage = ''
				this.selectedModelIds = []
				this.localError = ''
				this.$nextTick(() => this.focusDialog())
			}
		},
		methods: {
			focusDialog() {
				const dialog = this.$refs.dialog?.$el || this.$refs.dialog
				const first = dialog?.querySelector?.('button:not([disabled]), input:not([disabled])')
				;(first || dialog)?.focus?.({ preventScroll: true })
			},
			trapFocus(event) {
				const dialog = this.$refs.dialog?.$el || this.$refs.dialog
				const focusable = Array.from(dialog?.querySelectorAll?.('button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])') || [])
				if (!focusable.length) return
				const first = focusable[0]
				const last = focusable[focusable.length - 1]
				if (event.shiftKey && document.activeElement === first) {
					event.preventDefault(); last.focus()
				} else if (!event.shiftKey && document.activeElement === last) {
					event.preventDefault(); first.focus()
				}
			},
			requestClose() {
				if (!this.busy) this.$emit('close')
			},
			handleExpiryChange(value) {
				this.expirySelection = value
			},
			handleExpiryValidity(result) {
				this.expiryValid = result?.valid === true
				this.expiryValidationMessage = result?.message || ''
			},
			submit() {
				this.localError = ''
				if (this.selectedModelIds.length < 1 || this.selectedModelIds.length > 500) {
					this.localError = '请选择 1～500 个可用模型。'
					return
				}
				if (!this.expiryValid) {
					this.localError = this.expiryValidationMessage || '请选择有效的过期日期。'
					return
				}
				let expiresAt
				try {
					expiresAt = expiresAtFromExpirySelection(this.expirySelection, new Date())
				} catch (error) {
					this.localError = error?.message || '请选择有效的过期日期。'
					return
				}
				this.$emit('submit', {
					expiresAt,
					modelPublicIds: [...new Set(this.selectedModelIds)]
				})
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';
	.api-key-dialog-layer { position: fixed; inset: 0; z-index: 40; display: flex; align-items: center; justify-content: center; padding: 22px; box-sizing: border-box; background: rgba(0, 0, 0, .72); }
	.api-key-dialog { @include user-frosted-surface; width: min(680px, 100%); max-height: min(860px, calc(100dvh - 44px)); overflow-y: auto; padding: 24px; box-sizing: border-box; border-radius: 22px; color: #f3f5f4; }
	.api-key-dialog-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
	.api-key-dialog-title { display: block; font-size: 24px; font-weight: 760; }
	.api-key-dialog-subtitle { display: block; margin-top: 6px; color: #8f9b95; font-size: 13px; }
	.api-key-dialog-close { width: 44px; height: 44px; min-height: 44px; margin: 0; padding: 0; border: 1px solid rgba(151, 170, 160, .2); border-radius: 12px; background: #171b18; color: #aeb9b3; font-size: 24px; }
	.api-key-dialog-close:focus-visible { outline: 2px solid rgba(55, 211, 154, .72); }
	.api-key-form-section { margin-top: 24px; }
	.api-key-form-label { display: block; margin-bottom: 10px; color: #cbd4cf; font-size: 13px; font-weight: 700; }
	.api-key-dialog-error { display: block; margin-top: 18px; color: #efb0aa; font-size: 13px; }
	.api-key-dialog-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 24px; }
	.api-key-cancel, .api-key-submit { @include user-frosted-control; min-width: 128px; margin: 0; padding: 0 18px; border-radius: 12px; color: #dce5e0; }
	.api-key-submit { border-color: rgba(55, 211, 154, .46); background: rgba(55, 211, 154, .13); color: #75dfb7; }
	@media screen and (max-width: 640px) { .api-key-dialog-layer { padding: 0; align-items: stretch; } .api-key-dialog { width: 100%; max-height: 100dvh; min-height: 100dvh; padding: 20px 16px calc(24px + env(safe-area-inset-bottom)); border-radius: 0; } .api-key-dialog-actions { position: sticky; bottom: 0; padding-top: 12px; background: #151816; } .api-key-cancel, .api-key-submit { min-width: 0; flex: 1; } }
</style>

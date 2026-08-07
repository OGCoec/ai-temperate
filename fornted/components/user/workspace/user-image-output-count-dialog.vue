<template>
	<uni-popup ref="popup" type="dialog" :mask-click="false" @change="handlePopupChange">
		<view
			class="image-count-dialog"
			role="dialog"
			aria-modal="true"
			aria-labelledby="image-count-title"
			@keydown.esc.stop="cancel"
		>
			<text id="image-count-title" class="image-count-title">生成图片数量</text>
			<text id="image-count-help" class="image-count-help">请输入 1 到 10 的整数；每张图片使用一路独立流式生成。</text>
			<input
				class="image-count-input"
				type="number"
				inputmode="numeric"
				maxlength="2"
				:value="draft"
				:focus="inputFocused"
				:aria-invalid="String(Boolean(error))"
				aria-describedby="image-count-help image-count-error"
				aria-label="图片数量"
				@input="handleInput"
				@confirm="confirm"
			/>
			<text id="image-count-error" class="image-count-error" role="alert" aria-live="polite">{{ error }}</text>
			<view class="image-count-actions">
				<button class="image-count-button image-count-cancel" type="button" @click="cancel">取消</button>
				<button class="image-count-button image-count-confirm" type="button" :disabled="!valid" @click="confirm">确认</button>
			</view>
		</view>
	</uni-popup>
</template>

<script>
	import { parseImageOutputCount } from '@/common/aichat/ai-conversation-image-generation.js'

	export default {
		emits: ['confirm', 'close'],
		data() {
			return { draft: '1', error: '', inputFocused: false, invalidInput: false }
		},
		computed: {
			valid() { return !this.invalidInput && parseImageOutputCount(this.draft) != null }
		},
		methods: {
			open(value = 1) {
				this.draft = String(parseImageOutputCount(value) || 1)
				this.error = ''
				this.invalidInput = false
				this.$refs.popup?.open?.()
				this.$nextTick(() => { this.inputFocused = true })
			},
			close() { this.$refs.popup?.close?.() },
			handleInput(event) {
				const raw = String(event?.detail?.value || '')
				this.invalidInput = /[^0-9]/.test(raw)
				this.draft = raw
					.replace(/\D/g, '')
					.slice(0, 2)
				this.error = (this.draft || this.invalidInput) && !this.valid
					? '数量必须是 1 到 10 的整数。'
					: ''
			},
			confirm() {
				const value = parseImageOutputCount(this.draft)
				if (value == null) {
					this.error = '请输入 1 到 10 的整数。'
					return
				}
				this.$emit('confirm', value)
				this.close()
			},
			cancel() {
				this.close()
			},
			handlePopupChange(event) {
				const shown = Boolean(event?.show)
				if (!shown) {
					this.inputFocused = false
					this.$emit('close')
				}
			}
		}
	}
</script>

<style lang="scss">
	@import '@/common/ui/user-material.scss';
	.image-count-dialog { width: min(360px, calc(100vw - 36px)); padding: 20px; border: 1px solid #34413a; border-radius: 18px; background: #151a17; box-shadow: 0 24px 64px rgba(0, 0, 0, .48); box-sizing: border-box; }
	.image-count-title, .image-count-help, .image-count-error { display: block; }
	.image-count-title { color: #edf6f1; font-size: 17px; font-weight: 760; }
	.image-count-help { margin-top: 8px; color: #9ba6a0; font-size: 12px; line-height: 1.55; }
	.image-count-input { height: 44px; margin-top: 16px; padding: 0 12px; border: 1px solid #435149; border-radius: 11px; background: #0f1311; color: #edf6f1; font-size: 16px; font-variant-numeric: tabular-nums; box-sizing: border-box; }
	.image-count-input:focus { border-color: #37d39a; outline: 2px solid rgba(55, 211, 154, .28); outline-offset: 1px; }
	.image-count-error { min-height: 18px; margin-top: 6px; color: #ff9b94; font-size: 11px; }
	.image-count-actions { margin-top: 12px; display: flex; justify-content: flex-end; gap: 8px; }
	.image-count-button { @include user-frosted-control; min-width: 72px; min-height: 38px; margin: 0; padding: 0 14px; border-radius: 10px; color: #dce5e0; font-size: 13px; }
	.image-count-button::after { border: 0; }
	.image-count-confirm { background: #37d39a; color: #07110d; font-weight: 750; }
	.image-count-confirm[disabled] { opacity: .45; }
</style>

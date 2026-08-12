<template>
	<view
		v-if="block && block.blockType === 'dialog'"
		:id="dialogId"
		class="ai-dialog-scrim"
		role="presentation"
		@keydown.esc="close"
	>
		<view
			ref="dialog"
			class="ai-dialog-card"
			role="dialog"
			aria-modal="true"
			:aria-labelledby="titleId"
			tabindex="-1"
		>
			<text :id="titleId" class="ai-dialog-title">{{ block.payload.title }}</text>
			<text class="ai-dialog-body">{{ block.payload.body }}</text>
			<view class="ai-dialog-actions">
				<button
					v-for="action in actions"
					:key="action.id"
					class="ai-dialog-action"
					type="button"
					@click="emitAction(action)"
				>
					<text>{{ action.label }}</text>
				</button>
				<button class="ai-dialog-action ai-dialog-cancel" type="button" aria-label="Close dialog" @click="close">
					<text>Close</text>
				</button>
			</view>
		</view>
	</view>
</template>

<script>
	export default {
		name: 'UserDialogBlock',
		emits: ['action', 'close'],
		props: {
			block: { type: Object, default: null }
		},
		computed: {
			safeBlockId() {
				return String(this.block?.blockId || 'dialog').replace(/[^a-zA-Z0-9_-]/g, '-')
			},
			dialogId() {
				return 'ai-dialog-' + this.safeBlockId
			},
			titleId() {
				return this.dialogId + '-title'
			},
			actions() {
				return Array.isArray(this.block?.payload?.actions)
					? this.block.payload.actions
					: []
			}
		},
		watch: {
			block: {
				immediate: true,
				handler() {
					this.$nextTick(() => this.$refs.dialog?.focus?.())
				}
			}
		},
		methods: {
			emitAction(action) {
				this.$emit('action', {
					blockId: this.block.blockId,
					commandId: action.commandId
				})
			},
			close() {
				this.$emit('close', this.block?.blockId || '')
			}
		}
	}
</script>

<style lang="scss">
	.ai-dialog-scrim { position: fixed; z-index: 1000; inset: 0; padding: 24px; display: flex; align-items: center; justify-content: center; background: rgba(0, 0, 0, .58); box-sizing: border-box; }
	.ai-dialog-card { width: min(100%, 460px); padding: 20px; display: flex; flex-direction: column; gap: 12px; border: 1px solid rgba(151, 177, 163, .28); border-radius: 16px; outline: none; background: rgba(22, 27, 24, .96); box-shadow: 0 8px 24px rgba(0, 0, 0, .32); }
	.ai-dialog-title { color: #f4faf6; font-size: 18px; font-weight: 760; }
	.ai-dialog-body { color: #c3d0c8; font-size: 15px; line-height: 1.6; white-space: pre-wrap; }
	.ai-dialog-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
	.ai-dialog-action { min-width: 44px; min-height: 44px; margin: 0; padding: 0 14px; border: 1px solid rgba(55, 211, 154, .36); border-radius: 12px; background: rgba(55, 211, 154, .10); color: #c9f5df; font-size: 13px; transition: background-color 100ms ease-out, transform 100ms ease-out; }
	.ai-dialog-action:active { background: rgba(55, 211, 154, .18); transform: scale(.985); }
	.ai-dialog-action:focus-visible { outline: 2px solid #8fe8c4; outline-offset: 2px; }
	.ai-dialog-action { cursor: pointer; }
	.ai-dialog-cancel { border-color: rgba(181, 197, 187, .3); background: transparent; color: #c3d0c8; }
	@supports (backdrop-filter: blur(12px)) { .ai-dialog-card { background: rgba(22, 27, 24, .86); backdrop-filter: blur(20px) saturate(118%); -webkit-backdrop-filter: blur(20px) saturate(118%); } }
	@media (prefers-reduced-motion: reduce) {
		.ai-dialog-action { transition: none; }
		.ai-dialog-action:active { transform: none; }
	}
	@media (prefers-reduced-transparency: reduce), (prefers-contrast: more) { .ai-dialog-card { background: #161b18; backdrop-filter: none; -webkit-backdrop-filter: none; } }
</style>

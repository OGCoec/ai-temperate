<template>
	<view v-if="open" class="h5-generation-settings-layer">
		<transition name="h5-generation-settings-backdrop-motion" appear>
			<view class="h5-generation-settings-backdrop" aria-hidden="true" @click="requestClose"></view>
		</transition>
		<transition name="h5-generation-settings-surface-motion" appear>
			<view
				id="h5-generation-settings"
				ref="panel"
				class="h5-generation-settings-panel"
				:class="`is-${presentation}`"
				role="dialog"
				aria-modal="true"
				aria-labelledby="h5-generation-settings-title"
				tabindex="-1"
				@keydown.esc.stop.prevent="requestClose"
				@keydown.tab="trapFocus"
			>
				<view class="h5-generation-settings-heading">
					<view class="h5-generation-settings-heading-copy">
						<text id="h5-generation-settings-title" class="h5-generation-settings-title">生成设置</text>
						<text class="h5-generation-settings-caption">{{ summary || '模型、推理、联网与媒体参数' }}</text>
					</view>
					<button class="h5-generation-settings-close" type="button" aria-label="关闭生成设置" @click="requestClose">
						<uni-icons type="closeempty" size="21" color="#dce5e0" aria-hidden="true" />
					</button>
				</view>

				<scroll-view class="h5-generation-settings-scroll" scroll-y>
					<view class="h5-generation-settings-fields">
						<view class="h5-generation-settings-section-heading">
							<text class="h5-generation-settings-section-label">当前模型</text>
						</view>
						<user-model-selector
							class="h5-generation-settings-model"
							:options="models"
							:selected-index="selectedModelIndex"
							:disabled="disabled || !models.length"
							:loading="loading"
							platform-mode="web"
							presentation="embedded"
							grouped
							@change="handleModelChange"
						/>
						<slot name="context"></slot>
						<user-generation-option-group
							v-for="section in visibleSections"
							:key="section.key"
							:section="section"
							:disabled="disabled || loading"
							@change="forwardChange"
						/>
					</view>
				</scroll-view>
			</view>
		</transition>
	</view>
</template>

<script>
	import UserGenerationOptionGroup from './user-generation-option-group.vue'
	import UserModelSelector from './user-model-selector.vue'

	export default {
		name: 'UserH5GenerationSettings',
		components: {
			UserGenerationOptionGroup,
			UserModelSelector
		},
		props: {
			open: {
				type: Boolean,
				default: false
			},
			presentation: {
				type: String,
				default: 'popover',
				validator: value => ['popover', 'sheet'].includes(value)
			},
			models: {
				type: Array,
				default: () => []
			},
			selectedModelIndex: {
				type: Number,
				default: 0
			},
			sections: {
				type: Array,
				default: () => []
			},
			disabled: {
				type: Boolean,
				default: false
			},
			loading: {
				type: Boolean,
				default: false
			},
			summary: {
				type: String,
				default: ''
			}
		},
		computed: {
			visibleSections() {
				return this.sections.filter(section =>
					section?.key && section.hiddenOnH5 !== true
					&& Array.isArray(section.options) && section.options.length)
			}
		},
		watch: {
			open(value) {
				if (!value) return
				this.$nextTick(() => {
					const panel = this.$refs.panel?.$el || this.$refs.panel
					panel?.focus?.()
				})
			},
			disabled(value) {
				if (value && this.open) this.requestClose()
			}
		},
		mounted() {
			if (this.open) {
				this.$nextTick(() => {
					const panel = this.$refs.panel?.$el || this.$refs.panel
					panel?.focus?.()
				})
			}
		},
		methods: {
			handleModelChange(event) {
				this.$emit('change', { key: 'model', detail: event.detail })
			},
			forwardChange(event) {
				this.$emit('change', event)
			},
			requestClose() {
				this.$emit('close')
			},
			closeIfOpen() {
				if (!this.open) return false
				this.requestClose()
				return true
			},
			trapFocus(event) {
				const panel = this.$refs.panel?.$el || this.$refs.panel
				if (!panel?.querySelectorAll) return
				const focusable = Array.from(panel.querySelectorAll(
					'button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex="-1"])'
				)).filter(element => element.offsetParent !== null)
				if (!focusable.length) {
					event.preventDefault()
					panel.focus?.()
					return
				}
				const first = focusable[0]
				const last = focusable[focusable.length - 1]
				const active = panel.ownerDocument?.activeElement
				if (event.shiftKey && (active === first || active === panel)) {
					event.preventDefault()
					last.focus()
				} else if (!event.shiftKey && active === last) {
					event.preventDefault()
					first.focus()
				}
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.h5-generation-settings-layer { position: absolute; inset: 0; z-index: 79; pointer-events: none; }
	.h5-generation-settings-backdrop { position: fixed; inset: 0; z-index: 79; background: transparent; pointer-events: auto; }
	.h5-generation-settings-panel { width: min(460px, calc(100vw - 32px)); max-height: min(72dvh, 640px); position: absolute; bottom: calc(100% + 10px); left: 0; z-index: 80; overflow: hidden; border: 1px solid rgba(151, 177, 163, .24); border-radius: 18px; background: rgba(20, 25, 22, .98); box-shadow: 0 24px 70px rgba(0, 0, 0, .42); transform-origin: left bottom; box-sizing: border-box; pointer-events: auto; }
	@supports (backdrop-filter: blur(12px)) { .h5-generation-settings-panel { background: rgba(20, 25, 22, .91); backdrop-filter: blur(22px) saturate(116%); -webkit-backdrop-filter: blur(22px) saturate(116%); } }
	.h5-generation-settings-heading { min-height: 68px; padding: 12px 12px 12px 16px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-bottom: 1px solid rgba(151, 177, 163, .16); box-sizing: border-box; }
	.h5-generation-settings-heading-copy { min-width: 0; flex: 1; }
	.h5-generation-settings-title, .h5-generation-settings-caption { display: block; }
	.h5-generation-settings-title { color: #f3f5f4; font-size: 16px; font-weight: 750; line-height: 1.3; }
	.h5-generation-settings-caption { margin-top: 3px; overflow: hidden; color: #98a39d; font-size: 11px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
	.h5-generation-settings-close { @include user-frosted-control; width: 44px; height: 44px; min-height: 44px; margin: 0; padding: 0; flex: 0 0 44px; border-radius: 12px; }
	.h5-generation-settings-close::after { border: 0; }
	.h5-generation-settings-close:focus-visible { outline: 2px solid rgba(55, 211, 154, .82); outline-offset: 2px; }
	.h5-generation-settings-close:active { transform: scale(.97); }
	.h5-generation-settings-scroll { max-height: calc(min(72dvh, 640px) - 68px); }
	.h5-generation-settings-fields { min-width: 0; padding: 12px 14px 16px; box-sizing: border-box; }
	.h5-generation-settings-section-heading { margin-bottom: 7px; }
	.h5-generation-settings-section-label { color: #9ba8a1; font-size: 11px; font-weight: 720; letter-spacing: .35px; line-height: 1.35; }
	.h5-generation-settings-model { width: 100%; }
	.h5-generation-settings-fields :deep(.context-usage) { width: 100%; margin-top: 10px; }
	.h5-generation-settings-backdrop-motion-enter-active { transition: opacity 220ms ease-out; }
	.h5-generation-settings-backdrop-motion-leave-active { transition: opacity 180ms ease-in; }
	.h5-generation-settings-backdrop-motion-enter, .h5-generation-settings-backdrop-motion-enter-from, .h5-generation-settings-backdrop-motion-leave-to { opacity: 0; }
	.h5-generation-settings-surface-motion-enter-active { transition: opacity 230ms ease-out, transform 230ms cubic-bezier(.2, .8, .2, 1); }
	.h5-generation-settings-surface-motion-leave-active { transition: opacity 190ms ease-in, transform 190ms cubic-bezier(.4, 0, 1, 1); }
	.h5-generation-settings-surface-motion-enter, .h5-generation-settings-surface-motion-enter-from, .h5-generation-settings-surface-motion-leave-to { opacity: 0; transform: translateY(8px) scale(.985); }
	@media screen and (max-width: 767px) {
		.h5-generation-settings-backdrop { background: rgba(0, 0, 0, .58); }
		.h5-generation-settings-panel.is-sheet { width: 100%; max-height: min(86dvh, 680px); position: fixed; right: 0; bottom: 0; left: 0; border-right: 0; border-bottom: 0; border-left: 0; border-radius: 22px 22px 0 0; transform: none; transform-origin: center bottom; }
		.h5-generation-settings-panel.is-sheet .h5-generation-settings-scroll { max-height: calc(min(86dvh, 680px) - 68px - env(safe-area-inset-bottom)); padding-bottom: env(safe-area-inset-bottom); box-sizing: border-box; }
		.h5-generation-settings-surface-motion-enter.is-sheet, .h5-generation-settings-surface-motion-enter-from.is-sheet, .h5-generation-settings-surface-motion-leave-to.is-sheet { transform: translateY(24px); }
	}
	@media (prefers-reduced-motion: reduce) { .h5-generation-settings-backdrop-motion-enter-active, .h5-generation-settings-backdrop-motion-leave-active, .h5-generation-settings-surface-motion-enter-active, .h5-generation-settings-surface-motion-leave-active { transition: none; } .h5-generation-settings-close:active { transform: none; } }
	@media (prefers-reduced-transparency: reduce), (prefers-contrast: more) { .h5-generation-settings-panel { background: #141916; backdrop-filter: none; -webkit-backdrop-filter: none; } }
</style>

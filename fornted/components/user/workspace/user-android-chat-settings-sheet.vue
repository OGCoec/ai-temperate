<template>
	<view v-if="opened" class="android-chat-settings-layer">
		<view class="android-chat-settings-backdrop" aria-hidden="true" @click="close"></view>
		<view
			ref="panel"
			class="android-chat-settings-sheet"
			:class="`is-${String(mode || 'TEXT').toLowerCase()}`"
			role="dialog"
			aria-modal="true"
			:aria-label="title"
			tabindex="-1"
			@keydown.esc.stop.prevent="close"
		>
			<view class="android-chat-settings-heading">
				<view class="android-chat-settings-heading-copy">
					<text class="android-chat-settings-title">{{ title }}</text>
					<text v-if="summary" class="android-chat-settings-summary">{{ summary }}</text>
				</view>
				<button class="android-chat-settings-close" type="button" aria-label="关闭设置" @click="close">
					<uni-icons type="closeempty" size="20" color="#dce5e0" aria-hidden="true" />
				</button>
			</view>

			<scroll-view class="android-chat-settings-scroll" scroll-y>
				<view class="android-chat-settings-section">
					<text class="android-chat-settings-section-label">当前模型</text>
					<view
						class="android-chat-settings-model-list"
						:style="{ '--visible-model-items': String(normalizedMaxVisibleItems) }"
					>
						<button
							v-for="(model, index) in models"
							:key="model.publicId || `${model.modelName}-${index}`"
							class="android-chat-settings-row"
							:class="{ 'is-selected': index === normalizedSelectedModelIndex }"
							type="button"
							:disabled="disabled || loading || model.disabled === true || model.enabled === false"
							:aria-current="index === normalizedSelectedModelIndex ? 'true' : undefined"
							@click="select('model', index)"
						>
							<view class="android-chat-settings-row-copy">
								<text class="android-chat-settings-row-title">{{ model.modelName || '未命名模型' }}</text>
								<text v-if="model.providerName || model.provider || model.vendor" class="android-chat-settings-row-detail">
									{{ model.providerName || model.provider || model.vendor }}
								</text>
							</view>
							<uni-icons v-if="index === normalizedSelectedModelIndex" type="checkmarkempty" size="20" color="#37d39a" aria-hidden="true" />
						</button>
					</view>
				</view>

				<view
					v-for="section in visibleSections"
					:key="section.key"
					class="android-chat-settings-section"
				>
					<text class="android-chat-settings-section-label">{{ section.label }}</text>
					<view
						v-if="section.presentation === 'segmented'"
						class="android-chat-settings-segments"
						:class="{ 'is-wrapping': section.options.length > 3 }"
					>
						<button
							v-for="(option, index) in section.options"
							:key="`${section.key}-${option.value ?? index}`"
							class="android-chat-settings-segment"
							:class="{ 'is-selected': index === normalizedSectionIndex(section) }"
							type="button"
							:disabled="disabled || loading || section.disabled || option.disabled"
							@click="select(section.key, index)"
						>
							{{ option.label ?? option.value }}
						</button>
					</view>
					<view v-else class="android-chat-settings-option-list">
						<button
							v-for="(option, index) in section.options"
							:key="`${section.key}-${option.value ?? index}`"
							class="android-chat-settings-row"
							:class="{ 'is-selected': index === normalizedSectionIndex(section) }"
							type="button"
							:disabled="disabled || loading || section.disabled || option.disabled"
							@click="select(section.key, index)"
						>
							<text class="android-chat-settings-row-title">{{ option.label ?? option.value }}</text>
							<uni-icons v-if="index === normalizedSectionIndex(section)" type="checkmarkempty" size="20" color="#37d39a" aria-hidden="true" />
						</button>
					</view>
				</view>
			</scroll-view>
		</view>
	</view>
</template>

<script>
	export default {
		name: 'UserAndroidChatSettingsSheet',
		props: {
			models: {
				type: Array,
				default: () => []
			},
			selectedModelIndex: {
				type: Number,
				default: 0
			},
			summary: {
				type: String,
				default: ''
			},
			mode: {
				type: String,
				default: 'TEXT',
				validator: value => ['TEXT', 'IMAGE', 'VIDEO'].includes(value)
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
			title: {
				type: String,
				default: '模型与能力'
			},
			maxVisibleItems: {
				type: Number,
				default: 6
			},
			platformMode: {
				type: String,
				default: 'native'
			}
		},
		data() {
			return {
				opened: false,
				focusReturnTarget: null
			}
		},
		computed: {
			normalizedSelectedModelIndex() {
				const index = Number(this.selectedModelIndex)
				return Number.isInteger(index) && index >= 0 && index < this.models.length
					? index
					: 0
			},
			normalizedMaxVisibleItems() {
				const count = Math.trunc(Number(this.maxVisibleItems))
				return Number.isFinite(count) ? Math.min(8, Math.max(1, count)) : 6
			},
			visibleSections() {
				return this.sections.filter(section =>
					section?.key && Array.isArray(section.options) && section.options.length)
			}
		},
		watch: {
			opened(value) {
				if (!value) return
				this.$nextTick(() => {
					const panel = this.$refs.panel
					const element = panel?.$el || panel
					element?.focus?.()
				})
			},
			disabled(value) {
				if (value) this.close()
			}
		},
		methods: {
			normalizedSectionIndex(section) {
				const index = Number(section?.selectedIndex)
				return Number.isInteger(index) && index >= 0 && index < section.options.length
					? index
					: 0
			},
			open() {
				if (this.disabled || this.loading || !this.models.length) return
				this.focusReturnTarget = this.$el?.ownerDocument?.activeElement || null
				this.opened = true
			},
			close() {
				if (!this.opened) return
				this.opened = false
				this.$emit('close')
				this.$nextTick(() => {
					this.focusReturnTarget?.focus?.()
					this.focusReturnTarget = null
				})
			},
			closeIfOpen() {
				if (!this.opened) return false
				this.close()
				return true
			},
			select(key, index) {
				if (this.disabled || this.loading) return
				this.$emit('change', { key, detail: { value: String(index) } })
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.android-chat-settings-layer { position: fixed; inset: 0; z-index: 96; }
	.android-chat-settings-backdrop { position: absolute; inset: 0; background: rgba(0, 0, 0, .62); }
	.android-chat-settings-sheet {
		width: 100%;
		max-height: min(68dvh, 520px);
		position: absolute;
		right: 0;
		bottom: 0;
		left: 0;
		overflow: hidden;
		border: 1px solid rgba(151, 177, 163, .24);
		border-right: 0;
		border-bottom: 0;
		border-left: 0;
		border-radius: 22px 22px 0 0;
		background: rgba(20, 25, 22, .98);
		box-shadow: 0 -14px 36px rgba(0, 0, 0, .34);
		box-sizing: border-box;
	}
	.android-chat-settings-heading { min-height: 62px; padding: 10px 10px 10px 16px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-bottom: 1px solid rgba(151, 177, 163, .15); box-sizing: border-box; }
	.android-chat-settings-heading-copy { min-width: 0; flex: 1; }
	.android-chat-settings-title { display: block; color: #f3f5f4; font-size: 16px; font-weight: 760; line-height: 1.25; }
	.android-chat-settings-summary { display: block; margin-top: 2px; overflow: hidden; color: #8e9a94; font-size: 11px; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
	.android-chat-settings-close { @include user-frosted-control; width: 42px; height: 42px; min-height: 42px; margin: 0; padding: 0; flex: 0 0 42px; border-radius: 13px; }
	.android-chat-settings-close::after,
	.android-chat-settings-row::after,
	.android-chat-settings-segment::after { border: 0; }
	.android-chat-settings-scroll { max-height: calc(min(68dvh, 520px) - 62px - env(safe-area-inset-bottom)); padding-bottom: env(safe-area-inset-bottom); box-sizing: border-box; }
	.android-chat-settings-section { padding: 12px 14px 4px; }
	.android-chat-settings-section + .android-chat-settings-section { padding-top: 14px; border-top: 1px solid rgba(151, 177, 163, .1); }
	.android-chat-settings-section-label { display: block; margin: 0 2px 7px; color: #89968f; font-size: 11px; font-weight: 700; letter-spacing: .35px; }
	.android-chat-settings-model-list { max-height: calc(var(--visible-model-items) * 48px); overflow-y: auto; border: 1px solid rgba(151, 177, 163, .14); border-radius: 13px; background: rgba(8, 13, 10, .22); }
	.android-chat-settings-option-list { overflow: hidden; border: 1px solid rgba(151, 177, 163, .14); border-radius: 13px; }
	.android-chat-settings-row { width: 100%; min-height: 48px; margin: 0; padding: 7px 10px 7px 12px; display: flex; align-items: center; justify-content: space-between; gap: 10px; border: 0; border-bottom: 1px solid rgba(151, 177, 163, .1); border-radius: 0; background: transparent; color: #e8eeeb; text-align: left; box-sizing: border-box; }
	.android-chat-settings-row:last-child { border-bottom: 0; }
	.android-chat-settings-row.is-selected { background: rgba(55, 211, 154, .09); }
	.android-chat-settings-row:active { background: rgba(55, 211, 154, .13); }
	.android-chat-settings-row:disabled { opacity: .45; }
	.android-chat-settings-row-copy { min-width: 0; display: flex; flex: 1; flex-direction: column; }
	.android-chat-settings-row-title { overflow: hidden; font-size: 13px; font-weight: 670; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
	.android-chat-settings-row-detail { margin-top: 1px; overflow: hidden; color: #88948e; font-size: 10px; line-height: 1.25; text-overflow: ellipsis; white-space: nowrap; }
	.android-chat-settings-segments { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 5px; }
	.android-chat-settings-segments.is-wrapping { grid-template-columns: repeat(2, minmax(0, 1fr)); }
	.android-chat-settings-segment { min-height: 42px; margin: 0; padding: 5px 8px; display: flex; align-items: center; justify-content: center; border: 1px solid rgba(151, 177, 163, .18); border-radius: 11px; background: rgba(243, 245, 244, .035); color: #aeb9b3; font-size: 12px; font-weight: 650; line-height: 1.25; text-align: center; box-sizing: border-box; }
	.android-chat-settings-segment.is-selected { border-color: rgba(55, 211, 154, .5); background: rgba(55, 211, 154, .12); color: #c9f4e2; }
	.android-chat-settings-segment:disabled { opacity: .45; }
	.android-chat-settings-close:focus-visible,
	.android-chat-settings-row:focus-visible,
	.android-chat-settings-segment:focus-visible { outline: 2px solid rgba(55, 211, 154, .76); outline-offset: -2px; }
	@media screen and (orientation: landscape) and (max-height: 520px) {
		.android-chat-settings-sheet { max-height: 82dvh; }
		.android-chat-settings-scroll { max-height: calc(82dvh - 62px - env(safe-area-inset-bottom)); }
	}
	@media (prefers-reduced-transparency: reduce), (prefers-contrast: more) {
		.android-chat-settings-sheet { background: #141916; }
	}
</style>

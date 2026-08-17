<template>
	<view class="user-model-selector" :class="{ 'is-open': open, 'is-native': platformMode === 'native', 'is-embedded': embedded, 'is-grouped': grouped }">
		<button
			ref="trigger"
			class="user-model-selector-trigger"
			type="button"
			:disabled="disabled || loading || !options.length"
			:aria-expanded="String(open)"
			:aria-haspopup="embedded ? 'listbox' : 'dialog'"
			@click="toggle"
		>
			<text class="user-model-selector-trigger-label">{{ selectedOption?.modelName || '选择模型' }}</text>
			<uni-icons :type="open ? 'up' : 'down'" size="14" color="#a0aaa5" aria-hidden="true" />
		</button>

		<template v-if="open">
			<view v-if="open && !embedded" class="user-model-selector-backdrop" aria-hidden="true" @click="close"></view>
			<view
				ref="panel"
				class="user-model-selector-panel"
				:role="embedded ? 'region' : 'dialog'"
				:aria-modal="embedded ? undefined : 'true'"
				aria-label="选择模型"
				tabindex="-1"
				@keydown.esc.stop.prevent="close"
				@keydown.down.prevent="moveOptionFocus(1)"
				@keydown.up.prevent="moveOptionFocus(-1)"
				@keydown.home.prevent="focusBoundaryOption(0)"
				@keydown.end.prevent="focusBoundaryOption(flatModelEntries.length - 1)"
			>
				<view class="user-model-selector-heading">
					<view>
						<text class="user-model-selector-title">模型</text>
						<text class="user-model-selector-caption">选择本次对话使用的模型</text>
					</view>
					<button class="user-model-selector-close" type="button" aria-label="关闭模型列表" @click="close">
						<uni-icons type="closeempty" size="20" color="#dce5e0" aria-hidden="true" />
					</button>
				</view>
				<scroll-view class="user-model-selector-list" scroll-y role="listbox" aria-label="可用模型">
					<view
						v-for="group in modelGroups"
						:key="group.key"
						class="user-model-selector-group"
						role="group"
						:aria-labelledby="group.label ? `user-model-group-${group.key}` : undefined"
						:aria-label="group.label ? undefined : '模型'"
					>
						<text v-if="group.label" :id="`user-model-group-${group.key}`" class="user-model-selector-group-label">{{ group.label }}</text>
						<button
							v-for="entry in group.models"
							:key="entry.model.publicId || `${entry.model.modelName}-${entry.originalIndex}`"
							ref="optionButtons"
							class="user-model-selector-option"
							:class="{ 'is-selected': entry.originalIndex === normalizedSelectedIndex }"
							type="button"
							role="option"
							:disabled="entry.model.disabled === true || entry.model.enabled === false"
							:aria-selected="String(entry.originalIndex === normalizedSelectedIndex)"
							:data-model-index="String(entry.originalIndex)"
							@click="select(entry.originalIndex)"
							@focus="focusedOriginalIndex = entry.originalIndex"
						>
							<user-model-provider-mark v-if="grouped" :model="entry.model" :size="20" />
							<view class="user-model-selector-option-copy">
								<view class="user-model-selector-option-heading">
									<text class="user-model-selector-option-name">{{ entry.model.modelName || '未命名模型' }}</text>
									<view v-if="capabilityBadges(entry.model).length" class="user-model-selector-option-badges">
										<text v-for="badge in capabilityBadges(entry.model)" :key="badge" class="user-model-selector-option-badge">{{ badge }}</text>
									</view>
								</view>
								<text v-if="grouped && optionDescription(entry.model)" class="user-model-selector-option-description">{{ optionDescription(entry.model) }}</text>
								<text v-if="providerLabel(entry.model)" class="user-model-selector-option-provider">{{ providerLabel(entry.model) }}</text>
							</view>
							<uni-icons v-if="entry.originalIndex === normalizedSelectedIndex" type="checkmarkempty" size="22" color="#37d39a" aria-hidden="true" />
						</button>
					</view>
				</scroll-view>
			</view>
		</template>
	</view>
</template>

<script>
	import UserModelProviderMark from './user-model-provider-mark.vue'

	export default {
		name: 'UserModelSelector',
		components: { UserModelProviderMark },
		props: {
			options: {
				type: Array,
				default: () => []
			},
			selectedIndex: {
				type: Number,
				default: 0
			},
			disabled: {
				type: Boolean,
				default: false
			},
			loading: {
				type: Boolean,
				default: false
			},
			platformMode: {
				type: String,
				default: 'web'
			},
			presentation: {
				type: String,
				default: 'overlay',
				validator: value => ['overlay', 'embedded'].includes(value)
			},
			grouped: {
				type: Boolean,
				default: false
			}
		},
		data() {
			return {
				open: false,
				focusedOriginalIndex: -1
			}
		},
		computed: {
			embedded() {
				return this.presentation === 'embedded'
			},
			normalizedSelectedIndex() {
				const index = Number(this.selectedIndex)
				return Number.isInteger(index) && index >= 0 && index < this.options.length ? index : 0
			},
			selectedOption() {
				return this.options[this.normalizedSelectedIndex] || null
			},
			modelGroups() {
				if (!this.grouped) {
					return [{
						key: 'all',
						label: '',
						models: this.options.map((model, originalIndex) => ({ model, originalIndex }))
					}]
				}
				const definitions = [
					{ key: 'video', label: '视频' },
					{ key: 'image', label: '图片' },
					{ key: 'chat', label: '对话' },
					{ key: 'other', label: '其他' }
				]
				const grouped = new Map(definitions.map(definition => [definition.key, []]))
				this.options.forEach((model, originalIndex) => {
					grouped.get(this.primaryModelGroup(model)).push({ model, originalIndex })
				})
				return definitions
					.map(definition => ({ ...definition, models: grouped.get(definition.key) }))
					.filter(group => group.models.length)
			},
			flatModelEntries() {
				return this.modelGroups.flatMap(group => group.models)
			}
		},
		watch: {
			open(value) {
				if (!value) return
				this.focusedOriginalIndex = this.normalizedSelectedIndex
				this.$nextTick(() => {
					this.focusOriginalIndex(this.normalizedSelectedIndex)
				})
			},
			disabled(value) {
				if (value) this.close()
			},
			loading(value) {
				if (value) this.close()
			}
		},
		methods: {
			modelCapabilities(model) {
				return Array.isArray(model?.capabilities)
					? model.capabilities.map(value => String(value || '').toUpperCase())
					: []
			},
			primaryModelGroup(model) {
				const capabilities = this.modelCapabilities(model)
				if (capabilities.includes('VIDEO_GENERATION')) return 'video'
				if (capabilities.includes('IMAGE_GENERATION')) return 'image'
				if (capabilities.includes('RESPONSES') || capabilities.includes('CHAT_COMPLETIONS')) return 'chat'
				return 'other'
			},
			capabilityBadges(model) {
				if (!this.grouped || this.primaryModelGroup(model) !== 'chat') return []
				const capabilities = this.modelCapabilities(model)
				const badges = []
				if (capabilities.includes('RESPONSES')) badges.push('Responses')
				if (capabilities.includes('CHAT_COMPLETIONS')) badges.push('Chat Completions')
				return badges
			},
			providerLabel(model) {
				return model?.providerName || model?.provider || model?.vendor || ''
			},
			optionDescription(model) {
				return String(model?.description || '').trim()
			},
			toggle() {
				if (this.disabled || this.loading || !this.options.length) return
				this.open = !this.open
			},
			close() {
				this.open = false
				this.focusedOriginalIndex = -1
				this.$nextTick(() => {
					const trigger = this.$refs.trigger
					if (trigger && typeof trigger.focus === 'function' && !this.disabled) trigger.focus()
				})
			},
			select(index) {
				if (!this.options[index] || this.options[index].disabled === true || this.options[index].enabled === false) return
				this.$emit('change', { detail: { value: String(index) } })
				this.close()
			},
			focusOriginalIndex(originalIndex) {
				const buttons = Array.isArray(this.$refs.optionButtons)
					? this.$refs.optionButtons
					: [this.$refs.optionButtons].filter(Boolean)
				const candidate = buttons.find(button => {
					const element = button?.$el || button
					return Number(element?.dataset?.modelIndex) === Number(originalIndex)
				})
				const element = candidate?.$el || candidate
				if (element?.focus) {
					this.focusedOriginalIndex = Number(originalIndex)
					element.focus()
					return
				}
				const panel = this.$refs.panel?.$el || this.$refs.panel
				panel?.focus?.()
			},
			moveOptionFocus(delta) {
				if (!this.flatModelEntries.length) return
				const current = this.flatModelEntries.findIndex(entry =>
					entry.originalIndex === this.focusedOriginalIndex)
				let position = current >= 0 ? current : this.flatModelEntries.findIndex(entry =>
					entry.originalIndex === this.normalizedSelectedIndex)
				for (let count = 0; count < this.flatModelEntries.length; count += 1) {
					position = (position + delta + this.flatModelEntries.length) % this.flatModelEntries.length
					const entry = this.flatModelEntries[position]
					if (entry.model.disabled !== true && entry.model.enabled !== false) {
						this.focusOriginalIndex(entry.originalIndex)
						return
					}
				}
			},
			focusBoundaryOption(position) {
				const bounded = Math.max(0, Math.min(this.flatModelEntries.length - 1, Number(position)))
				const direction = bounded === 0 ? 1 : -1
				let index = bounded
				while (index >= 0 && index < this.flatModelEntries.length) {
					const entry = this.flatModelEntries[index]
					if (entry.model.disabled !== true && entry.model.enabled !== false) {
						this.focusOriginalIndex(entry.originalIndex)
						return
					}
					index += direction
				}
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.user-model-selector { min-width: 0; position: relative; z-index: 4; }
	.user-model-selector.is-open { z-index: 90; }
	.user-model-selector.is-embedded,
	.user-model-selector.is-embedded.is-open { width: 100%; z-index: auto; }
	.user-model-selector-trigger {
		@include user-frosted-control;
		min-width: 0;
		min-height: 36px;
		margin: 0;
		padding: 0 10px;
		justify-content: flex-start;
		gap: 6px;
		border-radius: 10px;
		color: #cfd8d3;
		font-size: 12px;
		line-height: 1;
	}
	.user-model-selector-trigger::after,
	.user-model-selector-close::after,
	.user-model-selector-option::after { border: 0; }
	.user-model-selector-trigger-label { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
	.user-model-selector-trigger:focus-visible,
	.user-model-selector-close:focus-visible,
	.user-model-selector-option:focus-visible { outline: 2px solid rgba(55, 211, 154, .82); outline-offset: 2px; }
	.user-model-selector-backdrop { position: fixed; inset: 0; z-index: 50; background: rgba(0, 0, 0, .44); }
	.user-model-selector-panel {
		width: min(420px, calc(100vw - 32px));
		max-height: min(60dvh, 560px);
		position: absolute;
		z-index: 51;
		bottom: calc(100% + 10px);
		left: 0;
		overflow: hidden;
		border: 1px solid rgba(151, 177, 163, .26);
		border-radius: 16px;
		background: rgba(22, 27, 24, .96);
		box-shadow: 0 8px 24px rgba(0, 0, 0, .32);
	}
	@supports (backdrop-filter: blur(12px)) {
		.user-model-selector-panel { background: rgba(22, 27, 24, .86); backdrop-filter: blur(20px) saturate(118%); -webkit-backdrop-filter: blur(20px) saturate(118%); }
	}
	.user-model-selector-heading { min-height: 68px; padding: 12px 12px 12px 16px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-bottom: 1px solid rgba(151, 177, 163, .16); box-sizing: border-box; }
	.user-model-selector-title { display: block; color: #f3f5f4; font-size: 16px; font-weight: 750; line-height: 1.25; }
	.user-model-selector-caption { display: block; margin-top: 3px; color: #a0aaa5; font-size: 11px; line-height: 1.35; }
	.user-model-selector-close { @include user-frosted-control; width: 38px; height: 38px; min-height: 38px; margin: 0; padding: 0; border-radius: 11px; }
	.user-model-selector-list { max-height: calc(min(60dvh, 560px) - 68px); }
	.user-model-selector-group + .user-model-selector-group { border-top: 1px solid rgba(151, 177, 163, .15); }
	.user-model-selector-group-label { display: block; padding: 11px 16px 7px; color: #7f8b85; font-size: 10px; font-weight: 760; letter-spacing: .65px; line-height: 1.25; }
	.user-model-selector.is-embedded .user-model-selector-trigger,
	.user-model-selector.is-embedded .user-model-selector-close { min-height: 44px; }
	.user-model-selector.is-embedded .user-model-selector-close { width: 44px; height: 44px; }
	.user-model-selector.is-embedded .user-model-selector-panel {
		width: 100%;
		max-height: min(48dvh, 420px);
		position: relative;
		z-index: auto;
		right: auto;
		bottom: auto;
		left: auto;
		margin-top: 8px;
		border-radius: 14px;
		box-shadow: none;
	}
	.user-model-selector.is-embedded .user-model-selector-list { max-height: calc(min(48dvh, 420px) - 68px); }
	.user-model-selector-option { width: 100%; min-height: 58px; margin: 0; padding: 10px 14px 10px 16px; display: flex; align-items: center; justify-content: space-between; gap: 11px; border: 0; border-bottom: 1px solid rgba(151, 177, 163, .12); border-radius: 0; background: transparent; color: #e7ece9; text-align: left; box-sizing: border-box; transition: background-color 140ms ease-out, transform 100ms ease-out; }
	.user-model-selector.is-grouped .user-model-selector-option { min-height: 70px; }
	.user-model-selector-option:last-child { border-bottom: 0; }
	.user-model-selector-option:active { background: rgba(55, 211, 154, .10); transform: scale(.99); }
	.user-model-selector-option.is-selected { background: rgba(55, 211, 154, .08); }
	.user-model-selector-option-copy { min-width: 0; display: flex; flex: 1; flex-direction: column; gap: 3px; }
	.user-model-selector-option-heading { min-width: 0; display: flex; align-items: center; gap: 7px; }
	.user-model-selector-option-name { overflow: hidden; color: #e9eeeb; font-size: 14px; font-weight: 680; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
	.user-model-selector-option-badges { min-width: 0; display: flex; align-items: center; gap: 4px; }
	.user-model-selector-option-badge { padding: 2px 5px; flex: 0 0 auto; border: 1px solid rgba(55, 211, 154, .22); border-radius: 999px; background: rgba(55, 211, 154, .07); color: #8fdcbe; font-size: 8px; font-weight: 720; line-height: 1.25; }
	.user-model-selector-option-description { display: -webkit-box; overflow: hidden; color: #9aa59f; font-size: 10px; line-height: 1.35; -webkit-box-orient: vertical; -webkit-line-clamp: 1; }
	.user-model-selector-option-provider { overflow: hidden; color: #a0aaa5; font-size: 11px; line-height: 1.3; text-overflow: ellipsis; white-space: nowrap; }
	@media (hover: hover) and (pointer: fine) { .user-model-selector-option:hover { background: rgba(243, 245, 244, .06); } }
	.user-model-selector.is-native .user-model-selector-backdrop { z-index: 80; background: rgba(0, 0, 0, .56); }
	.user-model-selector.is-native .user-model-selector-trigger { min-height: 48px; }
	.user-model-selector.is-native .user-model-selector-panel { width: 100%; max-height: min(86dvh, 680px); position: fixed; z-index: 81; right: 0; bottom: 0; left: 0; border-right: 0; border-bottom: 0; border-left: 0; border-radius: 22px 22px 0 0; }
	.user-model-selector.is-native .user-model-selector-list { max-height: calc(min(86dvh, 680px) - 68px - env(safe-area-inset-bottom)); padding-bottom: env(safe-area-inset-bottom); }
	@media screen and (max-width: 767px) {
		.user-model-selector-backdrop { z-index: 80; background: rgba(0, 0, 0, .56); }
		.user-model-selector-panel { width: 100%; max-height: min(86dvh, 680px); position: fixed; z-index: 81; right: 0; bottom: 0; left: 0; border-right: 0; border-bottom: 0; border-left: 0; border-radius: 22px 22px 0 0; }
		.user-model-selector-list { max-height: calc(min(86dvh, 680px) - 68px - env(safe-area-inset-bottom)); padding-bottom: env(safe-area-inset-bottom); }
	}
	@media (prefers-reduced-motion: reduce) { .user-model-selector-option { transition: background-color 100ms ease-out; } .user-model-selector-option:active { transform: none; } }
	@media (prefers-reduced-transparency: reduce), (prefers-contrast: more) { .user-model-selector-panel { background: #161b18; backdrop-filter: none; -webkit-backdrop-filter: none; } }
</style>

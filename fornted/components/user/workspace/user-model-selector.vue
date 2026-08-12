<template>
	<view class="user-model-selector" :class="{ 'is-open': open, 'is-native': platformMode === 'native' }">
		<button
			ref="trigger"
			class="user-model-selector-trigger"
			type="button"
			:disabled="disabled || !options.length"
			:aria-expanded="String(open)"
			aria-haspopup="dialog"
			@click="toggle"
		>
			<text class="user-model-selector-trigger-label">{{ selectedOption?.modelName || '选择模型' }}</text>
			<uni-icons :type="open ? 'up' : 'down'" size="14" color="#a0aaa5" aria-hidden="true" />
		</button>

		<template v-if="open">
			<view class="user-model-selector-backdrop" aria-hidden="true" @click="close"></view>
			<view
				ref="panel"
				class="user-model-selector-panel"
				role="dialog"
				aria-modal="true"
				aria-label="选择模型"
				tabindex="-1"
				@keydown.esc.stop.prevent="close"
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
				<scroll-view class="user-model-selector-list" scroll-y>
					<button
						v-for="(option, index) in options"
						:key="option.publicId || `${option.modelName}-${index}`"
						class="user-model-selector-option"
						:class="{ 'is-selected': index === normalizedSelectedIndex }"
						type="button"
						:aria-current="index === normalizedSelectedIndex ? 'true' : undefined"
						@click="select(index)"
					>
						<view class="user-model-selector-option-copy">
							<text class="user-model-selector-option-name">{{ option.modelName }}</text>
							<text v-if="option.providerName || option.provider" class="user-model-selector-option-provider">{{ option.providerName || option.provider }}</text>
						</view>
						<uni-icons v-if="index === normalizedSelectedIndex" type="checkmarkempty" size="22" color="#37d39a" aria-hidden="true" />
					</button>
				</scroll-view>
			</view>
		</template>
	</view>
</template>

<script>
	export default {
		name: 'UserModelSelector',
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
			}
		},
		data() {
			return { open: false }
		},
		computed: {
			normalizedSelectedIndex() {
				const index = Number(this.selectedIndex)
				return Number.isInteger(index) && index >= 0 && index < this.options.length ? index : 0
			},
			selectedOption() {
				return this.options[this.normalizedSelectedIndex] || null
			}
		},
		watch: {
			open(value) {
				if (!value) return
				this.$nextTick(() => {
					const panel = this.$refs.panel
					if (panel && typeof panel.focus === 'function') panel.focus()
				})
			},
			disabled(value) {
				if (value) this.close()
			},
		},
		methods: {
			toggle() {
				if (this.disabled || !this.options.length) return
				this.open = !this.open
			},
			close() {
				this.open = false
				this.$nextTick(() => {
					const trigger = this.$refs.trigger
					if (trigger && typeof trigger.focus === 'function' && !this.disabled) trigger.focus()
				})
			},
			select(index) {
				if (!this.options[index]) return
				this.$emit('change', { detail: { value: String(index) } })
				this.close()
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.user-model-selector { min-width: 0; position: relative; z-index: 4; }
	.user-model-selector.is-open { z-index: 90; }
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
	.user-model-selector-option { width: 100%; min-height: 58px; margin: 0; padding: 10px 14px 10px 16px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border: 0; border-bottom: 1px solid rgba(151, 177, 163, .12); border-radius: 0; background: transparent; color: #e7ece9; text-align: left; box-sizing: border-box; transition: background-color 140ms ease-out, transform 100ms ease-out; }
	.user-model-selector-option:last-child { border-bottom: 0; }
	.user-model-selector-option:active { background: rgba(55, 211, 154, .10); transform: scale(.99); }
	.user-model-selector-option.is-selected { background: rgba(55, 211, 154, .08); }
	.user-model-selector-option-copy { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
	.user-model-selector-option-name { overflow: hidden; color: #e9eeeb; font-size: 14px; font-weight: 680; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
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

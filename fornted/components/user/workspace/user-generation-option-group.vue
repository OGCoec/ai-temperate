<template>
	<view
		v-if="options.length"
		class="generation-option-group"
		:class="[`is-${presentation}`, { 'is-disabled': groupDisabled }]"
	>
		<text :id="labelId" class="generation-option-group-label">{{ section.label }}</text>
		<view
			class="generation-option-group-options"
			:class="{ 'is-wrapping': isSegmented && options.length === 4 }"
			:role="isSegmented ? 'radiogroup' : 'listbox'"
			:aria-labelledby="labelId"
			:aria-disabled="groupDisabled ? 'true' : undefined"
			@keydown.left.prevent="move(-1)"
			@keydown.right.prevent="move(1)"
			@keydown.up.prevent="move(-1)"
			@keydown.down.prevent="move(1)"
			@keydown.home.prevent="moveToBoundary(0)"
			@keydown.end.prevent="moveToBoundary(options.length - 1)"
		>
			<button
				v-for="(option, index) in options"
				:key="`${section.key}-${optionIdentity(option, index)}`"
				ref="optionButtons"
				class="generation-option-button"
				:class="{ 'is-selected': index === normalizedSelectedIndex }"
				type="button"
				:role="optionRole"
				:tabindex="index === normalizedSelectedIndex ? 0 : -1"
				:disabled="groupDisabled || option.disabled === true"
				:aria-checked="isSegmented ? String(index === normalizedSelectedIndex) : undefined"
				:aria-selected="!isSegmented ? String(index === normalizedSelectedIndex) : undefined"
				@click="select(index)"
			>
				<view class="generation-option-button-copy">
					<text class="generation-option-button-label">{{ optionLabel(option) }}</text>
					<text v-if="option.description" class="generation-option-button-description">{{ option.description }}</text>
				</view>
				<uni-icons
					v-if="!isSegmented && index === normalizedSelectedIndex"
					type="checkmarkempty"
					size="19"
					color="#37d39a"
					aria-hidden="true"
				/>
			</button>
		</view>
	</view>
</template>

<script>
	export default {
		name: 'UserGenerationOptionGroup',
		props: {
			section: {
				type: Object,
				required: true
			},
			disabled: {
				type: Boolean,
				default: false
			}
		},
		computed: {
			options() {
				return Array.isArray(this.section?.options) ? this.section.options : []
			},
			presentation() {
				const requested = this.section?.presentations?.h5 || this.section?.presentation
				return ['segmented', 'grid', 'rows'].includes(requested) ? requested : 'rows'
			},
			isSegmented() {
				return this.presentation === 'segmented'
			},
			optionRole() {
				return this.isSegmented ? 'radio' : 'option'
			},
			groupDisabled() {
				return this.disabled || this.section?.disabled === true
			},
			normalizedSelectedIndex() {
				const index = Number(this.section?.selectedIndex)
				return Number.isInteger(index) && index >= 0 && index < this.options.length ? index : 0
			},
			labelId() {
				const key = String(this.section?.key || 'setting').replace(/[^a-zA-Z0-9_-]/g, '-')
				return `generation-option-${key}-label`
			}
		},
		methods: {
			optionIdentity(option, index) {
				return option?.value == null ? index : option.value
			},
			optionLabel(option) {
				return option?.label == null ? String(option?.value ?? '') : option.label
			},
			select(index) {
				if (this.groupDisabled || this.options[index]?.disabled === true) return
				this.$emit('change', { key: this.section.key, detail: { value: String(index) } })
			},
			focusButton(index) {
				const buttons = Array.isArray(this.$refs.optionButtons)
					? this.$refs.optionButtons
					: [this.$refs.optionButtons].filter(Boolean)
				const candidate = buttons[index]
				const element = candidate?.$el || candidate
				element?.focus?.()
			},
			move(delta) {
				if (this.groupDisabled || !this.options.length) return
				let index = this.normalizedSelectedIndex
				for (let count = 0; count < this.options.length; count += 1) {
					index = (index + delta + this.options.length) % this.options.length
					if (this.options[index]?.disabled !== true) {
						this.select(index)
						this.$nextTick(() => this.focusButton(index))
						return
					}
				}
			},
			moveToBoundary(boundary) {
				if (this.groupDisabled || !this.options.length) return
				const direction = boundary === 0 ? 1 : -1
				let index = boundary
				while (index >= 0 && index < this.options.length) {
					if (this.options[index]?.disabled !== true) {
						this.select(index)
						this.$nextTick(() => this.focusButton(index))
						return
					}
					index += direction
				}
			}
		}
	}
</script>

<style lang="scss" scoped>
	.generation-option-group { min-width: 0; padding: 14px 0 2px; border-top: 1px solid rgba(151, 177, 163, .11); }
	.generation-option-group-label { display: block; margin: 0 2px 9px; color: #9ba8a1; font-size: 11px; font-weight: 720; letter-spacing: .35px; line-height: 1.35; }
	.generation-option-group-options { min-width: 0; }
	.generation-option-group.is-segmented .generation-option-group-options { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; }
	.generation-option-group.is-segmented .generation-option-group-options.is-wrapping { grid-template-columns: repeat(2, minmax(0, 1fr)); }
	.generation-option-group.is-grid .generation-option-group-options { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 6px; }
	.generation-option-group.is-rows .generation-option-group-options { overflow: hidden; border: 1px solid rgba(151, 177, 163, .14); border-radius: 13px; background: rgba(7, 12, 9, .18); }
	.generation-option-button { min-width: 0; min-height: 42px; margin: 0; padding: 7px 9px; display: flex; align-items: center; justify-content: center; gap: 8px; border: 1px solid rgba(151, 177, 163, .17); border-radius: 11px; background: rgba(243, 245, 244, .035); color: #aeb9b3; font-size: 12px; font-weight: 650; line-height: 1.25; text-align: center; box-sizing: border-box; transition: border-color 140ms ease-out, background-color 140ms ease-out, color 140ms ease-out, transform 100ms ease-out; }
	.generation-option-button::after { border: 0; }
	.generation-option-button-copy { min-width: 0; display: flex; flex: 1; flex-direction: column; gap: 2px; }
	.generation-option-button-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
	.generation-option-button-description { overflow: hidden; color: #839089; font-size: 10px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
	.generation-option-group.is-rows .generation-option-button { width: 100%; min-height: 48px; padding: 8px 11px 8px 13px; justify-content: space-between; border: 0; border-bottom: 1px solid rgba(151, 177, 163, .10); border-radius: 0; text-align: left; }
	.generation-option-group.is-rows .generation-option-button:last-child { border-bottom: 0; }
	.generation-option-button.is-selected { border-color: rgba(55, 211, 154, .52); background: rgba(55, 211, 154, .12); color: #d0f5e5; }
	.generation-option-button.is-selected .generation-option-button-description { color: #8fdcbe; }
	.generation-option-button:disabled { opacity: .45; }
	.generation-option-button:focus-visible { outline: 2px solid rgba(55, 211, 154, .82); outline-offset: 2px; }
	.generation-option-button:active:not(:disabled) { transform: scale(.98); }
	@media (hover: hover) and (pointer: fine) { .generation-option-button:hover:not(:disabled) { border-color: rgba(151, 177, 163, .34); background: rgba(243, 245, 244, .07); } .generation-option-button.is-selected:hover:not(:disabled) { border-color: rgba(55, 211, 154, .66); background: rgba(55, 211, 154, .15); } }
	@media screen and (max-width: 420px) { .generation-option-group.is-grid .generation-option-group-options { grid-template-columns: repeat(4, minmax(0, 1fr)); } }
	@media (prefers-reduced-motion: reduce) { .generation-option-button { transition: none; } .generation-option-button:active:not(:disabled) { transform: none; } }
</style>

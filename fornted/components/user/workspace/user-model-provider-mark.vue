<template>
	<view
		class="user-model-provider-mark"
		:style="markStyle"
		aria-hidden="true"
	>
		<image
			v-if="currentSource"
			class="user-model-provider-mark-image"
			:src="currentSource"
			mode="aspectFit"
			@error="handleImageError"
		/>
		<text v-else class="user-model-provider-mark-fallback">{{ fallbackLabel }}</text>
	</view>
</template>

<script>
	import {
		modelProviderFallbackLabel,
		modelProviderLogoSources
	} from '@/common/aimodel/ai-model-provider-presentation.js'

	export default {
		name: 'UserModelProviderMark',
		props: {
			model: {
				type: Object,
				default: null
			},
			size: {
				type: Number,
				default: 18,
				validator: value => Number.isFinite(value) && value >= 12 && value <= 32
			}
		},
		data() {
			return { sourceIndex: 0 }
		},
		computed: {
			logoSources() {
				return modelProviderLogoSources(this.model)
			},
			sourceSignature() {
				return [
					this.model?.publicId || this.model?.modelName || '',
					...this.logoSources
				].join('|')
			},
			currentSource() {
				return this.logoSources[this.sourceIndex] || ''
			},
			fallbackLabel() {
				return modelProviderFallbackLabel(this.model)
			},
			markStyle() {
				const size = Math.round(Number(this.size) || 18)
				return {
					width: `${size}px`,
					height: `${size}px`,
					flexBasis: `${size}px`,
					fontSize: `${Math.max(8, Math.round(size * .52))}px`
				}
			}
		},
		watch: {
			sourceSignature() {
				this.sourceIndex = 0
			}
		},
		methods: {
			handleImageError() {
				this.sourceIndex += 1
			}
		}
	}
</script>

<style scoped>
	.user-model-provider-mark { min-width: 0; display: flex; align-items: center; justify-content: center; flex-shrink: 0; overflow: visible; border: 0; background: transparent; box-shadow: none; color: #e7ece9; font-weight: 760; line-height: 1; }
	.user-model-provider-mark-image { width: 100%; height: 100%; display: block; }
	.user-model-provider-mark-fallback { display: block; color: inherit; font-size: inherit; font-weight: inherit; line-height: 1; white-space: nowrap; }
</style>

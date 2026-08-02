<template>
	<view class="phone-delivery-method">
		<text :id="`${controlId}-label`" class="delivery-label">手机验证码投递方式</text>
		<view
			class="delivery-options"
			role="radiogroup"
			:aria-labelledby="`${controlId}-label`"
		>
			<button
				v-for="(item, index) in options"
				ref="optionButtons"
				:key="item.value"
				type="button"
				class="delivery-option"
				:class="{ active: modelValue === item.value }"
				role="radio"
				:aria-checked="modelValue === item.value"
				:tabindex="modelValue === item.value ? 0 : -1"
				:disabled="disabled"
				@click="select(item.value)"
				@keydown="onOptionKeydown($event, index)"
			>
				{{ item.label }}
			</button>
		</view>
		<view
			v-if="modelValue === 'WHATSAPP'"
			class="sandbox-note"
			role="status"
			aria-live="polite"
		>
			<text>当前使用 Twilio Sandbox。该号码必须先加入该 Sandbox；加入状态可能过期，届时需要重新加入。</text>
		</view>
	</view>
</template>

<script>
	export default {
		name: 'PhoneDeliveryMethod',
		props: {
			modelValue: { type: String, default: 'SMS' },
			controlId: { type: String, required: true },
			disabled: { type: Boolean, default: false }
		},
		emits: ['update:modelValue'],
		data() {
			return {
				options: [
					{ value: 'SMS', label: '短信 SMS' },
					{ value: 'WHATSAPP', label: 'WhatsApp' }
				]
			}
		},
		methods: {
			select(value) {
				if (this.disabled || this.modelValue === value) return
				this.$emit('update:modelValue', value)
			},
			onOptionKeydown(event, index) {
				if (this.disabled || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
				event.preventDefault()
				let nextIndex = index
				if (event.key === 'ArrowLeft') nextIndex = (index - 1 + this.options.length) % this.options.length
				if (event.key === 'ArrowRight') nextIndex = (index + 1) % this.options.length
				if (event.key === 'Home') nextIndex = 0
				if (event.key === 'End') nextIndex = this.options.length - 1
				this.select(this.options[nextIndex].value)
				// #ifdef H5
				this.$nextTick(() => {
					const button = (this.$refs.optionButtons || [])[nextIndex]
					const element = button?.$el || button
					if (element?.focus) element.focus()
				})
				// #endif
			}
		}
	}
</script>

<style lang="scss" scoped>
	@import '@/common/ui/user-material.scss';

	.phone-delivery-method {
		margin: 4px 0 18px;
	}

	.delivery-label {
		display: block;
		margin-bottom: 8px;
		color: #dce5e0;
		font-size: 13px;
		font-weight: 600;
	}

	.delivery-options {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 8px;
	}

	.delivery-option {
		@include user-frosted-control;
		min-height: 48px;
		margin: 0;
		padding: 0 12px;
		border-radius: 10px;
		color: #aeb9b3;
		font-size: 13px;
		font-weight: 600;
		line-height: 1.2;
		text-align: center;
		white-space: nowrap;
	}

	.delivery-option::after {
		border: 0;
	}

	.delivery-option.active {
		border-color: #37d39a;
		background: rgba(55, 211, 154, 0.12);
		color: #e8fff6;
	}

	.delivery-option:focus-visible {
		outline: 2px solid #7ce6bd;
		outline-offset: 2px;
	}

	.sandbox-note {
		margin-top: 10px;
		padding: 10px 12px;
		border: 1px solid rgba(242, 190, 86, 0.3);
		border-radius: 10px;
		background: rgba(242, 190, 86, 0.08);
		color: #e8d7ad;
		font-size: 12px;
		line-height: 1.55;
	}
</style>

<template>
	<view class="model-panel">
		<user-model-catalog
			v-show="!modelPublicId"
			:authenticated="authenticated"
			@open-model="$emit('open-model', $event)"
			@open-conversation-drawer="$emit('open-conversation-drawer')"
		/>
		<user-model-detail
			v-if="modelPublicId"
			:authenticated="authenticated"
			:model-public-id="modelPublicId"
			@back="$emit('close-model')"
			@open-conversation-drawer="$emit('open-conversation-drawer')"
		/>
	</view>
</template>

<script>
	import UserModelCatalog from './user-model-catalog.vue'
	import UserModelDetail from './user-model-detail.vue'

	export default {
		components: {
			UserModelCatalog,
			UserModelDetail
		},
		props: {
			authenticated: {
				type: Boolean,
				default: false
			},
			modelPublicId: {
				type: String,
				default: ''
			}
		},
		methods: {
			closeIfOpen() {
				return false
			}
		}
	}
</script>

<style lang="scss" scoped>
	.model-panel {
		min-width: 0;
		min-height: 0;
		height: 100%;
		background: #0b0d0c;
	}
</style>

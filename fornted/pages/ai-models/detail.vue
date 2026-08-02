<template>
	<user-workspace
		ref="workspace"
		initial-destination="models"
		:initial-model-public-id="modelPublicId"
		:authenticated="authReady"
	/>
</template>

<script>
	import UserWorkspace from '@/components/user/user-workspace.vue'

	export default {
		components: { UserWorkspace },
		data() {
			return {
				modelPublicId: ''
			}
		},
		onLoad(options) {
			this.modelPublicId = String(options?.modelPublicId || '').trim()
		},
		methods: {
			onAuthenticatedPageReady() {
				this.$nextTick(() => {
					this.$refs.workspace?.handleAuthenticated()
				})
			}
		},
		onShow() {
			this.$nextTick(() => {
				this.$refs.workspace?.handlePageShow()
			})
		},
		onHide() {
			this.$refs.workspace?.handlePageHide()
		},
		onUnload() {
			this.$refs.workspace?.handlePageUnload()
		}
	}
</script>

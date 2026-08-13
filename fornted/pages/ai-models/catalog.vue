<template>
	<user-workspace
		ref="workspace"
		initial-destination="models"
		:authenticated="authReady"
	/>
</template>

<script>
	import UserWorkspace from '@/components/user/user-workspace.vue'

	export default {
		components: { UserWorkspace },
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
		},
		onBackPress() {
			return this.$refs.workspace?.handleBackPress() === true
		}
	}
</script>

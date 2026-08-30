<template>
	<user-workspace
		ref="workspace"
		initial-destination="chat"
		:authenticated="authReady"
	/>
</template>

<script>
	import UserWorkspace from '@/components/user/user-workspace.vue'
	import { createNativeSplashHandoff } from '@/common/launch/eagle-native-splash.js'

	export default {
		components: { UserWorkspace },
		data() {
			return {
				nativeSplashHandoff: null
			}
		},
		methods: {
			onAuthenticatedPageReady() {
				this.$nextTick(() => {
					this.$refs.workspace?.handleAuthenticated()
					// 工作区 DOM 提交后标记页面就绪；桥接仍需等待当前 App WebView 的显示事件。
					this.$nextTick(() => {
						this.nativeSplashHandoff?.markDomReady()
					})
				})
			}
		},
		onLoad() {
			this.nativeSplashHandoff = createNativeSplashHandoff(this, 'home-ready')
		},
		onShow() {
			this.nativeSplashHandoff?.bindWebview()
			this.$nextTick(() => {
				this.$refs.workspace?.handlePageShow()
			})
		},
		onHide() {
			this.$refs.workspace?.handlePageHide()
		},
		onUnload() {
			this.nativeSplashHandoff?.dispose()
			this.nativeSplashHandoff = null
			this.$refs.workspace?.handlePageUnload()
		},
		onBackPress() {
			return this.$refs.workspace?.handleBackPress() === true
		}
	}
</script>

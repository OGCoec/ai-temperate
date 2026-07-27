<template>
	<view class="challenge-complete"></view>
</template>

<script>
	import {
		restoreAdminRiskChallengeReturn
	} from '@/common/admin/admin-risk-challenge-navigation.js'

	export default {
		onLoad() {
			restoreAdminRiskChallengeReturn('/pages/index/index')
		}
	}
</script>

<style scoped>
	.challenge-complete {
		min-height: 100vh;
		background: #080b0d;
	}
</style>

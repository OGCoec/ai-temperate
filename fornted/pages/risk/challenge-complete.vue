<template>
	<view class="challenge-complete"></view>
</template>

<script>
	import { AUTH_ROUTES } from '@/common/auth/config.js'
	import { restoreRiskChallengeReturn } from '@/common/auth/risk-challenge-navigation.js'

	export default {
		onLoad() {
			restoreRiskChallengeReturn(AUTH_ROUTES.login)
		}
	}
</script>

<style scoped>
	.challenge-complete {
		min-height: 100vh;
		background: #0b0d0c;
	}
</style>

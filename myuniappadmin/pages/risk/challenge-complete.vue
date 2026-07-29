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
		position: relative;
		min-height: 100vh;
		overflow: hidden;
		background:
			radial-gradient(circle at 50% 42%, rgba(57, 214, 210, 0.1), transparent 24%),
			#080b0d;
	}

	.challenge-complete::after {
		position: absolute;
		top: 50%;
		left: 50%;
		width: 20px;
		height: 20px;
		border: 3px solid rgba(57, 214, 210, 0.2);
		border-top-color: #39d6d2;
		border-radius: 50%;
		content: "";
		transform: translate3d(-50%, -50%, 0);
		animation: challenge-complete-spin 0.8s linear infinite;
	}

	@keyframes challenge-complete-spin {
		to { transform: translate3d(-50%, -50%, 0) rotate(360deg); }
	}

	@media (prefers-reduced-motion: reduce) {
		.challenge-complete::after {
			animation: none;
		}
	}
</style>

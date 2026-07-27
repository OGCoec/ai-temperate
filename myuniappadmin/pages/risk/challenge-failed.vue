<template>
	<ChallengeFailedGate
		ref="gate"
		audience="ADMIN"
		:failure-reason="failureReason"
		:busy="busy"
		@retry="retry"
	/>
</template>

<script>
	import ChallengeFailedGate from '@shared-auth/risk-challenge-failed-gate.vue'
	import {
		adminRiskChallengeFailureReason,
		resetAdminRiskChallengeForManualRetry
	} from '@/common/admin/admin-risk-challenge-navigation.js'

	export default {
		components: { ChallengeFailedGate },
		data() {
			return {
				busy: false,
				failureReason: 'RECHECK_ERROR',
				popstateHandler: null
			}
		},
		onLoad() {
			this.failureReason = adminRiskChallengeFailureReason() || 'RECHECK_ERROR'
			this.lockBrowserHistory()
		},
		onReady() {
			this.$refs.gate?.focusTitle()
		},
		onUnload() {
			// #ifdef H5
			if (this.popstateHandler) {
				window.removeEventListener('popstate', this.popstateHandler)
			}
			// #endif
		},
		onBackPress() {
			return true
		},
		methods: {
			retry() {
				if (this.busy) return
				this.busy = true
				setTimeout(() => resetAdminRiskChallengeForManualRetry(), 80)
			},
			lockBrowserHistory() {
				// #ifdef H5
				window.history.pushState({ riskChallengeFailed: true }, '', window.location.href)
				this.popstateHandler = () => {
					window.history.pushState(
						{ riskChallengeFailed: true },
						'',
						window.location.href
					)
				}
				window.addEventListener('popstate', this.popstateHandler)
				// #endif
			}
		}
	}
</script>

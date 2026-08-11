import {
	createAndroidRiskChallengeCoordinator,
	repeatedAndroidRiskChallengeError
} from '@shared-auth/android-risk-challenge.js'

const coordinator = createAndroidRiskChallengeCoordinator({
	origin: 'https://niko000o.site',
	challengePath: '/api/_edge/risk-challenge',
	completionPath: '/pages/risk/challenge-complete',
	cookieName: '__Host-ait-preauth',
	webviewId: 'ait-user-risk-challenge'
})

export function ensureAndroidRiskChallenge(error) {
	return coordinator.ensure(error)
}

export { repeatedAndroidRiskChallengeError }

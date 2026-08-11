import {
	createAndroidRiskChallengeCoordinator,
	repeatedAndroidRiskChallengeError
} from '@shared-auth/android-risk-challenge.js'

const coordinator = createAndroidRiskChallengeCoordinator({
	origin: 'https://admin.niko000o.site',
	challengePath: '/api/admin/_edge/risk-challenge',
	completionPath: '/pages/risk/challenge-complete',
	cookieName: '__Host-ait-admin-preauth',
	webviewId: 'ait-admin-risk-challenge'
})

export function ensureAdminAndroidRiskChallenge(error) {
	return coordinator.ensure(error)
}

export { repeatedAndroidRiskChallengeError }

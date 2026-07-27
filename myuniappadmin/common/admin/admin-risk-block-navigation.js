import { clearAdminRiskChallengeFlow } from './admin-risk-challenge-navigation.js'

const BLOCK_PAGE = '/pages/risk/blocked'

let blockNavigationInFlight = false

export function presentAdminRiskBlock(error) {
	if (error?.code !== 'RISK_BLOCKED') return false
	clearAdminRiskChallengeFlow()
	if (blockNavigationInFlight || currentRoute() === BLOCK_PAGE.slice(1)) return true

	blockNavigationInFlight = true
	uni.reLaunch({
		url: BLOCK_PAGE,
		complete() {
			blockNavigationInFlight = false
		}
	})
	return true
}

function currentRoute() {
	const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
	return pages.length ? pages[pages.length - 1].route || '' : ''
}

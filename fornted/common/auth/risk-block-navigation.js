import { clearRiskChallengeFlow } from './risk-challenge-navigation.js'
import { clearRuntimeSessionAuthentication } from './authenticated-session-state.js'
import { clearAiModelCatalog } from '../aimodel/ai-model-catalog-store.js'
import { clearAiConversationStore } from '../aichat/ai-conversation-store.js'
import { clearGenerationManager } from '../aichat/ai-conversation-generation-manager.js'

const BLOCK_PAGE = '/pages/risk/blocked'

let blockNavigationInFlight = false

export function presentRiskBlock(error) {
	if (error?.code !== 'RISK_BLOCKED') return false
	clearRiskChallengeFlow()
	clearRuntimeSessionAuthentication()
	clearAiModelCatalog()
	clearAiConversationStore()
	clearGenerationManager()
	if (blockNavigationInFlight || currentRoute() === BLOCK_PAGE.slice(1)) return true

	blockNavigationInFlight = true
	// #ifdef H5
	if (typeof window !== 'undefined' && typeof window.location?.replace === 'function') {
		window.location.replace(BLOCK_PAGE)
		return true
	}
	// #endif
	uni.reLaunch({
		url: BLOCK_PAGE,
		success() {
			blockNavigationInFlight = false
		},
		fail() {
			blockNavigationInFlight = false
		},
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

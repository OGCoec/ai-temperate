import { browserCsrfToken, clearLegacyBrowserSession } from './browser-cookies.js'
import { clientPlatform } from './config.js'
import {
	clearAndroidSessionCredentials,
	loadAndroidSessionCredentials,
	saveAndroidSessionCredentials
} from './android-keystore.js'
import {
	containsSessionCredentialUpdate,
	emptySessionCredentials,
	hasPersistableAndroidCredentials,
	mergeSessionCredentials
} from './session-credentials.js'
import { clearProfileVault } from '../user/profile-vault.js'
import { clearRuntimeSessionAuthentication } from './authenticated-session-state.js'
import { clearAiModelCatalog } from '../aimodel/ai-model-catalog-store.js'
import { clearAiConversationStore } from '../aichat/ai-conversation-store.js'
import { clearGenerationManager } from '../aichat/ai-conversation-generation-manager.js'

let principal = null
let browserLegacyCleared = false

function clearBrowserLegacyOnce() {
	if (browserLegacyCleared || clientPlatform() !== 'H5') return
	clearLegacyBrowserSession()
	browserLegacyCleared = true
}

export function currentSession() {
	if (clientPlatform() === 'H5') {
		clearBrowserLegacyOnce()
		return {
			...emptySessionCredentials(),
			csrfToken: browserCsrfToken(),
			principal
		}
	}
	return { ...loadAndroidSessionCredentials(), principal }
}

export function saveSession(response) {
	if (!response) return
	if (response.publicUserId || response.displayName) {
		if (principal?.publicUserId && response.publicUserId &&
			principal.publicUserId !== response.publicUserId) {
			clearProfileVault()
			clearRuntimeSessionAuthentication()
			clearAiModelCatalog()
			clearAiConversationStore()
			clearGenerationManager()
		}
		principal = {
			publicUserId: response.publicUserId || principal?.publicUserId || '',
			displayName: response.displayName || principal?.displayName || ''
		}
	}
	if (clientPlatform() === 'H5') {
		clearBrowserLegacyOnce()
		return
	}
	if (!containsSessionCredentialUpdate(response)) return
	const credentials = mergeSessionCredentials(loadAndroidSessionCredentials(), response)
	if (hasPersistableAndroidCredentials(credentials)) {
		saveAndroidSessionCredentials(credentials)
	} else {
		clearAndroidSessionCredentials()
	}
}

export function loadAccessToken() {
	return clientPlatform() === 'ANDROID'
		? loadAndroidSessionCredentials().accessToken : ''
}

export function loadRefreshToken() {
	return clientPlatform() === 'ANDROID'
		? loadAndroidSessionCredentials().refreshToken : ''
}

export function clearSession() {
	principal = null
	clearRuntimeSessionAuthentication()
	clearProfileVault()
	clearAiModelCatalog()
	clearAiConversationStore()
	clearGenerationManager()
	if (clientPlatform() === 'ANDROID') clearAndroidSessionCredentials()
	else clearBrowserLegacyOnce()
}

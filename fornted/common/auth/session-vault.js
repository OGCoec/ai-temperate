import { browserCsrfToken, clearLegacyBrowserSession } from './browser-cookies.js'
import { ClientPlatform, clientPlatform } from './config.js'
import {
	clearAndroidSessionCredentials,
	loadAndroidSessionCredentials,
	saveAndroidSessionCredentials
} from './android-keystore.js'
import {
	clearWechatSessionCredentials,
	loadWechatSessionCredentials,
	saveWechatSessionCredentials
} from './wechat-session-storage.js'
import {
	containsSessionCredentialUpdate,
	emptySessionCredentials,
	hasCompleteAndroidOAuthCredentials,
	hasPersistableAndroidCredentials,
	hasPersistableSessionCredentials,
	mergeSessionCredentials
} from './session-credentials.js'
import { clearProfileVault } from '../user/profile-vault.js'
import { clearRuntimeSessionAuthentication } from './authenticated-session-state.js'
import { clearAiModelCatalog } from '../aimodel/ai-model-catalog-store.js'
import { clearAiConversationStore } from '../aichat/ai-conversation-store.js'
import { clearGenerationManager } from '../aichat/ai-conversation-generation-manager.js'
import { clearApiKeyCreateIntent } from '../user/api-key-create-intent.js'

let principal = null
let browserLegacyCleared = false

function clearBrowserLegacyOnce() {
	if (browserLegacyCleared || clientPlatform() !== ClientPlatform.H5) return
	clearLegacyBrowserSession()
	browserLegacyCleared = true
}

export function currentSession() {
	const platform = clientPlatform()
	if (platform === ClientPlatform.H5) {
		clearBrowserLegacyOnce()
		return {
			...emptySessionCredentials(),
			csrfToken: browserCsrfToken(),
			principal
		}
	}
	if (platform === ClientPlatform.WECHAT_MINI_PROGRAM) {
		return {
			...emptySessionCredentials(),
			...loadWechatSessionCredentials(),
			principal
		}
	}
	if (platform === ClientPlatform.ANDROID) {
		return { ...loadAndroidSessionCredentials(), principal }
	}
	return { ...emptySessionCredentials(), principal }
}

export function saveSession(response) {
	if (!response) return
	updatePrincipal(response)
	const platform = clientPlatform()
	if (platform === ClientPlatform.H5) {
		clearBrowserLegacyOnce()
		return
	}
	if (platform === ClientPlatform.WECHAT_MINI_PROGRAM) {
		if (!containsSessionCredentialUpdate(response)) return
		const credentials = mergeSessionCredentials(loadWechatSessionCredentials(), response)
		if (hasPersistableSessionCredentials(credentials)) {
			saveWechatSessionCredentials(credentials)
		} else {
			clearWechatSessionCredentials()
		}
		return
	}
	if (platform !== ClientPlatform.ANDROID) return
	if (!containsSessionCredentialUpdate(response)) return
	const credentials = mergeSessionCredentials(loadAndroidSessionCredentials(), response)
	if (hasPersistableAndroidCredentials(credentials)) {
		saveAndroidSessionCredentials(credentials)
	} else {
		clearAndroidSessionCredentials()
	}
}

function updatePrincipal(response) {
	if (response.publicUserId || response.displayName) {
		if (principal?.publicUserId && response.publicUserId &&
			principal.publicUserId !== response.publicUserId) {
			clearProfileVault()
			clearRuntimeSessionAuthentication()
			clearAiModelCatalog()
			clearAiConversationStore()
			clearGenerationManager()
			// 待确认创建意图不携带用户 ID；账号切换时必须清除，禁止把上一账号的 UUID 请求交给新账号。
			clearApiKeyCreateIntent()
		}
		principal = {
			publicUserId: response.publicUserId || principal?.publicUserId || '',
			displayName: response.displayName || principal?.displayName || ''
		}
	}
}

/**
 * 原生 OAuth 完成的凭据提交边界；校验通过后一次写入四个字段，任何缺失都不会覆盖旧会话。
 */
export function commitAndroidOAuthSession(response) {
	if (clientPlatform() !== ClientPlatform.ANDROID
		|| !hasCompleteAndroidOAuthCredentials(response)) {
		const error = new Error('OAuth 登录响应缺少完整会话凭据。')
		error.code = 'SESSION_RESPONSE_INVALID'
		throw error
	}
	const credentials = {
		accessToken: response.accessToken,
		refreshToken: response.refreshToken,
		csrfToken: response.csrfToken,
		preAuthToken: response.preAuthToken
	}
	saveAndroidSessionCredentials(credentials)
	updatePrincipal(response)
	return credentials
}

export function loadAccessToken() {
	const platform = clientPlatform()
	if (platform === ClientPlatform.ANDROID) return loadAndroidSessionCredentials().accessToken
	if (platform === ClientPlatform.WECHAT_MINI_PROGRAM) return loadWechatSessionCredentials().accessToken
	return ''
}

export function loadRefreshToken() {
	const platform = clientPlatform()
	if (platform === ClientPlatform.ANDROID) return loadAndroidSessionCredentials().refreshToken
	if (platform === ClientPlatform.WECHAT_MINI_PROGRAM) return loadWechatSessionCredentials().refreshToken
	return ''
}

export function clearSession() {
	principal = null
	clearRuntimeSessionAuthentication()
	clearProfileVault()
	clearAiModelCatalog()
	clearAiConversationStore()
	clearGenerationManager()
	// 全局会话出口统一清理，覆盖 API Key 页面从未挂载时的退出登录与会话失效场景。
	clearApiKeyCreateIntent()
	const platform = clientPlatform()
	if (platform === ClientPlatform.ANDROID) clearAndroidSessionCredentials()
	if (platform === ClientPlatform.WECHAT_MINI_PROGRAM) clearWechatSessionCredentials()
	if (platform === ClientPlatform.H5) clearBrowserLegacyOnce()
}

import { currentUserApi } from './current-user-api.js'
import { assertAuthorizedSessionCurrent } from '../auth/http-client.js'
import { clearProfileVault, readProfileVault, writeProfileVault } from './profile-vault.js'
import { recordAiConversationProfileRefresh } from '../aichat/ai-conversation-lifecycle-diagnostics.js'

let profileRequest = null
let profileRequestGeneration = null

function normalizeNullableText(value) {
	if (value == null) return null
	return String(value).trim() || null
}

function normalizeProfile(profile) {
	return {
		displayName: String(profile?.displayName || '').trim() || '用户',
		email: String(profile?.email || '').trim(),
		phone: normalizeNullableText(profile?.phone),
		avatarUrl: normalizeNullableText(profile?.avatarUrl),
		membershipTier: normalizeNullableText(profile?.membershipTier),
		quotaBalanceMinor: normalizeNullableText(profile?.quotaBalanceMinor),
		quotaBalance: normalizeNullableText(profile?.quotaBalance),
		quotaTotalMinor: normalizeNullableText(profile?.quotaTotalMinor),
		quotaTotal: normalizeNullableText(profile?.quotaTotal),
		quotaUsedMinor: normalizeNullableText(profile?.quotaUsedMinor),
		quotaUsed: normalizeNullableText(profile?.quotaUsed),
		quotaUsagePercent: normalizeNullableText(profile?.quotaUsagePercent),
		quotaPeriodStartedAt: normalizeNullableText(profile?.quotaPeriodStartedAt),
		quotaResetAt: normalizeNullableText(profile?.quotaResetAt)
	}
}

export function getCurrentUserProfile() {
	return readProfileVault()
}

export function loadCurrentUserProfile({ force = false } = {}) {
	let sessionGeneration
	try { sessionGeneration = assertAuthorizedSessionCurrent() } catch (error) { return Promise.reject(error) }
	const cached = readProfileVault()
	if (!force && cached) return Promise.resolve(cached)
	if (profileRequest && profileRequestGeneration === sessionGeneration) return profileRequest
	recordAiConversationProfileRefresh('PROFILE_REFRESH_STARTED')
	const task = currentUserApi.me()
		.then(profile => {
			// 请求完成和缓存落盘之间仍可能切换账号；旧回调不得回填新会话的资料。
			assertAuthorizedSessionCurrent(sessionGeneration)
			const normalized = normalizeProfile(profile)
			const quotaChanged = Boolean(cached)
				&& (cached.quotaBalanceMinor !== normalized.quotaBalanceMinor
					|| cached.quotaUsedMinor !== normalized.quotaUsedMinor)
			const stored = writeProfileVault(normalized)
			recordAiConversationProfileRefresh('PROFILE_REFRESH_COMPLETED', {
				quotaChanged
			})
			if (quotaChanged) {
				recordAiConversationProfileRefresh('PROFILE_QUOTA_CHANGED', {
					quotaChanged: true
				})
			}
			return stored
		})
		.catch(error => {
			recordAiConversationProfileRefresh('PROFILE_REFRESH_FAILED')
			throw error
		})
		.finally(() => { if (profileRequest === task) profileRequest = null })
	profileRequest = task
	profileRequestGeneration = sessionGeneration
	return profileRequest
}

export function clearCurrentUserProfile() {
	clearProfileVault()
}

export function updateCurrentUserAvatar(avatarUrl) {
	const current = readProfileVault()
	if (!current) return null
	return writeProfileVault({
		...current,
		avatarUrl: String(avatarUrl || '').trim() || null
	})
}

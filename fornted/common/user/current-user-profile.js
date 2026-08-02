import { currentUserApi } from './current-user-api.js'
import { clearProfileVault, readProfileVault, writeProfileVault } from './profile-vault.js'
import { recordAiConversationProfileRefresh } from '../aichat/ai-conversation-lifecycle-diagnostics.js'

let profileRequest = null

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
	const cached = readProfileVault()
	if (!force && cached) return Promise.resolve(cached)
	if (profileRequest) return profileRequest
	recordAiConversationProfileRefresh('PROFILE_REFRESH_STARTED')
	profileRequest = currentUserApi.me()
		.then(profile => {
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
		.finally(() => { profileRequest = null })
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

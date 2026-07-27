import { currentUserApi } from './current-user-api.js'
import { clearProfileVault, readProfileVault, writeProfileVault } from './profile-vault.js'

let profileRequest = null

function normalizeProfile(profile) {
	return {
		displayName: String(profile?.displayName || '').trim() || '用户',
		email: String(profile?.email || '').trim(),
		phone: profile?.phone == null ? null : String(profile.phone).trim(),
		avatarUrl: profile?.avatarUrl == null ? null : String(profile.avatarUrl).trim() || null
	}
}

export function getCurrentUserProfile() {
	return readProfileVault()
}

export function loadCurrentUserProfile({ force = false } = {}) {
	const cached = readProfileVault()
	if (!force && cached) return Promise.resolve(cached)
	if (profileRequest) return profileRequest
	profileRequest = currentUserApi.me()
		.then(profile => writeProfileVault(normalizeProfile(profile)))
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

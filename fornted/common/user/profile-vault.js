let currentProfile = null

export function readProfileVault() {
	return currentProfile ? { ...currentProfile } : null
}

export function writeProfileVault(profile) {
	currentProfile = profile ? { ...profile } : null
	return readProfileVault()
}

export function clearProfileVault() {
	currentProfile = null
}

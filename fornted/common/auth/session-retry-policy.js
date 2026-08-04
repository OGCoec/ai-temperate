export const SessionRenewalMode = Object.freeze({
	NONE: '',
	BOOTSTRAP: 'BOOTSTRAP'
})

export function sessionRenewalMode(platform, errorCode, alreadyRetried) {
	if (alreadyRetried) return SessionRenewalMode.NONE
	if (platform === 'H5' && errorCode === 'CSRF_INVALID') {
		return SessionRenewalMode.BOOTSTRAP
	}
	return SessionRenewalMode.NONE
}

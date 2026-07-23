export const SessionRenewalMode = Object.freeze({
	NONE: '',
	REFRESH: 'REFRESH',
	BOOTSTRAP: 'BOOTSTRAP'
})

export function sessionRenewalMode(platform, errorCode, alreadyRetried) {
	if (alreadyRetried) return SessionRenewalMode.NONE
	if (platform === 'H5' && errorCode === 'CSRF_INVALID') {
		return SessionRenewalMode.BOOTSTRAP
	}
	if (errorCode === 'AT_EXPIRED' || errorCode === 'AT_REQUIRED') {
		return SessionRenewalMode.REFRESH
	}
	return SessionRenewalMode.NONE
}

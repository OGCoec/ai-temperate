export function emptySessionCredentials() {
	return { accessToken: '', refreshToken: '', csrfToken: '' }
}

export function mergeSessionCredentials(current, update) {
	const existing = current || emptySessionCredentials()
	const incoming = update || {}
	return {
		accessToken: credentialValue(existing, incoming, 'accessToken'),
		refreshToken: credentialValue(existing, incoming, 'refreshToken'),
		csrfToken: credentialValue(existing, incoming, 'csrfToken')
	}
}

export function hasCompleteSessionCredentials(credentials) {
	return ['accessToken', 'refreshToken', 'csrfToken'].every(name =>
		typeof credentials?.[name] === 'string' && credentials[name].length > 0
	)
}

export function containsSessionCredentialUpdate(value) {
	return value != null && ['accessToken', 'refreshToken', 'csrfToken']
		.some(name => Object.prototype.hasOwnProperty.call(value, name))
}

function credentialValue(current, update, name) {
	if (Object.prototype.hasOwnProperty.call(update, name)) {
		return typeof update[name] === 'string' ? update[name] : ''
	}
	return typeof current[name] === 'string' ? current[name] : ''
}

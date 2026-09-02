export function emptySessionCredentials() {
	return { accessToken: '', refreshToken: '', csrfToken: '', preAuthToken: '' }
}

export function mergeSessionCredentials(current, update) {
	const existing = current || emptySessionCredentials()
	const incoming = update || {}
	return {
		accessToken: credentialValue(existing, incoming, 'accessToken'),
		refreshToken: credentialValue(existing, incoming, 'refreshToken'),
		csrfToken: credentialValue(existing, incoming, 'csrfToken'),
		preAuthToken: credentialValue(existing, incoming, 'preAuthToken')
	}
}

export function hasCompleteSessionCredentials(credentials) {
	return ['accessToken', 'refreshToken', 'csrfToken'].every(name =>
		typeof credentials?.[name] === 'string' && credentials[name].length > 0
	)
}

/** Android OAuth 完成必须同时带回新 PreAuth，防止旧绑定与新 Refresh Session 混用。 */
export function hasCompleteAndroidOAuthCredentials(credentials) {
	return ['accessToken', 'refreshToken', 'csrfToken', 'preAuthToken'].every(name =>
		typeof credentials?.[name] === 'string' && credentials[name].length > 0
	)
}

export function containsSessionCredentialUpdate(value) {
	return value != null && ['accessToken', 'refreshToken', 'csrfToken', 'preAuthToken']
		.some(name => Object.prototype.hasOwnProperty.call(value, name))
}

export function hasPersistableAndroidCredentials(credentials) {
	return hasCompleteSessionCredentials(credentials)
		|| (typeof credentials?.preAuthToken === 'string'
			&& credentials.preAuthToken.length > 0)
}

function credentialValue(current, update, name) {
	if (Object.prototype.hasOwnProperty.call(update, name)) {
		return typeof update[name] === 'string' ? update[name] : ''
	}
	return typeof current[name] === 'string' ? current[name] : ''
}

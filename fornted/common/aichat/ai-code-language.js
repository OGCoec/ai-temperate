const MAX_LANGUAGE_ID_LENGTH = 64

function requestedLanguageId(language) {
	const value = typeof language === 'object' && language
		? language.id || language.requestedId || language.canonicalId
		: language
	const normalized = String(value || '')
		.trim()
		.toLowerCase()
		.split(/\s+/)[0]
		.replace(/\\/g, '/')
		.split('/')
		.pop()
		.replace(/[^a-z0-9+#._-]/g, '')
	return normalized.slice(0, MAX_LANGUAGE_ID_LENGTH)
}

function fallbackLanguage(requestedId = '') {
	return {
		requestedId,
		canonicalId: 'text',
		label: 'Plain text',
		supported: false
	}
}

export function createAiCodeLanguageResolver(languageInfo = []) {
	const registered = new Map()
	for (const info of Array.isArray(languageInfo) ? languageInfo : []) {
		const canonicalId = requestedLanguageId(info?.id)
		if (!canonicalId) continue
		const record = {
			canonicalId,
			label: String(info?.name || canonicalId),
			supported: true
		}
		registered.set(canonicalId, record)
		for (const alias of info?.aliases || []) {
			const normalizedAlias = requestedLanguageId(alias)
			if (normalizedAlias) registered.set(normalizedAlias, record)
		}
	}

	return language => {
		const requestedId = requestedLanguageId(language)
		if (!requestedId || ['text', 'txt', 'plain', 'plaintext'].includes(requestedId)) {
			return fallbackLanguage(requestedId)
		}
		const match = registered.get(requestedId)
		if (!match) return fallbackLanguage(requestedId)
		return { requestedId, ...match }
	}
}

export { requestedLanguageId }

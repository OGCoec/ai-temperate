const HTML_PREVIEW_LANGUAGE_IDS = new Set(['html', 'htm'])

function requestedLanguageId(language) {
	const value = typeof language === 'object' && language
		? language.canonicalId || language.id || language.requestedId
		: language
	return String(value || '').trim().toLowerCase()
}

export function isAiHtmlPreviewLanguage(language) {
	return HTML_PREVIEW_LANGUAGE_IDS.has(requestedLanguageId(language))
}

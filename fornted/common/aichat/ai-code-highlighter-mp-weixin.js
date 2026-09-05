const PLAIN_TEXT_REASON = 'MP_WEIXIN_PLAIN_TEXT'

function requestedLanguage(language) {
	if (language && typeof language === 'object') {
		return String(language.canonicalId || language.id || language.requestedId || '')
			.trim()
			.toLowerCase()
	}
	return String(language || '').trim().toLowerCase()
}

function unavailableError(language) {
	const error = new Error('AI_CODE_LANGUAGE_UNSUPPORTED')
	error.code = 'AI_CODE_LANGUAGE_UNSUPPORTED'
	error.stage = PLAIN_TEXT_REASON
	error.languageId = requestedLanguage(language) || 'text'
	return error
}

export function resolveAiCodeLanguage(language) {
	const requestedId = requestedLanguage(language)
	const label = language && typeof language === 'object'
		? String(language.label || requestedId || 'Plain text')
		: String(requestedId || 'Plain text')
	return {
		requestedId,
		canonicalId: 'text',
		label,
		supported: false
	}
}

// 微信小程序先复用会话层已有的纯文本降级，避免把浏览器专属运行时带入启动包。
export async function prepareAiCodeHighlighterWithFallback(language) {
	throw unavailableError(language)
}

export async function createAiCodeTokenizer(language) {
	throw unavailableError(language)
}

export function prewarmAiCodeHighlighter() {
	return Promise.resolve({
		ready: false,
		reason: PLAIN_TEXT_REASON
	})
}

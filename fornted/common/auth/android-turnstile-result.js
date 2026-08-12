export const ANDROID_TURNSTILE_RESULT_URL_MATCH =
	'^aiturnstile://(?:verified|error|expired|timeout)(?:\\?.*)?$'

const MAX_RESULT_URL_LENGTH = 4608
const MAX_TOKEN_LENGTH = 4096
const CHANNEL_PATTERN = /^[A-Za-z0-9_-]{8,80}$/
const INVALID_RESULT_CHARACTERS = /[\u0000-\u0020\u007f\\#]/
const ERROR_CODE_PATTERN = /^(?:[0-9]{6}|unknown|config_invalid)$/

function decodeValue(value) {
	try {
		return decodeURIComponent(value)
	} catch (_) {
		return null
	}
}

function parseParameters(query) {
	if (!query) return new Map()
	const values = new Map()
	for (const pair of query.split('&')) {
		const separator = pair.indexOf('=')
		if (separator <= 0) return null
		const name = decodeValue(pair.slice(0, separator))
		const value = decodeValue(pair.slice(separator + 1))
		if (!name || value === null || values.has(name)) return null
		values.set(name, value)
	}
	return values
}

function hasExactly(parameters, expectedNames) {
	if (parameters.size !== expectedNames.length) return false
	return expectedNames.every((name) => parameters.has(name))
}

/**
 * 解析Android子WebView回传结果，并通过一次性通道隔离旧页面和重复验证实例。
 */
export function parseAndroidTurnstileResult(rawUrl, expectedChannel) {
	if (
		typeof rawUrl !== 'string' ||
		rawUrl.length === 0 ||
		rawUrl.length > MAX_RESULT_URL_LENGTH ||
		INVALID_RESULT_CHARACTERS.test(rawUrl) ||
		typeof expectedChannel !== 'string' ||
		!CHANNEL_PATTERN.test(expectedChannel)
	) return null

	const match = rawUrl.match(
		/^aiturnstile:\/\/(verified|error|expired|timeout)(?:\?([^#]*))?$/
	)
	if (!match) return null
	const parameters = parseParameters(match[2] || '')
	if (!parameters || parameters.get('channel') !== expectedChannel) return null

	if (match[1] === 'verified') {
		if (!hasExactly(parameters, ['channel', 'token'])) return null
		const token = parameters.get('token')
		if (!token || token.length > MAX_TOKEN_LENGTH || INVALID_RESULT_CHARACTERS.test(token)) return null
		return { type: 'VERIFIED', token }
	}

	if (match[1] === 'error') {
		if (!hasExactly(parameters, ['channel', 'code'])) return null
		const code = parameters.get('code')
		if (!ERROR_CODE_PATTERN.test(code)) return null
		return { type: 'ERROR', code }
	}

	if (!hasExactly(parameters, ['channel'])) return null
	return { type: match[1] === 'expired' ? 'EXPIRED' : 'TIMEOUT' }
}

function escapeRegExp(value) {
	return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function normalizedTokens(tokens) {
	if (!Array.isArray(tokens)) return []
	const unique = new Map()
	for (const candidate of tokens) {
		if (typeof candidate !== 'string') continue
		const token = candidate.trim()
		if (!token) continue
		const key = token.toLowerCase()
		if (!unique.has(key)) unique.set(key, token)
	}
	return [...unique.values()].sort((left, right) => right.length - left.length)
}

export function buildTextHighlightSegments(value, matchedTokens, fallbackText = '') {
	const text = typeof value === 'string' && value.trim()
		? value
		: fallbackText
	const tokens = normalizedTokens(matchedTokens)
	if (!tokens.length) return [{ text, matched: false }]

	const matched = new Set(tokens.map(token => token.toLowerCase()))
	const pattern = new RegExp(`(${tokens.map(escapeRegExp).join('|')})`, 'giu')
	return text
		.split(pattern)
		.filter(segment => segment.length > 0)
		.map(segment => ({
			text: segment,
			matched: matched.has(segment.toLowerCase())
		}))
}

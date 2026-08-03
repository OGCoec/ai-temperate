const MAX_SOURCES = 200
const MAX_SOURCE_ID = 128
const MAX_ACTIVITY_ID = 128
const MAX_TITLE = 512
const MAX_URL = 4096
const MAX_TIMESTAMP = 64
const SOURCE_ROLES = new Set(['CITED', 'CONSULTED'])

function boundedText(value, maximum) {
	const normalized = typeof value === 'string' ? value.trim() : ''
	return normalized && normalized.length <= maximum ? normalized : ''
}

function normalizedSourceUrl(value, includeFragment = true) {
	const raw = boundedText(value, MAX_URL)
	if (!raw) return null
	try {
		const parsed = new URL(raw)
		if (!['http:', 'https:'].includes(parsed.protocol.toLowerCase())) return null
		parsed.protocol = parsed.protocol.toLowerCase()
		parsed.hostname = parsed.hostname.toLowerCase()
		if (!includeFragment) parsed.hash = ''
		return parsed
	} catch (_) {
		return null
	}
}

export function canonicalAiConversationSourceUrl(value) {
	return normalizedSourceUrl(value, false)?.href || ''
}

function sourcePathKey(value) {
	const parsed = normalizedSourceUrl(value, false)
	return parsed ? `${parsed.origin}${parsed.pathname}` : ''
}

export function normalizeAiConversationSource(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) return null
	const parsed = normalizedSourceUrl(value.url)
	if (!parsed) return null
	const sequence = Number(value.sequence)
	const role = boundedText(value.role, 32).toUpperCase()
	return Object.freeze({
		sequence: Number.isSafeInteger(sequence) && sequence >= 0 ? sequence : 0,
		activityId: boundedText(value.activityId, MAX_ACTIVITY_ID),
		sourceId: boundedText(value.sourceId, MAX_SOURCE_ID),
		title: boundedText(value.title, MAX_TITLE),
		url: parsed.href,
		domain: parsed.hostname,
		role: SOURCE_ROLES.has(role) ? role : '',
		occurredAt: boundedText(value.occurredAt, MAX_TIMESTAMP)
	})
}

export function mergeAiConversationSources(...collections) {
	const byUrl = new Map()
	for (const collection of collections) {
		for (const candidate of Array.isArray(collection) ? collection : []) {
			const source = normalizeAiConversationSource(candidate)
			const key = source ? canonicalAiConversationSourceUrl(source.url) : ''
			if (!key || byUrl.has(key) || byUrl.size >= MAX_SOURCES) continue
			byUrl.set(key, source)
		}
	}
	return Object.freeze([...byUrl.values()])
}

export function createAiConversationSourceIndex(sources) {
	const exact = new Map()
	const byPath = new Map()
	for (const source of mergeAiConversationSources(sources)) {
		const exactKey = canonicalAiConversationSourceUrl(source.url)
		const fallbackKey = sourcePathKey(source.url)
		if (exactKey) exact.set(exactKey, source)
		if (!fallbackKey) continue
		const existing = byPath.get(fallbackKey)
		byPath.set(fallbackKey, existing === undefined ? source : null)
	}
	return Object.freeze({ exact, byPath })
}

export function matchAiConversationSource(index, href) {
	if (!index?.exact || !index?.byPath) return null
	const exactKey = canonicalAiConversationSourceUrl(href)
	if (!exactKey) return null
	return index.exact.get(exactKey) || index.byPath.get(sourcePathKey(href)) || null
}

function removeMatchedSourceParentheses(children) {
	if (!Array.isArray(children) || children.length < 3) return children
	for (let index = 1; index < children.length - 1; index += 1) {
		const link = children[index]
		const before = children[index - 1]
		const after = children[index + 1]
		if (link?.type !== 'link' || !link.source
			|| before?.type !== 'text' || after?.type !== 'text') continue
		const opening = String(before.value || '').match(/([\(（])\s*$/u)
		const closing = String(after.value || '').match(/^\s*([\)）])/u)
		if (!opening || !closing) continue
		const matching = opening[1] === '(' ? ')' : '）'
		if (closing[1] !== matching) continue
		before.value = String(before.value || '').slice(0, opening.index)
		after.value = String(after.value || '').slice(closing[0].length)
	}
	return children
}

function decorateSourceNode(node, sourceIndex) {
	if (Array.isArray(node)) {
		return removeMatchedSourceParentheses(
			node.map(item => decorateSourceNode(item, sourceIndex)))
	}
	if (!node || typeof node !== 'object') return node
	const decorated = { ...node }
	if (Array.isArray(node.children)) {
		decorated.children = removeMatchedSourceParentheses(
			node.children.map(child => decorateSourceNode(child, sourceIndex)))
	}
	if (Array.isArray(node.headers)) {
		decorated.headers = node.headers.map(cell => decorateSourceNode(cell, sourceIndex))
	}
	if (Array.isArray(node.rows)) {
		decorated.rows = node.rows.map(row => Array.isArray(row)
			? row.map(cell => decorateSourceNode(cell, sourceIndex)) : [])
	}
	if (node.type === 'link' && node.safe === true) {
		const source = matchAiConversationSource(sourceIndex, node.href)
		if (source) decorated.source = source
	}
	return decorated
}

export function decorateAiMarkdownSources(ast, sources) {
	return decorateSourceNode(ast, createAiConversationSourceIndex(sources))
}

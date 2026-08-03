import {
	canonicalAiConversationSourceUrl,
	mergeAiConversationSources
} from './ai-conversation-source-presentation.js'
import { normalizeAiSourceFaviconDomain } from './ai-source-favicon.js'

const COMPLETE_URL_PATTERN = /https?:\/\/[^\s<>"'`]+/i
const SITE_TARGET_PATTERN = /(?:^|\s)site:([^\s]+)/i

function normalizedSearchUrl(value) {
	let candidate = String(value || '').trim().replace(/[\]\[}{),;]+$/g, '')
	if (!candidate) return null
	try {
		const parsed = new URL(candidate)
		if (!['http:', 'https:'].includes(parsed.protocol.toLowerCase())
			|| parsed.username || parsed.password) return null
		const domain = normalizeAiSourceFaviconDomain(parsed.hostname)
		if (!domain) return null
		parsed.protocol = parsed.protocol.toLowerCase()
		parsed.hostname = domain
		return parsed
	} catch (_) {
		return null
	}
}

function directUrlTarget(query) {
	const match = String(query || '').match(COMPLETE_URL_PATTERN)
	const parsed = normalizedSearchUrl(match?.[0])
	if (!parsed) return null
	return {
		domain: parsed.hostname,
		pathHint: parsed.pathname === '/' ? '' : parsed.pathname,
		exactUrl: canonicalAiConversationSourceUrl(parsed.href)
	}
}

function siteQueryTarget(query) {
	const match = String(query || '').match(SITE_TARGET_PATTERN)
	if (!match?.[1]) return null
	const parsed = normalizedSearchUrl(`https://${match[1]}`)
	if (!parsed || parsed.port) return null
	return {
		domain: parsed.hostname,
		pathHint: parsed.pathname === '/' ? '' : parsed.pathname,
		exactUrl: ''
	}
}

function sourcePathname(source) {
	try { return new URL(source.url).pathname } catch (_) { return '' }
}

function matchesPathPrefix(pathname, pathHint) {
	return pathname === pathHint
		|| pathname.startsWith(pathHint.endsWith('/') ? pathHint : `${pathHint}/`)
}

export function presentAiSearchActivity(activity, sources) {
	if (activity?.phase !== 'WEB_SEARCH') return null
	const target = directUrlTarget(activity.query) || siteQueryTarget(activity.query)
	if (!target) return null
	const candidates = mergeAiConversationSources(sources)
		.filter(source => source.domain === target.domain)
	let source = null
	if (target.exactUrl) {
		source = candidates.find(candidate =>
			canonicalAiConversationSourceUrl(candidate.url) === target.exactUrl) || null
	} else if (target.pathHint) {
		const pathMatches = candidates.filter(candidate =>
			matchesPathPrefix(sourcePathname(candidate), target.pathHint))
		if (pathMatches.length === 1) source = pathMatches[0]
	} else if (candidates.length === 1) {
		source = candidates[0]
	}
	return Object.freeze({
		domain: target.domain,
		pathHint: target.pathHint,
		source,
		clickable: Boolean(source)
	})
}

function timelineSequence(value) {
	const sequence = Number(value)
	return Number.isSafeInteger(sequence) && sequence >= 0 ? sequence : null
}

export function presentAiResearchTimeline(activities, sources) {
	const normalizedSources = mergeAiConversationSources(sources)
	const matchedSourceUrls = new Set()
	const rows = []
	let insertionOrder = 0
	for (const activity of Array.isArray(activities) ? activities : []) {
		const sequence = timelineSequence(activity?.sequence)
		if (sequence == null) continue
		const sourcePresentation = presentAiSearchActivity(
			activity, normalizedSources)
		const matchedUrl = canonicalAiConversationSourceUrl(
			sourcePresentation?.source?.url)
		if (matchedUrl) matchedSourceUrls.add(matchedUrl)
		rows.push({
			type: 'activity',
			sequence,
			insertionOrder: insertionOrder++,
			activity,
			sourcePresentation
		})
	}
	for (const source of normalizedSources) {
		const sourceUrl = canonicalAiConversationSourceUrl(source.url)
		if (sourceUrl && matchedSourceUrls.has(sourceUrl)) continue
		rows.push({
			type: 'source',
			sequence: source.sequence,
			insertionOrder: insertionOrder++,
			source
		})
	}
	rows.sort((left, right) => left.sequence - right.sequence
		|| left.insertionOrder - right.insertionOrder)
	return Object.freeze(rows.map(({ insertionOrder: _, ...row }) =>
		Object.freeze(row)))
}

export function formatAiReasoningSummaryMarkdown(summaries) {
	const items = []
	for (const summary of Array.isArray(summaries) ? summaries : []) {
		const text = typeof summary?.textDelta === 'string'
			? summary.textDelta.trim() : ''
		if (!text) continue
		const lines = text.split(/\r?\n/)
		items.push(`- ${lines[0]}`)
		for (const line of lines.slice(1)) items.push(`  ${line}`)
	}
	return items.join('\n')
}

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function loadModule() {
	const sourcePresentation = fs.readFileSync(path.join(__dirname,
		'ai-conversation-source-presentation.js'), 'utf8')
		.replaceAll('export function ', 'function ')
	const favicon = fs.readFileSync(path.join(__dirname,
		'ai-source-favicon.js'), 'utf8')
		.replaceAll('export function ', 'function ')
	const research = fs.readFileSync(path.join(__dirname,
		'ai-conversation-research-presentation.js'), 'utf8')
		.replace(/^import\s*\{[\s\S]*?\}\s*from\s*'[^']+'\s*$/gm, '')
		.replaceAll('export function ', 'function ')
	const factory = new Function(`${sourcePresentation}\n${favicon}\n${research}; return {
		presentAiSearchActivity,
		presentAiResearchTimeline,
		formatAiReasoningSummaryMarkdown }`)
	return factory()
}

function source(url, sourceId = 'source-1') {
	return {
		sequence: 10,
		sourceId,
		title: sourceId,
		url,
		role: 'CONSULTED'
	}
}

test('presents a safe site query before its source arrives and activates it afterward', () => {
	const api = loadModule()
	const activity = {
		phase: 'WEB_SEARCH',
		query: 'site:docs.oracle.com/en/java/javase/21/docs/api java.lang.Object'
	}
	const pending = api.presentAiSearchActivity(activity, [])

	assert.deepEqual(pending, {
		domain: 'docs.oracle.com',
		pathHint: '/en/java/javase/21/docs/api',
		source: null,
		clickable: false
	})

	const active = api.presentAiSearchActivity(activity, [
		source('https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Object.html')
	])
	assert.equal(active.domain, 'docs.oracle.com')
	assert.equal(active.clickable, true)
	assert.equal(active.source.url,
		'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Object.html')
})

test('matches a complete query URL only to its exact normalized source URL', () => {
	const api = loadModule()
	const activity = {
		phase: 'WEB_SEARCH',
		query: 'Review https://openjdk.org/jeps/444?view=full for virtual threads'
	}
	const exact = api.presentAiSearchActivity(activity, [
		source('https://openjdk.org/jeps/444?view=full#summary')
	])
	const differentQuery = api.presentAiSearchActivity(activity, [
		source('https://openjdk.org/jeps/444?view=compact')
	])

	assert.equal(exact.clickable, true)
	assert.equal(exact.source.url, 'https://openjdk.org/jeps/444?view=full#summary')
	assert.equal(differentQuery.clickable, false)
	assert.equal(api.presentAiSearchActivity({
		phase: 'WEB_SEARCH', query: 'Open https://example.com:8443/docs'
	}, [source('https://example.com:8443/docs')]).clickable, true)
})

test('does not guess between multiple same-domain sources', () => {
	const api = loadModule()
	const hostOnly = api.presentAiSearchActivity({
		phase: 'WEB_SEARCH', query: 'site:docs.oracle.com java Object'
	}, [
		source('https://docs.oracle.com/object', 'object'),
		source('https://docs.oracle.com/thread', 'thread')
	])
	const uniquePath = api.presentAiSearchActivity({
		phase: 'WEB_SEARCH', query: 'site:docs.oracle.com/object java Object'
	}, [
		source('https://docs.oracle.com/object/api', 'object'),
		source('https://docs.oracle.com/thread/api', 'thread')
	])

	assert.equal(hostOnly.clickable, false)
	assert.equal(hostOnly.source, null)
	assert.equal(uniquePath.source.sourceId, 'object')
	assert.equal(uniquePath.clickable, true)
	assert.equal(api.presentAiSearchActivity({
		phase: 'WEB_SEARCH', query: 'site:docs.oracle.com/object java Object'
	}, [source('https://docs.oracle.com/object-other', 'other')]).clickable, false)
})

test('rejects unsafe or non-public search targets without inventing a source URL', () => {
	const api = loadModule()

	assert.equal(api.presentAiSearchActivity({
		phase: 'WEB_SEARCH', query: 'site:127.0.0.1/admin'
	}, []), null)
	assert.equal(api.presentAiSearchActivity({
		phase: 'WEB_SEARCH', query: 'site:localhost/private'
	}, []), null)
	assert.equal(api.presentAiSearchActivity({
		phase: 'REASONING', query: 'site:docs.oracle.com'
	}, []), null)
})

test('keeps query-null searches generic and adds real sources as ordered timeline rows', () => {
	const api = loadModule()
	const started = {
		sequence: 2,
		phase: 'WEB_SEARCH',
		status: 'IN_PROGRESS',
		query: null,
		occurredAt: '2026-08-03T17:10:37Z'
	}
	const completed = {
		sequence: 4,
		phase: 'WEB_SEARCH',
		status: 'COMPLETED',
		query: null,
		occurredAt: '2026-08-03T17:11:08Z'
	}
	const cited = source('https://threejs.org/docs/', 'threejs')
	cited.sequence = 3
	cited.occurredAt = '2026-08-03T17:11:00Z'

	assert.equal(api.presentAiSearchActivity(started, [cited]), null)
	const timeline = api.presentAiResearchTimeline(
		[completed, started], [cited])

	assert.deepEqual(timeline.map(item => item.type), [
		'activity', 'source', 'activity'
	])
	assert.equal(timeline[1].source.domain, 'threejs.org')
	assert.equal(timeline[1].source.url, 'https://threejs.org/docs/')
})

test('formats each reasoning summary event as one ordered-preserving Markdown list item', () => {
	const api = loadModule()
	const markdown = api.formatAiReasoningSummaryMarkdown([
		{ sequence: 9, textDelta: ' **Preparing JDK 21 query** ' },
		{ sequence: 2, textDelta: 'Listing `Object` methods\nand constructors' },
		{ sequence: 3, textDelta: '   ' },
		{ sequence: 4, textDelta: '**Preparing JDK 21 query**' }
	])

	assert.equal(markdown, [
		'- **Preparing JDK 21 query**',
		'- Listing `Object` methods',
		'  and constructors',
		'- **Preparing JDK 21 query**'
	].join('\n'))
})

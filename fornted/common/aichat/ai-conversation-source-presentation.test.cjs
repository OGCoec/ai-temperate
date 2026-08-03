const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function loadModule() {
	const source = fs.readFileSync(path.join(__dirname,
		'ai-conversation-source-presentation.js'), 'utf8')
		.replaceAll('export function ', 'function ')
	const factory = new Function(`${source}; return {
		normalizeAiConversationSource,
		mergeAiConversationSources,
		createAiConversationSourceIndex,
		matchAiConversationSource,
		decorateAiMarkdownSources }`)
	return factory()
}

function source(overrides = {}) {
	return {
		sequence: 3,
		activityId: 'message-1',
		sourceId: 'source-1',
		title: 'Oracle Object documentation',
		url: 'HTTPS://Docs.Oracle.Com:443/en/java/javase/21/docs/api/java.base/java/lang/Object.html?view=full#wait()',
		domain: 'untrusted.example',
		role: 'CITED',
		occurredAt: '2026-08-03T12:11:08Z',
		...overrides
	}
}

test('normalizes safe sources and derives the hostname from the source URL', () => {
	const api = loadModule()
	const value = api.normalizeAiConversationSource(source())

	assert.equal(value.domain, 'docs.oracle.com')
	assert.equal(value.url,
		'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Object.html?view=full#wait()')
	assert.equal(api.normalizeAiConversationSource(source({
		url: 'javascript:alert(1)'
	})), null)
	assert.equal(api.normalizeAiConversationSource(null), null)
})

test('deduplicates normalized full URLs while ignoring fragments only', () => {
	const api = loadModule()
	const values = api.mergeAiConversationSources([
		source(),
		source({ sequence: 4, sourceId: 'source-duplicate',
			url: 'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Object.html?view=full#notify()' }),
		source({ sequence: 5, sourceId: 'source-query',
			url: 'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Object.html?view=compact' })
	])

	assert.equal(values.length, 2)
	assert.deepEqual(values.map(item => item.sourceId), ['source-1', 'source-query'])
})

test('matches exact URLs and only falls back to a unique origin plus pathname', () => {
	const api = loadModule()
	const index = api.createAiConversationSourceIndex([
		source({ url: 'https://docs.oracle.com/object?view=full' }),
		source({ sequence: 4, sourceId: 'source-about',
			url: 'https://docs.oracle.com/about' })
	])

	assert.equal(api.matchAiConversationSource(index,
		'https://docs.oracle.com/object?view=full#member')?.sourceId, 'source-1')
	assert.equal(api.matchAiConversationSource(index,
		'https://docs.oracle.com/object?view=compact')?.sourceId, 'source-1')

	const ambiguous = api.createAiConversationSourceIndex([
		source({ url: 'https://docs.oracle.com/object?view=one' }),
		source({ sequence: 4, sourceId: 'source-two',
			url: 'https://docs.oracle.com/object?view=two' })
	])
	assert.equal(api.matchAiConversationSource(ambiguous,
		'https://docs.oracle.com/object?view=three'), null)
})

test('decorates matched citations, removes their wrapper parentheses, and does not mutate parser AST', () => {
	const api = loadModule()
	const ast = {
		type: 'document',
		children: [{
			type: 'paragraph',
			children: [
				{ type: 'text', value: 'JDK documentation (  ' },
				{ type: 'link', safe: true, href: 'https://docs.oracle.com/object',
					children: [{ type: 'text', value: 'docs.oracle.com' }] },
				{ type: 'text', value: '  ) remains authoritative.' }
			]
		}, {
			type: 'paragraph',
			children: [
				{ type: 'text', value: '中文引用（' },
				{ type: 'link', safe: true, href: 'https://docs.oracle.com/object',
					children: [{ type: 'text', value: 'docs.oracle.com' }] },
				{ type: 'text', value: '）继续。' }
			]
		}]
	}
	const decorated = api.decorateAiMarkdownSources(ast, [
		source({ url: 'https://docs.oracle.com/object' })
	])

	assert.equal(ast.children[0].children[0].value, 'JDK documentation (  ')
	assert.equal(ast.children[0].children[1].source, undefined)
	assert.equal(decorated.children[0].children[0].value, 'JDK documentation ')
	assert.equal(decorated.children[0].children[1].source.domain, 'docs.oracle.com')
	assert.equal(decorated.children[0].children[2].value, ' remains authoritative.')
	assert.equal(decorated.children[1].children[0].value, '中文引用')
	assert.equal(decorated.children[1].children[2].value, '继续。')
})

test('preserves ordinary parentheses, code, unmatched links, and citations with extra wrapper content', () => {
	const api = loadModule()
	const ast = {
		type: 'document',
		children: [{
			type: 'paragraph',
			children: [
				{ type: 'text', value: 'ordinary (' },
				{ type: 'link', safe: true, href: 'https://example.com',
					children: [{ type: 'text', value: 'example.com' }] },
				{ type: 'text', value: ') and (' },
				{ type: 'link', safe: true, href: 'https://docs.oracle.com/object',
					children: [{ type: 'text', value: 'docs.oracle.com' }] },
				{ type: 'text', value: ' plus context)' }
			]
		}, {
			type: 'codeBlock', language: 'text', code: '([docs.oracle.com](url))'
		}]
	}
	const decorated = api.decorateAiMarkdownSources(ast, [
		source({ url: 'https://docs.oracle.com/object' })
	])

	assert.deepEqual(decorated.children[0].children.map(item => item.value || item.href), [
		'ordinary (', 'https://example.com', ') and (',
		'https://docs.oracle.com/object', ' plus context)'
	])
	assert.equal(decorated.children[1].code, '([docs.oracle.com](url))')
})

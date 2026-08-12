const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

function loadModule() {
	return loadEsmModule(path.join(__dirname,
		'ai-conversation-source-presentation.js'))
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

function parenthesizedDomainAst(visibleDomain, href) {
	return {
		type: 'document',
		children: [{
			type: 'paragraph',
			children: [
				{ type: 'text', value: 'Reference (' },
				{ type: 'link', safe: true, href,
					children: [{ type: 'text', value: visibleDomain }] },
				{ type: 'text', value: ') remains available.' }
			]
		}]
	}
}

test('normalizes safe sources and derives the hostname from the source URL', async () => {
	const api = await loadModule()
	const value = api.normalizeAiConversationSource(source())

	assert.equal(value.domain, 'docs.oracle.com')
	assert.equal(value.url,
		'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Object.html?view=full#wait()')
	assert.equal(api.normalizeAiConversationSource(source({
		url: 'javascript:alert(1)'
	})), null)
	assert.equal(api.normalizeAiConversationSource(null), null)
})

test('deduplicates normalized full URLs while ignoring fragments only', async () => {
	const api = await loadModule()
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

test('matches exact URLs and only falls back to a unique origin plus pathname', async () => {
	const api = await loadModule()
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

test('decorates matched citations, removes their wrapper parentheses, and does not mutate parser AST', async () => {
	const api = await loadModule()
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

test('uses a safe parenthesized Markdown domain link when no SSE source matches', async () => {
	const api = await loadModule()
	const ast = {
		type: 'document',
		children: [{
			type: 'paragraph',
			children: [
				{ type: 'text', value: 'Three.js documentation (' },
				{ type: 'link', safe: true,
					href: 'HTTPS://ThreeJS.org/docs/#manual/en/introduction/Creating-a-scene',
					children: [{ type: 'text', value: 'threejs.org' }] },
				{ type: 'text', value: ') remains available.' }
			]
		}]
	}
	const decorated = api.decorateAiMarkdownSources(ast, [])
	const link = decorated.children[0].children[1]

	assert.equal(ast.children[0].children[0].value, 'Three.js documentation (')
	assert.equal(ast.children[0].children[1].source, undefined)
	assert.equal(decorated.children[0].children[0].value, 'Three.js documentation ')
	assert.equal(link.source.domain, 'threejs.org')
	assert.equal(link.source.url,
		'https://threejs.org/docs/#manual/en/introduction/Creating-a-scene')
	assert.equal(decorated.children[0].children[2].value, ' remains available.')
})

test('treats one leading www label as equivalent for parenthesized domain links', async () => {
	const api = await loadModule()
	const rabbitMqUrl =
		'https://www.rabbitmq.com/docs/3.13/queues?utm_source=openai'
	const rabbitMqAst = parenthesizedDomainAst('rabbitmq.com', rabbitMqUrl)
	const decoratedRabbitMq = api.decorateAiMarkdownSources(rabbitMqAst, [])
	const rabbitMqLink = decoratedRabbitMq.children[0].children[1]

	assert.equal(rabbitMqAst.children[0].children[0].value, 'Reference (')
	assert.equal(rabbitMqAst.children[0].children[1].source, undefined)
	assert.equal(decoratedRabbitMq.children[0].children[0].value, 'Reference ')
	assert.equal(rabbitMqLink.source.domain, 'www.rabbitmq.com')
	assert.equal(rabbitMqLink.source.url, rabbitMqUrl)
	assert.equal(decoratedRabbitMq.children[0].children[2].value,
		' remains available.')

	for (const candidate of [
		{ visibleDomain: 'www.rabbitmq.com',
			href: 'https://rabbitmq.com/docs/queues', expectedDomain: 'rabbitmq.com' },
		{ visibleDomain: 'rabbitmq.com.',
			href: 'https://www.rabbitmq.com/docs/queues', expectedDomain: 'www.rabbitmq.com' },
		{ visibleDomain: 'WWW.RabbitMQ.COM',
			href: 'https://rabbitmq.com/docs/queues', expectedDomain: 'rabbitmq.com' }
	]) {
		const decorated = api.decorateAiMarkdownSources(
			parenthesizedDomainAst(candidate.visibleDomain, candidate.href), [])
		assert.equal(decorated.children[0].children[1].source?.domain,
			candidate.expectedDomain)
	}
})

test('does not collapse arbitrary or deceptive subdomains into source chips', async () => {
	const api = await loadModule()
	for (const candidate of [
		{ visibleDomain: 'apache.org', href: 'https://kafka.apache.org/documentation' },
		{ visibleDomain: 'rabbitmq.com',
			href: 'https://www.rabbitmq.com.evil.example/docs' },
		{ visibleDomain: 'rabbitmq.com', href: 'https://www2.rabbitmq.com/docs' },
		{ visibleDomain: 'RabbitMQ documentation', href: 'https://www.rabbitmq.com/docs' }
	]) {
		const decorated = api.decorateAiMarkdownSources(
			parenthesizedDomainAst(candidate.visibleDomain, candidate.href), [])
		assert.equal(decorated.children[0].children[1].source, undefined)
		assert.equal(decorated.children[0].children[0].value, 'Reference (')
		assert.equal(decorated.children[0].children[2].value,
			') remains available.')
	}
})

test('preserves ordinary parentheses, code, unmatched links, and citations with extra wrapper content', async () => {
	const api = await loadModule()
	const ast = {
		type: 'document',
		children: [{
			type: 'paragraph',
			children: [
				{ type: 'text', value: 'ordinary (' },
				{ type: 'link', safe: true, href: 'https://example.com',
					children: [{ type: 'text', value: 'example documentation' }] },
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

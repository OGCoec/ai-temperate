const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadParser() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'ai-conversation-sse-parser.js'),
		'utf8'
	)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

test('parses split CRLF frames and ignores heartbeat comments', async () => {
	const module = await loadParser()
	const events = []
	const parser = module.createAiConversationSseParser(event => events.push(event))

	parser.push(': heartbeat\r\nevent: accepted\r\ndata: {"conversationPublicId":"A')
	parser.push('AAAAAAAAAAAAAAAAAAAAQ"}\r\n\r\nevent: delta\ndata: {"text":"你')
	parser.push('好"}\n\n')
	parser.finish()

	assert.deepEqual(events.map(event => event.type), ['accepted', 'delta'])
	assert.equal(events[1].data.text, '你好')
})

test('rejects malformed JSON instead of emitting an untrusted frame', async () => {
	const module = await loadParser()
	const parser = module.createAiConversationSseParser()

	assert.throws(
		() => parser.push('event: completed\ndata: {not-json}\n\n'),
		error => error?.code === 'AI_CONVERSATION_SSE_PROTOCOL_INVALID'
	)
})

const assert = require('node:assert/strict')
const test = require('node:test')
const { createHarness, deferred, flush } = require('../auth/session-terminal-test-harness.cjs')

function streamingHarness(platform) {
	const h = createHarness(platform)
	const streams = []
	const parser = h.load('../aichat/ai-conversation-sse-parser.js')
	const transportBindings = {
		...parser,
		openSseRequest(options) {
			const stream = { options, closed: 0 }
			streams.push(stream)
			return { close() { stream.closed++ } }
		},
		fetch(url, options) {
			const response = deferred()
			const reads = []
			let nextRead = null
			const stream = {
				url, options, response, closed: 0,
				push(value) { if (nextRead) { const read = nextRead; nextRead = null; read.resolve(value) } else reads.push(value) },
				body: { getReader() { return {
					read() { if (reads.length) return Promise.resolve(reads.shift()); nextRead = deferred(); return nextRead.promise },
					releaseLock() {}, async cancel() {}
				} } }
			}
			options.signal.addEventListener('abort', () => { stream.closed++; stream.push({ done: true }) })
			streams.push(stream)
			return response.promise
		}
	}
	const transport = h.load(platform === 'ANDROID'
		? '../aichat/ai-conversation-sse-app.js' : '../aichat/ai-conversation-sse-h5.js', transportBindings)
	const open = transport.openAiConversationSseApp || transport.openAiConversationSseH5
	const noop = () => {}
	const diagnostics = { record: noop, finish: noop, clientRequestId: 'unavailable' }
	const bindings = {
		openAiConversationSseApp: open, openAiConversationSseH5: open,
		isAndroidEdgeChallenge: error => error?.code === 'EDGE_CHALLENGE',
		repeatedAndroidEdgeChallengeError: error => error,
		createAiConversationStreamDiagnostics: () => diagnostics,
		reportAiConversationStreamDiagnostics: noop,
		createAiConversationLifecycleDiagnostics: () => diagnostics,
		asyncGenerationEnabled: () => true, bindGenerationObserver: noop,
		getGeneration: () => ({}), markGenerationTerminal: noop,
		registerGeneration: noop, updateGeneration: noop,
		mergeAiConversationSources: () => [],
		buildQueryString: entries => entries.map(([key, value]) => `${key}=${value}`).join('&')
	}
	return {
		...h, h, streams, open,
		stream: h.load('../aichat/ai-conversation-stream.js', bindings),
		context: h.load('../aichat/ai-conversation-context-stream.js', bindings),
		fail(index, code = 'REFRESH_TOKEN_INVALID', statusCode = 401) {
			const stream = streams[index]
			if (platform === 'ANDROID') stream.options.onError({ code, statusCode })
			else {
				stream.response.resolve({ ok: false, status: statusCode, headers: { get: () => '' }, body: stream.body })
				stream.push({ done: false, value: new TextEncoder().encode(JSON.stringify({ code })) })
				stream.push({ done: true })
			}
		},
		start(index) {
			const stream = streams[index]
			if (platform === 'ANDROID') stream.options.onOpen({ statusCode: 200, contentType: 'text/event-stream' })
			else stream.response.resolve({ ok: true, status: 200, headers: { get: name => name === 'content-type' ? 'text/event-stream' : '' }, body: stream.body })
		},
		event(index, type, data = {}) {
			const text = `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`
			if (platform === 'ANDROID') streams[index].options.onChunk(text)
			else streams[index].push({ done: false, value: new TextEncoder().encode(text) })
		},
		end(index) {
			if (platform === 'ANDROID') streams[index].options.onClosed()
			else streams[index].push({ done: true })
		}
	}
}

for (const platform of ['H5', 'ANDROID']) {
	test(`${platform}: generation observer 401 stops before generic reconnect`, async () => {
		const h = streamingHarness(platform)
		const handle = await h.stream.openAiConversationGenerationStream('test-generation')
		const failure = handle.completed.catch(error => error)
		h.fail(0)
		assert.equal((await failure).code, 'REFRESH_TOKEN_INVALID')
		assert.equal(h.streams.length, 1)
		assert.equal(h.h.clears, 1)
		assert.equal(h.navigations.length, 1)
	})

	test(`${platform}: context observer initial 401 exits without opening a replacement`, async () => {
		const h = streamingHarness(platform)
		const pending = h.context.openAiConversationContextStream({ conversationPublicId: 'test-conversation' }).catch(error => error)
		await flush()
		h.fail(0, 'UNKNOWN_AUTH_FAILURE')
		assert.equal((await pending).code, 'UNKNOWN_AUTH_FAILURE')
		assert.equal(h.streams.length, 1)
		assert.equal(h.h.clears, 1)
		assert.equal(h.navigations.length, 1)
	})

	test(`${platform}: termination while context reconnect waits prevents another dispatch`, async () => {
		const h = streamingHarness(platform)
		const opening = h.context.openAiConversationContextStream({ conversationPublicId: 'test-conversation' })
		await flush()
		h.start(0)
		const handle = await opening
		const failure = handle.completed.catch(error => error)
		h.end(0)
		await flush()
		h.http.handleTerminalSessionError({ statusCode: 401, code: 'REFRESH_TOKEN_REQUIRED' })
		assert.equal((await failure).code, 'SESSION_TERMINATED')
		assert.equal(h.streams.length, 1)
	})

	test(`${platform}: accepted generation is never replayed as POST after observer 401`, async () => {
		const h = streamingHarness(platform)
		const handle = await h.stream.openAiConversationStream({ idempotencyKey: 'test-key', body: {} })
		const failure = handle.completed.catch(error => error)
		await flush()
		h.start(0)
		h.event(0, 'accepted', { generationPublicId: 'test-generation' })
		await flush()
		h.end(0)
		// 生产重连等待 250 ms；测试只等待有界的观察器创建，不改写重试实现。
		for (let attempt = 0; attempt < 50 && h.streams.length < 2; attempt++) {
			await new Promise(resolve => setTimeout(resolve, 10))
		}
		assert.equal(h.streams.length, 2)
		h.fail(1)
		assert.equal((await failure).code, 'REFRESH_TOKEN_INVALID')
		assert.equal(h.streams.filter(stream => stream.options.method === 'POST').length, 1)
		assert.equal(h.streams[1].options.method, 'GET')
	})

	test(`${platform}: late stream error from the previous login cannot clear the new session`, async () => {
		const h = streamingHarness(platform)
		const connection = h.open({ url: 'https://example.test/events', sessionGeneration: 0, headers: {} })
		const failure = connection.completed.catch(error => error)
		h.login()
		h.fail(0)
		assert.equal((await failure).code, 'SESSION_GENERATION_STALE')
		assert.equal(h.h.clears, 0)
		assert.equal(h.navigations.length, 0)
	})
}

test('Android SSE never saves a late renewal token after a new login', async () => {
	const h = streamingHarness('ANDROID')
	const connection = h.open({ url: 'https://example.test/events', sessionGeneration: 0, headers: {} })
	const failure = connection.completed.catch(error => error)
	h.login()
	h.streams[0].options.onOpen({ statusCode: 200, contentType: 'text/event-stream', sessionRenewed: 'true', newAccessToken: 'old-test-access' })
	assert.equal((await failure).code, 'SESSION_GENERATION_STALE')
	assert.equal(h.saved.length, 0)
	assert.equal(h.streams[0].closed, 1)
})

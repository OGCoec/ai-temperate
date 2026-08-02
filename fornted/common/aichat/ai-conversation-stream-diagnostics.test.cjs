const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadDiagnostics() {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-conversation-stream-diagnostics.js'),
		'utf8'
	)
	const url = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(`${url}#${Date.now()}-${Math.random()}`)
}

test('disabled diagnostics do not allocate windows or write output', async () => {
	const { createAiConversationStreamDiagnostics } = await loadDiagnostics()
	const entries = []
	const diagnostics = createAiConversationStreamDiagnostics({
		enabled: false,
		sink: entry => entries.push(entry)
	})

	diagnostics.record('BROWSER_READ', { byteCount: 100 })
	diagnostics.finish('COMPLETE')

	assert.equal(entries.length, 0)
	assert.equal(diagnostics.snapshot(), null)
})

test('records network, parsed and rendered timing without model content', async () => {
	const { createAiConversationStreamDiagnostics } = await loadDiagnostics()
	let now = 0
	const entries = []
	const diagnostics = createAiConversationStreamDiagnostics({
		enabled: true,
		now: () => now,
		sink: entry => entries.push(entry),
		logEveryChunks: 2,
		windowMs: 1000
	})
	const secretText = 'PRIVATE_MODEL_TEXT_SHOULD_NOT_BE_LOGGED'

	diagnostics.record('BROWSER_READ', {
		eventType: 'HEADERS',
		statusCode: 200,
		contentType: 'text/event-stream; charset=utf-8'
	})
	now = 25
	diagnostics.record('BROWSER_READ', { eventType: 'BYTES', byteCount: 128 })
	now = 40
	diagnostics.bindUsagePublicId('AZ-50wCZAQGBuCvbSqIYsA')
	diagnostics.record('BROWSER_SSE_PARSED', { eventType: 'accepted' })
	now = 50
	diagnostics.record('BROWSER_SSE_PARSED', {
		eventType: 'delta',
		sequence: '9',
		textCharacters: secretText.length
	})
	now = 75
	diagnostics.record('FRONTEND_RENDERED', {
		textCharacters: secretText.length
	})
	diagnostics.finish('COMPLETE')

	const serialized = JSON.stringify(entries)
	assert.equal(serialized.includes(secretText), false)
	assert.equal(serialized.includes('AZ-50wCZAQGBuCvbSqIYsA'), true)
	assert.ok(entries.some(entry =>
		entry.event === 'ai_stream_client_timing_first_byte'
			&& entry.boundary === 'BROWSER_READ'
			&& entry.elapsedMs === 25
	))
	assert.ok(entries.some(entry =>
		entry.event === 'ai_stream_client_timing_summary'
			&& entry.outcome === 'COMPLETE'
			&& entry.responseHeadersMs === 0
			&& entry.responseStatusCode === 200
			&& entry.responseContentType === 'text/event-stream'
			&& entry.lastNetworkByteMs === 25
			&& entry.lastDeltaSequence === 9
			&& entry.parsedEvents === 2
			&& entry.renderedTextCharacters === secretText.length
	))
})

test('summarizes missing delta sequences without retaining event text', async () => {
	const { createAiConversationStreamDiagnostics } = await loadDiagnostics()
	let now = 0
	const entries = []
	const secretText = 'PRIVATE_MODEL_TEXT_MUST_NEVER_REACH_DIAGNOSTICS'
	const diagnostics = createAiConversationStreamDiagnostics({
		enabled: true,
		now: () => now,
		sink: entry => entries.push(entry)
	})

	diagnostics.bindUsagePublicId('AZ-50wCZAQGBuCvbSqIYsA')
	diagnostics.record('BROWSER_SSE_PARSED', {
		eventType: 'delta', sequence: '1', textCharacters: secretText.length,
		untrustedText: secretText
	})
	now = 10
	diagnostics.record('BROWSER_SSE_PARSED', {
		eventType: 'delta', sequence: '3', textCharacters: 2
	})
	diagnostics.finish('COMPLETE')

	const summary = entries.find(entry =>
		entry.event === 'ai_stream_client_timing_summary')
	assert.equal(summary.lastDeltaSequence, 3)
	assert.equal(summary.deltaSequenceGapCount, 1)
	assert.equal(JSON.stringify(entries).includes(secretText), false)
})

test('sends one safe terminal summary to the optional diagnostics reporter', async () => {
	const { createAiConversationStreamDiagnostics } = await loadDiagnostics()
	const summaries = []
	const diagnostics = createAiConversationStreamDiagnostics({
		enabled: true,
		onSummary: summary => summaries.push(summary)
	})

	diagnostics.bindUsagePublicId('AZ-50wCZAQGBuCvbSqIYsA')
	diagnostics.bindGenerationPublicId('AZ-vpV3kfag70-0EMMUETQ')
	diagnostics.bindTraceId('4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6')
	diagnostics.record('BROWSER_SSE_PARSED', {
		eventType: 'delta',
		textCharacters: 42,
		untrustedText: 'PRIVATE_MODEL_TEXT_MUST_NOT_BE_REPORTED'
	})
	diagnostics.finish('COMPLETE')

	assert.equal(summaries.length, 1)
	assert.equal(summaries[0].generationPublicId, 'AZ-vpV3kfag70-0EMMUETQ')
	assert.equal(summaries[0].traceId, '4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6')
	assert.equal(JSON.stringify(summaries).includes('PRIVATE_MODEL_TEXT_MUST_NOT_BE_REPORTED'), false)
})

test('ignores unknown boundaries instead of creating unbounded labels', async () => {
	const { createAiConversationStreamDiagnostics } = await loadDiagnostics()
	const diagnostics = createAiConversationStreamDiagnostics({ enabled: true })

	assert.equal(
		diagnostics.record('UNTRUSTED_BOUNDARY', { byteCount: 1 }),
		false
	)
})

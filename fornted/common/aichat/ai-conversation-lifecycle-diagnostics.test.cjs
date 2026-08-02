const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadDiagnostics() {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-conversation-lifecycle-diagnostics.js'),
		'utf8'
	)
	const url = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(`${url}#${Date.now()}-${Math.random()}`)
}

test('disabled lifecycle diagnostics use a no-op context', async () => {
	const { createAiConversationLifecycleDiagnostics } = await loadDiagnostics()
	const entries = []
	const diagnostics = createAiConversationLifecycleDiagnostics({
		enabled: false,
		sink: entry => entries.push(entry)
	})

	diagnostics.record('CLIENT_FETCH_SENT')
	diagnostics.finish('CANCEL')

	assert.equal(entries.length, 0)
	assert.equal(diagnostics.snapshot(), null)
})

test('records one client lifecycle without retaining content or balances', async () => {
	const { createAiConversationLifecycleDiagnostics } = await loadDiagnostics()
	let now = 0
	const entries = []
	const diagnostics = createAiConversationLifecycleDiagnostics({
		enabled: true,
		now: () => now,
		uuid: () => '4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6',
		sink: entry => entries.push(entry)
	})

	diagnostics.record('CLIENT_STREAM_CREATED')
	now = 10
	diagnostics.bindServerTraceId('f47ac10b-58cc-4372-a567-0e02b2c3d479')
	diagnostics.bindUsagePublicId('AZ-50wCZAQGBuCvbSqIYsA')
	diagnostics.record('CLIENT_SSE_ACCEPTED')
	now = 20
	diagnostics.observeVisibleOutput(25)
	diagnostics.record('CLIENT_FIRST_DELTA', {
		untrustedText: 'PRIVATE_MODEL_TEXT',
		quotaBalance: '999999'
	})
	diagnostics.reportedUsageObserved()
	diagnostics.finish('COMPLETE')

	const serialized = JSON.stringify(entries)
	assert.equal(serialized.includes('PRIVATE_MODEL_TEXT'), false)
	assert.equal(serialized.includes('999999'), false)
	assert.ok(entries.some(entry =>
		entry.phase === 'CLIENT_TERMINAL_RENDERED'
			&& entry.outcome === 'COMPLETE'
			&& entry.elapsedMs === 20
	))
})

test('accepted reservation id is not reported as final upstream usage', async () => {
	const { createAiConversationLifecycleDiagnostics } = await loadDiagnostics()
	const entries = []
	const diagnostics = createAiConversationLifecycleDiagnostics({
		enabled: true,
		uuid: () => '4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6',
		sink: entry => entries.push(entry)
	})

	diagnostics.bindUsagePublicId('AZ-50wCZAQGBuCvbSqIYsA')
	diagnostics.record('CLIENT_SSE_ACCEPTED')
	diagnostics.stopRequested('USER_STOP')

	assert.equal(entries.at(-1).hasReportedUsage, false)
})

test('each enabled lifecycle gets a distinct client request id', async () => {
	const { createAiConversationLifecycleDiagnostics } = await loadDiagnostics()
	const values = [
		'4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6',
		'2057e14f-9fe0-4f6d-a923-d0a470b11db2'
	]
	const first = createAiConversationLifecycleDiagnostics({
		enabled: true,
		uuid: () => values[0],
		sink() {}
	})
	const second = createAiConversationLifecycleDiagnostics({
		enabled: true,
		uuid: () => values[1],
		sink() {}
	})

	assert.notEqual(first.clientRequestId, second.clientRequestId)
})

test('stop and abort phases are idempotent', async () => {
	const { createAiConversationLifecycleDiagnostics } = await loadDiagnostics()
	const entries = []
	const diagnostics = createAiConversationLifecycleDiagnostics({
		enabled: true,
		uuid: () => '4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6',
		sink: entry => entries.push(entry)
	})

	diagnostics.stopRequested('PAGE_HIDDEN', {
		hasVisibleOutput: false,
		emittedTextCharacters: 0
	})
	diagnostics.stopRequested('PAGE_UNLOAD')
	diagnostics.abortCalled()
	diagnostics.abortCalled()

	assert.equal(entries.filter(entry =>
		entry.phase === 'CLIENT_STOP_REQUESTED').length, 1)
	assert.equal(entries.filter(entry =>
		entry.phase === 'CLIENT_ABORT_CALLED').length, 1)
})

test('frontend sources wire explicit cancellation reasons without cancelling on page hide', () => {
	const streamSource = fs.readFileSync(
		path.join(__dirname, 'ai-conversation-stream.js'), 'utf8')
	const panelSource = fs.readFileSync(
		path.join(__dirname, '../../components/user/workspace/user-chat-panel.vue'),
		'utf8'
	)

	assert.equal(streamSource.includes("'X-AI-Client-Request-Id'"), true)
	for (const reason of [
		'USER_STOP', 'PAGE_UNLOAD', 'COMPONENT_UNMOUNT'
	]) {
		assert.equal(panelSource.includes(reason), true)
	}
	assert.equal(panelSource.includes("close?.('PAGE_HIDDEN'"), false)
	assert.equal(panelSource.includes("finish?.('CANCEL')"), true)
})

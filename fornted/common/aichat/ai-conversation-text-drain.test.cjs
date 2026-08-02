const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadDrain() {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-conversation-text-drain.js'),
		'utf8'
	)
	const url = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(`${url}#${Date.now()}-${Math.random()}`)
}

function fakeScheduler() {
	const tasks = []
	return {
		schedule(callback) { tasks.push(callback); return callback },
		cancel(callback) {
			const index = tasks.indexOf(callback)
			if (index >= 0) tasks.splice(index, 1)
		},
		run() { tasks.shift()?.() },
		pending() { return tasks.length }
	}
}

test('drains burst text progressively without splitting grapheme clusters', async () => {
	const { createAiConversationTextDrain } = await loadDrain()
	const scheduler = fakeScheduler()
	const chunks = []
	const drain = createAiConversationTextDrain({
		onText: text => chunks.push(text),
		schedule: scheduler.schedule,
		cancel: scheduler.cancel,
		now: () => 0,
		frameBudgetMs: 16,
		maxVisualLagMs: 500
	})

	drain.push('你👨‍👩‍👧‍👦好')
	assert.equal(chunks.length, 0)
	while (scheduler.pending()) scheduler.run()

	assert.equal(chunks.join(''), '你👨‍👩‍👧‍👦好')
	assert.ok(chunks.length > 1)
})

test('reduced motion flushes immediately and terminal waits for queued text', async () => {
	const { createAiConversationTextDrain } = await loadDrain()
	const chunks = []
	const drain = createAiConversationTextDrain({
		onText: text => chunks.push(text),
		reducedMotion: true
	})

	drain.push('完整答案')
	let completed = false
	drain.finish(() => { completed = true })

	assert.equal(chunks.join(''), '完整答案')
	assert.equal(completed, true)
})

test('close cancels pending presentation and late frames cannot mutate output', async () => {
	const { createAiConversationTextDrain } = await loadDrain()
	const scheduler = fakeScheduler()
	const chunks = []
	const drain = createAiConversationTextDrain({
		onText: text => chunks.push(text),
		schedule: scheduler.schedule,
		cancel: scheduler.cancel
	})

	drain.push('late text')
	drain.close()
	while (scheduler.pending()) scheduler.run()

	assert.equal(chunks.join(''), '')
})

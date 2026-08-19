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

test('stop tail snapshots at most thirty two queued graphemes', async () => {
	const {
		createAiConversationTextDrain,
		STOP_TAIL_MAX_DURATION_MS,
		STOP_TAIL_MAX_GRAPHEMES
	} = await loadDrain()
	const scheduler = fakeScheduler()
	const chunks = []
	let completed = 0
	const drain = createAiConversationTextDrain({
		onText: text => chunks.push(text),
		schedule: scheduler.schedule,
		cancel: scheduler.cancel,
		now: () => 0
	})

	drain.push('字'.repeat(100))
	drain.stopWithTail({}, () => { completed += 1 })
	while (scheduler.pending()) scheduler.run()

	assert.equal(STOP_TAIL_MAX_DURATION_MS, 200)
	assert.equal(STOP_TAIL_MAX_GRAPHEMES, 32)
	assert.equal(chunks.join(''), '字'.repeat(32))
	assert.equal(completed, 1)
})

test('stop tail keeps every queued grapheme below the cap and rejects late pushes', async () => {
	const { createAiConversationTextDrain } = await loadDrain()
	const scheduler = fakeScheduler()
	const chunks = []
	const drain = createAiConversationTextDrain({
		onText: text => chunks.push(text),
		schedule: scheduler.schedule,
		cancel: scheduler.cancel,
		now: () => 0
	})

	drain.push('点击前文本')
	drain.stopWithTail({}, () => {})
	drain.push('点击后文本')
	while (scheduler.pending()) scheduler.run()

	assert.equal(chunks.join(''), '点击前文本')
})

test('stop tail flushes the allowed snapshot when the two hundred millisecond deadline arrives', async () => {
	const { createAiConversationTextDrain } = await loadDrain()
	const scheduler = fakeScheduler()
	const chunks = []
	let now = 0
	const drain = createAiConversationTextDrain({
		onText: text => chunks.push(text),
		schedule: scheduler.schedule,
		cancel: scheduler.cancel,
		now: () => now
	})

	drain.push('x'.repeat(32))
	drain.stopWithTail({ maxDurationMs: 200, maxGraphemes: 32 }, () => {})
	now = 200
	scheduler.run()
	const atDeadline = chunks.join('')
	while (scheduler.pending()) scheduler.run()

	assert.equal(atDeadline, 'x'.repeat(32))
	assert.equal(chunks.join(''), atDeadline)
})

test('stop tail preserves emoji and combining grapheme boundaries', async () => {
	const { createAiConversationTextDrain } = await loadDrain()
	const scheduler = fakeScheduler()
	const chunks = []
	const drain = createAiConversationTextDrain({
		onText: text => chunks.push(text),
		schedule: scheduler.schedule,
		cancel: scheduler.cancel,
		now: () => 0
	})
	const grapheme = '👨‍👩‍👧‍👦e\u0301'

	drain.push(grapheme.repeat(40))
	drain.stopWithTail({}, () => {})
	while (scheduler.pending()) scheduler.run()

	assert.equal(chunks.join(''), grapheme.repeat(16))
})

test('empty and reduced motion stop tails complete immediately without synthetic text', async () => {
	const { createAiConversationTextDrain } = await loadDrain()
	const emptyChunks = []
	let emptyCompleted = 0
	const empty = createAiConversationTextDrain({
		onText: text => emptyChunks.push(text)
	})
	empty.stopWithTail({}, () => { emptyCompleted += 1 })

	const reducedChunks = []
	let reducedCompleted = 0
	const reduced = createAiConversationTextDrain({
		onText: text => reducedChunks.push(text),
		reducedMotion: true
	})
	reduced.push('视觉尾部')
	reduced.stopWithTail({}, () => { reducedCompleted += 1 })

	assert.deepEqual(emptyChunks, [])
	assert.equal(emptyCompleted, 1)
	assert.equal(reducedChunks.join(''), '视觉尾部')
	assert.equal(reducedCompleted, 1)
})

test('stop tail completion callback runs at most once', async () => {
	const { createAiConversationTextDrain } = await loadDrain()
	const scheduler = fakeScheduler()
	let completed = 0
	const drain = createAiConversationTextDrain({
		onText() {},
		schedule: scheduler.schedule,
		cancel: scheduler.cancel,
		now: () => 0
	})

	drain.push('queued')
	drain.stopWithTail({}, () => { completed += 1 })
	while (scheduler.pending()) scheduler.run()
	drain.finish(() => { completed += 1 })
	scheduler.run()

	assert.equal(completed, 1)
})

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

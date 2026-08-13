const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadModule() {
	const source = fs.readFileSync(
		path.join(__dirname, 'voice-live-transcript-presenter.js'),
		'utf8')
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function createScheduler() {
	let now = 0
	let sequence = 0
	const tasks = new Map()
	return {
		schedule(callback, delay) {
			sequence += 1
			tasks.set(sequence, { callback, dueAt: now + delay })
			return sequence
		},
		cancel(identifier) {
			tasks.delete(identifier)
		},
		advance(milliseconds) {
			const target = now + milliseconds
			while (true) {
				const next = [...tasks.entries()]
					.filter(([, task]) => task.dueAt <= target)
					.sort((left, right) => left[1].dueAt - right[1].dueAt
						|| left[0] - right[0])[0]
				if (!next) break
				const [identifier, task] = next
				tasks.delete(identifier)
				now = task.dueAt
				task.callback()
			}
			now = target
		},
		pendingCount() {
			return tasks.size
		}
	}
}

async function createHarness() {
	const { createVoiceLiveTranscriptPresenter } = await loadModule()
	const scheduler = createScheduler()
	const displayed = []
	const presenter = createVoiceLiveTranscriptPresenter({
		onDisplay: value => displayed.push(value),
		characterIntervalMs: 24,
		schedule: scheduler.schedule,
		cancel: scheduler.cancel
	})
	return { displayed, presenter, scheduler }
}

test('reveals a received partial one Unicode code point every 24ms', async () => {
	const { displayed, presenter, scheduler } = await createHarness()

	presenter.setTarget('你好😊', { reduced: false })
	assert.deepEqual(displayed, [])

	scheduler.advance(23)
	assert.deepEqual(displayed, [])
	scheduler.advance(1)
	assert.equal(displayed.at(-1), '你')
	scheduler.advance(48)
	assert.equal(displayed.at(-1), '你好😊')
})

test('keeps the visible prefix and follows only the newest growing target', async () => {
	const { displayed, presenter, scheduler } = await createHarness()

	presenter.setTarget('hello', { reduced: false })
	scheduler.advance(48)
	assert.equal(displayed.at(-1), 'he')

	presenter.setTarget('hello world', { reduced: false })
	presenter.setTarget('hello there', { reduced: false })
	scheduler.advance(24 * 9)
	assert.equal(displayed.at(-1), 'hello there')
	assert.equal(displayed.includes('hello world'), false)
})

test('removes an unstable suffix immediately before revealing its correction', async () => {
	const { displayed, presenter, scheduler } = await createHarness()

	presenter.setTarget('我想去上课', { reduced: true })
	assert.equal(displayed.at(-1), '我想去上课')

	presenter.setTarget('我想去上海', { reduced: false })
	assert.equal(displayed.at(-1), '我想去上')
	scheduler.advance(24)
	assert.equal(displayed.at(-1), '我想去上海')
})

test('reduced motion displays the complete latest partial immediately', async () => {
	const { displayed, presenter, scheduler } = await createHarness()

	presenter.setTarget('实时转写', { reduced: false })
	assert.equal(scheduler.pendingCount(), 1)
	presenter.setTarget('实时转写完成', { reduced: true })

	assert.equal(displayed.at(-1), '实时转写完成')
	assert.equal(scheduler.pendingCount(), 0)
})

test('reset and dispose cancel stale character delivery', async () => {
	const { displayed, presenter, scheduler } = await createHarness()

	presenter.setTarget('旧会话', { reduced: false })
	presenter.reset()
	assert.equal(displayed.at(-1), '')
	assert.equal(scheduler.pendingCount(), 0)
	scheduler.advance(100)
	assert.equal(displayed.at(-1), '')

	presenter.setTarget('新会话', { reduced: false })
	presenter.dispose()
	assert.equal(scheduler.pendingCount(), 0)
	scheduler.advance(100)
	assert.equal(displayed.at(-1), '')
	presenter.setTarget('不应显示', { reduced: true })
	assert.equal(displayed.at(-1), '')
})

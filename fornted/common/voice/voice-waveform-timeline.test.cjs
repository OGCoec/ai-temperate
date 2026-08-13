const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const timelineModulePath = path.resolve(
	__dirname,
	'voice-waveform-timeline.js')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadTimeline() {
	const source = fs.readFileSync(timelineModulePath, 'utf8')
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function recorded(snapshot) {
	return snapshot.settledBars.filter(bar => bar.recorded)
}

test('start creates a complete baseline plus one stable pending slot', async () => {
	const { createVoiceWaveformTimeline } = await loadTimeline()
	const timeline = createVoiceWaveformTimeline({ capacity: 4, now: () => 0 })

	assert.equal(timeline.start(7), true)
	const snapshot = timeline.snapshot(0)

	assert.equal(snapshot.epoch, 7)
	assert.equal(snapshot.cycle, 0)
	assert.equal(snapshot.capacity, 4)
	assert.equal(snapshot.settledBars.length, 4)
	assert.equal(snapshot.movingBars.length, 5)
	assert.ok(snapshot.movingBars.every(bar => bar.level === 0 && bar.recorded === false))
	assert.equal(new Set(snapshot.movingBars.map(bar => bar.id)).size, 5)
})

test('strict boundaries settle one bar at 300ms, two at 600ms, and ten at 3s', async () => {
	const { createVoiceWaveformTimeline } = await loadTimeline()
	const timeline = createVoiceWaveformTimeline({ capacity: 12, now: () => 0 })
	timeline.start(3)

	assert.equal(timeline.advance(299), false)
	assert.equal(recorded(timeline.snapshot(299)).length, 0)
	for (let cycle = 1; cycle <= 10; cycle += 1) {
		assert.equal(timeline.advance(cycle * 300), true)
		assert.equal(recorded(timeline.snapshot(cycle * 300)).length, cycle)
	}
	assert.equal(timeline.snapshot(3000).cycle, 10)
})

test('the latest fifteen 20ms levels collapse into one RMS bar', async () => {
	const { createVoiceWaveformTimeline } = await loadTimeline()
	const timeline = createVoiceWaveformTimeline({ capacity: 3, now: () => 0 })
	const values = [
		0.1, 0.2, 0.3, 0.4, 0.5,
		0.6, 0.7, 0.8, 0.9, 1,
		0.9, 0.8, 0.7, 0.6, 0.5
	]
	const expected = Math.sqrt(
		values.reduce((sum, value) => sum + value * value, 0) / values.length)
	timeline.start(4)
	for (let index = 0; index < 3; index += 1) {
		timeline.accept({
			epoch: 4,
			sequence: index + 1,
			levels: values.slice(index * 5, index * 5 + 5)
		})
	}

	assert.equal(timeline.advance(300), true)
	const bar = recorded(timeline.snapshot(300)).at(-1)
	assert.ok(Math.abs(bar.level - expected) < 1e-6)
})

test('silence still settles a recorded zero-level time bar', async () => {
	const { createVoiceWaveformTimeline } = await loadTimeline()
	const timeline = createVoiceWaveformTimeline({ capacity: 3, now: () => 0 })
	timeline.start(8)

	assert.equal(timeline.advance(300), true)
	assert.deepEqual(recorded(timeline.snapshot(300)).map(bar => ({
		level: bar.level,
		recorded: bar.recorded
	})), [{ level: 0, recorded: true }])
})

test('moving progress is linear throughout the active 300ms cycle', async () => {
	const { createVoiceWaveformTimeline } = await loadTimeline()
	const timeline = createVoiceWaveformTimeline({ capacity: 3, now: () => 0 })
	timeline.start(1)

	assert.equal(timeline.snapshot(0).progress, 0)
	assert.equal(timeline.snapshot(150).progress, 0.5)
	assert.ok(timeline.snapshot(299).progress > 0.996)
	assert.ok(timeline.snapshot(299).progress < 1)
})

test('the pending slot keeps its id when it becomes the settled sound bar', async () => {
	const { createVoiceWaveformTimeline } = await loadTimeline()
	const timeline = createVoiceWaveformTimeline({ capacity: 3, now: () => 0 })
	timeline.start(2)
	const pendingId = timeline.snapshot(0).movingBars.at(-1).id
	timeline.accept({ epoch: 2, sequence: 1, levels: [0.75] })

	timeline.advance(300)
	const newest = timeline.snapshot(300).settledBars.at(-1)
	assert.equal(newest.id, pendingId)
	assert.equal(newest.recorded, true)
})

test('stale epochs plus duplicate and descending sequences are rejected', async () => {
	const { createVoiceWaveformTimeline } = await loadTimeline()
	const timeline = createVoiceWaveformTimeline({ capacity: 3, now: () => 0 })
	timeline.start(9)

	assert.equal(timeline.accept({ epoch: 8, sequence: 1, levels: [1] }), false)
	assert.equal(timeline.accept({ epoch: 9, sequence: 2, levels: [0.5] }), true)
	assert.equal(timeline.accept({ epoch: 9, sequence: 2, levels: [1] }), false)
	assert.equal(timeline.accept({ epoch: 9, sequence: 1, levels: [1] }), false)
})

test('a 950ms stall settles only one bar and restarts the boundary at 1250ms', async () => {
	const { createVoiceWaveformTimeline } = await loadTimeline()
	const timeline = createVoiceWaveformTimeline({ capacity: 4, now: () => 0 })
	timeline.start(5)

	assert.equal(timeline.advance(950), true)
	assert.equal(recorded(timeline.snapshot(950)).length, 1)
	assert.equal(timeline.advance(1249), false)
	assert.equal(timeline.advance(1250), true)
	assert.equal(recorded(timeline.snapshot(1250)).length, 2)
})

test('capacity changes preserve newest history, left-pad baselines, and keep one pending slot', async () => {
	const { createVoiceWaveformTimeline } = await loadTimeline()
	const timeline = createVoiceWaveformTimeline({ capacity: 3, now: () => 0 })
	timeline.start(6)
	for (let cycle = 1; cycle <= 3; cycle += 1) {
		timeline.accept({ epoch: 6, sequence: cycle, levels: [cycle / 10] })
		timeline.advance(cycle * 300)
	}
	const newestIds = timeline.snapshot(900).settledBars.slice(-2).map(bar => bar.id)
	const pendingId = timeline.snapshot(900).movingBars.at(-1).id

	assert.equal(timeline.setCapacity(2), 2)
	assert.deepEqual(timeline.snapshot(900).settledBars.map(bar => bar.id), newestIds)
	assert.equal(timeline.snapshot(900).movingBars.at(-1).id, pendingId)
	assert.equal(timeline.setCapacity(4), 4)
	const enlarged = timeline.snapshot(900)
	assert.equal(enlarged.settledBars.length, 4)
	assert.ok(enlarged.settledBars.slice(0, 2).every(bar => !bar.recorded))
	assert.deepEqual(enlarged.settledBars.slice(-2).map(bar => bar.id), newestIds)
	assert.equal(enlarged.movingBars.at(-1).id, pendingId)
})

test('stop, reset, and dispose clear visible timeline state safely', async () => {
	const { createVoiceWaveformTimeline } = await loadTimeline()
	const timeline = createVoiceWaveformTimeline({ capacity: 3, now: () => 0 })
	timeline.start(1)
	timeline.stop()
	assert.deepEqual(timeline.snapshot(0).movingBars, [])

	timeline.start(2)
	timeline.reset(3)
	assert.equal(timeline.snapshot(0).epoch, 3)
	assert.deepEqual(timeline.snapshot(0).settledBars, [])

	timeline.start(4)
	timeline.dispose()
	assert.deepEqual(timeline.snapshot(0).movingBars, [])
	assert.equal(timeline.start(5), false)
})

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const controllerModulePath = path.resolve(
	__dirname,
	'voice-waveform-android-controller.js')
const timelineModulePath = path.resolve(
	__dirname,
	'voice-waveform-timeline.js')
const presentationModulePath = path.resolve(
	__dirname,
	'voice-waveform-presentation.js')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadController() {
	const timelineUrl = sourceUrl(fs.readFileSync(timelineModulePath, 'utf8'))
	const presentationUrl = sourceUrl(fs.readFileSync(presentationModulePath, 'utf8')
		.replace('./voice-waveform-timeline.js', timelineUrl))
	const source = fs.readFileSync(controllerModulePath, 'utf8')
		.replace('./voice-waveform-timeline.js', timelineUrl)
		.replace('./voice-waveform-presentation.js', presentationUrl)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

async function loadTimeline() {
	return import(`${sourceUrl(fs.readFileSync(timelineModulePath, 'utf8'))}#${Date.now()}-${Math.random()}`)
}

function fakeClock() {
	let now = 0
	let sequence = 0
	const jobs = new Map()
	return {
		now: () => now,
		schedule(callback, delay) {
			const id = ++sequence
			jobs.set(id, { callback, delay })
			return id
		},
		cancel(id) { jobs.delete(id) },
		setNow(value) { now = value },
		runNext() {
			const entry = jobs.entries().next().value
			assert.ok(entry, 'a scheduled Android waveform tick must exist')
			const [id, job] = entry
			jobs.delete(id)
			job.callback()
			return job.delay
		},
		pending() { return jobs.size },
		nextDelay() { return jobs.values().next().value?.delay }
	}
}

function recorded(snapshot) {
	return snapshot.settledBars.filter(bar => bar.recorded)
}

test('Android publishes the initial baseline and exactly one bar per 300ms boundary', async () => {
	const { createAndroidVoiceWaveformController } = await loadController()
	const clock = fakeClock()
	const snapshots = []
	const controller = createAndroidVoiceWaveformController({
		capacity: 4,
		now: clock.now,
		schedule: clock.schedule,
		cancel: clock.cancel,
		onSnapshot: snapshot => snapshots.push(snapshot)
	})

	assert.equal(controller.start(7), true)
	assert.equal(snapshots[0].settledBars.length, 4)
	assert.equal(snapshots[0].movingBars.length, 5)
	for (let sequence = 1; sequence <= 3; sequence += 1) {
		controller.accept({
			epoch: 7,
			sequence,
			levels: [0.4, 0.4, 0.4, 0.4, 0.4]
		})
	}
	clock.setNow(299)
	clock.runNext()
	assert.equal(recorded(controller.snapshot()).length, 0)
	assert.equal(clock.nextDelay(), 1)

	clock.setNow(300)
	clock.runNext()
	assert.equal(recorded(controller.snapshot()).length, 1)
	controller.accept({ epoch: 7, sequence: 4, levels: [0.8, 0.8, 0.8, 0.8, 0.8] })
	clock.setNow(600)
	clock.runNext()
	assert.equal(recorded(controller.snapshot()).length, 2)
})

test('Android delegates the latest-fifteen RMS aggregation to the shared timeline', async () => {
	const { createAndroidVoiceWaveformController } = await loadController()
	const clock = fakeClock()
	const controller = createAndroidVoiceWaveformController({
		capacity: 3,
		now: clock.now,
		schedule: clock.schedule,
		cancel: clock.cancel
	})
	const values = [
		0.1, 0.2, 0.3, 0.4, 0.5,
		0.6, 0.7, 0.8, 0.9, 1,
		0.9, 0.8, 0.7, 0.6, 0.5
	]
	const expected = Math.sqrt(
		values.reduce((sum, value) => sum + value * value, 0) / values.length)

	controller.start(2)
	controller.accept({ epoch: 2, sequence: 1, levels: [1, 1, 1, 1, 1] })
	for (let index = 0; index < 3; index += 1) {
		controller.accept({
			epoch: 2,
			sequence: index + 2,
			levels: values.slice(index * 5, index * 5 + 5)
		})
	}
	clock.setNow(300)
	clock.runNext()

	assert.ok(Math.abs(recorded(controller.snapshot()).at(-1).level - expected) < 1e-6)
})

test('Android presentation uses the shared 2-to-20px neutral-gray mapping', async () => {
	const { presentAndroidVoiceWaveformBar } = await loadController()

	assert.deepEqual(presentAndroidVoiceWaveformBar({ id: 1, level: 0, recorded: false }), {
		id: 1,
		level: 0,
		recorded: false,
		height: 2,
		color: 'rgba(174,185,179,0.24)'
	})
	assert.deepEqual(presentAndroidVoiceWaveformBar({ id: 2, level: 1, recorded: true }), {
		id: 2,
		level: 1,
		recorded: true,
		height: 20,
		color: 'rgba(205,211,208,0.88)'
	})
})

test('Android advances silence and never catches up a delayed backlog', async () => {
	const { createAndroidVoiceWaveformController } = await loadController()
	const clock = fakeClock()
	const controller = createAndroidVoiceWaveformController({
		capacity: 3,
		now: clock.now,
		schedule: clock.schedule,
		cancel: clock.cancel
	})

	controller.start(4)
	clock.setNow(950)
	clock.runNext()
	assert.equal(recorded(controller.snapshot()).length, 1)
	assert.equal(recorded(controller.snapshot())[0].level, 0)
	assert.equal(clock.nextDelay(), 300)

	clock.setNow(951)
	clock.runNext()
	assert.equal(recorded(controller.snapshot()).length, 1)
	assert.equal(clock.nextDelay(), 299)
	clock.setNow(1250)
	clock.runNext()
	assert.equal(recorded(controller.snapshot()).length, 2)
})

test('Android rejects stale packets and clears timer plus timeline between sessions', async () => {
	const { createAndroidVoiceWaveformController } = await loadController()
	const clock = fakeClock()
	const controller = createAndroidVoiceWaveformController({
		now: clock.now,
		schedule: clock.schedule,
		cancel: clock.cancel
	})

	controller.start(5)
	assert.equal(controller.accept({ epoch: 4, sequence: 1, levels: [1] }), false)
	assert.equal(controller.accept({ epoch: 5, sequence: 1, levels: [-1, 0.5, 2] }), true)
	assert.equal(controller.accept({ epoch: 5, sequence: 1, levels: [1] }), false)
	clock.setNow(300)
	clock.runNext()
	assert.equal(recorded(controller.snapshot()).length, 1)

	controller.stop()
	assert.equal(clock.pending(), 0)
	assert.deepEqual(controller.snapshot().movingBars, [])
	controller.start(6)
	assert.equal(recorded(controller.snapshot()).length, 0)
	controller.dispose()
	assert.equal(clock.pending(), 0)
	assert.deepEqual(controller.snapshot().settledBars, [])
})

test('Android capacity follows shared 5.5px pitch and remains capped at 192', async () => {
	const {
		createAndroidVoiceWaveformController,
		resolveAndroidVoiceWaveformCapacity
	} = await loadController()
	const clock = fakeClock()
	const controller = createAndroidVoiceWaveformController({
		capacity: 192,
		now: clock.now,
		schedule: clock.schedule,
		cancel: clock.cancel
	})

	assert.equal(resolveAndroidVoiceWaveformCapacity(24), 4)
	assert.equal(resolveAndroidVoiceWaveformCapacity(103), 18)
	assert.equal(resolveAndroidVoiceWaveformCapacity(5000), 192)
	controller.start(8)
	assert.equal(controller.setCapacity(4), 4)
	assert.equal(controller.snapshot().settledBars.length, 4)
	assert.equal(controller.snapshot().movingBars.length, 5)
})

test('Android callback failure is fail-open and reports bounded per-session phases', async () => {
	const { createAndroidVoiceWaveformController } = await loadController()
	const clock = fakeClock()
	const phases = []
	const controller = createAndroidVoiceWaveformController({
		now: clock.now,
		schedule: clock.schedule,
		cancel: clock.cancel,
		onSnapshot: snapshot => {
			if (recorded(snapshot).length) throw new Error('synthetic visual failure')
		},
		report: phase => phases.push(phase)
	})

	controller.start(9)
	controller.accept({ epoch: 9, sequence: 1, levels: [0.7] })
	controller.accept({ epoch: 9, sequence: 2, levels: [0.8] })
	clock.setNow(300)
	assert.doesNotThrow(() => clock.runNext())
	assert.deepEqual(phases, [
		'ANDROID_WAVEFORM_STARTED',
		'ANDROID_WAVEFORM_PACKET_ACCEPTED',
		'ANDROID_WAVEFORM_FAILED'
	])
	assert.equal(clock.pending(), 0)
})

test('Android controller and the shared timeline produce identical bar values and order', async () => {
	const { createAndroidVoiceWaveformController } = await loadController()
	const { createVoiceWaveformTimeline } = await loadTimeline()
	const clock = fakeClock()
	const android = createAndroidVoiceWaveformController({
		capacity: 4,
		now: clock.now,
		schedule: clock.schedule,
		cancel: clock.cancel
	})
	const shared = createVoiceWaveformTimeline({ capacity: 4, now: clock.now })
	android.start(3)
	shared.start(3)

	for (let sequence = 1; sequence <= 3; sequence += 1) {
		const packet = {
			epoch: 3,
			sequence,
			levels: [sequence / 10, 0.4, 0.6, 0.8, 1]
		}
		android.accept(packet)
		shared.accept(packet)
	}
	clock.setNow(300)
	clock.runNext()
	shared.advance(300)

	assert.deepEqual(
		android.snapshot().settledBars.map(({ level, recorded }) => ({ level, recorded })),
		shared.snapshot(300).settledBars.map(({ level, recorded }) => ({ level, recorded })))
})

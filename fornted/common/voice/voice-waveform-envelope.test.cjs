const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'voice-waveform-envelope.js'), 'utf8')
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function pcm16Frame(samples) {
	const buffer = new ArrayBuffer(samples.length * 2)
	const view = new DataView(buffer)
	for (let index = 0; index < samples.length; index += 1) {
		view.setInt16(index * 2, samples[index], true)
	}
	return buffer
}

function constantFrame(sample, count = 1600) {
	return pcm16Frame(new Array(count).fill(sample))
}

test('a regular silent frame produces five silent twenty millisecond levels', async () => {
	const { createVoiceWaveformAnalyzer } = await loadModule()
	const analyzer = createVoiceWaveformAnalyzer()

	assert.deepEqual(analyzer.analyze(constantFrame(0)), [0, 0, 0, 0, 0])
})

test('positive and negative PCM amplitudes produce the same bounded envelope', async () => {
	const { createVoiceWaveformAnalyzer } = await loadModule()
	const positive = createVoiceWaveformAnalyzer().analyze(constantFrame(12000))
	const negative = createVoiceWaveformAnalyzer().analyze(constantFrame(-12000))

	assert.equal(positive.length, 5)
	assert.deepEqual(positive, negative)
	assert.ok(positive.every(level => level > 0 && level <= 1))
})

test('a legal recorder tail emits only the windows represented by its samples', async () => {
	const { createVoiceWaveformAnalyzer } = await loadModule()
	const analyzer = createVoiceWaveformAnalyzer()

	assert.equal(analyzer.analyze(constantFrame(8000, 321)).length, 2)
	assert.equal(analyzer.analyze(constantFrame(8000, 80)).length, 1)
})

test('invalid PCM input fails open without changing the analyzer state', async () => {
	const { createVoiceWaveformAnalyzer } = await loadModule()
	const analyzer = createVoiceWaveformAnalyzer()
	const first = analyzer.analyze(constantFrame(10000))
	const odd = new ArrayBuffer(3)

	assert.deepEqual(analyzer.analyze(null), [])
	assert.deepEqual(analyzer.analyze(new Uint8Array(4)), [])
	assert.deepEqual(analyzer.analyze(new ArrayBuffer(0)), [])
	assert.deepEqual(analyzer.analyze(odd), [])

	analyzer.reset()
	assert.deepEqual(analyzer.analyze(constantFrame(10000)), first)
})

test('attack reacts faster than release and reset restores deterministic output', async () => {
	const { createVoiceWaveformAnalyzer } = await loadModule()
	const analyzer = createVoiceWaveformAnalyzer()
	const loud = analyzer.analyze(constantFrame(20000))
	const release = analyzer.analyze(constantFrame(0))

	assert.ok(loud[0] > 0)
	assert.ok(loud[4] > loud[0])
	assert.ok(release[0] < loud[4])
	assert.ok(release[0] > 0)
	assert.ok(release[4] < release[0])

	analyzer.reset()
	assert.deepEqual(analyzer.analyze(constantFrame(20000)), loud)
})

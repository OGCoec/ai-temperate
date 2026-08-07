const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'pcm16-buffer.js'), 'utf8')
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

test('encodes clipped signed PCM16 samples in little-endian order', async () => {
	const pcm = await loadModule()
	const buffer = pcm.float32ToPcm16([-2, -1, 0, 1, 2])
	const view = new DataView(buffer)

	assert.equal(view.getInt16(0, true), -32768)
	assert.equal(view.getInt16(2, true), -32768)
	assert.equal(view.getInt16(4, true), 0)
	assert.equal(view.getInt16(6, true), 32767)
	assert.equal(view.getInt16(8, true), 32767)
})

test('resamples device audio to approximately sixteen thousand samples per second', async () => {
	const pcm = await loadModule()
	const input = new Float32Array(48000)
	const output = pcm.resampleLinearMono(input, 48000)

	assert.equal(output.length, 16000)
})

test('batches one hundred millisecond frames and flushes only the remainder', async () => {
	const pcm = await loadModule()
	const batcher = new pcm.Pcm16FrameBuffer()
	const frames = batcher.push(new Float32Array(1700))

	assert.equal(frames.length, 1)
	assert.equal(frames[0].byteLength, 3200)
	assert.equal(batcher.flush().byteLength, 200)
	assert.equal(batcher.flush(), null)
})

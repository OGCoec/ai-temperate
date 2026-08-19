const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadStopCoordinator() {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-conversation-stop.js'),
		'utf8'
	)
	const url = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(`${url}#${Date.now()}-${Math.random()}`)
}

test('starts the cancellation request synchronously before closing transport', async () => {
	const { startDirectResponseCancellation } = await loadStopCoordinator()
	const order = []
	let resolveCancellation
	const cancellation = new Promise(resolve => { resolveCancellation = resolve })

	const result = startDirectResponseCancellation({
		requestCancellation() {
			order.push('request')
			return cancellation
		},
		closeTransport() { order.push('close') }
	})

	assert.deepEqual(order, ['request', 'close'])
	resolveCancellation('confirmed')
	assert.equal(await result, 'confirmed')
})

test('closes transport even when cancellation throws synchronously', async () => {
	const { startDirectResponseCancellation } = await loadStopCoordinator()
	let closed = false
	const result = startDirectResponseCancellation({
		requestCancellation() { throw new Error('cancel failed') },
		closeTransport() { closed = true }
	})

	assert.equal(closed, true)
	await assert.rejects(result, /cancel failed/)
})

test('does not wait for cancellation rejection before closing transport', async () => {
	const { startDirectResponseCancellation } = await loadStopCoordinator()
	let rejectCancellation
	let closed = false
	const cancellation = new Promise((_resolve, reject) => {
		rejectCancellation = reject
	})
	const result = startDirectResponseCancellation({
		requestCancellation: () => cancellation,
		closeTransport() { closed = true }
	})

	assert.equal(closed, true)
	rejectCancellation(new Error('not confirmed'))
	await assert.rejects(result, /not confirmed/)
})

test('keeps the cancellation request active when transport close throws', async () => {
	const { startDirectResponseCancellation } = await loadStopCoordinator()
	let requested = false
	const result = startDirectResponseCancellation({
		requestCancellation() {
			requested = true
			return Promise.resolve('confirmed')
		},
		closeTransport() { throw new Error('close failed') }
	})

	assert.equal(requested, true)
	await assert.rejects(result, /close failed/)
})

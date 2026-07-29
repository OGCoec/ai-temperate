const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function loadClient() {
	let source = fs.readFileSync(
		path.join(__dirname, 'mail-inspection-sse-client.js'),
		'utf8')
	source = source
		.replace(/^import[\s\S]*?from '.\/admin-http\.js'\r?\n/m, '')
		.replace(/^import[\s\S]*?from '.\/mail-inspection-sse-parser\.js'\r?\n/m, '')
		.replace(/^import[\s\S]*?from '.\/mail-inspection-sse-h5\.js'\r?\n/m, '')
		.replace(/^import[\s\S]*?from '.\/mail-inspection-sse-app\.js'\r?\n/m, '')
		.replace('export const MAIL_INSPECTION_CONNECTION_STATES', 'const MAIL_INSPECTION_CONNECTION_STATES')
		.replace('export function createMailInspectionSseClient', 'function createMailInspectionSseClient')
	source = [
		'const prepareAdminEventStream = async () => ({})',
		'const createMailInspectionSseParser = () => ({})',
		'const openMailInspectionSseH5 = () => ({})',
		'const openMailInspectionSseApp = () => ({})',
		source,
		'module.exports = { createMailInspectionSseClient, MAIL_INSPECTION_CONNECTION_STATES }'
	].join('\n')
	const module = { exports: {} }
	new Function('module', 'exports', source)(module, module.exports)
	return module.exports
}

test('uses the fixed 1,2,5,10,30 second reconnect sequence then stops', async () => {
	const { createMailInspectionSseClient } = loadClient()
	const scheduled = []
	const states = []
	let handlers
	const client = createMailInspectionSseClient({
		prepare: async () => ({ url: 'https://example.test/events', headers: {} }),
		openTransport(request, nextHandlers) {
			handlers = nextHandlers
			return { completed: new Promise(() => {}), close() {} }
		},
		setTimer(callback, delay) {
			scheduled.push({ callback, delay })
			return scheduled.length
		},
		clearTimer() {}
	})
	client.connect({
		path: '/events',
		lastRevision: () => 9,
		onState: value => states.push(value)
	})
	await Promise.resolve()
	for (let index = 0; index < 5; index += 1) {
		handlers.onError(Object.assign(new Error('offline'), { code: 'NETWORK_ERROR' }))
		const timer = scheduled.find(item => [1000, 2000, 5000, 10000, 30000][index] === item.delay)
		assert.ok(timer)
		timer.callback()
		await Promise.resolve()
	}
	handlers.onError(Object.assign(new Error('offline'), { code: 'NETWORK_ERROR' }))
	assert.equal(states.at(-1), 'FAILED')
})

test('marks a 404 handshake as expired and never starts polling', async () => {
	const { createMailInspectionSseClient } = loadClient()
	const states = []
	const errors = []
	const client = createMailInspectionSseClient({
		prepare: async () => {
			const error = new Error('missing')
			error.code = 'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND'
			error.statusCode = 404
			throw error
		},
		setTimer() {
			throw new Error('404 must not schedule reconnect')
		},
		clearTimer() {}
	})
	client.connect({
		path: '/events',
		lastRevision: () => 0,
		onState: value => states.push(value),
		onError: error => errors.push(error)
	})
	await Promise.resolve()
	await Promise.resolve()
	assert.equal(states.at(-1), 'EXPIRED')
	assert.equal(errors.length, 1)
})

test('lets business cleanup close a missing job before publishing EXPIRED', async () => {
	const { createMailInspectionSseClient } = loadClient()
	const states = []
	let connection
	const client = createMailInspectionSseClient({
		prepare: async () => {
			const error = new Error('missing')
			error.code = 'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND'
			error.statusCode = 404
			throw error
		},
		setTimer() {
			throw new Error('missing job must not schedule reconnect')
		},
		clearTimer() {}
	})
	connection = client.connect({
		path: '/events',
		lastRevision: () => 0,
		onState: value => states.push(value),
		onError: () => connection.close()
	})
	await Promise.resolve()
	await Promise.resolve()
	assert.equal(states.includes('EXPIRED'), false)
})

test('deduplicates Android onError, onClosed and completed rejection for one missing job', async () => {
	const { createMailInspectionSseClient } = loadClient()
	const states = []
	const errors = []
	const scheduledDelays = []
	let handlers
	let rejectCompleted
	const client = createMailInspectionSseClient({
		prepare: async () => ({ url: 'https://example.test/events', headers: {} }),
		openTransport(_request, nextHandlers) {
			handlers = nextHandlers
			return {
				completed: new Promise((_resolve, reject) => {
					rejectCompleted = reject
				}),
				close() {}
			}
		},
		setTimer(_callback, delay) {
			scheduledDelays.push(delay)
			return scheduledDelays.length
		},
		clearTimer() {}
	})
	client.connect({
		path: '/events',
		lastRevision: () => 0,
		onState: value => states.push(value),
		onError: error => errors.push(error)
	})
	await Promise.resolve()
	const error = Object.assign(new Error('missing'), {
		code: 'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND',
		statusCode: 404
	})
	handlers.onError(error)
	handlers.onClosed()
	rejectCompleted(error)
	await Promise.resolve()
	assert.equal(errors.length, 1)
	assert.equal(states.filter(value => value === 'EXPIRED').length, 1)
	assert.deepEqual(
		scheduledDelays.filter(delay => delay !== 45000),
		[])
})

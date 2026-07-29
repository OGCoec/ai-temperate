const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	let source = fs.readFileSync(
		path.join(__dirname, 'mail-inspection-job-controller.js'),
		'utf8')
	source = source.replace(
		"import { analyzeMailboxCredentialText } from './mail-inspection-credential-parser.js'",
		"const analyzeMailboxCredentialText = text => ({ valid: Boolean(text), credentialLines: text ? text.split('\\n') : [], errors: [], lineCount: text ? 1 : 0, byteCount: text.length })")
	source = source.replace(
		"import { recoverRetryCredentialLines } from './mail-inspection-presenter.js'",
		"const recoverRetryCredentialLines = (results, lines) => results.filter(item => item.retryable && item.retryExhausted).map(item => lines[item.lineNumber - 1])")
	source = source.replace(
		"import { createMailInspectionClientRequestId } from './mail-inspection-idempotency.js'",
		"const createMailInspectionClientRequestId = () => '550e8400-e29b-41d4-a716-446655440000'")
	source = source.replace(
		/import \{[\s\S]*?\} from '.\/mail-inspection-sse-client\.js'/,
		`const createMailInspectionSseClient = () => { throw new Error('streamClient required') }
		const MAIL_INSPECTION_CONNECTION_STATES = {
			CONNECTING: 'CONNECTING', SYNCING: 'SYNCING', STREAMING: 'STREAMING',
			RECONNECTING: 'RECONNECTING', COMPLETED: 'COMPLETED',
			FAILED: 'FAILED', EXPIRED: 'EXPIRED'
		}`)
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

function memoryStore(initial = {}) {
	const contexts = new Map(Object.entries(initial))
	return {
		contexts,
		load: type => contexts.get(type) || {},
		save: (type, value) => {
			const safe = {
				jobId: value.jobId,
				lastRevision: value.lastRevision
			}
			contexts.set(type, safe)
			return safe
		},
		clear: type => contexts.delete(type)
	}
}

function fakeStreamClient() {
	const connections = []
	return {
		connections,
		connect(configuration) {
			const connection = {
				configuration,
				closed: false,
				close() {
					connection.closed = true
				}
			}
			connections.push(connection)
			return connection
		}
	}
}

function api() {
	return {
		async createJob(type) {
			return {
				jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
				inspectionType: type,
				status: 'QUEUED',
				requestedCount: 1,
				acceptedCount: 1,
				duplicateCount: 0,
				invalidCount: 0
			}
		},
		eventsPath(jobId) {
			return `/api/admin/mail-inspection/jobs/${jobId}/events`
		}
	}
}

test('creation opens SSE and never issues a periodic Job GET', async () => {
	const { createMailInspectionJobController } = await loadModule()
	const streamClient = fakeStreamClient()
	const client = api()
	client.getJob = async () => {
		throw new Error('periodic GET must not be called')
	}
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: client,
		store: memoryStore(),
		streamClient
	})

	await controller.submit('credential-line')

	assert.equal(streamClient.connections.length, 1)
	assert.equal(
		streamClient.connections[0].configuration.path,
		'/api/admin/mail-inspection/jobs/AZ9nEjRWeJCrze8SNFZ4kA/events')
	assert.equal(controller.snapshot().jobId, 'AZ9nEjRWeJCrze8SNFZ4kA')
})

test('snapshot batches and sync-complete atomically advance the persisted revision', async () => {
	const { createMailInspectionJobController } = await loadModule()
	const streamClient = fakeStreamClient()
	const store = memoryStore()
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: api(),
		store,
		streamClient
	})
	await controller.submit('credential-line')
	const handlers = streamClient.connections[0].configuration

	handlers.onEvent({
		type: 'snapshot-meta',
		id: '',
		data: {
			revision: 7,
			data: {
				status: 'RUNNING',
				requestedCount: 1,
				processedCount: 0,
				runningCount: 1,
				queuedCount: 0
			}
		}
	})
	assert.equal(store.load('OPENAI_STATUS').lastRevision, 0)
	handlers.onEvent({
		type: 'result-batch',
		id: '',
		data: {
			revision: 7,
			data: { results: [{ lineNumber: 1, status: 'FOUND' }] }
		}
	})
	handlers.onEvent({
		type: 'sync-complete',
		id: '7',
		data: { revision: 7, data: { resultCount: 1 } }
	})

	assert.equal(controller.snapshot().lastRevision, 7)
	assert.equal(controller.snapshot().results.length, 1)
	assert.equal(store.load('OPENAI_STATUS').lastRevision, 7)
})

test('restore reconnects with the stored Last-Event-ID and pause cancels the stream', async () => {
	const { createMailInspectionJobController } = await loadModule()
	const streamClient = fakeStreamClient()
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: api(),
		store: memoryStore({
			OPENAI_STATUS: {
				jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
				lastRevision: 19
			}
		}),
		streamClient
	})

	await controller.restore()
	const active = streamClient.connections[0]
	assert.equal(active.configuration.lastRevision(), 19)
	controller.pause()
	assert.equal(active.closed, true)
})

test('terminal event closes SSE and never persists a fake streaming state', async () => {
	const { createMailInspectionJobController } = await loadModule()
	const streamClient = fakeStreamClient()
	const store = memoryStore()
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: api(),
		store,
		streamClient
	})
	await controller.submit('credential-line')
	const active = streamClient.connections[0]
	active.configuration.onEvent({
		type: 'terminal',
		id: '9',
		data: {
			revision: 9,
			data: { status: 'COMPLETED', processedCount: 1 }
		}
	})

	assert.equal(controller.snapshot().state, 'COMPLETED')
	assert.equal(controller.snapshot().connectionState, 'COMPLETED')
	assert.equal(active.closed, true)
	assert.deepEqual(store.load('OPENAI_STATUS'), {
		jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
		lastRevision: 9
	})
})

test('one uncertain creation retry reuses the exact client request ID', async () => {
	const { createMailInspectionJobController } = await loadModule()
	const ids = []
	let attempt = 0
	const client = api()
	client.createJob = async (_type, _lines, _concurrency, requestId) => {
		ids.push(requestId)
		attempt += 1
		if (attempt === 1) {
			const error = new Error('offline')
			error.code = 'NETWORK_ERROR'
			throw error
		}
		return {
			jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
			inspectionType: 'OPENAI_STATUS',
			status: 'QUEUED'
		}
	}
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: client,
		store: memoryStore(),
		streamClient: fakeStreamClient(),
		wait: async () => {}
	})

	await controller.submit('credential-line')
	assert.equal(ids.length, 2)
	assert.equal(ids[0], ids[1])
})

test('controller source contains no polling fallback contract', () => {
	const source = fs.readFileSync(
		path.join(__dirname, 'mail-inspection-job-controller.js'),
		'utf8')
	assert.doesNotMatch(
		source,
		/pollNow|pollAfterMillis|pollingFailures|NETWORK_BACKOFF|schedule\(/)
	assert.match(source, /实时连接已中断；不会回退到 HTTP 轮询/)
})

test('missing restored job clears only its type and returns to IDLE', async () => {
	const { createMailInspectionJobController } = await loadModule()
	const streamClient = fakeStreamClient()
	const store = memoryStore({
		OPENAI_STATUS: {
			jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
			lastRevision: 19
		},
		KIRO_STATUS: {
			jobId: 'AZ9nEjRWeJCrze8SNFZ4kB',
			lastRevision: 8
		}
	})
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: api(),
		store,
		streamClient
	})

	await controller.restore()
	const active = streamClient.connections[0]
	active.configuration.onError(Object.assign(new Error(
		'原检查任务已过期或不存在，请重新创建检查任务。'), {
		code: 'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND',
		statusCode: 404
	}))

	const state = controller.snapshot()
	assert.equal(active.closed, true)
	assert.equal(state.state, 'IDLE')
	assert.equal(state.jobId, '')
	assert.equal(state.lastRevision, 0)
	assert.equal(state.draftText, '')
	assert.deepEqual(state.credentialLines, [])
	assert.deepEqual(state.results, [])
	assert.equal(state.clientRequestId, '')
	assert.equal(state.businessConcurrency, 4)
	assert.equal(
		state.message,
		'原检查任务已过期或不存在，请重新创建检查任务。')
	assert.deepEqual(store.load('OPENAI_STATUS'), {})
	assert.equal(
		store.load('KIRO_STATUS').jobId,
		'AZ9nEjRWeJCrze8SNFZ4kB')

	const reopenedStreamClient = fakeStreamClient()
	const reopenedController = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: api(),
		store,
		streamClient: reopenedStreamClient
	})
	await reopenedController.restore()
	assert.equal(reopenedController.snapshot().state, 'IDLE')
	assert.equal(reopenedStreamClient.connections.length, 0)
})

test('missing active job clears credentials but preserves selected concurrency', async () => {
	const { createMailInspectionJobController } = await loadModule()
	const streamClient = fakeStreamClient()
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: api(),
		store: memoryStore(),
		streamClient
	})
	controller.setBusinessConcurrency(8)
	await controller.submit('credential-line')

	streamClient.connections[0].configuration.onError(Object.assign(
		new Error('missing'), {
			code: 'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND',
			statusCode: 404
		}))

	const state = controller.snapshot()
	assert.equal(state.state, 'IDLE')
	assert.equal(state.businessConcurrency, 8)
	assert.equal(state.draftText, '')
	assert.deepEqual(state.credentialLines, [])
	assert.equal(state.clientRequestId, '')
})

test('late 404 from an old connection cannot clear a newly tracked job', async () => {
	const { createMailInspectionJobController } = await loadModule()
	const streamClient = fakeStreamClient()
	const store = memoryStore({
		OPENAI_STATUS: {
			jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
			lastRevision: 2
		}
	})
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: api(),
		store,
		streamClient
	})
	await controller.restore()
	const oldConnection = streamClient.connections[0]
	await controller.trackJob({
		jobId: 'AZ9nEjRWeJCrze8SNFZ4kB',
		inspectionType: 'OPENAI_STATUS',
		status: 'RUNNING',
		revision: 1,
		results: []
	})

	oldConnection.configuration.onError(Object.assign(new Error('missing'), {
		code: 'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND',
		statusCode: 404
	}))

	assert.equal(
		controller.snapshot().jobId,
		'AZ9nEjRWeJCrze8SNFZ4kB')
	assert.equal(
		store.load('OPENAI_STATUS').jobId,
		'AZ9nEjRWeJCrze8SNFZ4kB')
})

test('network and Redis 503 failures keep the current job context', async () => {
	const { createMailInspectionJobController } = await loadModule()
	for (const failure of [
		Object.assign(new Error('offline'), {
			code: 'NETWORK_ERROR',
			statusCode: 0
		}),
		Object.assign(new Error('redis unavailable'), {
			code: 'ADMIN_MAIL_INSPECTION_UNAVAILABLE',
			statusCode: 503
		})
	]) {
		const streamClient = fakeStreamClient()
		const store = memoryStore({
			OPENAI_STATUS: {
				jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
				lastRevision: 5
			}
		})
		const controller = createMailInspectionJobController({
			inspectionType: 'OPENAI_STATUS',
			api: api(),
			store,
			streamClient
		})
		await controller.restore()

		streamClient.connections[0].configuration.onError(failure)

		assert.equal(
			controller.snapshot().jobId,
			'AZ9nEjRWeJCrze8SNFZ4kA')
		assert.equal(
			store.load('OPENAI_STATUS').jobId,
			'AZ9nEjRWeJCrze8SNFZ4kA')
	}
})

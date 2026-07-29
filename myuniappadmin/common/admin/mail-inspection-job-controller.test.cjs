const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	let source = fs.readFileSync(path.join(__dirname, 'mail-inspection-job-controller.js'), 'utf8')
	source = source.replace(
		"import { analyzeMailboxCredentialText } from './mail-inspection-credential-parser.js'",
		"const analyzeMailboxCredentialText = text => ({ valid: true, credentialLines: text.split('\\n'), errors: [], lineCount: 1, byteCount: text.length })")
	source = source.replace(
		"import { recoverRetryCredentialLines } from './mail-inspection-presenter.js'",
		"const recoverRetryCredentialLines = (results, lines) => results.filter(item => item.retryable && item.retryExhausted).map(item => lines[item.lineNumber - 1])")
	source = source.replace(
		"import { createMailInspectionClientRequestId } from './mail-inspection-idempotency.js'",
		"const createMailInspectionClientRequestId = () => '550e8400-e29b-41d4-a716-446655440000'")
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

function memoryStore() {
	const contexts = new Map()
	return {
		load: type => contexts.get(type) || {},
		save: (type, value) => contexts.set(type, { ...value }),
		clear: type => contexts.delete(type)
	}
}

test('create stores one submitted array and polls the returned public job ID', async () => {
	const { createMailInspectionJobController } = await loadModule()
	const calls = []
	const api = {
		async createJob(type, lines, businessConcurrency, requestId) {
			calls.push(['create', type, lines, businessConcurrency, requestId])
			return { jobId: 'AAAAAAAAAAE', inspectionType: type, status: 'QUEUED', pollAfterMillis: 2000 }
		},
		async getJob(jobId) {
			calls.push(['get', jobId])
			return {
				jobId,
				inspectionType: 'OPENAI_STATUS',
				status: 'COMPLETED',
				requestedCount: 1,
				processedCount: 1,
				runningCount: 0,
				queuedCount: 0,
				summary: { counts: {} },
				results: []
			}
		}
	}
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api,
		store: memoryStore(),
		setTimer: () => 1,
		clearTimer: () => {}
	})

	await controller.submit('credential-line')
	assert.deepEqual(calls, [
		['create', 'OPENAI_STATUS', ['credential-line'], 4, '550e8400-e29b-41d4-a716-446655440000'],
		['get', 'AAAAAAAAAAE']
	])
	assert.equal(controller.snapshot().state, 'COMPLETED')
})

test('polling network interruption retains the same job and never creates another', async () => {
	const { createMailInspectionJobController } = await loadModule()
	let creates = 0
	const timers = []
	const store = memoryStore()
	store.save('OPENAI_STATUS', {
		draftText: 'credential-line',
		credentialLines: ['credential-line'],
		jobId: 'AAAAAAAAAAE',
		jobStatus: 'RUNNING'
	})
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: {
			createJob: async () => { creates += 1 },
			getJob: async () => {
				const error = new Error('offline')
				error.code = 'NETWORK_ERROR'
				throw error
			}
		},
		store,
		setTimer: (callback, delay) => {
			timers.push({ callback, delay })
			return timers.length
		},
		clearTimer: () => {}
	})

	await controller.restore()
	assert.equal(controller.snapshot().state, 'POLLING_INTERRUPTED')
	assert.equal(controller.snapshot().jobId, 'AAAAAAAAAAE')
	assert.equal(creates, 0)
	assert.equal(timers[0].delay, 2000)
})

test('creation network retry reuses exactly the same client request ID', async () => {
	const { createMailInspectionJobController } = await loadModule()
	const requestIds = []
	let attempts = 0
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: {
			createJob: async (_type, _lines, _concurrency, requestId) => {
				requestIds.push(requestId)
				attempts += 1
				if (attempts === 1) {
					const error = new Error('offline')
					error.code = 'NETWORK_ERROR'
					throw error
				}
				return {
					jobId: 'AAAAAAAAAAE',
					inspectionType: 'OPENAI_STATUS',
					status: 'COMPLETED',
					pollAfterMillis: 2000
				}
			},
			getJob: async jobId => ({
				jobId,
				inspectionType: 'OPENAI_STATUS',
				status: 'COMPLETED',
				requestedCount: 1,
				processedCount: 1,
				runningCount: 0,
				queuedCount: 0,
				results: []
			})
		},
		store: memoryStore(),
		wait: async () => {},
		setTimer: () => 1,
		clearTimer: () => {}
	})

	await controller.submit('credential-line')

	assert.equal(requestIds.length, 2)
	assert.equal(requestIds[0], requestIds[1])
	assert.equal(controller.snapshot().jobId, 'AAAAAAAAAAE')
})

test('explicit incomplete submission remains resumable with the original request ID', async () => {
	const { createMailInspectionJobController } = await loadModule()
	const requestIds = []
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: {
			createJob: async (_type, _lines, _concurrency, requestId) => {
				requestIds.push(requestId)
				const error = new Error('部分凭证尚未持久化')
				error.code = 'ADMIN_MAIL_INSPECTION_SUBMISSION_INCOMPLETE'
				error.statusCode = 503
				throw error
			}
		},
		store: memoryStore(),
		wait: async () => {},
		setTimer: () => 1,
		clearTimer: () => {}
	})

	await controller.submit('credential-line')

	assert.equal(requestIds.length, 1)
	assert.equal(
		controller.snapshot().state,
		'AWAITING_CLIENT_RESUBMISSION')
	assert.equal(
		controller.snapshot().clientRequestId,
		'550e8400-e29b-41d4-a716-446655440000')
})

test('explicit service unavailable does not retry or become submission unknown', async () => {
	const { createMailInspectionJobController } = await loadModule()
	let attempts = 0
	const store = memoryStore()
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: {
			createJob: async () => {
				attempts += 1
				const error = new Error('service unavailable')
				error.code = 'ADMIN_MAIL_INSPECTION_UNAVAILABLE'
				error.statusCode = 503
				throw error
			}
		},
		store,
		wait: async () => {},
		setTimer: () => 1,
		clearTimer: () => {}
	})

	await controller.submit('credential-line')

	assert.equal(attempts, 1)
	assert.equal(controller.snapshot().state, 'SERVICE_UNAVAILABLE')
	assert.equal(
		controller.snapshot().message,
		'该类型邮箱检查正在恢复或恢复失败，当前未接收新任务。原提交尚未创建，无需重复提交凭证。')
	assert.equal(
		controller.snapshot().clientRequestId,
		'550e8400-e29b-41d4-a716-446655440000')
	assert.deepEqual(controller.snapshot().credentialLines, ['credential-line'])

	const restored = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: {},
		store,
		setTimer: () => 1,
		clearTimer: () => {}
	})
	await restored.restore({ allowNetwork: false })
	assert.equal(restored.snapshot().state, 'SERVICE_UNAVAILABLE')
	assert.equal(
		restored.snapshot().message,
		'该类型邮箱检查正在恢复或恢复失败，当前未接收新任务。原提交尚未创建，无需重复提交凭证。')
})

test('local restore preserves the draft without polling when the API contract is unavailable', async () => {
	const { createMailInspectionJobController } = await loadModule()
	let requests = 0
	const store = memoryStore()
	store.save('OPENAI_STATUS', {
		draftText: 'credential-line',
		credentialLines: ['credential-line'],
		jobId: 'AAAAAAAAAAE',
		jobStatus: 'RUNNING',
		businessConcurrency: 16
	})
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: {
			getJob: async () => {
				requests += 1
				throw new Error('must not be called')
			}
		},
		store,
		setTimer: () => {
			throw new Error('polling must not start')
		},
		clearTimer: () => {}
	})

	const restored = await controller.restore({ allowNetwork: false })

	assert.equal(requests, 0)
	assert.equal(restored.draftText, 'credential-line')
	assert.equal(restored.businessConcurrency, 16)
	assert.equal(restored.jobId, 'AAAAAAAAAAE')
})

test('manual retry submits only retry-exhausted lines as a new user action', async () => {
	const { createMailInspectionJobController } = await loadModule()
	const submissions = []
	const store = memoryStore()
	store.save('OPENAI_STATUS', {
		credentialLines: ['permanent-line', 'retry-line'],
		draftText: 'permanent-line\nretry-line',
		jobId: 'AAAAAAAAAAE',
		jobStatus: 'COMPLETED'
	})
	const controller = createMailInspectionJobController({
		inspectionType: 'OPENAI_STATUS',
		api: {
			createJob: async (type, lines) => {
				submissions.push(lines)
				return { jobId: 'AAAAAAAAAAE', inspectionType: type, status: 'FAILED', pollAfterMillis: 2000 }
			},
			getJob: async jobId => ({
				jobId: 'AAAAAAAAAAE',
				inspectionType: 'OPENAI_STATUS',
				status: 'FAILED',
				requestedCount: 2,
				processedCount: 2,
				runningCount: 0,
				queuedCount: 0,
				summary: { counts: {} },
				results: jobId === 'AAAAAAAAAAE' && submissions.length === 0
					? [
						{ lineNumber: 1, retryable: false, retryExhausted: false },
						{ lineNumber: 2, retryable: true, retryExhausted: true }
					]
					: []
			})
		},
		store,
		setTimer: () => 1,
		clearTimer: () => {}
	})
	await controller.restore()
	await controller.retryExhausted()
	assert.deepEqual(submissions, [['retry-line']])
})

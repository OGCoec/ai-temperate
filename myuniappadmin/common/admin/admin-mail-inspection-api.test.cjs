const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	let source = fs.readFileSync(path.join(__dirname, 'admin-mail-inspection-api.js'), 'utf8')
	source = source.replace(
		"import { adminRequest } from './admin-http.js'",
		'const adminRequest = async () => { throw new Error("not configured") }')
	source = source.replace(
		"import { requireMailInspectionClientRequestId } from './mail-inspection-idempotency.js'",
		"const requireMailInspectionClientRequestId = value => value")
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('one API client maps four inspection types to fixed protected routes', async () => {
	const { createAdminMailInspectionApi } = await loadModule()
	const calls = []
	const api = createAdminMailInspectionApi(async (requestPath, options) => {
		calls.push({ requestPath, options })
		return { data: {
			jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
			inspectionType: requestPath.includes('kiro') ? 'KIRO_STATUS' : 'OPENAI_STATUS',
			status: 'QUEUED',
			requestedCount: 1,
			acceptedCount: 1,
			duplicateCount: 0,
			invalidCount: 0,
			requestedBusinessConcurrency: 32,
			appliedBusinessConcurrency: 32,
			dispatchFailedCount: 0,
			submissionChunkCount: 1,
			confirmedSubmissionChunkCount: 1,
			dispatchedSubmissionChunkCount: 0,
			submissionPendingChunkCount: 0,
			createdAt: '2026-07-28T00:00:00Z'
		}, headers: { 'Idempotency-Replayed': 'false' } }
	})

	for (const type of [
		'OPENAI_STATUS',
		'KIRO_STATUS',
		'IP2LOCATION_REGISTRATION',
		'IP2LOCATION_VERIFY_LINK'
	]) {
		await api.createJob(type, ['mail@example.com----<password>----00000000-0000-0000-0000-000000000000----<refresh-token>'], 32, '550e8400-e29b-41d4-a716-446655440000')
	}

	assert.deepEqual(calls.map(call => call.requestPath), [
		'/api/admin/mail-inspection/openai-status-jobs',
		'/api/admin/mail-inspection/kiro-status-jobs',
		'/api/admin/mail-inspection/ip2location-registration-jobs',
		'/api/admin/mail-inspection/ip2location-verify-link-jobs'
	])
	assert.ok(calls.every(call => call.options.method === 'POST'))
	assert.ok(calls.every(call => Array.isArray(call.options.data.credentialLines)))
	assert.ok(calls.every(call => call.options.data.businessConcurrency === 32))
	assert.ok(calls.every(call => call.options.headers['Idempotency-Key']
		=== '550e8400-e29b-41d4-a716-446655440000'))
	assert.ok(calls.every(call => call.options.timeout === 30000))
})

test('API client forwards credential arrays larger than one hundred lines', async () => {
	const { createAdminMailInspectionApi } = await loadModule()
	let submitted
	const api = createAdminMailInspectionApi(async (_requestPath, options) => {
		submitted = options.data.credentialLines
		return { data: {
			jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
			inspectionType: 'OPENAI_STATUS',
			status: 'QUEUED',
			requestedCount: 101,
			acceptedCount: 101,
			duplicateCount: 0,
			invalidCount: 0,
			requestedBusinessConcurrency: 4,
			appliedBusinessConcurrency: 4,
			dispatchFailedCount: 0,
			submissionChunkCount: 1,
			confirmedSubmissionChunkCount: 1,
			dispatchedSubmissionChunkCount: 0,
			submissionPendingChunkCount: 0,
			createdAt: '2026-07-28T00:00:00Z'
		}, headers: {} }
	})

	const lines = Array.from({ length: 101 }, (_, index) => `credential-${index}`)
	await api.createJob('OPENAI_STATUS', lines, 4, '550e8400-e29b-41d4-a716-446655440000')
	assert.equal(submitted.length, 101)
})

test('the shared API singleton exposes the complete mailbox inspection contract', async () => {
	const {
		ADMIN_MAIL_INSPECTION_API_CONTRACT_VERSION,
		adminMailInspectionApi
	} = await loadModule()

	assert.equal(ADMIN_MAIL_INSPECTION_API_CONTRACT_VERSION, 4)
	assert.equal(adminMailInspectionApi.contractVersion, 4)
	for (const method of ['createJob', 'getJob', 'eventsPath', 'getRecoveredJobs', 'resumeJob']) {
		assert.equal(typeof adminMailInspectionApi[method], 'function', `${method} must be callable`)
	}
})

test('recovered jobs remain paused until the explicit resume endpoint is called', async () => {
	const { createAdminMailInspectionApi } = await loadModule()
	const calls = []
	const api = createAdminMailInspectionApi(async (requestPath, options) => {
		calls.push([requestPath, options.method])
		if (requestPath.endsWith('/recovered-jobs')) {
			return [{
				jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
				inspectionType: 'OPENAI_STATUS',
				status: 'AWAITING_ADMIN_RESUME',
				remainingCount: 2,
				remainingDeliveryCount: 2,
				businessConcurrency: 32,
				recoveredAt: '2026-07-28T12:00:00Z',
				resultHistoryLost: true,
				lostResultCount: 98,
				pendingItems: [
					{ lineNumber: 65, maskedEmail: 'o***@example.test' }
				]
			}]
		}
		return {
			jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
			revision: 5,
			inspectionType: 'OPENAI_STATUS',
			status: 'RUNNING',
			requestedCount: 100,
			acceptedCount: 100,
			duplicateCount: 0,
			invalidCount: 0,
			processedCount: 0,
			runningCount: 0,
			queuedCount: 2,
			businessConcurrency: 32,
			pendingItems: [],
			summary: { counts: {} },
			results: []
		}
	})

	const recovered = await api.getRecoveredJobs()
	assert.equal(recovered[0].status, 'AWAITING_ADMIN_RESUME')
	assert.equal(recovered[0].pendingItems[0].maskedEmail, 'o***@example.test')
	await api.resumeJob('AZ9nEjRWeJCrze8SNFZ4kA')
	assert.deepEqual(calls, [
		['/api/admin/mail-inspection/recovered-jobs', 'GET'],
		['/api/admin/mail-inspection/jobs/AZ9nEjRWeJCrze8SNFZ4kA/resume', 'POST']
	])
})

test('job lookup rejects non-canonical public IDs before a request is issued', async () => {
	const { createAdminMailInspectionApi } = await loadModule()
	let calls = 0
	const api = createAdminMailInspectionApi(async () => {
		calls += 1
		return {}
	})

	await assert.rejects(
		() => api.getJob('123'),
		error => error.code === 'MAIL_INSPECTION_PUBLIC_ID_INVALID')
	await assert.rejects(
		() => api.getJob('AAAAAAAAAA='),
		error => error.code === 'MAIL_INSPECTION_PUBLIC_ID_INVALID')
	assert.equal(calls, 0)
})

test('job response must preserve the unified lifecycle and safe result array', async () => {
	const { createAdminMailInspectionApi } = await loadModule()
	const api = createAdminMailInspectionApi(async () => ({
		jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
		revision: 3,
		inspectionType: 'OPENAI_STATUS',
		status: 'RUNNING',
		requestedCount: 2,
		acceptedCount: 2,
		duplicateCount: 0,
		invalidCount: 0,
		processedCount: 1,
		runningCount: 1,
		queuedCount: 0,
		createdAt: '2026-07-28T00:00:00Z',
		startedAt: '2026-07-28T00:00:01Z',
		completedAt: null,
		expiresAt: null,
		summary: { counts: { OPENAI_REGISTERED_NORMAL: 1 } },
		results: [{
			lineNumber: 1,
			email: 'mail@example.com',
			status: 'OPENAI_REGISTERED_NORMAL',
			password: 'must-not-enter-client-state',
			refreshToken: 'must-not-enter-client-state'
		}]
	}))

	const job = await api.getJob('AZ9nEjRWeJCrze8SNFZ4kA')
	assert.equal(job.status, 'RUNNING')
	assert.equal(job.results[0].lineNumber, 1)
	assert.equal(job.results[0].status, 'OPENAI_REGISTERED_NORMAL')
	assert.equal('password' in job.results[0], false)
	assert.equal('refreshToken' in job.results[0], false)
})

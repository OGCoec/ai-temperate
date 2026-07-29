import { adminRequest } from './admin-http.js'

import { requireMailInspectionClientRequestId } from './mail-inspection-idempotency.js'

export const ADMIN_MAIL_INSPECTION_API_CONTRACT_VERSION = 3

export const MAIL_INSPECTION_TYPES = Object.freeze({
	OPENAI_STATUS: 'OPENAI_STATUS',
	KIRO_STATUS: 'KIRO_STATUS',
	IP2LOCATION_REGISTRATION: 'IP2LOCATION_REGISTRATION',
	IP2LOCATION_VERIFY_LINK: 'IP2LOCATION_VERIFY_LINK'
})

const CREATE_PATHS = Object.freeze({
	[MAIL_INSPECTION_TYPES.OPENAI_STATUS]: '/api/admin/mail-inspection/openai-status-jobs',
	[MAIL_INSPECTION_TYPES.KIRO_STATUS]: '/api/admin/mail-inspection/kiro-status-jobs',
	[MAIL_INSPECTION_TYPES.IP2LOCATION_REGISTRATION]: '/api/admin/mail-inspection/ip2location-registration-jobs',
	[MAIL_INSPECTION_TYPES.IP2LOCATION_VERIFY_LINK]: '/api/admin/mail-inspection/ip2location-verify-link-jobs'
})

const JOB_STATUSES = new Set([
	'QUEUED',
	'RUNNING',
	'DISPATCHING',
	'AWAITING_CLIENT_RESUBMISSION',
	'AWAITING_ADMIN_RESUME',
	'RECOVERY_FAILED',
	'ABANDONED',
	'COMPLETED',
	'FAILED'
])
const PUBLIC_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/

function apiError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

function requirePublicId(jobId) {
	const value = String(jobId || '')
	if (!PUBLIC_ID_PATTERN.test(value)) {
		throw apiError('MAIL_INSPECTION_PUBLIC_ID_INVALID', '邮箱检查任务编号无效。')
	}
	return value
}

function requireJobStatus(value) {
	if (!JOB_STATUSES.has(value)) {
		throw apiError('MAIL_INSPECTION_RESPONSE_INVALID', '邮箱检查任务状态响应无效。')
	}
	return value
}

function requireCount(value) {
	return Number.isInteger(value) && value >= 0
}

function requireBusinessConcurrency(value) {
	const concurrency = Number(value)
	if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 64) {
		throw apiError('MAIL_INSPECTION_CONCURRENCY_INVALID', '业务并发数必须是 1 到 64 的正整数。')
	}
	return concurrency
}

function safePendingItems(items) {
	if (!Array.isArray(items)
		|| items.some(item => !Number.isInteger(item?.lineNumber)
			|| item.lineNumber < 1
			|| typeof item.maskedEmail !== 'string')) {
		throw apiError('MAIL_INSPECTION_RESPONSE_INVALID', '恢复任务的脱敏等待列表无效。')
	}
	return items
		.map(item => Object.freeze({
			lineNumber: item.lineNumber,
			maskedEmail: item.maskedEmail
		}))
		.sort((left, right) => left.lineNumber - right.lineNumber)
}

function validateCreateResponse(response) {
	if (!response || typeof response !== 'object'
		|| !PUBLIC_ID_PATTERN.test(String(response.jobId || ''))
		|| !Object.values(MAIL_INSPECTION_TYPES).includes(response.inspectionType)
		|| !JOB_STATUSES.has(response.status)
		|| !requireCount(response.requestedCount)
		|| !requireCount(response.acceptedCount)
		|| !requireCount(response.duplicateCount)
		|| !requireCount(response.invalidCount)
		|| !requireCount(response.dispatchFailedCount)
		|| !requireCount(response.submissionChunkCount)
		|| !requireCount(response.confirmedSubmissionChunkCount)
		|| !requireCount(response.dispatchedSubmissionChunkCount)
		|| !requireCount(response.submissionPendingChunkCount)
		|| !Number.isInteger(response.requestedBusinessConcurrency)
		|| !Number.isInteger(response.appliedBusinessConcurrency)
		|| !Number.isFinite(Number(response.pollAfterMillis))) {
		throw apiError('MAIL_INSPECTION_RESPONSE_INVALID', '邮箱检查任务创建响应无效。')
	}
	return Object.freeze({
		jobId: response.jobId,
		inspectionType: response.inspectionType,
		status: response.status,
		requestedCount: response.requestedCount,
		acceptedCount: response.acceptedCount,
		duplicateCount: response.duplicateCount,
		invalidCount: response.invalidCount,
		requestedBusinessConcurrency: requireBusinessConcurrency(response.requestedBusinessConcurrency),
		appliedBusinessConcurrency: requireBusinessConcurrency(response.appliedBusinessConcurrency),
		dispatchFailedCount: response.dispatchFailedCount,
		idempotencyReplayed: response.idempotencyReplayed === true,
		submissionChunkCount: response.submissionChunkCount,
		confirmedSubmissionChunkCount: response.confirmedSubmissionChunkCount,
		dispatchedSubmissionChunkCount: response.dispatchedSubmissionChunkCount,
		submissionPendingChunkCount: response.submissionPendingChunkCount,
		submissionExpiresAt: response.submissionExpiresAt || null,
		createdAt: response.createdAt || null,
		pollAfterMillis: Number(response.pollAfterMillis)
	})
}

function safeResult(result) {
	return Object.freeze({
		lineNumber: result.lineNumber,
		email: typeof result.email === 'string' ? result.email : null,
		status: result.status,
		failureStage: typeof result.failureStage === 'string' ? result.failureStage : null,
		reason: typeof result.reason === 'string' ? result.reason : null,
		oauthAttempts: Number(result.oauthAttempts) || 0,
		imapAttempts: Number(result.imapAttempts) || 0,
		retryable: result.retryable === true,
		retryExhausted: result.retryExhausted === true,
		mailFound: result.mailFound === true,
		folderName: typeof result.folderName === 'string' ? result.folderName : null,
		sender: typeof result.sender === 'string' ? result.sender : null,
		subject: typeof result.subject === 'string' ? result.subject : null,
		receivedAt: typeof result.receivedAt === 'string' ? result.receivedAt : null,
		evidencePhrase: typeof result.evidencePhrase === 'string' ? result.evidencePhrase : null,
		imapRoute: typeof result.imapRoute === 'string' ? result.imapRoute : null,
		clientId: typeof result.clientId === 'string' ? result.clientId : null,
		registered: typeof result.registered === 'boolean' ? result.registered : null,
		verifyUrl: typeof result.verifyUrl === 'string' ? result.verifyUrl : null,
		verifyToken: typeof result.verifyToken === 'string' ? result.verifyToken : null
	})
}

function validateJobResponse(response) {
	if (!response || typeof response !== 'object'
		|| !PUBLIC_ID_PATTERN.test(String(response.jobId || ''))
		|| !Object.values(MAIL_INSPECTION_TYPES).includes(response.inspectionType)
		|| !JOB_STATUSES.has(response.status)
		|| !requireCount(response.requestedCount)
		|| !requireCount(response.processedCount)
		|| !requireCount(response.runningCount)
		|| !requireCount(response.queuedCount)
		|| !Array.isArray(response.results)
		|| response.results.some(result => !Number.isInteger(result?.lineNumber)
			|| result.lineNumber < 1
			|| typeof result.status !== 'string')) {
		throw apiError('MAIL_INSPECTION_RESPONSE_INVALID', '邮箱检查任务查询响应无效。')
	}
	return Object.freeze({
		jobId: response.jobId,
		inspectionType: response.inspectionType,
		status: requireJobStatus(response.status),
		requestedCount: response.requestedCount,
		processedCount: response.processedCount,
		runningCount: response.runningCount,
		queuedCount: response.queuedCount,
		recoveredAfterRestart: response.recoveredAfterRestart === true,
		resumeRequired: response.resumeRequired === true,
		resultHistoryLost: response.resultHistoryLost === true,
		lostResultCount: requireCount(response.lostResultCount) ? response.lostResultCount : 0,
		remainingCount: requireCount(response.remainingCount) ? response.remainingCount : 0,
		remainingDeliveryCount: requireCount(response.remainingDeliveryCount) ? response.remainingDeliveryCount : 0,
		businessConcurrency: requireBusinessConcurrency(response.businessConcurrency || 4),
		dispatchFailedCount: requireCount(response.dispatchFailedCount) ? response.dispatchFailedCount : 0,
		submissionChunkCount: requireCount(response.submissionChunkCount) ? response.submissionChunkCount : 0,
		confirmedSubmissionChunkCount: requireCount(response.confirmedSubmissionChunkCount) ? response.confirmedSubmissionChunkCount : 0,
		dispatchedSubmissionChunkCount: requireCount(response.dispatchedSubmissionChunkCount) ? response.dispatchedSubmissionChunkCount : 0,
		submissionPendingChunkCount: requireCount(response.submissionPendingChunkCount) ? response.submissionPendingChunkCount : 0,
		submissionExpiresAt: response.submissionExpiresAt || null,
		recoveredAt: response.recoveredAt || null,
		pendingItems: safePendingItems(response.pendingItems || []),
		createdAt: response.createdAt || null,
		startedAt: response.startedAt || null,
		completedAt: response.completedAt || null,
		expiresAt: response.expiresAt || null,
		summary: response.summary && typeof response.summary === 'object'
			? { counts: { ...(response.summary.counts || {}) } }
			: { counts: {} },
		// 只挑选公共契约允许的字段，未知后端字段不会进入前端状态或组件。
		results: [...response.results]
			.sort((left, right) => left.lineNumber - right.lineNumber)
			.map(safeResult)
	})
}

function validateRecoveredJobs(response) {
	if (!Array.isArray(response)) {
		throw apiError('MAIL_INSPECTION_RESPONSE_INVALID', '恢复任务列表响应无效。')
	}
	return response.map(item => {
		if (!item || typeof item !== 'object'
			|| !PUBLIC_ID_PATTERN.test(String(item.jobId || ''))
			|| !Object.values(MAIL_INSPECTION_TYPES).includes(item.inspectionType)
			|| !['AWAITING_CLIENT_RESUBMISSION', 'AWAITING_ADMIN_RESUME', 'RECOVERY_FAILED'].includes(item.status)
			|| item.resultHistoryLost !== true
			|| !requireCount(item.remainingCount)
			|| !requireCount(item.remainingDeliveryCount)
			|| !requireCount(item.lostResultCount)) {
			throw apiError('MAIL_INSPECTION_RESPONSE_INVALID', '恢复任务列表响应无效。')
		}
		return Object.freeze({
			jobId: item.jobId,
			inspectionType: item.inspectionType,
			status: item.status,
			remainingCount: item.remainingCount,
			remainingDeliveryCount: item.remainingDeliveryCount,
			businessConcurrency: requireBusinessConcurrency(item.businessConcurrency),
			recoveredAt: item.recoveredAt || null,
			resultHistoryLost: item.resultHistoryLost === true,
			lostResultCount: item.lostResultCount,
			pendingItems: safePendingItems(item.pendingItems || [])
		})
	})
}

export function createAdminMailInspectionApi(request = adminRequest) {
	return Object.freeze({
		contractVersion: ADMIN_MAIL_INSPECTION_API_CONTRACT_VERSION,

		async createJob(inspectionType, credentialLines, businessConcurrency = 4, clientRequestId) {
			const path = CREATE_PATHS[inspectionType]
			if (!path) {
				throw apiError('MAIL_INSPECTION_TYPE_UNSUPPORTED', '不支持的邮箱检查类型。')
			}
			if (!Array.isArray(credentialLines) || credentialLines.length < 1) {
				throw apiError('MAIL_INSPECTION_INPUT_INVALID', '邮箱凭证列表不能为空。')
			}
			const concurrency = requireBusinessConcurrency(businessConcurrency)
			const requestId = requireMailInspectionClientRequestId(clientRequestId)
			const response = await request(path, {
				method: 'POST',
				headers: { 'Idempotency-Key': requestId },
				data: {
					credentialLines: [...credentialLines],
					businessConcurrency: concurrency
				},
				timeout: 30000,
				returnResponse: true
			})
			const body = validateCreateResponse(response.data)
			const replayHeader = Object.entries(response.headers || {})
				.find(([name]) => name.toLowerCase() === 'idempotency-replayed')?.[1]
			return Object.freeze({
				...body,
				idempotencyReplayed: String(replayHeader).toLowerCase() === 'true'
			})
		},

		async getJob(jobId) {
			const publicId = requirePublicId(jobId)
			const response = await request(
				`/api/admin/mail-inspection/jobs/${encodeURIComponent(publicId)}`,
				{ method: 'GET', timeout: 10000 })
			return validateJobResponse(response)
		},

		async getRecoveredJobs() {
			const response = await request(
				'/api/admin/mail-inspection/recovered-jobs',
				{ method: 'GET', timeout: 10000 })
			return validateRecoveredJobs(response)
		},

		async resumeJob(jobId) {
			const publicId = requirePublicId(jobId)
			const response = await request(
				`/api/admin/mail-inspection/jobs/${encodeURIComponent(publicId)}/resume`,
				{ method: 'POST', data: {}, timeout: 10000 })
			return validateJobResponse(response)
		}
	})
}

export const adminMailInspectionApi = createAdminMailInspectionApi()

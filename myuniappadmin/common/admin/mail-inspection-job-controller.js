import { analyzeMailboxCredentialText } from './mail-inspection-credential-parser.js'
import { recoverRetryCredentialLines } from './mail-inspection-presenter.js'
import { createMailInspectionClientRequestId } from './mail-inspection-idempotency.js'
import {
	createMailInspectionSseClient,
	MAIL_INSPECTION_CONNECTION_STATES
} from './mail-inspection-sse-client.js'

const TERMINAL_JOB_STATES = new Set([
	'COMPLETED',
	'FAILED',
	'ABANDONED',
	'EXPIRED'
])
const PERSISTABLE_EVENT_TYPES = new Set([
	'sync-complete',
	'progress',
	'result',
	'status',
	'terminal',
	'heartbeat'
])
const MISSING_JOB_CODES = new Set([
	'ADMIN_MAIL_INSPECTION_JOB_NOT_FOUND',
	'ADMIN_MAIL_INSPECTION_JOB_EXPIRED',
	'MAIL_INSPECTION_JOB_NOT_FOUND',
	'MAIL_INSPECTION_JOB_EXPIRED'
])

export const MAIL_INSPECTION_MISSING_JOB_MESSAGE =
	'原检查任务已过期或不存在，请重新创建检查任务。'

function isMissingJob(error) {
	return MISSING_JOB_CODES.has(String(error?.code || ''))
		|| Number(error?.statusCode) === 404
}

function isSubmissionRetryable(error) {
	return error?.code === 'NETWORK_ERROR'
		|| error?.code === 'HTTP_408'
		|| error?.code === 'HTTP_429'
		|| error?.statusCode === 408
		|| error?.statusCode === 429
}

function safeMessage(error, fallback) {
	return typeof error?.message === 'string' && error.message
		? error.message
		: fallback
}

function safeRevision(value) {
	const revision = Number(value)
	return Number.isSafeInteger(revision) && revision >= 0 ? revision : 0
}

function upsertResults(current, incoming) {
	const byLine = new Map(current.map(result => [result.lineNumber, result]))
	for (const result of incoming) {
		if (Number.isInteger(result?.lineNumber) && result.lineNumber > 0) {
			byLine.set(result.lineNumber, Object.freeze({ ...result }))
		}
	}
	return [...byLine.values()].sort((left, right) => left.lineNumber - right.lineNumber)
}

function initialState(inspectionType) {
	return {
		state: 'IDLE',
		connectionState: '',
		inspectionType,
		draftText: '',
		analysis: analyzeMailboxCredentialText(''),
		credentialLines: [],
		clientRequestId: '',
		submissionStartedAt: '',
		jobId: '',
		lastRevision: 0,
		job: null,
		results: [],
		message: '',
		businessConcurrency: 4
	}
}

export function createMailInspectionJobController(options) {
	const inspectionType = options.inspectionType
	const api = options.api
	const store = options.store
	const streamClient = options.streamClient || createMailInspectionSseClient()
	const notify = typeof options.onChange === 'function' ? options.onChange : () => {}
	const wait = options.wait
		|| (milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)))
	let connection = null
	let state = initialState(inspectionType)

	function snapshot() {
		return {
			...state,
			analysis: {
				...state.analysis,
				credentialLines: [...state.analysis.credentialLines],
				errors: [...state.analysis.errors]
			},
			credentialLines: [...state.credentialLines],
			results: [...state.results],
			job: state.job ? {
				...state.job,
				results: [...state.results]
			} : null
		}
	}

	function emit() {
		const value = snapshot()
		notify(value)
		return value
	}

	function persist() {
		store.save(inspectionType, {
			jobId: state.jobId,
			lastRevision: state.lastRevision
		})
	}

	function closeConnection() {
		connection?.close()
		connection = null
	}

	function resetMissingJob(expectedJobId) {
		if (!expectedJobId || state.jobId !== expectedJobId) return false
		const businessConcurrency = state.businessConcurrency
		closeConnection()
		store.clear(inspectionType)
		state = {
			...initialState(inspectionType),
			businessConcurrency,
			message: MAIL_INSPECTION_MISSING_JOB_MESSAGE
		}
		emit()
		return true
	}

	function applyMeta(meta) {
		if (!meta || typeof meta !== 'object') return
		state.job = {
			...(state.job || {}),
			...meta,
			jobId: state.jobId,
			inspectionType,
			results: [...state.results]
		}
		if (typeof meta.status === 'string') state.state = meta.status
		if (Number.isInteger(meta.businessConcurrency)) {
			state.businessConcurrency = meta.businessConcurrency
		}
	}

	function updateRevision(event) {
		if (!PERSISTABLE_EVENT_TYPES.has(event.type)) return
		const eventRevision = safeRevision(event.data?.revision || event.id)
		state.lastRevision = Math.max(state.lastRevision, eventRevision)
		persist()
	}

	function acceptStreamEvent(event) {
		const envelope = event?.data
		if (!envelope || typeof envelope !== 'object'
			|| !Number.isSafeInteger(Number(envelope.revision))
			|| Number(envelope.revision) < 0) {
			const error = new Error('实时事件修订号无效。')
			error.code = 'MAIL_INSPECTION_SSE_PROTOCOL_INVALID'
			throw error
		}
		if (event.type === 'snapshot-meta') {
			state.results = []
			applyMeta(envelope.data)
		} else if (event.type === 'result-batch') {
			state.results = upsertResults(
				state.results,
				Array.isArray(envelope.data?.results) ? envelope.data.results : [])
		} else if (event.type === 'result') {
			state.results = upsertResults(state.results, [envelope.data])
		} else if (['progress', 'status', 'terminal'].includes(event.type)) {
			applyMeta(envelope.data)
		}
		updateRevision(event)
		if (event.type === 'sync-complete') {
			state.message = ''
		}
		if (event.type === 'terminal') {
			state.connectionState = state.state === 'COMPLETED'
				? MAIL_INSPECTION_CONNECTION_STATES.COMPLETED
				: state.state === 'EXPIRED'
					? MAIL_INSPECTION_CONNECTION_STATES.EXPIRED
					: MAIL_INSPECTION_CONNECTION_STATES.FAILED
			closeConnection()
		}
		if (state.job) state.job = { ...state.job, results: [...state.results] }
		emit()
	}

	function handleConnectionState(connectionState) {
		state.connectionState = connectionState
		if (connectionState === MAIL_INSPECTION_CONNECTION_STATES.RECONNECTING) {
			state.message = '实时连接已中断，正在进行有限重连。'
		} else if (connectionState === MAIL_INSPECTION_CONNECTION_STATES.FAILED) {
			state.message = '实时连接已中断；不会回退到 HTTP 轮询。'
		} else if (connectionState === MAIL_INSPECTION_CONNECTION_STATES.EXPIRED) {
			state.state = 'EXPIRED'
			state.message = '任务已过期或不存在。'
		} else if (connectionState === MAIL_INSPECTION_CONNECTION_STATES.STREAMING) {
			state.message = ''
		}
		emit()
	}

	function connect() {
		closeConnection()
		if (!state.jobId || TERMINAL_JOB_STATES.has(state.state)) return emit()
		const expectedJobId = state.jobId
		connection = streamClient.connect({
			path: api.eventsPath(expectedJobId),
			lastRevision: () => state.lastRevision,
			onState(connectionState) {
				if (state.jobId === expectedJobId) {
					handleConnectionState(connectionState)
				}
			},
			onEvent(event) {
				if (state.jobId === expectedJobId) acceptStreamEvent(event)
			},
			onError(error) {
				if (isMissingJob(error)) {
					resetMissingJob(expectedJobId)
					return
				}
				if (state.jobId !== expectedJobId) return
				state.message = safeMessage(error, '实时连接已中断。')
				emit()
			}
		})
		return emit()
	}

	function setDraftText(text) {
		state.draftText = String(text || '')
		state.analysis = analyzeMailboxCredentialText(state.draftText)
		if (state.state === 'VALIDATING') state.state = 'IDLE'
		state.message = ''
		return emit()
	}

	function setBusinessConcurrency(value) {
		if (state.jobId) return emit()
		const concurrency = Number(value)
		if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 64) {
			state.message = '业务并发数必须是 1 到 64 的正整数。'
			return emit()
		}
		state.businessConcurrency = concurrency
		state.message = ''
		return emit()
	}

	async function startLines(lines, draftText, startOptions = {}) {
		closeConnection()
		const requestId = startOptions.clientRequestId
			|| createMailInspectionClientRequestId()
		state = {
			...initialState(inspectionType),
			state: 'CREATING',
			draftText,
			analysis: analyzeMailboxCredentialText(draftText),
			credentialLines: [...lines],
			clientRequestId: requestId,
			submissionStartedAt: startOptions.clientRequestId
				? (state.submissionStartedAt || new Date().toISOString())
				: new Date().toISOString(),
			businessConcurrency: state.businessConcurrency
		}
		emit()
		try {
			let created
			try {
				created = await api.createJob(
					inspectionType,
					lines,
					state.businessConcurrency,
					requestId)
			} catch (firstError) {
				if (!isSubmissionRetryable(firstError)) throw firstError
				state.state = 'SUBMISSION_UNKNOWN'
				state.message = '提交结果暂不确定，正在使用原提交编号确认一次。'
				emit()
				await wait(2000)
				created = await api.createJob(
					inspectionType,
					lines,
					state.businessConcurrency,
					requestId)
			}
			state.jobId = created.jobId
			state.job = { ...created, revision: 0, results: [] }
			state.state = created.status
			state.lastRevision = 0
			persist()
			emit()
			return connect()
		} catch (error) {
			if (error?.code === 'ADMIN_MAIL_INSPECTION_UNAVAILABLE') {
				state.state = 'SERVICE_UNAVAILABLE'
				state.message = 'Redis 任务服务不可用，当前未回退到进程内任务。'
			} else if (error?.code === 'ADMIN_MAIL_INSPECTION_SUBMISSION_INCOMPLETE') {
				state.state = 'AWAITING_CLIENT_RESUBMISSION'
				state.message = '部分凭证尚未持久化，请继续原提交。'
			} else if (isSubmissionRetryable(error)) {
				state.state = 'SUBMISSION_UNKNOWN'
				state.message = '提交结果暂不确定，请使用原提交编号继续确认。'
			} else {
				state.state = 'FAILED'
				state.message = safeMessage(error, '邮箱检查任务创建失败。')
			}
			return emit()
		}
	}

	async function submit(text = state.draftText) {
		const analysis = analyzeMailboxCredentialText(text)
		state.draftText = String(text || '')
		state.analysis = analysis
		state.message = ''
		if (!analysis.valid) {
			state.state = 'VALIDATING'
			return emit()
		}
		return startLines([...analysis.credentialLines], state.draftText)
	}

	async function restore(restoreOptions = {}) {
		const context = store.load(inspectionType)
		state = {
			...initialState(inspectionType),
			jobId: context.jobId || '',
			lastRevision: safeRevision(context.lastRevision),
			state: context.jobId ? 'QUEUED' : 'IDLE'
		}
		emit()
		if (state.jobId && restoreOptions.allowNetwork !== false) connect()
		return snapshot()
	}

	async function resume() {
		if (!state.jobId || TERMINAL_JOB_STATES.has(state.state)) return emit()
		return connect()
	}

	async function trackJob(job) {
		closeConnection()
		state.jobId = job.jobId
		state.job = { ...job, results: [...(job.results || [])] }
		state.results = [...(job.results || [])]
		state.state = job.status
		state.lastRevision = safeRevision(job.revision)
		state.businessConcurrency = Number(job.businessConcurrency)
			|| state.businessConcurrency
		state.message = ''
		persist()
		emit()
		if (!TERMINAL_JOB_STATES.has(state.state)) connect()
		return snapshot()
	}

	function pause() {
		closeConnection()
	}

	function clear() {
		closeConnection()
		store.clear(inspectionType)
		state = initialState(inspectionType)
		return emit()
	}

	function retryLines() {
		return recoverRetryCredentialLines(state.results, state.credentialLines)
	}

	async function retryExhausted() {
		const lines = retryLines()
		if (!lines.length) {
			const error = new Error('当前结果没有可手动重试的网络失败项。')
			error.code = 'MAIL_INSPECTION_RETRY_EMPTY'
			throw error
		}
		return startLines(lines, lines.join('\n'))
	}

	async function continueSubmission() {
		if (!state.clientRequestId || !state.credentialLines.length) {
			const error = new Error('原提交编号或凭证已被清除，无法继续确认。')
			error.code = 'MAIL_INSPECTION_SUBMISSION_CONTEXT_MISSING'
			throw error
		}
		return startLines(
			[...state.credentialLines],
			state.draftText,
			{ clientRequestId: state.clientRequestId })
	}

	return Object.freeze({
		snapshot,
		setDraftText,
		setBusinessConcurrency,
		submit,
		restore,
		resume,
		trackJob,
		pause,
		clear,
		retryLines,
		retryExhausted,
		continueSubmission
	})
}

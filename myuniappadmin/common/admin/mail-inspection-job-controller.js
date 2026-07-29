import { analyzeMailboxCredentialText } from './mail-inspection-credential-parser.js'
import { recoverRetryCredentialLines } from './mail-inspection-presenter.js'
import { createMailInspectionClientRequestId } from './mail-inspection-idempotency.js'

const TERMINAL_STATES = new Set([
	'COMPLETED',
	'FAILED',
	'ABANDONED',
	'SUBMISSION_UNKNOWN',
	'SERVICE_UNAVAILABLE',
	'AWAITING_CLIENT_RESUBMISSION',
	'EXPIRED',
	'AWAITING_ADMIN_RESUME',
	'RECOVERY_FAILED'
])
const NETWORK_BACKOFF = [2000, 4000, 8000, 15000]

function clampPollDelay(value) {
	return Math.min(10000, Math.max(1000, Number(value) || 2000))
}

function isNotFound(error) {
	return error?.statusCode === 404 || error?.code === 'HTTP_404'
}

function isPollingNetworkError(error) {
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

export function createMailInspectionJobController(options) {
	const inspectionType = options.inspectionType
	const api = options.api
	const store = options.store
	const notify = typeof options.onChange === 'function' ? options.onChange : () => {}
	const setTimer = options.setTimer || setTimeout
	const clearTimer = options.clearTimer || clearTimeout
	const wait = options.wait || (milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)))
	let timerId = null
	let pollingFailures = 0
	let state = {
		state: 'IDLE',
		inspectionType,
		draftText: '',
		analysis: analyzeMailboxCredentialText(''),
		credentialLines: [],
		clientRequestId: '',
		submissionStartedAt: '',
		jobId: '',
		job: null,
		results: [],
		message: '',
		pollAfterMillis: 2000,
		businessConcurrency: 4
	}

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
			job: state.job ? { ...state.job, results: [...(state.job.results || [])] } : null
		}
	}

	function emit() {
		const value = snapshot()
		notify(value)
		return value
	}

	function persist() {
		store.save(inspectionType, {
			draftText: state.draftText,
			credentialLines: state.credentialLines,
			clientRequestId: state.clientRequestId,
			submissionStartedAt: state.submissionStartedAt,
			jobId: state.jobId,
			jobStatus: state.state,
			pollAfterMillis: state.pollAfterMillis,
			businessConcurrency: state.businessConcurrency,
			createdAt: state.job?.createdAt,
			expiresAt: state.job?.expiresAt
		})
	}

	function pause() {
		if (timerId !== null) clearTimer(timerId)
		timerId = null
	}

	function schedule(delay) {
		pause()
		timerId = setTimer(async () => {
			timerId = null
			await pollNow()
		}, delay)
	}

	function setDraftText(text) {
		state.draftText = String(text || '')
		state.analysis = analyzeMailboxCredentialText(state.draftText)
		if (state.state === 'VALIDATING') state.state = 'IDLE'
		state.message = ''
		persist()
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
		persist()
		return emit()
	}

	async function startLines(lines, draftText, options = {}) {
		pause()
		const requestId = options.clientRequestId || createMailInspectionClientRequestId()
		state = {
			...state,
			state: 'CREATING',
			draftText,
			analysis: analyzeMailboxCredentialText(draftText),
			credentialLines: [...lines],
			clientRequestId: requestId,
			submissionStartedAt: options.clientRequestId
				? (state.submissionStartedAt || new Date().toISOString())
				: new Date().toISOString(),
			jobId: '',
			job: null,
			results: [],
			message: '',
			pollAfterMillis: 2000
		}
		persist()
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
				if (!isPollingNetworkError(firstError)) throw firstError
				state.state = 'SUBMISSION_UNKNOWN'
				state.message = '提交结果暂不确定，正在使用原提交编号确认。'
				persist()
				emit()
				await wait(2000)
				created = await api.createJob(
					inspectionType,
					lines,
					state.businessConcurrency,
					requestId)
			}
			state.jobId = created.jobId
			state.job = created
			state.state = created.status
			state.pollAfterMillis = clampPollDelay(created.pollAfterMillis)
			pollingFailures = 0
			persist()
			emit()
			return await pollNow()
		} catch (error) {
			if (error?.code === 'ADMIN_MAIL_INSPECTION_UNAVAILABLE') {
				state.state = 'SERVICE_UNAVAILABLE'
				state.message = '该类型邮箱检查正在恢复或恢复失败，当前未接收新任务。原提交尚未创建，无需重复提交凭证。'
			} else if (error?.code === 'ADMIN_MAIL_INSPECTION_SUBMISSION_INCOMPLETE') {
				state.state = 'AWAITING_CLIENT_RESUBMISSION'
				state.message = '部分凭证尚未持久化，请继续原提交。'
			} else if (isPollingNetworkError(error)) {
				state.state = 'SUBMISSION_UNKNOWN'
				state.message = '提交结果暂不确定，请使用原提交编号继续确认。'
			} else {
				state.state = 'FAILED'
				state.message = safeMessage(error, '邮箱检查任务创建失败。')
			}
			persist()
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
			persist()
			return emit()
		}
		return startLines([...analysis.credentialLines], state.draftText)
	}

	async function pollNow() {
		pause()
		if (!state.jobId) return emit()
		try {
			const job = await api.getJob(state.jobId)
			state.job = job
			state.results = [...job.results]
			state.state = job.status
			state.message = ''
			pollingFailures = 0
			persist()
			const current = emit()
			if (!TERMINAL_STATES.has(state.state)) schedule(state.pollAfterMillis)
			return current
		} catch (error) {
			if (isNotFound(error)) {
				state.state = 'EXPIRED'
				state.message = '任务已过期、服务进程已重启或任务不存在；原输入仍保留，可手动重新创建。'
				persist()
				return emit()
			}
			if (isPollingNetworkError(error)) {
				const delay = NETWORK_BACKOFF[Math.min(pollingFailures, NETWORK_BACKOFF.length - 1)]
				pollingFailures += 1
				state.state = 'POLLING_INTERRUPTED'
				state.message = `任务仍在后端运行，查询连接中断，将在 ${delay / 1000} 秒后继续。`
				persist()
				const current = emit()
				schedule(delay)
				return current
			}
			state.state = 'FAILED'
			state.message = safeMessage(error, '邮箱检查任务查询失败。')
			persist()
			emit()
			throw error
		}
	}

	async function restore(options = {}) {
		const allowNetwork = options.allowNetwork !== false
		const context = store.load(inspectionType)
		state.draftText = context.draftText || ''
		state.analysis = analyzeMailboxCredentialText(state.draftText)
		state.credentialLines = [...(context.credentialLines || [])]
		state.clientRequestId = context.clientRequestId || ''
		state.submissionStartedAt = context.submissionStartedAt || ''
		state.jobId = context.jobId || ''
		state.state = context.jobStatus || 'IDLE'
		state.pollAfterMillis = clampPollDelay(context.pollAfterMillis)
		state.businessConcurrency = Number.isInteger(context.businessConcurrency)
			? context.businessConcurrency
			: 4
		if (!state.jobId
			&& state.clientRequestId
			&& ['CREATING', 'DISPATCHING'].includes(state.state)) {
			state.state = 'SUBMISSION_UNKNOWN'
			state.message = '上次提交结果尚未确认，请使用原提交编号继续确认。'
		}
		if (!state.jobId && state.state === 'SERVICE_UNAVAILABLE') {
			state.message = '该类型邮箱检查正在恢复或恢复失败，当前未接收新任务。原提交尚未创建，无需重复提交凭证。'
		}
		emit()
		if (state.jobId && allowNetwork) return pollNow()
		return snapshot()
	}

	async function resume() {
		if (!state.jobId) return emit()
		return pollNow()
	}

	async function trackJob(job) {
		pause()
		state.jobId = job.jobId
		state.job = job
		state.results = [...(job.results || [])]
		state.state = job.status
		state.businessConcurrency = Number(job.businessConcurrency) || state.businessConcurrency
		state.message = ''
		pollingFailures = 0
		persist()
		emit()
		if (!TERMINAL_STATES.has(state.state)) return pollNow()
		return snapshot()
	}

	function clear() {
		pause()
		store.clear(inspectionType)
		state = {
			state: 'IDLE',
			inspectionType,
			draftText: '',
			analysis: analyzeMailboxCredentialText(''),
			credentialLines: [],
			clientRequestId: '',
			submissionStartedAt: '',
			jobId: '',
			job: null,
			results: [],
			message: '',
			pollAfterMillis: 2000,
			businessConcurrency: 4
		}
		pollingFailures = 0
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
		pollNow,
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

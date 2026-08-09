import { mergeAiConversationSources } from './ai-conversation-source-presentation.js'
import { presentAiSearchActivity } from './ai-conversation-research-presentation.js'

const ACTIVE_STATUSES = new Set(['STARTED', 'IN_PROGRESS'])
const TERMINAL_STATUSES = new Set(['FAILED', 'UNAVAILABLE'])

const MODEL_ACTIVITY_STATES = Object.freeze({
	PROCESSING: 'working',
	REASONING: 'solving',
	GENERATING: 'composing',
	FINALIZING: 'shaping'
})

const MODEL_ACTIVITY_LABELS = Object.freeze({
	PROCESSING: '正在准备回答',
	REASONING: '正在思考和整理信息',
	GENERATING: '正在生成回答',
	FINALIZING: '正在完成回答'
})

const WEB_SEARCH_LABELS = Object.freeze({
	STARTED: '已开始联网搜索',
	IN_PROGRESS: '正在联网搜索',
	COMPLETED: '正在整理搜索来源',
	FAILED: '联网搜索失败',
	UNAVAILABLE: '联网搜索不可用'
})

function safeSequence(value) {
	const sequence = Number(value)
	return Number.isSafeInteger(sequence) && sequence >= 0 ? sequence : -1
}

function latestSourceForActivity(activity, sources) {
	const activityId = String(activity?.activityId || '')
	if (!activityId) return null
	return mergeAiConversationSources(sources)
		.filter(source => source.activityId === activityId)
		.sort((left, right) => safeSequence(right.sequence) - safeSequence(left.sequence))[0] || null
}

function normalizedActivity(activity) {
	if (!activity || typeof activity !== 'object') return null
	const phase = String(activity.phase || '').toUpperCase()
	const status = String(activity.status || '').toUpperCase()
	if (!phase || (!status && !MODEL_ACTIVITY_STATES[phase])) return null
	return Object.freeze({
		...activity,
		phase,
		status,
		activityId: String(activity.activityId || ''),
		query: activity.query == null ? null : String(activity.query)
	})
}

export function presentAiActivity(activity, sources = []) {
	const normalized = normalizedActivity(activity)
	if (!normalized) return null
	if (normalized.phase === 'VIDEO_GENERATION') {
		const progress = Math.max(0, Math.min(100, Number(normalized.progress || 0)))
		return Object.freeze({
			state: 'composing',
			label: `正在生成视频 · ${progress}%`,
			looping: true,
			terminal: false,
			sourcePresentation: null,
			activity: normalized
		})
	}
	if (normalized.phase === 'VIDEO_TRANSFER') {
		return Object.freeze({
			state: 'shaping',
			label: '正在安全保存视频到 OSS',
			looping: true,
			terminal: false,
			sourcePresentation: null,
			activity: normalized
		})
	}

	if (normalized.phase === 'WEB_SEARCH') {
		const source = latestSourceForActivity(normalized, sources)
		const inferred = presentAiSearchActivity(normalized, sources)
		const sourcePresentation = source
			? Object.freeze({
				domain: source.domain,
				pathHint: '',
				source,
				clickable: true
			})
			: inferred
		const label = WEB_SEARCH_LABELS[normalized.status]
			|| '联网搜索状态已更新'
		const query = normalized.query?.trim()
		const displayLabel = normalized.status === 'STARTED'
			&& query && !sourcePresentation
			? `${label}：${query}`
			: normalized.status === 'IN_PROGRESS' && query && !sourcePresentation
				? `${label}：${query}` : label
		return Object.freeze({
			state: normalized.status === 'COMPLETED' ? 'weaving' : 'searching',
			label: displayLabel,
			looping: !TERMINAL_STATUSES.has(normalized.status),
			terminal: TERMINAL_STATUSES.has(normalized.status),
			sourcePresentation: sourcePresentation || null,
			activity: normalized
		})
	}

	const state = MODEL_ACTIVITY_STATES[normalized.phase]
	if (!state) return null
	return Object.freeze({
		state,
		label: MODEL_ACTIVITY_LABELS[normalized.phase],
		looping: !TERMINAL_STATUSES.has(normalized.status),
		terminal: TERMINAL_STATUSES.has(normalized.status),
		sourcePresentation: null,
		activity: normalized
	})
}

export function presentAiVoiceActivity(voiceState, {
	queuePosition = 0,
	queueCapacity = 5,
	limitReached = false
} = {}) {
	const state = String(voiceState || '').toUpperCase()
	const result = {
		REQUESTING_PERMISSION: ['connecting', '正在请求麦克风权限'],
		ISSUING_TICKET: ['connecting', '正在准备安全语音连接'],
		CONNECTING: ['connecting', '正在连接本地语音识别'],
		QUEUED: ['breathing', `正在排队，第 ${queuePosition} / ${queueCapacity} 位`],
		RECORDING: ['listening', '正在聆听'],
		FINALIZING: ['composing', limitReached
			? '已达到 5 分钟上限，正在生成最终文字' : '正在生成最终文字']
	}[state]
	if (!result) return null
	return Object.freeze({
		state: result[0],
		label: result[1],
		looping: true,
		terminal: false,
		sourcePresentation: null
	})
}

export function presentAiCompactionActivity(compactionStatus) {
	const status = String(compactionStatus || '').toUpperCase()
	if (status === 'QUEUED') {
		return Object.freeze({ state: 'breathing', label: 'Queued · 正在排队压缩上下文', looping: true })
	}
	if (status === 'RUNNING') {
		return Object.freeze({ state: 'weaving', label: 'Compacting · 正在压缩上下文', looping: true })
	}
	return null
}

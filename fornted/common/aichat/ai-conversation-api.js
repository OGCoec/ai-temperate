import { authorizedRequest } from '../auth/http-client.js'

const LONG_PUBLIC_ID = /^[A-Za-z0-9_-]{11}$/
const HYBRID_PUBLIC_ID = /^[A-Za-z0-9_-]{22}$/
const CURSOR = /^[A-Za-z0-9_-]{32}$/
const ATTACHMENT_ID = /^[A-Za-z0-9_-]{38}$/
const DECIMAL = /^(?:0|[1-9]\d*)$/
const ATTACHMENT_CATEGORIES = new Set(['IMAGE', 'AUDIO', 'VIDEO', 'DOCUMENT', 'ARCHIVE', 'OTHER'])
const ATTACHMENT_STATES = new Set(['AVAILABLE', 'STORAGE_FAILED'])
const GENERATION_STATES = new Set([
	'QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'TERMINAL_PENDING_BILLING',
	'SETTLED', 'REFUNDED', 'RECONCILE_REQUIRED'
])
const OBSERVER_STATES = new Set(['ATTACHED', 'DETACHED'])

function error(code, message) {
	const value = new Error(message)
	value.code = code
	return value
}

function requiredText(value, field) {
	if (typeof value !== 'string' || !value.trim()) {
		throw error('AI_CONVERSATION_RESPONSE_INVALID', `${field} 无效。`)
	}
	return value.trim()
}

function nullableText(value) {
	return typeof value === 'string' && value.trim() ? value.trim() : null
}

function nullableContent(value) {
	return typeof value === 'string' && value.trim() ? value : null
}

function publicId(value, pattern, field) {
	const normalized = requiredText(value, field)
	if (!pattern.test(normalized)) {
		throw error('AI_CONVERSATION_RESPONSE_INVALID', `${field} 格式无效。`)
	}
	return normalized
}

function positiveDecimal(value, field) {
	const normalized = String(value ?? '')
	if (!DECIMAL.test(normalized) || normalized === '0') {
		throw error('AI_CONVERSATION_RESPONSE_INVALID', `${field} 无效。`)
	}
	return normalized
}

function nullableDecimal(value, field) {
	if (value == null) return null
	const normalized = String(value)
	if (!DECIMAL.test(normalized)) {
		throw error('AI_CONVERSATION_RESPONSE_INVALID', `${field} 无效。`)
	}
	return normalized
}

function attachment(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) {
		throw error('AI_CONVERSATION_RESPONSE_INVALID', '附件响应无效。')
	}
	const attachmentId = requiredText(value.attachmentId, 'attachmentId')
	if (!ATTACHMENT_ID.test(attachmentId)) {
		throw error('AI_CONVERSATION_RESPONSE_INVALID', '附件标识或大小无效。')
	}
	const sizeBytes = String(value.sizeBytes ?? '')
	if (!DECIMAL.test(sizeBytes)) throw error('AI_CONVERSATION_RESPONSE_INVALID', '附件大小无效。')
	const category = requiredText(value.category, 'category')
	const state = requiredText(value.state, 'state')
	if (!ATTACHMENT_CATEGORIES.has(category) || !ATTACHMENT_STATES.has(state)) {
		throw error('AI_CONVERSATION_RESPONSE_INVALID', '附件类别或状态无效。')
	}
	const url = nullableText(value.url)
	const failureCode = nullableText(value.failureCode)
	if ((state === 'AVAILABLE' && (!url || failureCode))
		|| (state === 'STORAGE_FAILED' && (url || failureCode !== 'OSS_PERSIST_FAILED'))) {
		throw error('AI_CONVERSATION_RESPONSE_INVALID', '附件存储状态不一致。')
	}
	const schemaVersion = Number(value.schemaVersion)
	if (schemaVersion !== 1) throw error('AI_CONVERSATION_RESPONSE_INVALID', '附件版本无效。')
	return Object.freeze({
		schemaVersion,
		attachmentId,
		fileName: requiredText(value.fileName, 'fileName'),
		contentType: requiredText(value.contentType, 'contentType'),
		sizeBytes,
		category,
		url,
		state,
		failureCode
	})
}

function preuploadFile(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) {
		throw error('AI_ATTACHMENT_PREUPLOAD_INVALID', '附件预上传响应无效。')
	}
	const attachmentId = publicId(value.attachmentId, ATTACHMENT_ID, 'attachmentId')
	const uploadUrl = requiredText(value.uploadUrl, 'uploadUrl')
	if (!/^https:\/\/[^\s]+$/i.test(uploadUrl)) {
		throw error('AI_ATTACHMENT_PREUPLOAD_INVALID', '附件上传地址无效。')
	}
	if (value.method !== 'PUT' || !value.uploadHeaders || typeof value.uploadHeaders !== 'object' || Array.isArray(value.uploadHeaders)) {
		throw error('AI_ATTACHMENT_PREUPLOAD_INVALID', '附件上传条件无效。')
	}
	const uploadHeaders = {}
	Object.entries(value.uploadHeaders).forEach(([name, headerValue]) => {
		if (!name.trim() || typeof headerValue !== 'string') {
			throw error('AI_ATTACHMENT_PREUPLOAD_INVALID', '附件上传请求头无效。')
		}
		uploadHeaders[name] = headerValue
	})
	return Object.freeze({
		attachmentId,
		fileName: requiredText(value.fileName, 'fileName'),
		contentType: requiredText(value.contentType, 'contentType'),
		sizeBytes: positiveDecimal(value.sizeBytes, 'sizeBytes'),
		uploadUrl,
		method: 'PUT',
		uploadHeaders: Object.freeze(uploadHeaders),
		expiresAt: requiredText(value.expiresAt, 'expiresAt')
	})
}

function generationView(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) {
		throw error('AI_CONVERSATION_RESPONSE_INVALID', '后台生成响应无效。')
	}
	const status = requiredText(value.status, 'generation.status')
	const observerStatus = requiredText(value.observerStatus, 'generation.observerStatus')
	if (!GENERATION_STATES.has(status) || !OBSERVER_STATES.has(observerStatus)) {
		throw error('AI_CONVERSATION_RESPONSE_INVALID', '后台生成状态无效。')
	}
	const observerEpoch = Number(value.observerEpoch)
	if (!Number.isSafeInteger(observerEpoch) || observerEpoch < 0) {
		throw error('AI_CONVERSATION_RESPONSE_INVALID', '后台生成观察版本无效。')
	}
	return Object.freeze({
		...value,
		generationPublicId: publicId(value.generationPublicId, HYBRID_PUBLIC_ID, 'generationPublicId'),
		conversationPublicId: publicId(value.conversationPublicId, HYBRID_PUBLIC_ID, 'conversationPublicId'),
		usagePublicId: publicId(value.usagePublicId, HYBRID_PUBLIC_ID, 'usagePublicId'),
		status,
		observerStatus,
		observerEpoch
	})
}

function historyMessage(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) {
		throw error('AI_CONVERSATION_RESPONSE_INVALID', '消息历史响应无效。')
	}
	const contentAttachments = Object.freeze((value.contentAttachments || []).map(attachment))
	const responseAttachments = Object.freeze((value.responseAttachments || []).map(attachment))
	const contentText = nullableContent(value.contentText)
	const responseText = nullableContent(value.responseText)
	if ((!contentText && !contentAttachments.length) || (!responseText && !responseAttachments.length)) {
		throw error('AI_CONVERSATION_RESPONSE_INVALID', '完整消息内容无效。')
	}
	return Object.freeze({
		messagePublicId: publicId(value.messagePublicId, LONG_PUBLIC_ID, 'messagePublicId'),
		contentText,
		contentAttachments,
		responseText,
		responseAttachments,
		createdAt: requiredText(value.createdAt, 'createdAt'),
		usagePublicId: value.usagePublicId == null ? null : publicId(value.usagePublicId, HYBRID_PUBLIC_ID, 'usagePublicId'),
		modelPublicId: value.modelPublicId == null ? null : publicId(value.modelPublicId, LONG_PUBLIC_ID, 'modelPublicId'),
		modelName: nullableText(value.modelName),
		promptTokens: nullableDecimal(value.promptTokens, 'promptTokens'),
		cachedPromptTokens: nullableDecimal(value.cachedPromptTokens, 'cachedPromptTokens'),
		completionTokens: nullableDecimal(value.completionTokens, 'completionTokens'),
		reasoningTokens: nullableDecimal(value.reasoningTokens, 'reasoningTokens'),
		chargedQuotaMinor: nullableDecimal(value.chargedQuotaMinor, 'chargedQuotaMinor'),
		finishReason: nullableText(value.finishReason)
	})
}

function pageSize(value, maximum) {
	if (!Number.isSafeInteger(value) || value < 1 || value > maximum) {
		throw error('AI_CONVERSATION_PAGE_INVALID', '分页大小无效。')
	}
	return value
}

export const aiConversationApi = Object.freeze({
	async listConversations({ cursor = '', pageSize: size = 20 } = {}) {
		pageSize(size, 50)
		if (cursor && !CURSOR.test(cursor)) throw error('AI_CONVERSATION_CURSOR_INVALID', '会话游标无效。')
		const query = new URLSearchParams({ pageSize: String(size) })
		if (cursor) query.set('cursor', cursor)
		const result = await authorizedRequest(`/api/ai/conversations?${query}`, { method: 'GET' })
		if (!Array.isArray(result?.conversations)) throw error('AI_CONVERSATION_RESPONSE_INVALID', '会话列表无效。')
		return Object.freeze({
			conversations: Object.freeze(result.conversations.map(item => {
				const conversationPublicId = requiredText(item.conversationPublicId, 'conversationPublicId')
				if (!HYBRID_PUBLIC_ID.test(conversationPublicId)) throw error('AI_CONVERSATION_RESPONSE_INVALID', '会话标识无效。')
				return Object.freeze({
					conversationPublicId,
					title: nullableText(item.title),
					lastMessagePublicId: publicId(item.lastMessagePublicId, LONG_PUBLIC_ID, 'lastMessagePublicId'),
					createdAt: requiredText(item.createdAt, 'createdAt')
				})
			})),
			nextCursor: result.nextCursor == null ? null : publicId(result.nextCursor, CURSOR, 'nextCursor'),
			hasMore: result.hasMore === true
		})
	},
	async messages(conversationPublicId, { before = '', pageSize: size = 50 } = {}) {
		if (!HYBRID_PUBLIC_ID.test(String(conversationPublicId || ''))) throw error('AI_CONVERSATION_ID_INVALID', '会话标识无效。')
		pageSize(size, 100)
		if (before && !LONG_PUBLIC_ID.test(before)) throw error('AI_MESSAGE_CURSOR_INVALID', '消息游标无效。')
		const query = new URLSearchParams({ pageSize: String(size) })
		if (before) query.set('before', before)
		const result = await authorizedRequest(`/api/ai/conversations/${encodeURIComponent(conversationPublicId)}/messages?${query}`, { method: 'GET' })
		if (!Array.isArray(result?.messages)) throw error('AI_CONVERSATION_RESPONSE_INVALID', '消息历史无效。')
		return Object.freeze({
			messages: Object.freeze(result.messages.map(historyMessage)),
			nextBefore: result.nextBefore == null ? null : publicId(result.nextBefore, LONG_PUBLIC_ID, 'nextBefore'),
			hasMore: result.hasMore === true
		})
	},
	async createPreuploads(files) {
		if (!Array.isArray(files) || files.length < 1 || files.length > 8) {
			throw error('AI_ATTACHMENT_INPUT_INVALID', '请选择 1 至 8 个文件。')
		}
		const result = await authorizedRequest('/api/ai/conversation-attachments/preuploads', {
			method: 'POST',
			data: { files: files.map(file => ({
				fileName: file.fileName,
				contentType: file.contentType,
				sizeBytes: String(file.sizeBytes)
			})) }
		})
		const uploadSessionId = publicId(result?.uploadSessionId, HYBRID_PUBLIC_ID, 'uploadSessionId')
		if (!Array.isArray(result?.files) || result.files.length !== files.length) {
			throw error('AI_ATTACHMENT_PREUPLOAD_INVALID', '附件预上传响应数量不一致。')
		}
		return Object.freeze({
			uploadSessionId,
			files: Object.freeze(result.files.map(preuploadFile))
		})
	},
	async activeGenerations() {
		const result = await authorizedRequest('/api/ai/conversations/generations', { method: 'GET' })
		if (!Array.isArray(result)) throw error('AI_CONVERSATION_RESPONSE_INVALID', '活动生成列表无效。')
		return Object.freeze(result.map(generationView))
	},
	async generationByIdempotency(idempotencyKey) {
		return generationView(await authorizedRequest('/api/ai/conversations/generations/by-idempotency', {
			method: 'GET',
			headers: { 'Idempotency-Key': requiredText(idempotencyKey, 'idempotencyKey') }
		}))
	},
	async generation(generationPublicId) {
		publicId(generationPublicId, HYBRID_PUBLIC_ID, 'generationPublicId')
		return generationView(await authorizedRequest(
			`/api/ai/conversations/generations/${encodeURIComponent(generationPublicId)}`,
			{ method: 'GET' }
		))
	},
	async cancelGeneration(generationPublicId) {
		publicId(generationPublicId, HYBRID_PUBLIC_ID, 'generationPublicId')
		return authorizedRequest(`/api/ai/conversations/generations/${encodeURIComponent(generationPublicId)}/cancel`, {
			method: 'POST'
		})
	}
})

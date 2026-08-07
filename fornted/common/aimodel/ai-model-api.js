import { authorizedRequest } from '../auth/http-client.js'

const PUBLIC_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/
const DECIMAL_PATTERN = /^(?:0|[1-9]\d*)(?:\.\d+)?$/
const INTEGER_PATTERN = /^(?:0|[1-9]\d*)$/
const DEFAULT_PAGE_NUM = 1
const DEFAULT_PAGE_SIZE = 20
const MAX_PAGE_SIZE = 50
const MAX_KEYWORD_LENGTH = 128

function responseError(message) {
	const error = new Error(message)
	error.code = 'AI_MODEL_RESPONSE_INVALID'
	return error
}

function inputError(message) {
	const error = new Error(message)
	error.code = 'AI_MODEL_PAGE_INVALID'
	return error
}

function publicIdError() {
	const error = new Error('模型标识无效。')
	error.code = 'AI_MODEL_PUBLIC_ID_INVALID'
	return error
}

function normalizedRequiredText(value, field) {
	if (typeof value !== 'string') throw responseError(`模型响应中的 ${field} 无效。`)
	const normalized = value.trim()
	if (!normalized) throw responseError(`模型响应中的 ${field} 不能为空。`)
	return normalized
}

function normalizedOptionalText(value, field) {
	if (value == null) return null
	if (typeof value !== 'string') throw responseError(`模型响应中的 ${field} 无效。`)
	return value.trim() || null
}

function normalizedStringList(value, field) {
	if (!Array.isArray(value)) throw responseError(`模型响应中的 ${field} 无效。`)
	return value.map((item) => normalizedRequiredText(item, field))
}

function normalizedOptionalStringList(value, field) {
	if (value == null) return []
	return normalizedStringList(value, field)
}

function normalizedRatio(value, field) {
	if (typeof value === 'number') {
		if (!Number.isFinite(value) || value < 0) {
			throw responseError(`模型响应中的 ${field} 无效。`)
		}
		return String(value)
	}
	if (typeof value === 'string') {
		const normalized = value.trim()
		if (DECIMAL_PATTERN.test(normalized)) return normalized
	}
	throw responseError(`模型响应中的 ${field} 无效。`)
}

function normalizedSafeInteger(value, field, minimum = 0) {
	const candidate = typeof value === 'string' && INTEGER_PATTERN.test(value.trim())
		? Number(value.trim())
		: value
	if (!Number.isSafeInteger(candidate) || candidate < minimum) {
		throw responseError(`模型响应中的 ${field} 无效。`)
	}
	return candidate
}

function normalizedBoolean(value, field) {
	if (typeof value !== 'boolean') throw responseError(`模型响应中的 ${field} 无效。`)
	return value
}

function normalizedReasoningEffortLevels(value) {
	if (!Array.isArray(value) || !value.length) {
		throw responseError('模型响应中的 supportedReasoningEffortLevels 无效。')
	}
	const levels = value.map((level) => {
		if (!Number.isSafeInteger(level) || level < 1 || level > 5) {
			throw responseError('模型响应中的 supportedReasoningEffortLevels 无效。')
		}
		return level
	})
	if (new Set(levels).size !== levels.length) {
		throw responseError('模型响应中的 supportedReasoningEffortLevels 包含重复档位。')
	}
	return levels
}

function normalizedOptionalImageGenerationLevels(value) {
	if (value == null) return []
	if (!Array.isArray(value)) {
		throw responseError('模型响应中的 supportedImageGenerationLevels 无效。')
	}
	const levels = value.map((level) => {
		if (!Number.isSafeInteger(level) || level < 1 || level > 4) {
			throw responseError('模型响应中的 supportedImageGenerationLevels 无效。')
		}
		return level
	})
	if (new Set(levels).size !== levels.length) {
		throw responseError('模型响应中的 supportedImageGenerationLevels 包含重复档位。')
	}
	return levels
}

function normalizedOptionalImageAspects(value) {
	if (value == null) return []
	if (!Array.isArray(value)) {
		throw responseError('模型响应中的 supportedImageAspects 无效。')
	}
	const allowed = new Set(['SQUARE', 'LANDSCAPE', 'PORTRAIT'])
	const aspects = value.map((aspect) => normalizedRequiredText(
		aspect, 'supportedImageAspects').toUpperCase())
	if (aspects.some(aspect => !allowed.has(aspect))
		|| new Set(aspects).size !== aspects.length) {
		throw responseError('模型响应中的 supportedImageAspects 无效。')
	}
	return aspects
}

function normalizedModel(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) {
		throw responseError('模型响应中的模型条目无效。')
	}
	const publicId = normalizedRequiredText(value.publicId, 'publicId')
	if (!PUBLIC_ID_PATTERN.test(publicId)) throw responseError('模型响应中的 publicId 无效。')
	const supportedReasoningEffortLevels =
		normalizedReasoningEffortLevels(value.supportedReasoningEffortLevels)
	const supportedImageGenerationLevels =
		normalizedOptionalImageGenerationLevels(value.supportedImageGenerationLevels)
	const supportedImageAspects =
		normalizedOptionalImageAspects(value.supportedImageAspects)
	const defaultReasoningEffortLevel = value.defaultReasoningEffortLevel
	if (!Number.isSafeInteger(defaultReasoningEffortLevel)
		|| defaultReasoningEffortLevel < 1
		|| defaultReasoningEffortLevel > 5
		|| !supportedReasoningEffortLevels.includes(defaultReasoningEffortLevel)) {
		throw responseError('模型响应中的 defaultReasoningEffortLevel 无效。')
	}
	return Object.freeze({
		publicId,
		modelName: normalizedRequiredText(value.modelName, 'modelName'),
		contextWindowTokens: normalizedSafeInteger(
			value.contextWindowTokens, 'contextWindowTokens', 1),
		contextWindowK: normalizedSafeInteger(
			value.contextWindowK, 'contextWindowK', 1),
		maxOutputTokens: normalizedSafeInteger(
			value.maxOutputTokens, 'maxOutputTokens', 1),
		maxOutputK: normalizedSafeInteger(
			value.maxOutputK, 'maxOutputK', 1),
		modelNameMatchedTokens: Object.freeze(normalizedOptionalStringList(
			value.modelNameMatchedTokens,
			'modelNameMatchedTokens'
		)),
		vendor: normalizedRequiredText(value.vendor, 'vendor'),
		description: normalizedOptionalText(value.description, 'description') || '',
		descriptionMatchedTokens: Object.freeze(normalizedStringList(
			value.descriptionMatchedTokens,
			'descriptionMatchedTokens'
		)),
		icon: normalizedOptionalText(value.icon, 'icon'),
		tags: Object.freeze(normalizedStringList(value.tags, 'tags')),
		inputRatio: normalizedRatio(value.inputRatio, 'inputRatio'),
		cachedInputRatio: normalizedRatio(value.cachedInputRatio, 'cachedInputRatio'),
		outputRatio: normalizedRatio(value.outputRatio, 'outputRatio'),
		capabilities: Object.freeze(normalizedStringList(value.capabilities, 'capabilities')),
		supportedReasoningEffortLevels: Object.freeze(supportedReasoningEffortLevels),
		supportedImageGenerationLevels: Object.freeze(supportedImageGenerationLevels),
		supportedImageAspects: Object.freeze(supportedImageAspects),
		defaultReasoningEffortLevel
	})
}

function normalizedPage(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value) || !Array.isArray(value.models)) {
		throw responseError('模型目录响应无效。')
	}
	const pageNum = normalizedSafeInteger(value.pageNum, 'pageNum', 1)
	const pageSize = normalizedSafeInteger(value.pageSize, 'pageSize', 1)
	if (pageSize > MAX_PAGE_SIZE) throw responseError('模型目录响应中的 pageSize 超出范围。')
	return Object.freeze({
		models: Object.freeze(value.models.map(normalizedModel)),
		pageNum,
		pageSize,
		total: normalizedSafeInteger(value.total, 'total'),
		pages: normalizedSafeInteger(value.pages, 'pages'),
		hasPrevious: normalizedBoolean(value.hasPrevious, 'hasPrevious'),
		hasNext: normalizedBoolean(value.hasNext, 'hasNext')
	})
}

function normalizedPageRequest({
	pageNum = DEFAULT_PAGE_NUM,
	pageSize = DEFAULT_PAGE_SIZE,
	keyword = ''
} = {}) {
	if (!Number.isSafeInteger(pageNum) || pageNum < 1) {
		throw inputError('模型目录页码无效。')
	}
	if (!Number.isSafeInteger(pageSize) || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
		throw inputError('模型目录每页数量无效。')
	}
	const normalizedKeyword = typeof keyword === 'string' ? keyword.trim() : ''
	if (typeof keyword !== 'string' || normalizedKeyword.length > MAX_KEYWORD_LENGTH) {
		throw inputError('模型目录搜索关键词无效。')
	}
	return { pageNum, pageSize, keyword: normalizedKeyword }
}

export const aiModelApi = Object.freeze({
	async list(options) {
		const { pageNum, pageSize, keyword } = normalizedPageRequest(options)
		const keywordQuery = keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''
		const response = await authorizedRequest(
			`/api/ai-models?pageNum=${pageNum}&pageSize=${pageSize}${keywordQuery}`,
			{ method: 'GET' }
		)
		return normalizedPage(response)
	},
	async detail(modelPublicId) {
		const normalized = typeof modelPublicId === 'string' ? modelPublicId.trim() : ''
		if (!PUBLIC_ID_PATTERN.test(normalized)) throw publicIdError()
		const response = await authorizedRequest(
			`/api/ai-models/${encodeURIComponent(normalized)}`,
			{ method: 'GET' }
		)
		return normalizedModel(response)
	}
})

import { authorizedRequest } from '../auth/http-client.js'

const API_KEY_PUBLIC_ID_PATTERN = /^[0-7][0-9A-HJKMNP-TV-Z]{25}$/
const MODEL_PUBLIC_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/
const CURSOR_PATTERN = /^[A-Za-z0-9_-]{38}$/
const DECIMAL_PATTERN = /^(0|[1-9][0-9]*)$/
const ZONED_DATE_TIME_PATTERN = /(Z|[+-][0-9]{2}:[0-9]{2})$/i
const MAXIMUM_RANGE_MILLISECONDS = 31 * 24 * 60 * 60 * 1000
const BILLING_STATUSES = new Set([
	'RESERVED',
	'SETTLED',
	'FAILED_REFUNDED',
	'RECONCILE_REQUIRED',
	'REFUNDED'
])

function inputError(message) {
	const error = new Error(message)
	error.code = 'API_KEY_USAGE_INPUT_INVALID'
	return error
}

function responseError() {
	const error = new Error('API Key 调用记录响应无效。')
	error.code = 'API_KEY_USAGE_RESPONSE_INVALID'
	return error
}

function objectValue(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) throw responseError()
	return value
}

function publicId(value) {
	const normalized = typeof value === 'string' ? value.trim() : ''
	if (!API_KEY_PUBLIC_ID_PATTERN.test(normalized)
		|| normalized === '00000000000000000000000000') {
		throw inputError('API Key 公共 ID 无效。')
	}
	return normalized
}

function responseText(value, nullable = false) {
	if (value == null && nullable) return null
	if (typeof value !== 'string' || !value.trim()) throw responseError()
	return value.trim()
}

function decimal(value, nullable = false) {
	if (value == null && nullable) return null
	if (typeof value !== 'string' || !DECIMAL_PATTERN.test(value)) throw responseError()
	return value
}

function responseDate(value, nullable = false) {
	if (value == null && nullable) return null
	if (typeof value !== 'string'
		|| !ZONED_DATE_TIME_PATTERN.test(value)
		|| !Number.isFinite(Date.parse(value))) throw responseError()
	return new Date(value).toISOString()
}

function inputDate(value, field) {
	if (typeof value !== 'string'
		|| !ZONED_DATE_TIME_PATTERN.test(value.trim())
		|| !Number.isFinite(Date.parse(value))) {
		throw inputError(`${field} 必须是带时区的 ISO-8601 时间。`)
	}
	return new Date(value).toISOString()
}

function normalizePeriod(value) {
	const source = objectValue(value)
	const from = responseDate(source.from)
	const to = responseDate(source.to)
	if (Date.parse(to) <= Date.parse(from)) throw responseError()
	return Object.freeze({ from, to })
}

function normalizeSummary(value) {
	const source = objectValue(value)
	return Object.freeze({
		requestCount: decimal(source.requestCount),
		promptTokens: decimal(source.promptTokens),
		cachedPromptTokens: decimal(source.cachedPromptTokens),
		uncachedPromptTokens: decimal(source.uncachedPromptTokens),
		completionTokens: decimal(source.completionTokens),
		chargedQuotaMinor: decimal(source.chargedQuotaMinor),
		pendingRequestCount: decimal(source.pendingRequestCount),
		pendingReservedQuotaMinor: decimal(source.pendingReservedQuotaMinor)
	})
}

function normalizeItem(value) {
	const source = objectValue(value)
	const billingStatus = responseText(source.billingStatus)
	if (!BILLING_STATUSES.has(billingStatus) || typeof source.stream !== 'boolean') {
		throw responseError()
	}
	const chargedQuotaMinor = decimal(source.chargedQuotaMinor, billingStatus === 'RESERVED')
	if ((billingStatus === 'RESERVED' && chargedQuotaMinor !== null)
		|| ((billingStatus === 'FAILED_REFUNDED' || billingStatus === 'REFUNDED')
			&& chargedQuotaMinor !== '0')) {
		throw responseError()
	}
	return Object.freeze({
		modelPublicId: (() => {
			const id = responseText(source.modelPublicId)
			if (!MODEL_PUBLIC_ID_PATTERN.test(id)) throw responseError()
			return id
		})(),
		modelName: responseText(source.modelName),
		vendor: responseText(source.vendor),
		stream: source.stream,
		billingStatus,
		promptTokens: decimal(source.promptTokens),
		cachedPromptTokens: decimal(source.cachedPromptTokens),
		uncachedPromptTokens: decimal(source.uncachedPromptTokens),
		completionTokens: decimal(source.completionTokens),
		chargedQuotaMinor,
		reservedQuotaMinor: decimal(source.reservedQuotaMinor),
		finishReason: responseText(source.finishReason, true),
		failureCode: responseText(source.failureCode, true),
		createdAt: responseDate(source.createdAt),
		settledAt: responseDate(source.settledAt, true)
	})
}

function normalizePage(value) {
	const source = objectValue(value)
	if (!Array.isArray(source.items) || source.items.length > 100) throw responseError()
	const nextCursor = source.nextCursor == null ? null : responseText(source.nextCursor)
	if (nextCursor != null && !CURSOR_PATTERN.test(nextCursor)) throw responseError()
	return Object.freeze({
		period: normalizePeriod(source.period),
		summary: normalizeSummary(source.summary),
		items: Object.freeze(source.items.map(normalizeItem)),
		nextCursor
	})
}

function queryOptions(options) {
	const source = options && typeof options === 'object' && !Array.isArray(options)
		? options
		: {}
	const hasFrom = source.from != null && source.from !== ''
	const hasTo = source.to != null && source.to !== ''
	if (hasFrom !== hasTo) throw inputError('from 和 to 必须同时提供。')
	const pageSize = source.pageSize == null ? 20 : Number(source.pageSize)
	if (!Number.isSafeInteger(pageSize) || pageSize < 1 || pageSize > 100) {
		throw inputError('调用记录分页大小无效。')
	}
	const cursor = source.cursor == null ? '' : String(source.cursor).trim()
	if (cursor && !CURSOR_PATTERN.test(cursor)) throw inputError('调用记录游标无效。')
	const entries = []
	if (hasFrom) {
		const from = inputDate(source.from, 'from')
		const to = inputDate(source.to, 'to')
		const duration = Date.parse(to) - Date.parse(from)
		if (duration <= 0 || duration > MAXIMUM_RANGE_MILLISECONDS) {
			throw inputError('调用记录时间范围无效。')
		}
		entries.push(['from', from], ['to', to])
	}
	if (cursor) entries.push(['cursor', cursor])
	entries.push(['pageSize', String(pageSize)])
	return entries.map(([key, value]) => `${key}=${encodeURIComponent(value)}`).join('&')
}

/**
 * 把额度最小单位按两位小数展示，全程只操作字符串，避免超过 JavaScript 安全整数时丢失精度。
 */
export function formatQuotaMinor(value) {
	if (typeof value !== 'string' || !DECIMAL_PATTERN.test(value)) return '—'
	const padded = value.padStart(3, '0')
	return `${padded.slice(0, -2)}.${padded.slice(-2)}`
}

export const apiKeyUsageApi = Object.freeze({
	async query(apiKeyPublicId, options = {}) {
		const id = publicId(apiKeyPublicId)
		const query = queryOptions(options)
		return normalizePage(await authorizedRequest(
			`/api/users/me/api-keys/${encodeURIComponent(id)}/usage?${query}`,
			{ method: 'GET' }
		))
	}
})

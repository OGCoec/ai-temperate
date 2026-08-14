import { authorizedRequest } from '../auth/http-client.js'

const PUBLIC_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/
const MASKED_KEY_PATTERN = /^sk-…[A-Za-z0-9_-]{4}$/
const FULL_KEY_PATTERN = /^sk-[A-Za-z0-9_-]{86}$/
const STRONG_ETAG_PATTERN = /^"v(0|[1-9][0-9]*)"$/
const STATUS_VALUES = new Set(['ENABLED', 'DISABLED'])

function inputError(message) {
	const error = new Error(message)
	error.code = 'API_KEY_INPUT_INVALID'
	return error
}

function responseError() {
	const error = new Error('API Key 响应数据无效。')
	error.code = 'API_KEY_RESPONSE_INVALID'
	return error
}

function objectValue(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) throw responseError()
	return value
}

function commandValue(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) {
		throw inputError('API Key 请求参数无效。')
	}
	return value
}

function hasOwn(value, property) {
	return Object.prototype.hasOwnProperty.call(value, property)
}

function normalizedPublicId(value, label = 'API Key') {
	const normalized = typeof value === 'string' ? value.trim() : ''
	if (!PUBLIC_ID_PATTERN.test(normalized)) throw inputError(`${label}公共 ID 无效。`)
	return normalized
}

function normalizedDate(value, nullable) {
	if (value == null && nullable) return null
	if (typeof value !== 'string' || !value.trim() || !Number.isFinite(Date.parse(value))) {
		throw responseError()
	}
	return value
}

function normalizedRowVersion(value) {
	if (!Number.isSafeInteger(value) || value < 0) throw responseError()
	return value
}

function normalizedSummary(value, allowSecret = false) {
	const source = objectValue(value)
	if (!allowSecret && hasOwn(source, 'apiKey')) throw responseError()
	if (typeof source.id !== 'string'
		|| !PUBLIC_ID_PATTERN.test(source.id)
		|| !MASKED_KEY_PATTERN.test(source.maskedKey)
		|| !STATUS_VALUES.has(source.status)
		|| typeof source.expired !== 'boolean') {
		throw responseError()
	}
	return Object.freeze({
		id: source.id,
		maskedKey: source.maskedKey,
		status: source.status,
		expiresAt: normalizedDate(source.expiresAt, true),
		expired: source.expired,
		lastUsedAt: normalizedDate(source.lastUsedAt, true),
		createdAt: normalizedDate(source.createdAt, false),
		updatedAt: normalizedDate(source.updatedAt, false),
		rowVersion: normalizedRowVersion(source.rowVersion)
	})
}

function normalizedModel(value) {
	const source = objectValue(value)
	if (typeof source.modelPublicId !== 'string'
		|| !PUBLIC_ID_PATTERN.test(source.modelPublicId)
		|| typeof source.modelName !== 'string'
		|| !source.modelName.trim()
		|| typeof source.vendor !== 'string'
		|| !source.vendor.trim()
		|| typeof source.enabled !== 'boolean') {
		throw responseError()
	}
	return Object.freeze({
		modelPublicId: source.modelPublicId,
		modelName: source.modelName,
		vendor: source.vendor,
		enabled: source.enabled
	})
}

function normalizedDetail(value, allowSecret = false) {
	const source = objectValue(value)
	const summary = normalizedSummary(source, allowSecret)
	if (!Array.isArray(source.models) || source.models.length > 500) throw responseError()
	const models = Object.freeze(source.models.map(normalizedModel))
	return Object.freeze({ ...summary, models })
}

function normalizedCreated(value) {
	const source = objectValue(value)
	const detail = normalizedDetail(source, true)
	if (!FULL_KEY_PATTERN.test(source.apiKey)) throw responseError()
	return Object.freeze({ ...detail, apiKey: source.apiKey })
}

function normalizedPage(value) {
	const source = objectValue(value)
	if (!Array.isArray(source.items)
		|| source.items.length > 100
		|| !(source.nextCursor == null
			|| (typeof source.nextCursor === 'string' && source.nextCursor.length <= 128))) {
		throw responseError()
	}
	return Object.freeze({
		items: Object.freeze(source.items.map(item => normalizedSummary(item))),
		nextCursor: source.nextCursor || null
	})
}

function normalizedMetadata(value, normalizer) {
	const source = objectValue(value)
	const normalized = normalizer(source.data)
	if (typeof source.etag !== 'string') throw responseError()
	const match = STRONG_ETAG_PATTERN.exec(source.etag)
	if (!match || Number(match[1]) !== normalized.rowVersion) throw responseError()
	return Object.freeze({ value: normalized, etag: source.etag })
}

function normalizedEtag(value) {
	const match = typeof value === 'string' ? STRONG_ETAG_PATTERN.exec(value) : null
	if (!match || !Number.isSafeInteger(Number(match[1]))) {
		throw inputError('API Key ETag 无效。')
	}
	return value
}

function normalizedExpiry(value) {
	if (value == null) return null
	if (typeof value !== 'string'
		|| !Number.isFinite(Date.parse(value))
		|| Date.parse(value) <= Date.now()) {
		throw inputError('API Key 过期时间无效。')
	}
	return value
}

function normalizedModelIds(values, minimum) {
	if (!Array.isArray(values) || values.length < minimum || values.length > 500) {
		throw inputError('API Key 模型授权数量无效。')
	}
	const ids = values.map(value => normalizedPublicId(value, '模型'))
	if (new Set(ids).size !== ids.length) throw inputError('API Key 模型授权不能重复。')
	return ids
}

export const apiKeyApi = Object.freeze({
	async list(options = {}) {
		const cursor = options.cursor == null ? '' : String(options.cursor).trim()
		const pageSize = options.pageSize == null ? 20 : Number(options.pageSize)
		if (cursor.length > 128 || !Number.isSafeInteger(pageSize) || pageSize < 1 || pageSize > 100) {
			throw inputError('API Key 分页参数无效。')
		}
		const cursorQuery = cursor ? `cursor=${encodeURIComponent(cursor)}&` : ''
		return normalizedPage(await authorizedRequest(
			`/api/users/me/api-keys?${cursorQuery}pageSize=${pageSize}`,
			{ method: 'GET' }
		))
	},

	async create(command) {
		const source = commandValue(command)
		const data = {
			expiresAt: normalizedExpiry(source.expiresAt),
			modelPublicIds: normalizedModelIds(source.modelPublicIds, 1)
		}
		return normalizedMetadata(await authorizedRequest('/api/users/me/api-keys', {
			method: 'POST',
			data,
			captureEtag: true
		}), normalizedCreated)
	},

	async detail(apiKeyPublicId) {
		const id = normalizedPublicId(apiKeyPublicId)
		return normalizedMetadata(await authorizedRequest(
			`/api/users/me/api-keys/${encodeURIComponent(id)}`,
			{ method: 'GET', captureEtag: true }
		), value => normalizedDetail(value))
	},

	async update(apiKeyPublicId, etag, command) {
		const id = normalizedPublicId(apiKeyPublicId)
		const source = commandValue(command)
		if (!STATUS_VALUES.has(source.status)) throw inputError('API Key 状态无效。')
		return normalizedMetadata(await authorizedRequest(
			`/api/users/me/api-keys/${encodeURIComponent(id)}`,
			{
				method: 'PUT',
				headers: { 'If-Match': normalizedEtag(etag) },
				data: { status: source.status, expiresAt: normalizedExpiry(source.expiresAt) },
				captureEtag: true
			}
		), value => normalizedDetail(value))
	},

	async replaceModels(apiKeyPublicId, etag, modelPublicIds) {
		const id = normalizedPublicId(apiKeyPublicId)
		return normalizedMetadata(await authorizedRequest(
			`/api/users/me/api-keys/${encodeURIComponent(id)}/models`,
			{
				method: 'PUT',
				headers: { 'If-Match': normalizedEtag(etag) },
				data: { modelPublicIds: normalizedModelIds(modelPublicIds, 0) },
				captureEtag: true
			}
		), value => normalizedDetail(value))
	},

	async remove(apiKeyPublicId, etag) {
		const id = normalizedPublicId(apiKeyPublicId)
		await authorizedRequest(`/api/users/me/api-keys/${encodeURIComponent(id)}`, {
			method: 'DELETE',
			headers: { 'If-Match': normalizedEtag(etag) }
		})
	}
})

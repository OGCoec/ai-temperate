import { adminRequest } from './admin-http.js'

const DISCOVERY_PATH = '/api/admin/ai-model-sources/cli-proxy/models'
const PUBLIC_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/
const MATCH_STATUSES = new Set(['MATCHED', 'UNREGISTERED'])

function contractError() {
	const error = new Error('网关模型响应格式无效，请稍后重试。')
	error.code = 'CLI_PROXY_MODEL_DISCOVERY_RESPONSE_INVALID'
	return error
}

function optionalText(value, maxLength = 128) {
	if (value === null || value === undefined) return null
	if (typeof value !== 'string') throw contractError()
	const normalized = value.trim()
	if (normalized.length > maxLength) throw contractError()
	return normalized || null
}

function optionalEpochSeconds(value) {
	if (value === null || value === undefined) return null
	const normalized = typeof value === 'string' && /^(?:0|[1-9]\d*)$/.test(value)
		? Number(value)
		: value
	if (!Number.isSafeInteger(normalized) || normalized < 0) throw contractError()
	return normalized
}

function ratio(value) {
	if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
		throw contractError()
	}
	return value
}

function normalizeModel(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)) {
		throw contractError()
	}
	const modelId = optionalText(value.modelId)
	const matchStatus = String(value.matchStatus || '')
	if (!modelId || !MATCH_STATUSES.has(matchStatus)) throw contractError()
	const model = {
		modelId,
		owner: optionalText(value.owner),
		createdEpochSeconds: optionalEpochSeconds(value.createdEpochSeconds),
		matchStatus,
		localModelPublicId: value.localModelPublicId ?? null,
		localVendor: value.localVendor ?? null,
		inputRatio: value.inputRatio ?? null,
		cachedInputRatio: value.cachedInputRatio ?? null,
		outputRatio: value.outputRatio ?? null,
		localEnabled: value.localEnabled ?? null
	}
	if (matchStatus === 'MATCHED') {
		if (!PUBLIC_ID_PATTERN.test(String(model.localModelPublicId || ''))
			|| !optionalText(model.localVendor)
			|| typeof model.localEnabled !== 'boolean') {
			throw contractError()
		}
		model.localVendor = optionalText(model.localVendor)
		model.inputRatio = ratio(model.inputRatio)
		model.cachedInputRatio = ratio(model.cachedInputRatio)
		model.outputRatio = ratio(model.outputRatio)
		return model
	}
	if (model.localModelPublicId !== null
		|| model.localVendor !== null
		|| model.inputRatio !== null
		|| model.cachedInputRatio !== null
		|| model.outputRatio !== null
		|| model.localEnabled !== null) {
		throw contractError()
	}
	return model
}

function normalizeDiscoveryResponse(value) {
	if (!value || value.source !== 'CLI_PROXY'
		|| typeof value.fetchedAt !== 'string'
		|| !Number.isFinite(Date.parse(value.fetchedAt))
		|| !Number.isInteger(value.total)
		|| value.total < 0
		|| value.total > 500
		|| !Array.isArray(value.models)
		|| value.models.length !== value.total) {
		throw contractError()
	}
	return {
		source: 'CLI_PROXY',
		fetchedAt: value.fetchedAt,
		total: value.total,
		models: value.models.map(normalizeModel)
	}
}

export function createAdminCliProxyModelApi(request = adminRequest) {
	return {
		async discover() {
			const response = await request(DISCOVERY_PATH, { method: 'GET' })
			return normalizeDiscoveryResponse(response)
		}
	}
}

export const adminCliProxyModelApi = createAdminCliProxyModelApi()

import { adminRequest } from './admin-http.js'

const BASE_PATH = '/api/admin/ai-models'
const PUBLIC_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/
const SORT_PRIORITIES = new Set(['INPUT_FIRST', 'OUTPUT_FIRST'])
const DIRECTIONS = new Set(['ASC', 'DESC'])

function contractError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

function requirePublicId(publicId) {
	const normalized = String(publicId || '').trim()
	if (!PUBLIC_ID_PATTERN.test(normalized)) {
		throw contractError('AI_MODEL_PUBLIC_ID_INVALID', '模型公共 ID 无效。')
	}
	return normalized
}

function requireListQuery(query) {
	const pageNum = Number(query?.pageNum ?? 1)
	const pageSize = Number(query?.pageSize ?? 50)
	const sortPriority = String(query?.sortPriority || 'INPUT_FIRST')
	const direction = String(query?.direction || 'ASC')
	const keyword = String(query?.keyword || '').trim()
	const enabled = query?.enabled
	if (!Number.isInteger(pageNum) || pageNum < 1
		|| !Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100
		|| !SORT_PRIORITIES.has(sortPriority)
		|| !DIRECTIONS.has(direction)
		|| keyword.length > 128
		|| ![undefined, null, true, false].includes(enabled)) {
		throw contractError('AI_MODEL_LIST_QUERY_INVALID', '模型查询参数无效。')
	}
	return { pageNum, pageSize, sortPriority, direction, keyword, enabled }
}

function appendQuery(parts, name, value) {
	if (value === undefined || value === null || value === '') return
	parts.push(`${encodeURIComponent(name)}=${encodeURIComponent(String(value))}`)
}

function responseHeader(headers, expectedName) {
	const entry = Object.entries(headers || {})
		.find(([name]) => name.toLowerCase() === expectedName.toLowerCase())
	return entry ? String(entry[1]) : ''
}

function detailResult(response) {
	const model = response?.data
	const etag = responseHeader(response?.headers, 'etag')
	if (!model || !PUBLIC_ID_PATTERN.test(String(model.publicId || '')) || !/^"v[1-9]\d*"$/.test(etag)) {
		throw contractError('AI_MODEL_RESPONSE_INVALID', '模型详情响应缺少有效的版本信息。')
	}
	return { model, etag }
}

export function createAdminAiModelApi(request = adminRequest) {
	return {
		list(query = {}) {
			const normalized = requireListQuery(query)
			const parts = []
			appendQuery(parts, 'pageNum', normalized.pageNum)
			appendQuery(parts, 'pageSize', normalized.pageSize)
			appendQuery(parts, 'keyword', normalized.keyword)
			appendQuery(parts, 'enabled', normalized.enabled)
			appendQuery(parts, 'sortPriority', normalized.sortPriority)
			appendQuery(parts, 'direction', normalized.direction)
			return request(`${BASE_PATH}?${parts.join('&')}`, { method: 'GET' })
		},

		async detail(publicId) {
			const response = await request(`${BASE_PATH}/${requirePublicId(publicId)}`, {
				method: 'GET',
				returnResponse: true
			})
			return detailResult(response)
		},

		create(command) {
			return request(BASE_PATH, {
				method: 'POST',
				data: { ...command }
			})
		},

		async patch(publicId, etag, patch) {
			if (!/^"v[1-9]\d*"$/.test(String(etag || ''))) {
				throw contractError('AI_MODEL_VERSION_REQUIRED', '模型版本信息无效，请重新加载详情。')
			}
			const response = await request(`${BASE_PATH}/${requirePublicId(publicId)}`, {
				method: 'PATCH',
				data: { ...patch },
				headers: {
					'Content-Type': 'application/merge-patch+json',
					'If-Match': etag
				},
				returnResponse: true
			})
			return detailResult(response)
		},

		setEnabled(publicId, enabled) {
			if (typeof enabled !== 'boolean') {
				throw contractError('AI_MODEL_STATUS_INVALID', '模型目标状态无效。')
			}
			return request(`${BASE_PATH}/${requirePublicId(publicId)}/status`, {
				method: 'PATCH',
				data: { enabled }
			})
		},

		setEnabledBatch(publicIds, enabled) {
			if (!Array.isArray(publicIds) || publicIds.length < 1 || publicIds.length > 100
				|| typeof enabled !== 'boolean') {
				throw contractError('AI_MODEL_BATCH_STATUS_INVALID', '批量模型状态请求无效。')
			}
			return request(`${BASE_PATH}/status/batch`, {
				method: 'POST',
				data: {
					publicIds: publicIds.map(requirePublicId),
					enabled
				}
			})
		}
	}
}

export const adminAiModelApi = createAdminAiModelApi()

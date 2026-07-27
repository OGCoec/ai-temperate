import { adminRequest } from './admin-http.js'

const BASE_PATH = '/api/admin/risk/ip2location/keys'
const MAX_KEYS = 100
const MAX_CURSOR_STEPS = 64

function contractError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

export function createAdminIp2LocationKeyApi(request = adminRequest) {
	return {
		async listAll() {
			const seenCursors = new Set()
			const entries = new Map()
			let cursor = 0
			for (let step = 0; step < MAX_CURSOR_STEPS; step += 1) {
				if (seenCursors.has(cursor)) {
					throw contractError('IP2LOCATION_CURSOR_LOOP', '凭据分页游标发生循环，请稍后重试。')
				}
				seenCursors.add(cursor)
				const page = await request(`${BASE_PATH}?cursor=${encodeURIComponent(cursor)}&size=100`, {
					method: 'GET'
				})
				if (!page || !Array.isArray(page.items)) {
					throw contractError('IP2LOCATION_RESPONSE_INVALID', '凭据列表响应无效。')
				}
				page.items.forEach(item => {
					if (item?.keyId) entries.set(String(item.keyId), item)
				})
				if (entries.size > MAX_KEYS) {
					throw contractError('IP2LOCATION_KEY_LIMIT_EXCEEDED', '服务端返回的凭据数量超过安全上限。')
				}
				const nextCursor = Number(page.nextCursor)
				if (!Number.isSafeInteger(nextCursor) || nextCursor < 0) {
					throw contractError('IP2LOCATION_RESPONSE_INVALID', '凭据分页游标无效。')
				}
				if (nextCursor === 0) return [...entries.values()]
				cursor = nextCursor
			}
			throw contractError('IP2LOCATION_CURSOR_LIMIT', '凭据分页次数超过安全上限。')
		},

		importBatch(command) {
			const payload = {
				planType: command.planType,
				initialQuota: command.initialQuota,
				mode: command.mode || 'CREATE_ONLY',
				apiKeys: [...command.apiKeys]
			}
			return request(`${BASE_PATH}/batch`, { method: 'POST', data: payload })
		},

		deleteBatch(keyIds) {
			return request(`${BASE_PATH}/delete`, {
				method: 'POST',
				data: { keyIds: [...keyIds] }
			})
		}
	}
}

export const adminIp2LocationKeyApi = createAdminIp2LocationKeyApi()

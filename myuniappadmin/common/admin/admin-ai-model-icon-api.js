import { adminRequest, adminUploadFile } from './admin-http.js'

const BASE_PATH = '/api/admin/ai-model-icons'
const PUBLIC_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/
const MAX_PAGES = 20

function contractError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

function requirePublicId(publicId) {
	const normalized = String(publicId || '').trim()
	if (!PUBLIC_ID_PATTERN.test(normalized)) {
		throw contractError('AI_MODEL_ICON_PUBLIC_ID_INVALID', '模型图标公共 ID 无效。')
	}
	return normalized
}

function requirePage(pageNum, pageSize) {
	if (!Number.isInteger(pageNum) || pageNum < 1
		|| !Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100) {
		throw contractError('AI_MODEL_ICON_PAGE_INVALID', '模型图标分页参数无效。')
	}
	return { pageNum, pageSize }
}

function requireFilePath(filePath) {
	const normalized = String(filePath || '').trim()
	if (!normalized) {
		throw contractError('AI_MODEL_ICON_FILE_REQUIRED', '请选择需要上传的图片。')
	}
	return normalized
}

export function createAdminAiModelIconApi(
	request = adminRequest,
	upload = adminUploadFile
) {
	return {
		list(pageNum = 1, pageSize = 100) {
			const page = requirePage(pageNum, pageSize)
			return request(`${BASE_PATH}?pageNum=${page.pageNum}&pageSize=${page.pageSize}`, {
				method: 'GET'
			})
		},

		async listAll() {
			const icons = []
			for (let pageNum = 1; pageNum <= MAX_PAGES; pageNum += 1) {
				const page = await this.list(pageNum, 100)
				icons.push(...(Array.isArray(page?.icons) ? page.icons : []))
				if (!page?.hasNext) return icons
			}
			throw contractError(
				'AI_MODEL_ICON_PAGE_LIMIT_EXCEEDED',
				'模型图标数量超过管理端单次加载上限。')
		},

		detail(publicId) {
			return request(`${BASE_PATH}/${requirePublicId(publicId)}`, { method: 'GET' })
		},

		createRemote(command) {
			return request(`${BASE_PATH}/remote`, {
				method: 'POST',
				data: { ...command }
			})
		},

		createUpload(command) {
			return upload(`${BASE_PATH}/upload`, {
				method: 'POST',
				filePath: requireFilePath(command?.filePath),
				name: 'file',
				formData: {
					iconName: String(command?.iconName || '').trim(),
					description: String(command?.description || '').trim()
				}
			})
		},

		patch(publicId, patch) {
			return request(`${BASE_PATH}/${requirePublicId(publicId)}`, {
				method: 'PATCH',
				data: { ...patch },
				headers: { 'Content-Type': 'application/merge-patch+json' }
			})
		},

		replaceFile(publicId, filePath) {
			// 跨端文件上传通道固定发起 POST；后端同一路径同时保留规范 PUT 与该兼容入口。
			return upload(`${BASE_PATH}/${requirePublicId(publicId)}/file`, {
				method: 'POST',
				filePath: requireFilePath(filePath),
				name: 'file'
			})
		},

		delete(publicId) {
			return request(`${BASE_PATH}/${requirePublicId(publicId)}`, {
				method: 'DELETE'
			})
		}
	}
}

export function aiModelIconUrlSource(iconUrl) {
	try {
		return new URL(String(iconUrl || '')).hostname || '未知来源'
	} catch {
		return '无效地址'
	}
}

export const adminAiModelIconApi = createAdminAiModelIconApi()

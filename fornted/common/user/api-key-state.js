function responseError() {
	const error = new Error('API Key 响应数据无效。')
	error.code = 'API_KEY_RESPONSE_INVALID'
	return error
}

/** 按“过期优先于生命周期状态”的规则生成稳定展示，禁止把未知状态伪装成可用。 */
export function apiKeyStatusPresentation(key) {
	if (!key || typeof key !== 'object') throw responseError()
	if (key.expired === true) {
		return Object.freeze({ label: '已过期', tone: 'expired' })
	}
	if (key.expired !== false) throw responseError()
	if (key.status === 'DISABLED') {
		return Object.freeze({ label: '已停用', tone: 'disabled' })
	}
	if (key.status === 'ENABLED') {
		return Object.freeze({ label: '已启用', tone: 'enabled' })
	}
	throw responseError()
}

/** 游标追加只接受首次出现的公共 ID，避免网络重试造成重复卡片。 */
export function mergeApiKeyPageItems(existing = [], incoming = []) {
	const merged = []
	const seen = new Set()
	for (const item of [...existing, ...incoming]) {
		if (!item || typeof item.id !== 'string' || seen.has(item.id)) continue
		seen.add(item.id)
		merged.push(item)
	}
	return Object.freeze(merged)
}

/** 创建结果进入列表前必须显式舍弃完整 Key 和模型详情，只保留列表契约字段。 */
export function summaryFromCreatedKey(created) {
	if (!created || typeof created !== 'object') throw responseError()
	return Object.freeze({
		id: created.id,
		maskedKey: created.maskedKey,
		status: created.status,
		expiresAt: created.expiresAt,
		expired: created.expired,
		lastUsedAt: created.lastUsedAt,
		createdAt: created.createdAt,
		updatedAt: created.updatedAt,
		rowVersion: created.rowVersion
	})
}

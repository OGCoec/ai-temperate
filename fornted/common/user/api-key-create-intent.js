const STORAGE_KEY = 'ait.user.api-key-create-intent.v1'
const SCHEMA_VERSION = 1
const UUID_V4_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const PUBLIC_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/
const ALLOWED_FIELDS = new Set([
	'schemaVersion',
	'idempotencyKey',
	'expiresAt',
	'modelPublicIds'
])

function intentError(message, code = 'API_KEY_CREATE_INTENT_INVALID') {
	const error = new Error(message)
	error.code = code
	return error
}

function browserUuidV4() {
	const secureCrypto = globalThis.crypto
	if (typeof secureCrypto?.randomUUID === 'function') {
		const generated = secureCrypto.randomUUID().toLowerCase()
		if (UUID_V4_PATTERN.test(generated)) return generated
	}
	if (typeof secureCrypto?.getRandomValues !== 'function') return ''
	const bytes = secureCrypto.getRandomValues(new Uint8Array(16))
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	const value = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
	return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`
}

function androidUuidV4() {
	// #ifdef APP-PLUS
	try {
		const JavaUuid = plus.android.importClass('java.util.UUID')
		return JavaUuid.randomUUID().toString().toLowerCase()
	} catch (_) {
		return ''
	}
	// #endif
	// #ifndef APP-PLUS
	return ''
	// #endif
}

function normalizedCommand(command) {
	if (!command || typeof command !== 'object' || Array.isArray(command)) {
		throw intentError('API Key 创建参数无效。')
	}
	const expiresAt = command.expiresAt == null ? null : command.expiresAt
	if (!(expiresAt == null || (typeof expiresAt === 'string' && Number.isFinite(Date.parse(expiresAt))))) {
		throw intentError('API Key 过期时间无效。')
	}
	if (!Array.isArray(command.modelPublicIds)
		|| command.modelPublicIds.length < 1
		|| command.modelPublicIds.length > 500
		|| command.modelPublicIds.some(value => typeof value !== 'string' || !PUBLIC_ID_PATTERN.test(value))
		|| new Set(command.modelPublicIds).size !== command.modelPublicIds.length) {
		throw intentError('API Key 模型授权无效。')
	}
	return {
		expiresAt,
		modelPublicIds: [...command.modelPublicIds]
	}
}

function normalizedIntent(value) {
	if (!value || typeof value !== 'object' || Array.isArray(value)
		|| Object.keys(value).some(field => !ALLOWED_FIELDS.has(field))
		|| value.schemaVersion !== SCHEMA_VERSION
		|| !UUID_V4_PATTERN.test(value.idempotencyKey)) {
		throw intentError('API Key 待确认创建记录无效。')
	}
	const command = normalizedCommand(value)
	return Object.freeze({
		schemaVersion: SCHEMA_VERSION,
		idempotencyKey: value.idempotencyKey,
		expiresAt: command.expiresAt,
		modelPublicIds: Object.freeze(command.modelPublicIds)
	})
}

/**
 * 第一次有效提交必须先同步持久化创建意图；存储失败时禁止发送没有恢复依据的创建请求。
 */
export function beginApiKeyCreateIntent(command) {
	const normalized = normalizedCommand(command)
	const idempotencyKey = browserUuidV4() || androidUuidV4()
	if (!UUID_V4_PATTERN.test(idempotencyKey)) {
		throw intentError(
			'当前运行环境无法安全生成 API Key 创建标识。',
			'API_KEY_CREATE_IDEMPOTENCY_UNAVAILABLE')
	}
	const intent = normalizedIntent({
		schemaVersion: SCHEMA_VERSION,
		idempotencyKey,
		expiresAt: normalized.expiresAt,
		modelPublicIds: normalized.modelPublicIds
	})
	try {
		uni.setStorageSync(STORAGE_KEY, JSON.stringify(intent))
	} catch (_) {
		throw intentError(
			'无法保存 API Key 创建状态，请释放存储空间后重试。',
			'API_KEY_CREATE_INTENT_STORAGE_FAILED')
	}
	return intent
}

/** 读取跨页面保留的非敏感创建意图；损坏或旧版本记录会立即清除。 */
export function loadApiKeyCreateIntent() {
	try {
		const raw = uni.getStorageSync(STORAGE_KEY)
		if (!raw) return null
		return normalizedIntent(JSON.parse(String(raw)))
	} catch (_) {
		clearApiKeyCreateIntent()
		return null
	}
}

/** 成功、已完成、明确放弃或退出登录时清除创建意图，不接收也不处理任何 Secret。 */
export function clearApiKeyCreateIntent() {
	try {
		uni.removeStorageSync(STORAGE_KEY)
	} catch (_) {
		// 清理失败不得把 API Key 明文写入其他位置；下次读取仍会再次校验记录。
	}
}

/** 将待确认记录恢复为不可编辑的原始请求参数。 */
export function commandFromApiKeyCreateIntent(intent) {
	const normalized = normalizedIntent(intent)
	return Object.freeze({
		expiresAt: normalized.expiresAt,
		modelPublicIds: Object.freeze([...normalized.modelPublicIds])
	})
}

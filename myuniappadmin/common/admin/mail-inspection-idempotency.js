const CANONICAL_UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

function browserUuidV4() {
	const cryptoApi = globalThis?.crypto
	if (!cryptoApi || typeof cryptoApi.getRandomValues !== 'function') {
		throw new Error('当前环境缺少安全随机数生成器。')
	}
	const bytes = new Uint8Array(16)
	cryptoApi.getRandomValues(bytes)
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('')
	return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/**
 * H5 与 Android 都只使用平台安全随机源；任何缺失都会 Fail Closed，禁止退化为 Math.random。
 */
export function createMailInspectionClientRequestId() {
	// #ifdef APP-PLUS
	if (typeof plus !== 'undefined' && plus?.os?.name === 'Android') {
		const JavaUuid = plus.android.importClass('java.util.UUID')
		return JavaUuid.randomUUID().toString()
	}
	// #endif
	return browserUuidV4()
}

export function requireMailInspectionClientRequestId(value) {
	const normalized = String(value || '')
	if (!CANONICAL_UUID_V4.test(normalized)) {
		const error = new Error('邮箱检查提交编号无效。')
		error.code = 'MAIL_INSPECTION_CLIENT_REQUEST_ID_INVALID'
		throw error
	}
	return normalized
}

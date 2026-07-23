const STORAGE_KEY = 'ait.auth.installation-id.v1'
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

function browserUuid() {
	if (typeof crypto === 'undefined' || typeof crypto.getRandomValues !== 'function') return ''
	const bytes = new Uint8Array(16)
	crypto.getRandomValues(bytes)
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	const value = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
	return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`
}

function androidUuid() {
	// #ifdef APP-PLUS
	try {
		const JavaUuid = plus.android.importClass('java.util.UUID')
		return JavaUuid.randomUUID().toString().toLowerCase()
	} catch (error) {
		return ''
	}
	// #endif
	// #ifndef APP-PLUS
	return ''
	// #endif
}

export function getDeviceInstallationId() {
	const stored = String(uni.getStorageSync(STORAGE_KEY) || '').toLowerCase()
	if (UUID_V4.test(stored)) return stored
	const generated = androidUuid() || browserUuid()
	if (!UUID_V4.test(generated)) {
		throw new Error('当前运行环境无法生成安全的安装标识。')
	}
	uni.setStorageSync(STORAGE_KEY, generated)
	return generated
}

// #ifdef MP-WEIXIN
import { createMpWeixinInstallationId } from './device-installation-mp-weixin.js'
// #endif

const STORAGE_KEY = 'ait.auth.installation-id.v1'
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
let initializationInFlight = null

function installationIdError(code, message) {
	const error = new Error(message)
	error.code = code
	return error
}

function storedInstallationId() {
	const stored = String(uni.getStorageSync(STORAGE_KEY) || '').toLowerCase()
	return UUID_V4.test(stored) ? stored : ''
}

function persistValidInstallationId(value) {
	const normalized = String(value || '').toLowerCase()
	if (!UUID_V4.test(normalized)) {
		throw installationIdError(
			'DEVICE_INSTALLATION_ID_INVALID',
			'当前运行环境生成了无效的安装标识。')
	}
	uni.setStorageSync(STORAGE_KEY, normalized)
	return normalized
}

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
	const stored = storedInstallationId()
	if (stored) return stored

	// #ifdef MP-WEIXIN
	throw installationIdError(
		'DEVICE_INSTALLATION_ID_NOT_READY',
		'微信安全安装标识尚未初始化。')
	// #endif

	// #ifndef MP-WEIXIN
	const generated = androidUuid() || browserUuid()
	if (!generated) {
		throw installationIdError(
			'DEVICE_INSTALLATION_ID_UNAVAILABLE',
			'当前运行环境无法生成安全的安装标识。')
	}
	return persistValidInstallationId(generated)
	// #endif
}

/**
 * 在任何认证请求构造 Header 之前完成安装标识初始化；并发调用共享同一个生成任务。
 */
export async function ensureDeviceInstallationId() {
	const stored = storedInstallationId()
	if (stored) return stored
	if (!initializationInFlight) {
		initializationInFlight = generateAndPersistInstallationId()
			.finally(() => { initializationInFlight = null })
	}
	return initializationInFlight
}

async function generateAndPersistInstallationId() {
	let generated = ''
	// #ifdef MP-WEIXIN
	generated = await createMpWeixinInstallationId(wx)
	// #endif

	// #ifndef MP-WEIXIN
	generated = androidUuid() || browserUuid()
	// #endif

	if (!generated) {
		throw installationIdError(
			'DEVICE_INSTALLATION_ID_UNAVAILABLE',
			'当前运行环境无法生成安全的安装标识。')
	}
	return persistValidInstallationId(generated)
}

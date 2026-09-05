import {
	emptySessionCredentials,
	hasPersistableSessionCredentials
} from './session-credentials.js'

const STORAGE_KEY = 'ait.auth.wechat-session.v1'
const SCHEMA_VERSION = 1

function isStorageAvailable() {
	return typeof uni !== 'undefined'
		&& typeof uni.getStorageSync === 'function'
		&& typeof uni.setStorageSync === 'function'
}

export function loadWechatSessionCredentials() {
	if (!isStorageAvailable()) return emptySessionCredentials()
	try {
		const raw = uni.getStorageSync(STORAGE_KEY)
		if (!raw) return emptySessionCredentials()
		const payload = typeof raw === 'string' ? JSON.parse(raw) : raw
		if (payload?.schemaVersion !== SCHEMA_VERSION) {
			clearWechatSessionCredentials()
			return emptySessionCredentials()
		}
		return {
			accessToken: typeof payload.accessToken === 'string' ? payload.accessToken : '',
			refreshToken: typeof payload.refreshToken === 'string' ? payload.refreshToken : '',
			csrfToken: typeof payload.csrfToken === 'string' ? payload.csrfToken : '',
			preAuthToken: typeof payload.preAuthToken === 'string' ? payload.preAuthToken : ''
		}
	} catch (error) {
		clearWechatSessionCredentials()
		return emptySessionCredentials()
	}
}

export function saveWechatSessionCredentials(credentials) {
	if (!isStorageAvailable()) return
	if (!hasPersistableSessionCredentials(credentials)) {
		clearWechatSessionCredentials()
		return
	}
	try {
		const payload = {
			schemaVersion: SCHEMA_VERSION,
			accessToken: typeof credentials?.accessToken === 'string' ? credentials.accessToken : '',
			refreshToken: typeof credentials?.refreshToken === 'string' ? credentials.refreshToken : '',
			csrfToken: typeof credentials?.csrfToken === 'string' ? credentials.csrfToken : '',
			preAuthToken: typeof credentials?.preAuthToken === 'string' ? credentials.preAuthToken : ''
		}
		uni.setStorageSync(STORAGE_KEY, JSON.stringify(payload))
	} catch (error) {
		// 存储异常静默，避免阻止业务流程
	}
}

export function clearWechatSessionCredentials() {
	if (!isStorageAvailable()) return
	try {
		uni.removeStorageSync(STORAGE_KEY)
	} catch (error) {
	}
}

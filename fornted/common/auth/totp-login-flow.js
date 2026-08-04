import { clientPlatform } from './config.js'
import {
	clearAndroidTotpLoginFlow,
	loadAndroidTotpLoginFlow,
	saveAndroidTotpLoginFlow
} from './android-flow-keystore.js'

const H5_STORAGE_KEY = 'ait.auth.totp-login-metadata.v1'

function valid(flow) {
	const expiresAt = Date.parse(flow?.expiresAt || flow?.totpExpiresAt || '')
	return Number.isFinite(expiresAt) && expiresAt > Date.now()
}

function h5Metadata(flow) {
	return {
		expiresAt: flow.totpExpiresAt || flow.expiresAt,
		attemptsRemaining: Number(flow.attemptsRemaining || 0)
	}
}

export function beginTotpLoginFlow(response) {
	if (!valid(response)) {
		clearTotpLoginFlow()
		return null
	}
	if (clientPlatform() === 'ANDROID') {
		saveAndroidTotpLoginFlow(response)
		return loadAndroidTotpLoginFlow()
	}
	const metadata = h5Metadata(response)
	uni.setStorageSync(H5_STORAGE_KEY, JSON.stringify(metadata))
	return metadata
}

export function loadTotpLoginFlow() {
	if (clientPlatform() === 'ANDROID') return loadAndroidTotpLoginFlow()
	try {
		const metadata = JSON.parse(uni.getStorageSync(H5_STORAGE_KEY) || 'null')
		if (valid(metadata)) return metadata
	} catch (_) {
		// 损坏或过期的非敏感元数据统一清理，真正流程凭据仍只存在 HttpOnly Cookie。
	}
	clearTotpLoginFlow()
	return null
}

export function clearTotpLoginFlow() {
	if (clientPlatform() === 'ANDROID') {
		clearAndroidTotpLoginFlow()
		return
	}
	uni.removeStorageSync(H5_STORAGE_KEY)
}

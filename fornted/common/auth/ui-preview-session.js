import { clearProfileVault, writeProfileVault } from '../user/profile-vault.js'

const STORAGE_KEY = 'ait.auth.ui-preview.enabled.v1'
const ENABLED_VALUE = '1'

const ENABLE_VALUES = new Set(['1', 'true', 'yes', 'on'])
const DISABLE_VALUES = new Set(['0', 'false', 'no', 'off'])

const PREVIEW_PROFILE = Object.freeze({
	displayName: '本地预览用户',
	email: 'preview.user@example.test',
	phone: '+14155552671'
})

function nodeEnvironment() {
	if (typeof process === 'undefined' || !process.env) return ''
	return String(process.env.NODE_ENV || '')
}

function normalizeFlag(value) {
	if (value == null) return null
	const normalized = String(Array.isArray(value) ? value[0] : value).trim().toLowerCase()
	if (ENABLE_VALUES.has(normalized)) return true
	if (DISABLE_VALUES.has(normalized)) return false
	return null
}

function previewFlagFromQuery(query) {
	if (!query || typeof query !== 'object') return null
	return normalizeFlag(query.authUiPreview ?? query.uiPreview ?? query.mockAuth)
}

function previewFlagFromLocation() {
	// #ifdef H5
	if (typeof window === 'undefined' || !window.location) return null
	try {
		const params = new URLSearchParams(window.location.search || '')
		return normalizeFlag(params.get('authUiPreview') ?? params.get('uiPreview') ?? params.get('mockAuth'))
	} catch (error) {
		return null
	}
	// #endif
	// #ifndef H5
	return null
	// #endif
}

function readStoredFlag() {
	try {
		if (typeof uni === 'undefined' || typeof uni.getStorageSync !== 'function') return false
		return uni.getStorageSync(STORAGE_KEY) === ENABLED_VALUE
	} catch (error) {
		return false
	}
}

function writeStoredFlag(enabled) {
	try {
		if (typeof uni === 'undefined') return
		if (enabled && typeof uni.setStorageSync === 'function') {
			uni.setStorageSync(STORAGE_KEY, ENABLED_VALUE)
			return
		}
		if (!enabled && typeof uni.removeStorageSync === 'function') {
			uni.removeStorageSync(STORAGE_KEY)
		}
	} catch (error) {
		// 本地预览入口不能影响真实认证流程，存储失败时按未开启处理。
	}
}

export function isAuthUiPreviewAvailable() {
	return nodeEnvironment() !== 'production'
}

export function createAuthUiPreviewProfile() {
	return { ...PREVIEW_PROFILE }
}

export function isAuthUiPreviewEnabled(query) {
	if (!isAuthUiPreviewAvailable()) return false
	const requested = previewFlagFromQuery(query)
	if (requested != null) return requested
	const browserRequested = previewFlagFromLocation()
	if (browserRequested != null) return browserRequested
	return readStoredFlag()
}

export function enableAuthUiPreviewSession() {
	if (!isAuthUiPreviewAvailable()) return false
	writeStoredFlag(true)
	writeProfileVault(createAuthUiPreviewProfile())
	return true
}

export function clearAuthUiPreviewSession() {
	writeStoredFlag(false)
	clearProfileVault()
}

export function activateAuthUiPreviewFromRoute(query) {
	const requested = previewFlagFromQuery(query) ?? previewFlagFromLocation()
	if (requested === true) return enableAuthUiPreviewSession()
	if (requested === false) {
		clearAuthUiPreviewSession()
		return false
	}
	return isAuthUiPreviewEnabled()
}

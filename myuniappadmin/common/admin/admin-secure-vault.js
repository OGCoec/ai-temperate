import { adminClientPlatform } from './admin-config.js'

const STORAGE_KEY = 'ait.admin.android-credentials.v1'
const KEY_ALIAS = 'ait-admin-credentials-v1'
const SCHEMA_VERSION = 1
let memoryState = Object.freeze({})

function emptyState() {
	return {}
}

function requireAndroid() {
	// #ifdef APP-PLUS
	if (plus.os.name !== 'Android') throw new Error('管理员安全存储仅支持 Android。')
	return
	// #endif
	// #ifndef APP-PLUS
	throw new Error('非 Android 环境不能访问 AndroidKeyStore。')
	// #endif
}

function keyStore() {
	requireAndroid()
	const KeyStore = plus.android.importClass('java.security.KeyStore')
	const store = KeyStore.getInstance('AndroidKeyStore')
	store.load(null)
	return store
}

function getOrCreateKey() {
	const store = keyStore()
	if (!store.containsAlias(KEY_ALIAS)) {
		const KeyGenerator = plus.android.importClass('javax.crypto.KeyGenerator')
		const KeyProperties = plus.android.importClass('android.security.keystore.KeyProperties')
		const Builder = plus.android.importClass('android.security.keystore.KeyGenParameterSpec$Builder')
		const generator = KeyGenerator.getInstance('AES', 'AndroidKeyStore')
		const purposes = KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
		const specification = new Builder(KEY_ALIAS, purposes)
			.setBlockModes([KeyProperties.BLOCK_MODE_GCM])
			.setEncryptionPaddings([KeyProperties.ENCRYPTION_PADDING_NONE])
			.setRandomizedEncryptionRequired(true)
			.build()
		generator.init(specification)
		generator.generateKey()
	}
	return store.getKey(KEY_ALIAS, null)
}

function encode(bytes) {
	const Base64 = plus.android.importClass('android.util.Base64')
	return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

function decode(value) {
	const Base64 = plus.android.importClass('android.util.Base64')
	return Base64.decode(value, Base64.NO_WRAP)
}

function saveAndroid(state) {
	requireAndroid()
	const Cipher = plus.android.importClass('javax.crypto.Cipher')
	const JavaString = plus.android.importClass('java.lang.String')
	const cipher = Cipher.getInstance('AES/GCM/NoPadding')
	cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
	const ciphertext = cipher.doFinal(
		new JavaString(JSON.stringify(state)).getBytes('UTF-8'))
	// uni storage 只保存随机 IV 和 AES-GCM 密文，原始流程 Token 与管理员 Token 不落入明文存储。
	uni.setStorageSync(STORAGE_KEY, JSON.stringify({
		schemaVersion: SCHEMA_VERSION,
		iv: encode(cipher.getIV()),
		ciphertext: encode(ciphertext)
	}))
}

function loadAndroid() {
	requireAndroid()
	const raw = uni.getStorageSync(STORAGE_KEY)
	if (!raw) return emptyState()
	try {
		const payload = JSON.parse(raw)
		if (payload.schemaVersion !== SCHEMA_VERSION || !payload.iv || !payload.ciphertext) {
			throw new Error('管理员安全凭据版本无效。')
		}
		const Cipher = plus.android.importClass('javax.crypto.Cipher')
		const GcmParameterSpec = plus.android.importClass('javax.crypto.spec.GCMParameterSpec')
		const JavaString = plus.android.importClass('java.lang.String')
		const cipher = Cipher.getInstance('AES/GCM/NoPadding')
		cipher.init(
			Cipher.DECRYPT_MODE,
			getOrCreateKey(),
			new GcmParameterSpec(128, decode(payload.iv)))
		const plaintext = cipher.doFinal(decode(payload.ciphertext))
		return JSON.parse(new JavaString(plaintext, 'UTF-8').toString())
	} catch (_) {
		clearAdminSecureState()
		return emptyState()
	}
}

export function loadAdminSecureState() {
	if (adminClientPlatform() === 'ANDROID') return loadAndroid()
	return { ...memoryState }
}

export function saveAdminSecureState(nextState) {
	const sanitized = {
		registerFlow: nextState?.registerFlow || undefined,
		loginFlow: nextState?.loginFlow || undefined,
		adminToken: nextState?.adminToken || undefined,
		preAuthToken: nextState?.preAuthToken || undefined
	}
	if (adminClientPlatform() === 'ANDROID') {
		saveAndroid(sanitized)
		return
	}
	memoryState = Object.freeze(sanitized)
}

export function updateAdminSecureState(patch) {
	saveAdminSecureState({ ...loadAdminSecureState(), ...patch })
}

export function clearAdminFlow(kind) {
	const state = loadAdminSecureState()
	if (kind === 'register') delete state.registerFlow
	if (kind === 'login') delete state.loginFlow
	saveAdminSecureState(state)
}

export function clearAdminSession() {
	const state = loadAdminSecureState()
	delete state.adminToken
	saveAdminSecureState(state)
}

export function clearAdminSecureState() {
	memoryState = Object.freeze({})
	uni.removeStorageSync(STORAGE_KEY)
	if (adminClientPlatform() !== 'ANDROID') return
	try {
		const store = keyStore()
		if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
	} catch (_) {
		// 密文已删除；残留的无引用 KeyStore 密钥不再能恢复任何管理员凭据。
	}
}

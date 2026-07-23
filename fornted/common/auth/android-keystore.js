import {
	emptySessionCredentials,
	hasCompleteSessionCredentials
} from './session-credentials.js'

const STORAGE_KEY = 'ait.auth.android-session.v3'
const LEGACY_STORAGE_KEY = 'ait.auth.android-rt.v1'
const KEY_ALIAS = 'ait-auth-session-v1'
const LEGACY_KEY_ALIAS = 'ait-auth-refresh-token-v1'
const SCHEMA_VERSION = 3

function requireAndroid() {
	// #ifdef APP-PLUS
	if (plus.os.name !== 'Android') throw new Error('当前安全存储仅支持 Android。')
	return true
	// #endif
	// #ifndef APP-PLUS
	throw new Error('非 Android 环境不能访问 AndroidKeyStore。')
	// #endif
}

function androidKeyStore() {
	requireAndroid()
	const KeyStore = plus.android.importClass('java.security.KeyStore')
	const keyStore = KeyStore.getInstance('AndroidKeyStore')
	keyStore.load(null)
	return keyStore
}

function clearLegacySession() {
	uni.removeStorageSync(LEGACY_STORAGE_KEY)
	try {
		const keyStore = androidKeyStore()
		if (keyStore.containsAlias(LEGACY_KEY_ALIAS)) keyStore.deleteEntry(LEGACY_KEY_ALIAS)
	} catch (error) {
		// 旧密钥清理失败不能阻止新会话使用独立别名。
	}
}

function getOrCreateKey() {
	const keyStore = androidKeyStore()
	if (!keyStore.containsAlias(KEY_ALIAS)) {
		const KeyGenerator = plus.android.importClass('javax.crypto.KeyGenerator')
		const KeyProperties = plus.android.importClass('android.security.keystore.KeyProperties')
		const Builder = plus.android.importClass('android.security.keystore.KeyGenParameterSpec$Builder')
		const generator = KeyGenerator.getInstance('AES', 'AndroidKeyStore')
		const purpose = KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
		const specification = new Builder(KEY_ALIAS, purpose)
			.setBlockModes([KeyProperties.BLOCK_MODE_GCM])
			.setEncryptionPaddings([KeyProperties.ENCRYPTION_PADDING_NONE])
			.setRandomizedEncryptionRequired(true)
			.build()
		generator.init(specification)
		generator.generateKey()
	}
	return keyStore.getKey(KEY_ALIAS, null)
}

function encode(bytes) {
	const Base64 = plus.android.importClass('android.util.Base64')
	return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

function decode(value) {
	const Base64 = plus.android.importClass('android.util.Base64')
	return Base64.decode(value, Base64.NO_WRAP)
}

export function saveAndroidSessionCredentials(credentials) {
	if (!hasCompleteSessionCredentials(credentials)) {
		clearAndroidSessionCredentials()
		return
	}
	requireAndroid()
	clearLegacySession()
	const Cipher = plus.android.importClass('javax.crypto.Cipher')
	const JavaString = plus.android.importClass('java.lang.String')
	const cipher = Cipher.getInstance('AES/GCM/NoPadding')
	cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
	const plaintext = JSON.stringify({
		accessToken: credentials.accessToken,
		refreshToken: credentials.refreshToken,
		csrfToken: credentials.csrfToken
	})
	const ciphertext = cipher.doFinal(new JavaString(plaintext).getBytes('UTF-8'))
	uni.setStorageSync(STORAGE_KEY, JSON.stringify({
		schemaVersion: SCHEMA_VERSION,
		iv: encode(cipher.getIV()),
		ciphertext: encode(ciphertext)
	}))
}

export function loadAndroidSessionCredentials() {
	requireAndroid()
	clearLegacySession()
	const raw = uni.getStorageSync(STORAGE_KEY)
	if (!raw) return emptySessionCredentials()
	try {
		const payload = JSON.parse(raw)
		if (payload.schemaVersion !== SCHEMA_VERSION || !payload.iv || !payload.ciphertext) {
			throw new Error('安全会话数据版本无效。')
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
		const credentials = JSON.parse(new JavaString(plaintext, 'UTF-8').toString())
		if (!hasCompleteSessionCredentials(credentials)) {
			throw new Error('安全会话数据不完整。')
		}
		return credentials
	} catch (error) {
		clearAndroidSessionCredentials()
		return emptySessionCredentials()
	}
}

export function clearAndroidSessionCredentials() {
	uni.removeStorageSync(STORAGE_KEY)
	uni.removeStorageSync(LEGACY_STORAGE_KEY)
	try {
		const keyStore = androidKeyStore()
		if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
		if (keyStore.containsAlias(LEGACY_KEY_ALIAS)) keyStore.deleteEntry(LEGACY_KEY_ALIAS)
	} catch (error) {
		// 密文已经移除；密钥删除失败时，下次读取仍会按损坏会话处理。
	}
}

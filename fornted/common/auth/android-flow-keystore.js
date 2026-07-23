const STORAGE_KEY = 'ait.auth.android-flow.v1'
const KEY_ALIAS = 'ait-auth-flow-v1'
const SCHEMA_VERSION = 1

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

function emptyState() {
	return { register: null, passwordReset: null }
}

function hasFutureExpiry(flow) {
	const expiresAt = Date.parse(flow?.expiresAt || '')
	return Number.isFinite(expiresAt) && expiresAt > Date.now()
}

function validRegisterFlow(flow) {
	return Boolean(flow?.registerToken && flow?.flowCsrf && flow?.challengeHandle &&
		hasFutureExpiry(flow))
}

function validPasswordResetFlow(flow) {
	return Boolean((flow?.resetFlowToken || flow?.forgetToken) &&
		flow?.challengeHandle && hasFutureExpiry(flow))
}

function sanitizeState(state) {
	return {
		register: validRegisterFlow(state?.register) ? state.register : null,
		passwordReset: validPasswordResetFlow(state?.passwordReset) ? state.passwordReset : null
	}
}

function clearStorageAndKey() {
	uni.removeStorageSync(STORAGE_KEY)
	try {
		const keyStore = androidKeyStore()
		if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
	} catch (error) {
		// 密文已经移除；密钥删除失败时，下次读取仍会按损坏流程处理。
	}
}

function readState() {
	requireAndroid()
	const raw = uni.getStorageSync(STORAGE_KEY)
	if (!raw) return emptyState()
	try {
		const payload = JSON.parse(raw)
		if (payload.schemaVersion !== SCHEMA_VERSION || !payload.iv || !payload.ciphertext) {
			throw new Error('安全流程数据版本无效。')
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
		return sanitizeState(JSON.parse(new JavaString(plaintext, 'UTF-8').toString()))
	} catch (error) {
		clearStorageAndKey()
		return emptyState()
	}
}

function writeState(nextState) {
	const state = sanitizeState(nextState)
	if (!state.register && !state.passwordReset) {
		clearStorageAndKey()
		return
	}
	requireAndroid()
	const Cipher = plus.android.importClass('javax.crypto.Cipher')
	const JavaString = plus.android.importClass('java.lang.String')
	const cipher = Cipher.getInstance('AES/GCM/NoPadding')
	cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
	const ciphertext = cipher.doFinal(new JavaString(JSON.stringify(state)).getBytes('UTF-8'))
	uni.setStorageSync(STORAGE_KEY, JSON.stringify({
		schemaVersion: SCHEMA_VERSION,
		iv: encode(cipher.getIV()),
		ciphertext: encode(ciphertext)
	}))
}

export function saveAndroidRegisterFlow(flow) {
	if (!validRegisterFlow(flow)) {
		clearAndroidRegisterFlow()
		return
	}
	const state = readState()
	state.register = {
		registerToken: flow.registerToken,
		flowCsrf: flow.flowCsrf,
		challengeHandle: flow.challengeHandle,
		expiresAt: flow.expiresAt
	}
	writeState(state)
}

export function loadAndroidRegisterFlow() {
	const state = readState()
	if (!state.register) clearAndroidRegisterFlow()
	return state.register
}

export function clearAndroidRegisterFlow() {
	const state = readState()
	state.register = null
	writeState(state)
}

export function saveAndroidPasswordResetFlow(update) {
	const state = readState()
	const current = state.passwordReset || {}
	const merged = {
		resetFlowToken: update.resetFlowToken || current.resetFlowToken || '',
		forgetToken: update.forgetToken || current.forgetToken || '',
		challengeHandle: update.challengeHandle || current.challengeHandle || '',
		expiresAt: update.expiresAt || current.expiresAt || ''
	}
	state.passwordReset = validPasswordResetFlow(merged) ? merged : null
	writeState(state)
}

export function loadAndroidPasswordResetFlow() {
	const state = readState()
	if (!state.passwordReset) clearAndroidPasswordResetFlow()
	return state.passwordReset
}

export function clearAndroidPasswordResetFlow() {
	const state = readState()
	state.passwordReset = null
	writeState(state)
}

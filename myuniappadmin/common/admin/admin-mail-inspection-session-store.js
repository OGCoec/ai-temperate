import { adminClientPlatform } from './admin-config.js'
import { clearMailInspectionCredentialExports } from './mail-inspection-credential-export.js'

const BROWSER_STORAGE_KEY = 'ait.admin.mail-inspection.v3'
const LEGACY_BROWSER_STORAGE_KEY = 'ait.admin.mail-inspection.v2'
const ANDROID_STORAGE_KEY = 'ait.admin.mail-inspection.android.v3'
const LEGACY_ANDROID_STORAGE_KEY = 'ait.admin.mail-inspection.android.v2'
const ANDROID_KEY_ALIAS = 'ait-admin-mail-inspection-v3'
const LEGACY_ANDROID_KEY_ALIAS = 'ait-admin-mail-inspection-v2'
const SCHEMA_VERSION = 3

function emptyRoot() {
	return { schemaVersion: SCHEMA_VERSION, contexts: {} }
}

function cloneRoot(root) {
	if (!root || root.schemaVersion !== SCHEMA_VERSION || !root.contexts || typeof root.contexts !== 'object') {
		return emptyRoot()
	}
	return {
		schemaVersion: SCHEMA_VERSION,
		contexts: { ...root.contexts }
	}
}

function sanitizeContext(value) {
	if (!value || typeof value !== 'object') return {}
	const context = {}
	if (/^[A-Za-z0-9_-]{22}$/.test(String(value.jobId || ''))) {
		context.jobId = value.jobId
	}
	const revision = Number(value.lastRevision)
	if (Number.isSafeInteger(revision) && revision >= 0) {
		context.lastRevision = revision
	}
	return context
}

function browserSessionStorage() {
	try {
		return typeof sessionStorage === 'undefined' ? null : sessionStorage
	} catch (_) {
		return null
	}
}

function requireAndroid() {
	// #ifdef APP-PLUS
	if (plus.os.name !== 'Android') throw new Error('邮箱检查安全存储仅支持 Android。')
	return
	// #endif
	// #ifndef APP-PLUS
	throw new Error('非 Android 环境不能访问 AndroidKeyStore。')
	// #endif
}

function androidKeyStore() {
	requireAndroid()
	const KeyStore = plus.android.importClass('java.security.KeyStore')
	const store = KeyStore.getInstance('AndroidKeyStore')
	store.load(null)
	return store
}

function getOrCreateAndroidKey() {
	const store = androidKeyStore()
	if (!store.containsAlias(ANDROID_KEY_ALIAS)) {
		const KeyGenerator = plus.android.importClass('javax.crypto.KeyGenerator')
		const KeyProperties = plus.android.importClass('android.security.keystore.KeyProperties')
		const Builder = plus.android.importClass('android.security.keystore.KeyGenParameterSpec$Builder')
		const generator = KeyGenerator.getInstance('AES', 'AndroidKeyStore')
		const purposes = KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
		const specification = new Builder(ANDROID_KEY_ALIAS, purposes)
			.setBlockModes([KeyProperties.BLOCK_MODE_GCM])
			.setEncryptionPaddings([KeyProperties.ENCRYPTION_PADDING_NONE])
			.setRandomizedEncryptionRequired(true)
			.build()
		generator.init(specification)
		generator.generateKey()
	}
	return store.getKey(ANDROID_KEY_ALIAS, null)
}

function encodeAndroid(bytes) {
	const Base64 = plus.android.importClass('android.util.Base64')
	return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

function decodeAndroid(value) {
	const Base64 = plus.android.importClass('android.util.Base64')
	return Base64.decode(value, Base64.NO_WRAP)
}

function createDefaultAndroidEncryptedStorage() {
	return {
		load() {
			requireAndroid()
			const raw = uni.getStorageSync(ANDROID_STORAGE_KEY)
			if (!raw) return emptyRoot()
			const payload = JSON.parse(raw)
			if (payload.schemaVersion !== SCHEMA_VERSION || !payload.iv || !payload.ciphertext) {
				throw new Error('邮箱检查安全存储版本无效。')
			}
			const Cipher = plus.android.importClass('javax.crypto.Cipher')
			const GcmParameterSpec = plus.android.importClass('javax.crypto.spec.GCMParameterSpec')
			const JavaString = plus.android.importClass('java.lang.String')
			const cipher = Cipher.getInstance('AES/GCM/NoPadding')
			cipher.init(
				Cipher.DECRYPT_MODE,
				getOrCreateAndroidKey(),
				new GcmParameterSpec(128, decodeAndroid(payload.iv)))
			const plaintext = cipher.doFinal(decodeAndroid(payload.ciphertext))
			return JSON.parse(new JavaString(plaintext, 'UTF-8').toString())
		},
		save(root) {
			requireAndroid()
			const Cipher = plus.android.importClass('javax.crypto.Cipher')
			const JavaString = plus.android.importClass('java.lang.String')
			const cipher = Cipher.getInstance('AES/GCM/NoPadding')
			cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAndroidKey())
			const ciphertext = cipher.doFinal(
				new JavaString(JSON.stringify(root)).getBytes('UTF-8'))
			// 这里只保存随机 IV 和密文；邮箱凭证不会进入明文 uni storage。
			uni.setStorageSync(ANDROID_STORAGE_KEY, JSON.stringify({
				schemaVersion: SCHEMA_VERSION,
				iv: encodeAndroid(cipher.getIV()),
				ciphertext: encodeAndroid(ciphertext)
			}))
		},
		clear() {
			uni.removeStorageSync(ANDROID_STORAGE_KEY)
			uni.removeStorageSync(LEGACY_ANDROID_STORAGE_KEY)
			try {
				const store = androidKeyStore()
				if (store.containsAlias(ANDROID_KEY_ALIAS)) store.deleteEntry(ANDROID_KEY_ALIAS)
				if (store.containsAlias(LEGACY_ANDROID_KEY_ALIAS)) {
					store.deleteEntry(LEGACY_ANDROID_KEY_ALIAS)
				}
			} catch (_) {
				// 密文删除后，孤立密钥不能恢复任何邮箱凭证。
			}
		}
	}
}

export function createAdminMailInspectionSessionStore(options = {}) {
	const platform = options.platform || adminClientPlatform()
	const storage = options.browserStorage === undefined
		? browserSessionStorage()
		: options.browserStorage
	const encrypted = options.androidEncryptedStorage || createDefaultAndroidEncryptedStorage()
	let fallbackRoot = emptyRoot()
	let mode = platform === 'ANDROID' ? 'ANDROID_ENCRYPTED' : storage ? 'H5_SESSION' : 'MEMORY'
	let legacyCleared = false

	function clearLegacyOnce() {
		if (legacyCleared) return
		legacyCleared = true
		try {
			if (platform === 'ANDROID') {
				uni.removeStorageSync(LEGACY_ANDROID_STORAGE_KEY)
				const keyStore = androidKeyStore()
				if (keyStore.containsAlias(LEGACY_ANDROID_KEY_ALIAS)) {
					keyStore.deleteEntry(LEGACY_ANDROID_KEY_ALIAS)
				}
			} else if (storage) {
				storage.removeItem(LEGACY_BROWSER_STORAGE_KEY)
			}
		} catch (_) {
			// 旧版本只包含本地任务上下文；清理失败时也禁止重新读取或迁移其中的凭证。
		}
	}

	function readRoot() {
		clearLegacyOnce()
		try {
			if (platform === 'ANDROID') return cloneRoot(encrypted.load())
			if (!storage) return cloneRoot(fallbackRoot)
			const raw = storage.getItem(BROWSER_STORAGE_KEY)
			return raw ? cloneRoot(JSON.parse(raw)) : emptyRoot()
		} catch (_) {
			clearPersisted()
			mode = 'MEMORY'
			return cloneRoot(fallbackRoot)
		}
	}

	function writeRoot(root) {
		const safeRoot = cloneRoot(root)
		try {
			if (platform === 'ANDROID') encrypted.save(safeRoot)
			else if (storage) storage.setItem(BROWSER_STORAGE_KEY, JSON.stringify(safeRoot))
			else fallbackRoot = safeRoot
			if (platform === 'ANDROID') mode = 'ANDROID_ENCRYPTED'
			else if (storage) mode = 'H5_SESSION'
		} catch (_) {
			// 任何持久化失败都只退化到内存，绝不把 Android 凭证改写成明文存储。
			fallbackRoot = safeRoot
			mode = 'MEMORY'
		}
	}

	function clearPersisted() {
		fallbackRoot = emptyRoot()
		try {
			if (platform === 'ANDROID') encrypted.clear()
			else if (storage) {
				storage.removeItem(BROWSER_STORAGE_KEY)
				storage.removeItem(LEGACY_BROWSER_STORAGE_KEY)
			}
		} catch (_) {}
		// 会话整体失效时同时清理 Android 私有导出目录，避免明文凭证文件跨会话残留。
		return clearMailInspectionCredentialExports({ platform })
	}

	return Object.freeze({
		load(inspectionType) {
			const root = readRoot()
			return sanitizeContext(root.contexts[String(inspectionType || '')])
		},
		save(inspectionType, value) {
			const type = String(inspectionType || '')
			if (!type) return {}
			const root = readRoot()
			const context = sanitizeContext({ ...value, updatedAt: new Date().toISOString() })
			root.contexts[type] = context
			writeRoot(root)
			return { ...context }
		},
		clear(inspectionType) {
			const root = readRoot()
			delete root.contexts[String(inspectionType || '')]
			writeRoot(root)
		},
		clearAll() {
			return clearPersisted()
		},
		persistenceMode() {
			return mode
		}
	})
}

export const adminMailInspectionSessionStore = createAdminMailInspectionSessionStore()

export function clearAdminMailInspectionSession() {
	return adminMailInspectionSessionStore.clearAll()
}

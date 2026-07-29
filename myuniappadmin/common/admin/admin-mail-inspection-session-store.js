import { adminClientPlatform } from './admin-config.js'
import { clearMailInspectionCredentialExports } from './mail-inspection-credential-export.js'

const BROWSER_STORAGE_KEY = 'ait.admin.mail-inspection.v2'
const ANDROID_STORAGE_KEY = 'ait.admin.mail-inspection.android.v2'
const ANDROID_KEY_ALIAS = 'ait-admin-mail-inspection-v2'
const SCHEMA_VERSION = 2
const MAX_DRAFT_CHARS = 1024 * 1024
const MAX_LINE_CHARS = 12288
const ALLOWED_JOB_STATUSES = new Set([
	'IDLE',
	'VALIDATING',
	'CREATING',
	'DISPATCHING',
	'SUBMISSION_UNKNOWN',
	'SERVICE_UNAVAILABLE',
	'AWAITING_CLIENT_RESUBMISSION',
	'QUEUED',
	'RUNNING',
	'AWAITING_ADMIN_RESUME',
	'RECOVERY_FAILED',
	'ABANDONED',
	'COMPLETED',
	'FAILED',
	'POLLING_INTERRUPTED',
	'EXPIRED'
])

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
	if (typeof value.draftText === 'string' && value.draftText.length <= MAX_DRAFT_CHARS) {
		context.draftText = value.draftText
	}
	if (Array.isArray(value.credentialLines)) {
		const credentialLines = value.credentialLines
			.filter(line => typeof line === 'string' && line.length <= MAX_LINE_CHARS)
		if (credentialUtf8Bytes(credentialLines) <= MAX_DRAFT_CHARS) {
			context.credentialLines = credentialLines
		}
	}
	if (/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
		.test(String(value.clientRequestId || ''))) {
		context.clientRequestId = value.clientRequestId
	}
	if (/^[A-Za-z0-9_-]{11}$/.test(String(value.jobId || ''))) context.jobId = value.jobId
	if (ALLOWED_JOB_STATUSES.has(value.jobStatus)) context.jobStatus = value.jobStatus
	if (Number.isFinite(Number(value.pollAfterMillis))) {
		context.pollAfterMillis = Math.min(10000, Math.max(1000, Number(value.pollAfterMillis)))
	}
	if (Number.isInteger(Number(value.businessConcurrency))
		&& Number(value.businessConcurrency) >= 1
		&& Number(value.businessConcurrency) <= 64) {
		context.businessConcurrency = Number(value.businessConcurrency)
	}
	for (const field of ['createdAt', 'expiresAt', 'updatedAt', 'submissionStartedAt']) {
		if (typeof value[field] === 'string' && value[field].length <= 64) context[field] = value[field]
	}
	return context
}

function credentialUtf8Bytes(lines) {
	let total = 0
	for (const line of lines) {
		for (const symbol of line) {
			const code = symbol.codePointAt(0)
			if (code <= 0x7f) total += 1
			else if (code <= 0x7ff) total += 2
			else if (code <= 0xffff) total += 3
			else total += 4
		}
	}
	return total
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
			try {
				const store = androidKeyStore()
				if (store.containsAlias(ANDROID_KEY_ALIAS)) store.deleteEntry(ANDROID_KEY_ALIAS)
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

	function readRoot() {
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
			else if (storage) storage.removeItem(BROWSER_STORAGE_KEY)
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
			return { ...context, credentialLines: [...(context.credentialLines || [])] }
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

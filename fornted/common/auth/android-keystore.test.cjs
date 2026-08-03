const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const bridge = createAndroidBridge()
let modulePromise

async function loadModule() {
	if (modulePromise) return modulePromise
	const credentialsSource = fs.readFileSync(
		path.resolve(__dirname, 'session-credentials.js'),
		'utf8'
	)
	const credentialsUrl = `data:text/javascript;base64,${Buffer.from(credentialsSource).toString('base64')}`
	globalThis.__androidSessionCredentials = await import(credentialsUrl)
	globalThis.uni = bridge.uni
	globalThis.plus = bridge.plus
	const source = fs.readFileSync(
		path.resolve(__dirname, 'android-keystore.js'),
		'utf8'
	).replace(
		/import \{[\s\S]*?\} from '\.\/session-credentials\.js'/,
		`const { emptySessionCredentials, hasPersistableAndroidCredentials } =
			globalThis.__androidSessionCredentials`
	)
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	modulePromise = import(sourceUrl)
	return modulePromise
}

test('encrypts and restores the complete Android credential set', async () => {
	const module = await loadModule()
	module.clearAndroidSessionCredentials()
	const credentials = {
		accessToken: 'access-secret',
		refreshToken: 'refresh-secret',
		csrfToken: 'csrf-secret'
	}

	module.saveAndroidSessionCredentials(credentials)

	const stored = bridge.storage.get('ait.auth.android-session.v3')
	assert.ok(stored)
	assert.equal(stored.includes(credentials.accessToken), false)
	assert.equal(stored.includes(credentials.refreshToken), false)
	assert.equal(stored.includes(credentials.csrfToken), false)
	assert.deepEqual(module.loadAndroidSessionCredentials(), credentials)
})

test('atomically replaces AT while retaining the fixed RT and CSRF', async () => {
	const module = await loadModule()
	module.clearAndroidSessionCredentials()
	module.saveAndroidSessionCredentials({
		accessToken: 'old-at', refreshToken: 'fixed-rt', csrfToken: 'fixed-csrf'
	})

	module.saveAndroidSessionCredentials({
		accessToken: 'new-at', refreshToken: 'fixed-rt', csrfToken: 'fixed-csrf'
	})

	assert.deepEqual(module.loadAndroidSessionCredentials(), {
		accessToken: 'new-at', refreshToken: 'fixed-rt', csrfToken: 'fixed-csrf'
	})
})

test('corrupted ciphertext clears both private storage and the KeyStore alias', async () => {
	const module = await loadModule()
	module.clearAndroidSessionCredentials()
	module.saveAndroidSessionCredentials({
		accessToken: 'at', refreshToken: 'rt', csrfToken: 'csrf'
	})
	const payload = JSON.parse(bridge.storage.get('ait.auth.android-session.v3'))
	payload.ciphertext = Buffer.from('broken').toString('base64')
	bridge.storage.set('ait.auth.android-session.v3', JSON.stringify(payload))

	assert.deepEqual(module.loadAndroidSessionCredentials(), {
		accessToken: '', refreshToken: '', csrfToken: '', preAuthToken: ''
	})
	assert.equal(bridge.storage.has('ait.auth.android-session.v3'), false)
	assert.equal(bridge.aliases.has('ait-auth-session-v1'), false)
})

test('logout cleanup removes the encrypted payload and its KeyStore key', async () => {
	const module = await loadModule()
	module.clearAndroidSessionCredentials()
	module.saveAndroidSessionCredentials({
		accessToken: 'at', refreshToken: 'rt', csrfToken: 'csrf'
	})

	module.clearAndroidSessionCredentials()

	assert.equal(bridge.storage.has('ait.auth.android-session.v3'), false)
	assert.equal(bridge.aliases.has('ait-auth-session-v1'), false)
})

function createAndroidBridge() {
	const storage = new Map()
	const aliases = new Map()
	const keyStore = {
		load() {},
		containsAlias(alias) { return aliases.has(alias) },
		getKey(alias) { return aliases.get(alias) },
		deleteEntry(alias) { aliases.delete(alias) }
	}
	class Builder {
		constructor(alias) { this.alias = alias }
		setBlockModes() { return this }
		setEncryptionPaddings() { return this }
		setRandomizedEncryptionRequired() { return this }
		build() { return { alias: this.alias } }
	}
	class GcmParameterSpec {
		constructor(tagLength, iv) {
			this.tagLength = tagLength
			this.iv = Buffer.from(iv)
		}
	}
	class JavaString {
		constructor(value) {
			this.value = Buffer.isBuffer(value) || value instanceof Uint8Array
				? Buffer.from(value).toString('utf8')
				: String(value)
		}
		getBytes() { return Buffer.from(this.value, 'utf8') }
		toString() { return this.value }
	}
	const Cipher = {
		ENCRYPT_MODE: 1,
		DECRYPT_MODE: 2,
		getInstance() {
			return {
				init(mode, key, specification) {
					this.mode = mode
					this.key = key
					this.iv = specification?.iv || Buffer.from('0123456789ab')
				},
				getIV() { return this.iv },
				doFinal(value) {
					const bytes = Buffer.from(value)
					const marker = Buffer.from('sealed:')
					if (this.mode === Cipher.ENCRYPT_MODE) {
						return Buffer.concat([marker, Buffer.from(bytes).reverse()])
					}
					if (!bytes.subarray(0, marker.length).equals(marker)) {
						throw new Error('authentication tag mismatch')
					}
					return Buffer.from(bytes.subarray(marker.length)).reverse()
				}
			}
		}
	}
	const classes = {
		'java.security.KeyStore': {
			getInstance() { return keyStore }
		},
		'javax.crypto.KeyGenerator': {
			getInstance() {
				return {
					init(specification) { this.specification = specification },
					generateKey() {
						const key = { alias: this.specification.alias }
						aliases.set(this.specification.alias, key)
						return key
					}
				}
			}
		},
		'android.security.keystore.KeyProperties': {
			PURPOSE_ENCRYPT: 1,
			PURPOSE_DECRYPT: 2,
			BLOCK_MODE_GCM: 'GCM',
			ENCRYPTION_PADDING_NONE: 'NoPadding'
		},
		'android.security.keystore.KeyGenParameterSpec$Builder': Builder,
		'javax.crypto.Cipher': Cipher,
		'javax.crypto.spec.GCMParameterSpec': GcmParameterSpec,
		'java.lang.String': JavaString,
		'android.util.Base64': {
			NO_WRAP: 2,
			encodeToString(value) { return Buffer.from(value).toString('base64') },
			decode(value) { return Buffer.from(value, 'base64') }
		}
	}
	return {
		storage,
		aliases,
		uni: {
			setStorageSync(key, value) { storage.set(key, value) },
			getStorageSync(key) { return storage.get(key) || '' },
			removeStorageSync(key) { storage.delete(key) }
		},
		plus: {
			os: { name: 'Android' },
			android: {
				importClass(name) {
					if (!classes[name]) throw new Error(`unsupported class: ${name}`)
					return classes[name]
				}
			}
		}
	}
}

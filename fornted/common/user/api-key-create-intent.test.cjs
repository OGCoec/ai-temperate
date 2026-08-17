const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadIntentModule(storage = new Map()) {
	globalThis.uni = {
		getStorageSync(key) { return storage.get(key) || '' },
		setStorageSync(key, value) { storage.set(key, value) },
		removeStorageSync(key) { storage.delete(key) }
	}
	Object.defineProperty(globalThis, 'crypto', {
		configurable: true,
		value: {
			getRandomValues(bytes) {
				bytes.fill(0)
				return bytes
			}
		}
	})
	const source = fs.readFileSync(
		path.resolve(__dirname, 'api-key-create-intent.js'),
		'utf8')
	return import(`${sourceUrl(source)}#${Date.now()}-${storage.size}`)
}

test('persists only a canonical UUIDv4 and the immutable original command', async () => {
	const storage = new Map()
	const module = await loadIntentModule(storage)

	const intent = module.beginApiKeyCreateIntent({
		expiresAt: null,
		modelPublicIds: ['AAAAAAAAAAI']
	})
	const persisted = JSON.parse([...storage.values()][0])

	assert.equal(intent.idempotencyKey, '00000000-0000-4000-8000-000000000000')
	assert.deepEqual(persisted, {
		schemaVersion: 1,
		idempotencyKey: '00000000-0000-4000-8000-000000000000',
		expiresAt: null,
		modelPublicIds: ['AAAAAAAAAAI']
	})
	assert.equal(JSON.stringify(persisted).includes('apiKey'), false)
	assert.equal(Object.isFrozen(intent), true)
	assert.equal(Object.isFrozen(intent.modelPublicIds), true)
})

test('restores the original command and only clears it explicitly', async () => {
	const storage = new Map()
	const module = await loadIntentModule(storage)
	const created = module.beginApiKeyCreateIntent({
		expiresAt: '2027-08-17T12:00:00Z',
		modelPublicIds: ['AAAAAAAAAAI', 'AAAAAAAAAAJ']
	})

	const restored = module.loadApiKeyCreateIntent()
	const command = module.commandFromApiKeyCreateIntent(restored)

	assert.equal(restored.idempotencyKey, created.idempotencyKey)
	assert.deepEqual(command, {
		expiresAt: '2027-08-17T12:00:00Z',
		modelPublicIds: ['AAAAAAAAAAI', 'AAAAAAAAAAJ']
	})
	assert.equal(storage.size, 1)
	module.clearApiKeyCreateIntent()
	assert.equal(storage.size, 0)
})

test('removes corrupted records without returning a resumable intent', async () => {
	const storage = new Map([['ait.user.api-key-create-intent.v1', JSON.stringify({
		schemaVersion: 1,
		idempotencyKey: '018f7777-2d11-7abc-8def-0123456789ab',
		expiresAt: null,
		modelPublicIds: ['AAAAAAAAAAI'],
		apiKey: 'must-not-survive'
	})]])
	const module = await loadIntentModule(storage)

	assert.equal(module.loadApiKeyCreateIntent(), null)
	assert.equal(storage.size, 0)
})

test('does not return an intent when persistence fails', async () => {
	const module = await loadIntentModule()
	globalThis.uni.setStorageSync = () => { throw new Error('quota exceeded') }

	assert.throws(
		() => module.beginApiKeyCreateIntent({
			expiresAt: null,
			modelPublicIds: ['AAAAAAAAAAI']
		}),
		error => error.code === 'API_KEY_CREATE_INTENT_STORAGE_FAILED')
})

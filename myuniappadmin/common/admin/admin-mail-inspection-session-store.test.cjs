const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	let source = fs.readFileSync(
		path.join(__dirname, 'admin-mail-inspection-session-store.js'),
		'utf8')
	source = source.replace(
		"import { adminClientPlatform } from './admin-config.js'",
		"const adminClientPlatform = () => 'H5'")
	source = source.replace(
		"import { clearMailInspectionCredentialExports } from './mail-inspection-credential-export.js'",
		'const clearMailInspectionCredentialExports = async () => false')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

function sessionStorageStub() {
	const values = new Map()
	return {
		getItem: key => values.get(key) || null,
		setItem: (key, value) => values.set(key, String(value)),
		removeItem: key => values.delete(key)
	}
}

test('H5 persists only a 22-character Job ID and last revision', async () => {
	const { createAdminMailInspectionSessionStore } = await loadModule()
	const storage = sessionStorageStub()
	const store = createAdminMailInspectionSessionStore({
		platform: 'H5',
		browserStorage: storage
	})

	store.save('OPENAI_STATUS', {
		jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
		lastRevision: 81,
		jobStatus: 'STREAMING',
		draftText: 'must-not-persist',
		credentialLines: ['must-not-persist'],
		results: [{ verifyToken: 'must-not-persist' }]
	})

	assert.deepEqual(store.load('OPENAI_STATUS'), {
		jobId: 'AZ9nEjRWeJCrze8SNFZ4kA',
		lastRevision: 81
	})
	const raw = storage.getItem('ait.admin.mail-inspection.v3')
	assert.doesNotMatch(raw, /STREAMING|must-not-persist|verifyToken/)
	assert.equal(storage.getItem('ait.admin.mail-inspection.v2'), null)
})

test('invalid IDs and revisions are discarded rather than normalized', async () => {
	const { createAdminMailInspectionSessionStore } = await loadModule()
	const store = createAdminMailInspectionSessionStore({
		platform: 'H5',
		browserStorage: sessionStorageStub()
	})
	store.save('OPENAI_STATUS', {
		jobId: 'AAAAAAAAAAE',
		lastRevision: -1
	})
	assert.deepEqual(store.load('OPENAI_STATUS'), {})
})

test('Android encrypted adapter receives the same minimal context', async () => {
	const { createAdminMailInspectionSessionStore } = await loadModule()
	const calls = []
	let root = { schemaVersion: 3, contexts: {} }
	const encrypted = {
		load: () => root,
		save: state => {
			root = state
			calls.push(['save', state])
		},
		clear: () => calls.push(['clear'])
	}
	const store = createAdminMailInspectionSessionStore({
		platform: 'ANDROID',
		androidEncryptedStorage: encrypted
	})

	store.save('KIRO_STATUS', {
		jobId: 'BZ9nEjRWeJCrze8SNFZ4kA',
		lastRevision: 3,
		draftText: 'must-not-persist'
	})
	assert.deepEqual(store.load('KIRO_STATUS'), {
		jobId: 'BZ9nEjRWeJCrze8SNFZ4kA',
		lastRevision: 3
	})
	assert.doesNotMatch(JSON.stringify(calls), /must-not-persist/)
	store.clearAll()
	assert.deepEqual(calls.map(call => call[0]), ['save', 'clear'])
})

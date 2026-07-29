const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	let source = fs.readFileSync(path.join(__dirname, 'admin-mail-inspection-session-store.js'), 'utf8')
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

test('H5 store persists only approved job context fields in session storage', async () => {
	const { createAdminMailInspectionSessionStore } = await loadModule()
	const storage = sessionStorageStub()
	const store = createAdminMailInspectionSessionStore({
		platform: 'H5',
		browserStorage: storage
	})

	store.save('OPENAI_STATUS', {
		draftText: 'secret-line',
		credentialLines: ['secret-line'],
		jobId: 'AAAAAAAAAAE',
		jobStatus: 'RUNNING',
		businessConcurrency: 32,
		results: [{ verifyToken: 'must-not-persist' }],
		verifyUrl: 'https://must-not-persist.example'
	})

	const restored = store.load('OPENAI_STATUS')
	assert.deepEqual(restored.credentialLines, ['secret-line'])
	assert.equal(restored.jobId, 'AAAAAAAAAAE')
	assert.equal(restored.businessConcurrency, 32)
	assert.equal('results' in restored, false)
	assert.equal('verifyUrl' in restored, false)
	assert.doesNotMatch(storage.getItem('ait.admin.mail-inspection.v2'), /must-not-persist/)
})

test('Android storage delegates to an encrypted adapter and never browser storage', async () => {
	const { createAdminMailInspectionSessionStore } = await loadModule()
	const calls = []
	const encrypted = {
		load: () => ({}),
		save: state => calls.push(['save', state]),
		clear: () => calls.push(['clear'])
	}
	const store = createAdminMailInspectionSessionStore({
		platform: 'ANDROID',
		androidEncryptedStorage: encrypted
	})

	store.save('KIRO_STATUS', { draftText: 'secret-line', credentialLines: ['secret-line'] })
	store.clearAll()
	assert.deepEqual(calls.map(call => call[0]), ['save', 'clear'])
})

test('clearing one type and all types removes retained credential lines', async () => {
	const { createAdminMailInspectionSessionStore } = await loadModule()
	const store = createAdminMailInspectionSessionStore({
		platform: 'H5',
		browserStorage: sessionStorageStub()
	})
	store.save('OPENAI_STATUS', { credentialLines: ['one'] })
	store.save('KIRO_STATUS', { credentialLines: ['two'] })
	store.clear('OPENAI_STATUS')
	assert.deepEqual(store.load('OPENAI_STATUS'), {})
	assert.deepEqual(store.load('KIRO_STATUS').credentialLines, ['two'])
	store.clearAll()
	assert.deepEqual(store.load('KIRO_STATUS'), {})
})

test('session v2 preserves more than one hundred lines under one MiB', async () => {
	const { createAdminMailInspectionSessionStore } = await loadModule()
	const store = createAdminMailInspectionSessionStore({
		platform: 'H5',
		browserStorage: sessionStorageStub()
	})
	const lines = Array.from({ length: 1000 }, (_, index) => `credential-${index}`)
	store.save('OPENAI_STATUS', {
		credentialLines: lines,
		clientRequestId: '550e8400-e29b-41d4-a716-446655440000',
		jobStatus: 'SUBMISSION_UNKNOWN',
		submissionStartedAt: '2026-07-28T12:00:00Z'
	})
	const restored = store.load('OPENAI_STATUS')
	assert.equal(restored.credentialLines.length, 1000)
	assert.equal(
		restored.clientRequestId,
		'550e8400-e29b-41d4-a716-446655440000')
})

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'session-retry-policy.js'),
		'utf8'
	)
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(sourceUrl)
}

test('never calls the removed refresh endpoint for access-token errors', async () => {
	const { SessionRenewalMode, sessionRenewalMode } = await loadModule()

	assert.equal(
		sessionRenewalMode('H5', 'AT_EXPIRED', false),
		SessionRenewalMode.NONE
	)
	assert.equal(
		sessionRenewalMode('ANDROID', 'AT_REQUIRED', false),
		SessionRenewalMode.NONE
	)
})

test('uses bootstrap only for H5 CSRF recovery', async () => {
	const { SessionRenewalMode, sessionRenewalMode } = await loadModule()

	assert.equal(
		sessionRenewalMode('H5', 'CSRF_INVALID', false),
		SessionRenewalMode.BOOTSTRAP
	)
	assert.equal(
		sessionRenewalMode('ANDROID', 'CSRF_INVALID', false),
		SessionRenewalMode.NONE
	)
})

test('Android network and PreAuth failures never trigger session bootstrap replay', async () => {
	const { SessionRenewalMode, sessionRenewalMode } = await loadModule()

	assert.equal(
		sessionRenewalMode('ANDROID', 'NETWORK_ERROR', false),
		SessionRenewalMode.NONE
	)
	assert.equal(
		sessionRenewalMode('ANDROID', 'PREAUTH_REQUIRED', false),
		SessionRenewalMode.NONE
	)
})

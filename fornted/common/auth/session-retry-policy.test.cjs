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

test('protected and recovery 401 failures terminate even without a known business code', async () => {
	const { isTerminalSessionError, SessionRequestPurpose } = await loadModule()
	for (const purpose of [SessionRequestPurpose.PROTECTED, SessionRequestPurpose.SESSION_RECOVERY]) {
		for (const code of [undefined, 'HTTP_401', 'UNRECOGNIZED_AUTH_FAILURE', 'REFRESH_TOKEN_REQUIRED']) {
			assert.equal(isTerminalSessionError({ statusCode: 401, code }, purpose), true)
		}
	}
})

test('public auth, recoverable preconditions, edge challenges and network failures do not log out', async () => {
	const { isTerminalSessionError, SessionRequestPurpose } = await loadModule()
	assert.equal(isTerminalSessionError({ statusCode: 401, code: 'CSRF_INVALID' }, SessionRequestPurpose.PUBLIC_AUTH), false)
	for (const error of [
		{ statusCode: 428, code: 'PREAUTH_REQUIRED' },
		{ statusCode: 428, code: 'EDGE_COOKIE_SCOPE_RESET_REQUIRED' },
		{ statusCode: 401, code: 'EDGE_CHALLENGE' },
		{ statusCode: 401, cfMitigated: 'challenge' },
		{ statusCode: 401, responseClassification: 'EDGE_CHALLENGE' },
		{ statusCode: 503, code: 'SERVICE_UNAVAILABLE' },
		{ code: 'NETWORK_ERROR' }
	]) assert.equal(isTerminalSessionError(error), false)
})

test('PREAUTH_REQUIRED terminates in session recovery or when session binding is lost', async () => {
	const { isTerminalSessionError, SessionRequestPurpose } = await loadModule()
	assert.equal(isTerminalSessionError(
		{ statusCode: 428, code: 'PREAUTH_REQUIRED' },
		SessionRequestPurpose.SESSION_RECOVERY
	), true)
	assert.equal(isTerminalSessionError(
		{ statusCode: 401, code: 'PREAUTH_REQUIRED' },
		SessionRequestPurpose.SESSION_RECOVERY
	), true)
	assert.equal(isTerminalSessionError(
		{ statusCode: 428, code: 'PREAUTH_REQUIRED', message: 'Authenticated PreAuth is no longer bound to this session.' },
		SessionRequestPurpose.PROTECTED
	), true)
})

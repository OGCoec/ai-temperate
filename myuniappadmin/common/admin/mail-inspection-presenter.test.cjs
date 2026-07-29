const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'mail-inspection-presenter.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('retryable exhausted flags override status-name guesses', async () => {
	const { presentMailInspectionResult } = await loadModule()
	const retryable = presentMailInspectionResult({
		lineNumber: 1,
		status: 'OAUTH_NETWORK_EXHAUSTED',
		retryable: true,
		retryExhausted: true
	})
	const permanent = presentMailInspectionResult({
		lineNumber: 2,
		status: 'OAUTH_NETWORK_EXHAUSTED',
		retryable: false,
		retryExhausted: false
	})

	assert.equal(retryable.group, 'RETRY_EXHAUSTED')
	assert.notEqual(permanent.group, 'RETRY_EXHAUSTED')
})

test('only retry-exhausted results can recover original credential lines', async () => {
	const { recoverRetryCredentialLines } = await loadModule()
	const lines = ['secret-line-1', 'secret-line-2', 'secret-line-3']
	const recovered = recoverRetryCredentialLines([
		{ lineNumber: 1, status: 'REFRESH_TOKEN_EXPIRED', retryable: false, retryExhausted: false },
		{ lineNumber: 3, status: 'IMAP_NETWORK_EXHAUSTED', retryable: true, retryExhausted: true }
	], lines)

	assert.deepEqual(recovered, ['secret-line-3'])
	assert.equal(recovered.includes('secret-line-1'), false)
})

test('business evidence labels distinguish no evidence from transport failures', async () => {
	const { presentMailInspectionResult } = await loadModule()
	const none = presentMailInspectionResult({
		lineNumber: 1,
		status: 'OPENAI_NO_REGISTRATION_EVIDENCE',
		retryable: false,
		retryExhausted: false
	})
	const failed = presentMailInspectionResult({
		lineNumber: 2,
		status: 'IMAP_AUTHENTICATION_FAILED',
		retryable: false,
		retryExhausted: false
	})

	assert.equal(none.group, 'BUSINESS')
	assert.equal(none.tone, 'neutral')
	assert.equal(failed.group, 'AUTH_ERROR')
	assert.notEqual(none.label, failed.label)
})

test('platform business statuses map to stable business categories', async () => {
	const { presentMailInspectionResult } = await loadModule()
	const cases = [
		['OPENAI_NO_REGISTRATION_EVIDENCE', 'UNREGISTERED'],
		['OPENAI_REGISTERED_NORMAL', 'REGISTERED'],
		['OPENAI_UNCLASSIFIED', 'REGISTERED'],
		['OPENAI_RESTRICTED_EVIDENCE_FOUND', 'RESTRICTED'],
		['KIRO_NO_REGISTRATION_EVIDENCE', 'UNREGISTERED'],
		['KIRO_REGISTERED_NORMAL', 'REGISTERED'],
		['KIRO_UNCLASSIFIED', 'REGISTERED'],
		['KIRO_RESTRICTED_EVIDENCE_FOUND', 'RESTRICTED'],
		['IP2_REGISTRATION_MAIL_NOT_FOUND', 'UNREGISTERED'],
		['IP2_REGISTRATION_MAIL_FOUND', 'REGISTERED'],
		['IP2_VERIFY_URL_FOUND', 'VERIFY_FOUND'],
		['IP2_VERIFY_URL_NOT_FOUND', 'VERIFY_NOT_FOUND'],
		['IP2_VERIFY_URL_MALFORMED', 'VERIFY_MALFORMED']
	]

	for (const [status, expected] of cases) {
		const presented = presentMailInspectionResult({ lineNumber: 1, status })
		assert.equal(presented.businessCategory, expected, status)
	}
	assert.equal(
		presentMailInspectionResult({ lineNumber: 1, status: 'OPENAI_UNCLASSIFIED' }).label,
		'OpenAI 邮件无法分类')
	assert.equal(
		presentMailInspectionResult({ lineNumber: 1, status: 'REFRESH_TOKEN_EXPIRED' })
			.businessCategory,
		null)
})

test('business filter options are selected by inspection type', async () => {
	const { mailInspectionBusinessCategoryOptions } = await loadModule()

	assert.deepEqual(
		mailInspectionBusinessCategoryOptions('OPENAI_STATUS').map(item => item.value),
		['UNREGISTERED', 'REGISTERED', 'RESTRICTED'])
	assert.deepEqual(
		mailInspectionBusinessCategoryOptions('KIRO_STATUS').map(item => item.value),
		['UNREGISTERED', 'REGISTERED', 'RESTRICTED'])
	assert.deepEqual(
		mailInspectionBusinessCategoryOptions('IP2LOCATION_REGISTRATION').map(item => item.value),
		['UNREGISTERED', 'REGISTERED'])
	assert.deepEqual(
		mailInspectionBusinessCategoryOptions('IP2LOCATION_VERIFY_LINK').map(item => item.value),
		['VERIFY_FOUND', 'VERIFY_NOT_FOUND', 'VERIFY_MALFORMED'])
})

test('only explicit unregistered statuses recover original credential lines', async () => {
	const { recoverUnregisteredCredentialLines } = await loadModule()
	const lines = [
		'mail-1----password-1----client-1----token-1',
		'mail-2----password-2----client-2----token-2',
		'mail-3----password-3----client-3----token-3',
		'mail-4----password-4----client-4----token-4'
	]
	const openAi = recoverUnregisteredCredentialLines('OPENAI_STATUS', [
		{ lineNumber: 4, status: 'OPENAI_NO_REGISTRATION_EVIDENCE' },
		{ lineNumber: 1, status: 'OPENAI_REGISTERED_NORMAL' },
		{ lineNumber: 3, status: 'OPENAI_RESTRICTED_EVIDENCE_FOUND' },
		{ lineNumber: 2, status: 'OPENAI_NO_REGISTRATION_EVIDENCE' },
		{ lineNumber: 99, status: 'OPENAI_NO_REGISTRATION_EVIDENCE' }
	], lines)

	assert.deepEqual(openAi, [lines[1], lines[3]])
	assert.deepEqual(
		recoverUnregisteredCredentialLines('IP2LOCATION_VERIFY_LINK', [
			{ lineNumber: 1, status: 'IP2_VERIFY_URL_NOT_FOUND' }
		], lines),
		[])
	assert.deepEqual(
		recoverUnregisteredCredentialLines('IP2LOCATION_REGISTRATION', [
			{ lineNumber: 1, status: 'IP2_REGISTRATION_MAIL_NOT_FOUND' },
			{ lineNumber: 2, status: 'IMAP_NETWORK_EXHAUSTED' }
		], lines),
		[lines[0]])
})

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const frontendRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

function method(source, start, end) {
	return source.slice(source.indexOf(start), source.indexOf(end))
}

test('verification identity summary supports email-only phone-only and combined flows', () => {
	const source = read('components/auth/verification-identity-summary.vue')

	assert.match(source, /v-if="email"/)
	assert.match(source, /v-if="phonePresentation"/)
	assert.match(source, /email:\s*\{\s*type:\s*String,\s*default:\s*''\s*\}/)
	assert.match(source, /phonePresentation:[\s\S]*default:\s*\(\)\s*=>\s*null/)
})

test('verification identity summary keeps restart inline with the last displayed contact', () => {
	const source = read('components/auth/verification-identity-summary.vue')

	assert.match(source, /restartDisabled:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
	assert.match(source, /emits:\s*\['restart'\]/)
	assert.match(source, /showEmailRestart\(\)[\s\S]*this\.email && !this\.phonePresentation/)
	assert.match(source, /showPhoneRestart\(\)[\s\S]*this\.phonePresentation/)
	assert.match(source, /class="identity-value-line"[\s\S]*v-if="showEmailRestart"[\s\S]*class="identity-restart"/)
	assert.match(source, /class="identity-value-line"[\s\S]*v-if="showPhoneRestart"[\s\S]*class="identity-restart"/)
	assert.match(source, /:disabled="restartDisabled"/)
	assert.match(source, /@click="\$emit\('restart'\)"/)
})

test('registration keeps identity editable until human verification and locks afterward', () => {
	const source = read('pages/auth/register.vue')
	const verifyHuman = method(source, 'async verifyHuman(token)', 'async sendCode(channel)')

	assert.match(source, /pendingIdentity:\s*null/)
	assert.match(source, /step === 1 \|\| \(step === 2 && !humanVerified(?: && !flowSuperseded)?\)/)
	assert.match(source, /<verification-identity-summary/)
	assert.match(source, /<verification-identity-summary[\s\S]*:restart-disabled="busy"[\s\S]*@restart="restartIdentityVerification"/)
	assert.doesNotMatch(source, />重新填写<\/button>/)
	assert.match(source, /invalidatePendingHumanFlow\(\)/)
	assert.match(verifyHuman, /const submittedFlow = this\.flow/)
	assert.match(verifyHuman, /if \(this\.flow !== submittedFlow\) return/)
})

test('code login discards a pending flow after identity edits and locks the accepted identity', () => {
	const source = read('pages/auth/login.vue')
	const verifyHuman = method(source, 'async verifyHuman(token)', 'async sendCode()')

	assert.match(source, /pendingIdentity:\s*null/)
	assert.match(source, /v-if="!humanVerified"/)
	assert.match(source, /<verification-identity-summary/)
	assert.match(source, /<verification-identity-summary[\s\S]*:restart-disabled="busy"[\s\S]*@restart="restartIdentityVerification"/)
	assert.doesNotMatch(source, />重新填写<\/button>/)
	assert.match(source, /invalidatePendingHumanFlow\(\)/)
	assert.match(verifyHuman, /const submittedFlow = this\.flow/)
	assert.match(verifyHuman, /if \(this\.flow !== submittedFlow\) return/)
})

test('password reset exposes the identity while human verification is pending and locks it afterward', () => {
	const source = read('pages/auth/password-reset.vue')
	const verifyHuman = method(source, 'async verifyHuman(token)', 'async send()')

	assert.match(source, /pendingIdentity:\s*null/)
	assert.match(source, /stage === 'START' \|\| stage === 'HUMAN'/)
	assert.match(source, /<verification-identity-summary/)
	assert.match(source, /<verification-identity-summary[\s\S]*:restart-disabled="busy"[\s\S]*@restart="restartIdentityVerification"/)
	assert.doesNotMatch(source, />重新填写<\/button>/)
	assert.match(source, /invalidatePendingHumanFlow\(\)/)
	assert.match(verifyHuman, /const submittedFlow = this\.flow/)
	assert.match(verifyHuman, /if \(this\.flow !== submittedFlow\) return/)
})

test('restart clears flow identity code cooldown and platform flow material', () => {
	const registration = read('pages/auth/register.vue')
	const login = read('pages/auth/login.vue')
	const reset = read('pages/auth/password-reset.vue')
	const registrationRestart = method(
		registration,
		'restartIdentityVerification()',
		'clearRegistrationIdentityMemory() {'
	)
	const loginRestart = method(login, 'restartIdentityVerification()', 'changeMethod(value)')
	const resetRestart = method(reset, 'restartIdentityVerification()', 'changeChannel(value)')

	for (const restart of [registrationRestart, loginRestart, resetRestart]) {
		assert.match(restart, /this\.flow = null/)
		assert.match(restart, /this\.pendingIdentity = null/)
		assert.match(restart, /this\.humanVerified = false/)
		assert.match(restart, /this\.code = ''|this\.emailCode = ''|this\.clearRegistrationIdentityMemory\(\)/)
		assert.match(restart, /this\.cooldown = 0|this\.emailCooldown = 0/)
		assert.match(restart, /this\.error = ''/)
	}

	assert.match(registrationRestart, /clearRegistrationFlowState\(\)/)
	assert.match(resetRestart, /clearAndroidPasswordResetFlow\(\)/)
	assert.doesNotMatch(registration, /restoreExistingFlow/)
})

test('phone delivery can still switch between sms and whatsapp after identity lock', () => {
	for (const page of [
		read('pages/auth/register.vue'),
		read('pages/auth/login.vue'),
		read('pages/auth/password-reset.vue')
	]) {
		assert.match(page, /<phone-delivery-method[\s\S]*v-model="phoneDeliveryMethod"[\s\S]*:disabled="busy"/)
	}
})

test('verification code transport never resubmits an editable identity', () => {
	const source = read('common/auth/auth-api.js')
	const registerSend = method(source, 'registerSend(flow, channel', 'registerVerify(flow')
	const loginSend = method(source, 'loginCodeSend(flow', 'async loginCodeVerify')
	const resetSend = method(source, 'passwordResetSend(flow', 'passwordResetVerify')

	for (const sendMethod of [registerSend, loginSend, resetSend]) {
		assert.doesNotMatch(sendMethod, /\b(?:email|phoneNumber|countryIso2)\b\s*(?:[:,}])/)
	}
})

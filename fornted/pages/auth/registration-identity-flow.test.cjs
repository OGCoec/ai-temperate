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

test('registration restores authoritative contacts before revealing verification inputs', () => {
	const source = read('pages/auth/register.vue')
	const restore = method(source, 'async restoreExistingFlow()', 'syncPhoneCountrySelection()')
	const verifyHuman = method(source, 'async verifyHuman(token)', 'async sendCode(channel)')

	assert.match(source, /registrationIdentity:\s*\{\s*email:\s*'',\s*phoneE164:\s*''\s*\}/)
	assert.ok(restore.indexOf('applyRegistrationIdentity(status)') < restore.indexOf('this.humanVerified = status.humanVerified'))
	assert.ok(verifyHuman.indexOf('applyRegistrationIdentity(currentStatus)') < verifyHuman.indexOf('this.humanVerified = true'))
	assert.ok(verifyHuman.lastIndexOf('applyRegistrationIdentity(status)') < verifyHuman.lastIndexOf('this.humanVerified = true'))
	assert.match(source, /<registration-identity-summary/)
	assert.match(source, /<template v-if="canDisplayRegistrationIdentity">[\s\S]*<registration-identity-summary[\s\S]*class="auth-code-row"/)
	assert.match(source, /v-else class="auth-banner"[\s\S]*注册联系方式暂时无法恢复，请重新开始注册/)
})

test('registration prefers verified server contacts and falls back to current-page memory', () => {
	const source = read('pages/auth/register.vue')
	const computed = method(source, 'computed: {', 'watch: {')
	const applyIdentity = method(source, 'applyRegistrationIdentity(status)', 'clearRegistrationIdentityMemory()')

	assert.match(computed, /return this\.registrationIdentity\.email \|\| this\.email/)
	assert.match(computed, /derivePhonePresentation\(this\.registrationIdentity\.phoneE164\)/)
	assert.match(computed, /if \(serverPhone\.bound\) return serverPhone/)
	assert.match(computed, /nationalDisplay: this\.phoneDisplay/)
	assert.match(applyIdentity, /status\?\.humanVerified !== true/)
	assert.match(applyIdentity, /this\.registrationIdentity = \{ email, phoneE164 \}/)
})

test('registration clears contact memory when a flow is replaced or leaves the page', () => {
	const source = read('pages/auth/register.vue')
	const verifyHuman = method(source, 'async verifyHuman(token)', 'async sendCode(channel)')

	assert.match(source, /clearRegistrationIdentityMemory\(\)/)
	assert.match(source, /message\?\.type !== 'FLOW_REPLACED'[\s\S]*clearRegistrationIdentityMemory\(\)/)
	assert.match(verifyHuman, /error\?\.code === 'REGISTRATION_FLOW_REPLACED'[\s\S]*clearRegistrationIdentityMemory\(\)/)
	assert.match(source, /onUnload\(\)[\s\S]*clearRegistrationIdentityMemory\(\)/)
	assert.match(source, /if \(result\?\.registered\)[\s\S]*clearRegistrationIdentityMemory\(\)/)
	assert.match(source, /goLogin\(\)[\s\S]*clearRegistrationIdentityMemory\(\)/)
})

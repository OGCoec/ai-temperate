const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const frontendRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

test('h5 flow vault no longer writes sensitive registration or reset tokens to sessionStorage', () => {
	const source = read('common/auth/flow-vault.js')

	assert.doesNotMatch(source, /sessionStorage/)
	assert.doesNotMatch(source, /ait\.auth\.flow-state/)
	assert.match(source, /let memoryState = \{\}/)
})

test('auth api sends flow token headers only from the Android flow vault', () => {
	const source = read('common/auth/auth-api.js')

	assert.match(source, /loadAndroidRegisterFlow/)
	assert.match(source, /loadAndroidPasswordResetFlow/)
	assert.match(source, /clientPlatform\(\) === 'ANDROID'/)
	assert.match(source, /if \(android\) \{[\s\S]*X-Register-Token/)
	assert.match(source, /if \(android && resetFlow\?\.resetFlowToken\)/)
	assert.match(source, /if \(clientPlatform\(\) !== 'ANDROID'\) return \{\}/)
})

test('android flow vault uses an alias separate from the login session keystore alias', () => {
	const source = read('common/auth/android-flow-keystore.js')

	assert.match(source, /ait-auth-flow-v1/)
	assert.doesNotMatch(source, /ait-auth-session-v1/)
	assert.match(source, /saveAndroidRegisterFlow/)
	assert.match(source, /saveAndroidPasswordResetFlow/)
	assert.match(source, /clearAndroidRegisterFlow/)
	assert.match(source, /clearAndroidPasswordResetFlow/)
})

test('register and password reset pages save and clear Android flow material without local token state', () => {
	const register = read('pages/auth/register.vue')
	const reset = read('pages/auth/password-reset.vue')

	assert.match(register, /saveAndroidRegisterFlow\(result\)/)
	assert.match(register, /clearAndroidRegisterFlow\(\)/)
	assert.doesNotMatch(register, /saveFlow|loadFlow|clearFlow/)
	assert.match(reset, /saveAndroidPasswordResetFlow\(result\)/)
	assert.match(reset, /clearAndroidPasswordResetFlow\(\)/)
	assert.doesNotMatch(reset, /forgetToken:\s*''/)
	assert.doesNotMatch(reset, /this\.forgetToken/)
})

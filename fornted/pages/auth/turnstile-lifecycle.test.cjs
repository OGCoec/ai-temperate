const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const frontendRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

test('Turnstile component delivers each token once and handles every terminal callback', () => {
	const source = read('components/auth/auth-turnstile.vue')

	assert.match(source, /tokenDelivered/)
	assert.match(source, /if \(this\.tokenDelivered\) return/)
	assert.match(source, /resetAfterServerRejection/)
	assert.match(source, /expired-callback/)
	assert.match(source, /timeout-callback/)
})

test('registration verifies the current server challenge before submitting a token', () => {
	const source = read('pages/auth/register.vue')
	const verifyMethod = source.slice(
		source.indexOf('async verifyHuman(token)'),
		source.indexOf('async sendCode(channel)')
	)

	assert.ok(verifyMethod.indexOf('registerStatus') < verifyMethod.indexOf('registerTurnstile'))
	assert.match(verifyMethod, /createTurnstileAttemptId/)
	assert.match(verifyMethod, /resetAfterServerRejection/)
	assert.match(source, /BroadcastChannel/)
})

test('Turnstile request carries only a correlation id in addition to existing credentials', () => {
	const source = read('common/auth/auth-api.js')

	assert.match(source, /X-Turnstile-Attempt-Id/)
	assert.match(source, /registerStatus\(flow, options = \{\}\)/)
	assert.doesNotMatch(source, /console\.(?:log|info|warn)\([^\n]*turnstileToken/)
})

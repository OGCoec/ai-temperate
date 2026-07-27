const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'admin-hcaptcha-error.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('hCaptcha exposes only whitelisted diagnostic codes', async () => {
	const { normalizeHcaptchaErrorCode } = await loadModule()

	for (const code of [
		'network-error',
		'challenge-error',
		'internal-error',
		'invalid-data',
		'rate-limited',
		'script-error'
	]) {
		assert.equal(normalizeHcaptchaErrorCode(code), code)
	}
	assert.equal(normalizeHcaptchaErrorCode('token=<secret>'), 'unknown')
})

test('hCaptcha retries only transient and script-loading errors', async () => {
	const { hcaptchaErrorPolicy } = await loadModule()

	for (const code of ['network-error', 'challenge-error', 'internal-error', 'script-error']) {
		assert.equal(hcaptchaErrorPolicy(code).retryable, true, code)
	}
	for (const code of ['invalid-data', 'rate-limited', 'unknown', 'arbitrary']) {
		assert.equal(hcaptchaErrorPolicy(code).retryable, false, code)
	}
})

test('hCaptcha page message contains only the sanitized code', async () => {
	const { hcaptchaErrorPolicy } = await loadModule()

	assert.deepEqual(hcaptchaErrorPolicy('network-error'), {
		code: 'network-error',
		retryable: true,
		message: '管理员安全验证失败（代码：network-error）。'
	})
	assert.equal(hcaptchaErrorPolicy('<secret>').message, '管理员安全验证失败（代码：unknown）。')
})

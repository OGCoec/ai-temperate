const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'turnstile-client-error.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('Turnstile error codes accept only six decimal digits', async () => {
	const { normalizeTurnstileErrorCode } = await loadModule()

	assert.equal(normalizeTurnstileErrorCode('110600'), '110600')
	assert.equal(normalizeTurnstileErrorCode(300123), '300123')
	for (const value of ['11060', '1106000', '11a600', '<script>', null, undefined]) {
		assert.equal(normalizeTurnstileErrorCode(value), 'unknown')
	}
})

test('Turnstile retries only transient provider error families', async () => {
	const { turnstileErrorPolicy } = await loadModule()

	for (const code of ['110600', '110620', '200500', '300001', '600999']) {
		assert.equal(turnstileErrorPolicy(code).retryable, true, code)
	}
	for (const code of ['110100', '110200', '400020', 'unknown', 'not-a-code']) {
		assert.equal(turnstileErrorPolicy(code).retryable, false, code)
	}
})

test('Turnstile page message contains only the sanitized code', async () => {
	const { turnstileErrorPolicy } = await loadModule()

	assert.deepEqual(turnstileErrorPolicy('300010'), {
		code: '300010',
		retryable: true,
		message: '安全验证失败（代码：300010）。'
	})
	assert.equal(turnstileErrorPolicy('token=<secret>').message, '安全验证失败（代码：unknown）。')
})

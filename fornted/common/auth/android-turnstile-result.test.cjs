const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const modulePath = path.resolve(__dirname, 'android-turnstile-result.js')
const channel = 'attempt_m4x8k2p9_0001_native'

async function loadResultModule() {
	const source = fs.readFileSync(modulePath, 'utf8')
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(sourceUrl)
}

test('Android Turnstile result matcher accepts a complete verified callback URL', async () => {
	const { ANDROID_TURNSTILE_RESULT_URL_MATCH } = await loadResultModule()
	const resultUrl = `aiturnstile://verified?channel=${channel}&token=${encodeURIComponent('0.sample_token-value')}`

	assert.equal(new RegExp(ANDROID_TURNSTILE_RESULT_URL_MATCH).test(resultUrl), true)
	assert.notEqual(ANDROID_TURNSTILE_RESULT_URL_MATCH, 'aiturnstile://*')
})

test('Android Turnstile result parser accepts each supported result kind', async () => {
	const { parseAndroidTurnstileResult } = await loadResultModule()

	assert.deepEqual(
		parseAndroidTurnstileResult(
			`aiturnstile://verified?token=${encodeURIComponent('0.sample_token-value')}&channel=${channel}`,
			channel
		),
		{ type: 'VERIFIED', token: '0.sample_token-value' }
	)
	assert.deepEqual(
		parseAndroidTurnstileResult(`aiturnstile://error?channel=${channel}&code=300030`, channel),
		{ type: 'ERROR', code: '300030' }
	)
	assert.deepEqual(
		parseAndroidTurnstileResult(`aiturnstile://expired?channel=${channel}`, channel),
		{ type: 'EXPIRED' }
	)
	assert.deepEqual(
		parseAndroidTurnstileResult(`aiturnstile://timeout?channel=${channel}`, channel),
		{ type: 'TIMEOUT' }
	)
})

test('Android Turnstile result parser rejects stale, ambiguous, malformed, and oversized results', async () => {
	const { parseAndroidTurnstileResult } = await loadResultModule()
	const validToken = encodeURIComponent('0.sample_token-value')

	for (const resultUrl of [
		`aiturnstile://verified?channel=attempt_stale_0001&token=${validToken}`,
		`aiturnstile://verified?token=${validToken}`,
		`aiturnstile://verified?channel=${channel}&channel=${channel}&token=${validToken}`,
		`aiturnstile://verified?channel=${channel}&token=${validToken}&unknown=value`,
		`aiturnstile://verified?channel=${channel}&token=%`,
		`aiturnstile://verified?channel=${channel}&token=bad%0Avalue`,
		`aiturnstile://verified?channel=${channel}&token=${validToken}#fragment`,
		`aiturnstile://cancelled?channel=${channel}`,
		`https://example.com/?channel=${channel}&token=${validToken}`,
		`aiturnstile://error?channel=${channel}&code=not_allowed`
	]) {
		assert.equal(parseAndroidTurnstileResult(resultUrl, channel), null, resultUrl)
	}

	const oversizedToken = encodeURIComponent('A'.repeat(4097))
	assert.equal(
		parseAndroidTurnstileResult(
			`aiturnstile://verified?channel=${channel}&token=${oversizedToken}`,
			channel
		),
		null
	)
})

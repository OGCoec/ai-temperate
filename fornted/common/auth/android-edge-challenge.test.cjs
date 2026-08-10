const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const source = file => fs.readFileSync(path.join(root, file), 'utf8')

async function loadPolicy() {
	const policy = source('common/auth/android-edge-challenge-policy.js')
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(policy).toString('base64')}`
	return import(sourceUrl)
}

function edgeChallenge() {
	const error = new Error('challenge')
	error.code = 'EDGE_CHALLENGE'
	error.cfMitigated = 'challenge'
	error.cfRay = 'test-ray-ord'
	return error
}

test('extracts only a bounded cf_clearance pair from the WebView cookie jar', async () => {
	const { extractAndroidClearanceCookie } = await loadPolicy()

	assert.equal(
		extractAndroidClearanceCookie(
			'access_token=secret; cf_clearance=valid.value-_123; XSRF-TOKEN=secret'),
		'cf_clearance=valid.value-_123'
	)
	assert.equal(extractAndroidClearanceCookie('access_token=secret'), '')
	assert.equal(extractAndroidClearanceCookie('cf_clearance=bad\r\nvalue'), '')
	assert.equal(extractAndroidClearanceCookie(`cf_clearance=${'a'.repeat(4097)}`), '')
})

test('replays a confirmed Android edge challenge exactly once', async () => {
	const { executeWithAndroidEdgeChallengeRecovery } = await loadPolicy()
	let attempts = 0
	let clearanceAttempts = 0

	const result = await executeWithAndroidEdgeChallengeRecovery(
		async () => {
			attempts += 1
			if (attempts === 1) throw edgeChallenge()
			return 'ok'
		},
		async () => { clearanceAttempts += 1 }
	)

	assert.equal(result, 'ok')
	assert.equal(attempts, 2)
	assert.equal(clearanceAttempts, 1)
})

test('does not replay ordinary failures and fails closed on a repeated challenge', async () => {
	const { executeWithAndroidEdgeChallengeRecovery } = await loadPolicy()
	let clearanceAttempts = 0
	const ordinary = Object.assign(new Error('backend rejection'), {
		code: 'ACCOUNT_UNAVAILABLE'
	})

	await assert.rejects(
		() => executeWithAndroidEdgeChallengeRecovery(
			async () => { throw ordinary },
			async () => { clearanceAttempts += 1 }
		),
		error => error === ordinary
	)
	await assert.rejects(
		() => executeWithAndroidEdgeChallengeRecovery(
			async () => { throw edgeChallenge() },
			async () => { clearanceAttempts += 1 }
		),
		error => error?.code === 'EDGE_CHALLENGE_REPEATED'
			&& error?.cfRay === 'test-ray-ord'
	)
	assert.equal(clearanceAttempts, 1)
})

test('Android coordinator uses a single full-screen managed challenge without persisting credentials', () => {
	const coordinator = source('common/auth/android-edge-challenge.js')
	const policy = source('common/auth/android-edge-challenge-policy.js')
	const http = source('common/auth/http-client.js')
	const preAuth = source('common/auth/pre-auth.js')

	assert.match(coordinator, /\/__edge\/android-clearance/)
	assert.match(coordinator, /\/__edge\/android-clearance\/status/)
	assert.match(coordinator, /ait-edge:\/\/verified/)
	assert.match(coordinator, /plus\.webview\.create/)
	assert.match(coordinator, /overrideUrlLoading/)
	assert.match(coordinator, /android\.webkit\.CookieManager/)
	assert.match(coordinator, /clearanceInFlight/)
	assert.match(policy, /EDGE_CHALLENGE_TIMEOUT/)
	assert.match(policy, /EDGE_CHALLENGE_CANCELLED/)
	assert.match(policy, /EDGE_CLEARANCE_NOT_SHARED/)
	assert.doesNotMatch(`${coordinator}\n${policy}`,
		/localStorage|sessionStorage|setStorage|saveAndroidSessionCredentials|console\./)
	assert.match(http, /runAndroidRequestWithEdgeRecovery/)
	assert.match(http, /androidEdgeRequestHeaders/)
	assert.match(preAuth, /runAndroidRequestWithEdgeRecovery/)
	assert.match(preAuth, /androidEdgeRequestHeaders/)
})

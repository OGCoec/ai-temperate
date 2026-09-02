const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'session-credentials.js'),
		'utf8'
	)
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(sourceUrl)
}

test('partial session update replaces AT and CSRF while preserving the fixed RT', async () => {
	const { mergeSessionCredentials } = await loadModule()
	const current = {
		accessToken: 'old-at',
		refreshToken: 'fixed-rt',
		csrfToken: 'old-csrf'
	}

	assert.deepEqual(
		mergeSessionCredentials(current, {
			accessToken: 'new-at',
			csrfToken: 'new-csrf'
		}),
		{
			accessToken: 'new-at',
			refreshToken: 'fixed-rt',
			csrfToken: 'new-csrf',
			preAuthToken: ''
		}
	)
})

test('requires all three credentials before persisting an Android session', async () => {
	const { hasCompleteSessionCredentials } = await loadModule()

	assert.equal(hasCompleteSessionCredentials({
		accessToken: 'at', refreshToken: 'rt', csrfToken: 'csrf'
	}), true)
	assert.equal(hasCompleteSessionCredentials({
		accessToken: '', refreshToken: 'rt', csrfToken: 'csrf'
	}), false)
})

test('Android OAuth completion requires the rotated PreAuth as part of the response', async () => {
	const { hasCompleteAndroidOAuthCredentials } = await loadModule()
	assert.equal(hasCompleteAndroidOAuthCredentials({
		accessToken: 'at', refreshToken: 'rt', csrfToken: 'csrf', preAuthToken: 'preauth'
	}), true)
	assert.equal(hasCompleteAndroidOAuthCredentials({
		accessToken: 'at', refreshToken: 'rt', csrfToken: 'csrf'
	}), false)
})

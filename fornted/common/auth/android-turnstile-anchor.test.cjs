const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadAnchorModule() {
	const source = fs.readFileSync(path.resolve(__dirname, 'android-turnstile-anchor.js'), 'utf8')
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(sourceUrl)
}

test('Android Turnstile converts viewport coordinates into static document coordinates', async () => {
	const {
		ANDROID_TURNSTILE_HOST_HEIGHT,
		ANDROID_TURNSTILE_WIDTH,
		resolveAndroidTurnstileAnchor
	} = await loadAnchorModule()

	assert.equal(ANDROID_TURNSTILE_WIDTH, 240)
	assert.equal(ANDROID_TURNSTILE_HOST_HEIGHT, 76)
	assert.deepEqual(
		resolveAndroidTurnstileAnchor({ left: 24.4, top: -40.2, width: 300, height: 76 }, 260.3),
		{ left: 24, top: 220, width: 240, height: 76 }
	)
})

test('Android Turnstile keeps real offscreen coordinates and rejects invalid hosts', async () => {
	const { resolveAndroidTurnstileAnchor } = await loadAnchorModule()

	assert.deepEqual(
		resolveAndroidTurnstileAnchor({ left: 10, top: -180, width: 300, height: 76 }, 0),
		{ left: 10, top: -180, width: 240, height: 76 }
	)
	assert.equal(resolveAndroidTurnstileAnchor(null, 0), null)
	assert.equal(resolveAndroidTurnstileAnchor({ left: 10, top: 20, width: 0, height: 76 }, 0), null)
	assert.equal(resolveAndroidTurnstileAnchor({ left: Number.NaN, top: 20, width: 300, height: 76 }, 0), null)
})

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadWebViewModule() {
	const resultSource = fs.readFileSync(path.resolve(__dirname, 'android-turnstile-result.js'), 'utf8')
	const resultUrl = `data:text/javascript;base64,${Buffer.from(resultSource).toString('base64')}`
	const anchorSource = fs.readFileSync(path.resolve(__dirname, 'android-turnstile-anchor.js'), 'utf8')
	const anchorUrl = `data:text/javascript;base64,${Buffer.from(anchorSource).toString('base64')}`
	const sessionSource = fs.readFileSync(path.resolve(__dirname, 'android-turnstile-webview.js'), 'utf8')
		.replace("'./android-turnstile-result.js'", `'${resultUrl}'`)
		.replace("'./android-turnstile-anchor.js'", `'${anchorUrl}'`)
	const sessionUrl = `data:text/javascript;base64,${Buffer.from(sessionSource).toString('base64')}`
	return import(sessionUrl)
}

function createHarness() {
	const calls = []
	const listeners = new Map()
	let overrideHandler = null
	let currentUrl = ''
	let closeCount = 0
	let timeoutHandler = null
	const webview = {
		overrideUrlLoading(options, handler) {
			calls.push(['override', options])
			overrideHandler = handler
		},
		addEventListener(name, handler) {
			calls.push(['listen', name])
			listeners.set(name, handler)
		},
		getURL() { return currentUrl },
		setStyle(styles) { calls.push(['style', styles]) },
		close() {
			closeCount += 1
			listeners.get('close')?.()
		}
	}
	const manager = {
		create(url, id, styles) {
			calls.push(['create', url, id, styles])
			return webview
		}
	}
	const parentWebview = {
		append(child) { calls.push(['append', child]) }
	}

	return {
		calls,
		manager,
		parentWebview,
		webview,
		closeCount: () => closeCount,
		setCurrentUrl(value) { currentUrl = value },
		triggerOverride(url) { overrideHandler?.({ url }) },
		trigger(name, event = {}) { listeners.get(name)?.(event) },
		setTimeout(handler) {
			timeoutHandler = handler
			return 7
		},
		clearTimeout() { timeoutHandler = null },
		triggerTimeout() { timeoutHandler?.() }
	}
}

function baseOptions(harness, overrides = {}) {
	return {
		webviewManager: harness.manager,
		parentWebview: harness.parentWebview,
		webviewId: 'ait-auth-turnstile-1',
		channel: 'attempt_m4x8k2p9_0001_native',
		bounds: { left: 20, top: 180, width: 240, height: 76 },
		load(webview) { harness.calls.push(['load', webview]) },
		setTimer: harness.setTimeout,
		clearTimer: harness.clearTimeout,
		...overrides
	}
}

test('Android Turnstile registers callbacks, appends the child, and only then loads HTTPS content', async () => {
	const { createAndroidTurnstileWebViewSession } = await loadWebViewModule()
	const harness = createHarness()

	createAndroidTurnstileWebViewSession(baseOptions(harness))

	const names = harness.calls.map((call) => call[0])
	assert.deepEqual(names.slice(0, 8), [
		'create', 'override', 'listen', 'listen', 'listen', 'listen', 'append', 'load'
	])
	assert.deepEqual(harness.calls[0][3], {
		left: '20px',
		top: '180px',
		width: '240px',
		height: '76px',
		position: 'static',
		background: 'transparent',
		plusrequire: 'none',
		render: 'always'
	})
	assert.deepEqual(harness.calls[1][1], {
		mode: 'reject',
		effect: 'instant',
		exclude: 'none',
		match: '^aiturnstile://(?:verified|error|expired|timeout)(?:\\?.*)?$'
	})
})

test('Android Turnstile delivers a verified result once across callback and loading fallback', async () => {
	const { createAndroidTurnstileWebViewSession } = await loadWebViewModule()
	const harness = createHarness()
	const results = []
	let nativeFailures = 0
	const url = 'aiturnstile://verified?channel=attempt_m4x8k2p9_0001_native&token=0.sample_token'
	createAndroidTurnstileWebViewSession(baseOptions(harness, {
		onResult(result, source) { results.push({ result, source }) },
		onError() { nativeFailures += 1 },
		onClosed() { nativeFailures += 1 }
	}))

	harness.triggerOverride(url)
	harness.setCurrentUrl(url)
	harness.trigger('loading')
	harness.triggerOverride(
		'aiturnstile://error?channel=attempt_m4x8k2p9_0001_native&code=300030'
	)
	harness.trigger('error')
	harness.trigger('close')

	assert.deepEqual(results, [
		{ result: { type: 'VERIFIED', token: '0.sample_token' }, source: 'override' }
	])
	assert.equal(nativeFailures, 0)
})

test('Android Turnstile deduplicates one native error signal but accepts a later provider retry failure', async () => {
	const { createAndroidTurnstileWebViewSession } = await loadWebViewModule()
	const harness = createHarness()
	const results = []
	let now = 1000
	const url = 'aiturnstile://error?channel=attempt_m4x8k2p9_0001_native&code=300030'
	createAndroidTurnstileWebViewSession(baseOptions(harness, {
		now: () => now,
		onResult(result) { results.push(result) }
	}))

	harness.triggerOverride(url)
	harness.setCurrentUrl(url)
	harness.trigger('loading')
	now += 2000
	harness.triggerOverride(url)

	assert.deepEqual(results, [
		{ type: 'ERROR', code: '300030' },
		{ type: 'ERROR', code: '300030' }
	])
})

test('Android Turnstile keeps static offscreen coordinates, times out, and closes idempotently', async () => {
	const { createAndroidTurnstileWebViewSession } = await loadWebViewModule()
	const harness = createHarness()
	let timedOut = 0
	const session = createAndroidTurnstileWebViewSession(baseOptions(harness, {
		onTimeout() { timedOut += 1 }
	}))

	session.setBounds({ left: 30, top: 220, width: 240, height: 76 })
	session.setBounds({ left: 30, top: -120, width: 240, height: 76 })
	harness.triggerTimeout()
	session.close()

	const styleCalls = harness.calls.filter((call) => call[0] === 'style')
	assert.equal(styleCalls.length, 2)
	assert.equal(styleCalls[0][1].left, '30px')
	assert.deepEqual(styleCalls[1][1], {
		left: '30px',
		top: '-120px',
		width: '240px',
		height: '76px'
	})
	assert.equal(timedOut, 1)
	assert.equal(harness.closeCount(), 1)
})

test('Android Turnstile reports the protected page loaded once per session', async () => {
	const { createAndroidTurnstileWebViewSession } = await loadWebViewModule()
	const harness = createHarness()
	let loaded = 0
	createAndroidTurnstileWebViewSession(baseOptions(harness, {
		onLoaded() { loaded += 1 }
	}))

	harness.trigger('loaded')
	harness.setCurrentUrl('https://niko000o.site/api/auth/turnstile/page?challenge=sample')
	harness.trigger('loaded')
	harness.trigger('loaded')

	assert.equal(loaded, 1)
})

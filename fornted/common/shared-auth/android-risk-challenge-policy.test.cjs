const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = __dirname
const source = file => fs.readFileSync(path.join(root, file), 'utf8')

async function loadPolicy() {
	const policy = source('android-risk-challenge.js')
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(policy).toString('base64')}`
	return import(sourceUrl)
}

function challenge(overrides = {}) {
	return {
		code: 'RISK_CHALLENGE_REQUIRED',
		preAuthToken: 'A'.repeat(43),
		challengeRef: 'B'.repeat(43),
		challengePath: '/api/_edge/risk-challenge',
		expiresAt: new Date(Date.now() + 180000).toISOString(),
		...overrides
	}
}

const rootConfig = Object.freeze({
	origin: 'https://niko000o.site',
	challengePath: '/api/_edge/risk-challenge',
	completionPath: '/pages/risk/challenge-complete',
	cookieName: '__Host-ait-preauth',
	webviewId: 'ait-user-risk-challenge'
})

test('initializes in an Android App service context without the browser URL global', async () => {
	const {
		createAndroidRiskChallengeCoordinator,
		validateAndroidRiskChallenge
	} = await loadPolicy()
	const previousUrl = global.URL
	global.URL = undefined
	try {
		const now = Date.now()
		const coordinator = createAndroidRiskChallengeCoordinator(rootConfig)
		const result = validateAndroidRiskChallenge(
			challenge({ expiresAt: new Date(now + 180000).toISOString() }),
			rootConfig,
			now)

		assert.equal(typeof coordinator.ensure, 'function')
		assert.equal(
			result.challengeUrl,
			`https://niko000o.site/api/_edge/risk-challenge?ref=${'B'.repeat(43)}`)
	} finally {
		global.URL = previousUrl
	}
})

test('builds only the configured HTTPS challenge and temporary HttpOnly cookie', async () => {
	const { validateAndroidRiskChallenge } = await loadPolicy()
	const result = validateAndroidRiskChallenge(
		challenge(),
		rootConfig,
		Date.parse('2026-08-10T18:00:00Z'))

	assert.equal(
		result.challengeUrl,
		`https://niko000o.site/api/_edge/risk-challenge?ref=${'B'.repeat(43)}`)
	assert.equal(
		result.completionUrl,
		'https://niko000o.site/pages/risk/challenge-complete')
	assert.equal(
		result.cookie,
		`__Host-ait-preauth=${'A'.repeat(43)}; Path=/; Max-Age=180; Secure; HttpOnly; SameSite=Strict`)
	assert.match(result.expiredCookie, /^__Host-ait-preauth=; Path=\/; Max-Age=0;/)
})

test('rejects crossed paths, expired references, and unsafe cookie values', async () => {
	const { validateAndroidRiskChallenge } = await loadPolicy()
	const now = Date.parse('2026-08-10T18:00:00Z')

	for (const input of [
		challenge({ challengePath: '/api/admin/_edge/risk-challenge' }),
		challenge({ expiresAt: '2026-08-10T17:59:59Z' }),
		challenge({ preAuthToken: `${'A'.repeat(42)}\n` }),
		challenge({ challengeRef: '../redirect' })
	]) {
		assert.throws(
			() => validateAndroidRiskChallenge(input, rootConfig, now),
			error => error?.code === 'RISK_CHALLENGE_RECHECK_FAILED')
	}
})

test('retries an Android risk rejection once and fails closed when it repeats', async () => {
	const { executeWithAndroidRiskChallengeRecovery } = await loadPolicy()
	let attempts = 0
	let challengeAttempts = 0
	const result = await executeWithAndroidRiskChallengeRecovery(
		async () => {
			attempts += 1
			if (attempts === 1) throw challenge()
			return 'ready'
		},
		async () => { challengeAttempts += 1 })

	assert.equal(result, 'ready')
	assert.equal(attempts, 2)
	assert.equal(challengeAttempts, 1)

	await assert.rejects(
		() => executeWithAndroidRiskChallengeRecovery(
			async () => { throw challenge() },
			async () => {}),
		error => error?.code === 'RISK_CHALLENGE_REPEATED')
})

test('coordinator uses one WebView and never clears the whole Android cookie jar', () => {
	const coordinator = source('android-risk-challenge.js')

	assert.match(coordinator, /android\.webkit\.CookieManager/)
	assert.match(coordinator, /setCookie/)
	assert.match(coordinator, /flush/)
	assert.match(coordinator, /plus\.webview\.create/)
	assert.match(coordinator, /challengeInFlight/)
	assert.match(coordinator, /120000/)
	assert.doesNotMatch(coordinator, /removeAllCookies|removeSessionCookies|console\./)
})

test('concurrent callers share one WebView and cleanup preserves cf_clearance', async () => {
	const { createAndroidRiskChallengeCoordinator } = await loadPolicy()
	const mock = installPlusMock()
	try {
		const coordinator = createAndroidRiskChallengeCoordinator(rootConfig)
		const first = coordinator.ensure(challenge())
		const second = coordinator.ensure(challenge())

		assert.equal(first, second)
		assert.equal(mock.createdWebviews(), 1)
		assert.match(mock.cookies(), /cf_clearance=keep/)
		assert.match(mock.cookies(), /__Host-ait-preauth=/)

		mock.complete('https://niko000o.site/pages/risk/challenge-complete')
		await Promise.all([first, second])

		assert.equal(mock.cookies(), 'cf_clearance=keep')
	} finally {
		mock.restore()
	}
})

test('cancelling the WebView returns a stable error and only removes the bridge cookie', async () => {
	const { createAndroidRiskChallengeCoordinator } = await loadPolicy()
	const mock = installPlusMock()
	try {
		const pending = createAndroidRiskChallengeCoordinator(rootConfig)
			.ensure(challenge())
		mock.cancel()

		await assert.rejects(
			() => pending,
			error => error?.code === 'RISK_CHALLENGE_CANCELLED')
		assert.equal(mock.cookies(), 'cf_clearance=keep')
	} finally {
		mock.restore()
	}
})

function installPlusMock() {
	const previous = global.plus
	let cookieHeader = 'cf_clearance=keep'
	let created = 0
	let completionHandler = null
	const listeners = new Map()
	const manager = {}
	const webview = {
		overrideUrlLoading(_options, handler) {
			completionHandler = handler
		},
		addEventListener(name, handler) {
			listeners.set(name, handler)
		},
		show() {},
		close() {
			listeners.get('close')?.()
		},
		getURL() {
			return ''
		}
	}
	global.plus = {
		android: {
			importClass() { return {} },
			invoke(_target, method, ...args) {
				if (method === 'getInstance') return manager
				if (method === 'getCookie') return cookieHeader
				if (method === 'setCookie') {
					const value = String(args[1] || '')
					const pair = value.split(';', 1)[0]
					const name = pair.split('=', 1)[0]
					const retained = cookieHeader.split(';')
						.map(item => item.trim())
						.filter(item => item && !item.startsWith(`${name}=`))
					if (!/Max-Age=0/.test(value)) retained.push(pair)
					cookieHeader = retained.join('; ')
				}
				return undefined
			}
		},
		webview: {
			create() {
				created += 1
				return webview
			}
		}
	}
	return {
		cookies: () => cookieHeader,
		createdWebviews: () => created,
		complete(url) { completionHandler?.({ url }) },
		cancel() { listeners.get('close')?.() },
		restore() { global.plus = previous }
	}
}

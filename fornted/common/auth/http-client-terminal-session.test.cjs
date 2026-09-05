const assert = require('node:assert/strict')
const test = require('node:test')
const { createHarness, deferred, flush } = require('./session-terminal-test-harness.cjs')

test('bootstrap clears once and the outer protected request still redirects with the original error', async () => {
	const h = createHarness('H5', { csrf: '' })
	const first = h.http.authorizedRequest('/protected')
	const second = h.http.authorizedRequest('/protected')
	const results = Promise.allSettled([first, second])
	await flush()
	assert.equal(h.requests.length, 1)
	assert.ok(h.requests[0].url.endsWith('/session/bootstrap'))
	h.respond(0, 401, 'REFRESH_TOKEN_REQUIRED')
	const failures = await results
	assert.equal(failures[0].reason.code, 'REFRESH_TOKEN_REQUIRED')
	assert.equal(failures[0].reason, failures[1].reason)
	assert.equal(h.clears, 1)
	assert.equal(h.navigations.length, 1)
	await assert.rejects(h.http.authorizedRequest('/protected'), { code: 'SESSION_TERMINATED' })
	assert.equal(h.requests.length, 1)
})

for (const platform of ['H5', 'ANDROID']) {
	test(`${platform}: concurrent protected 401 failures coalesce cleanup and redirect`, async () => {
		const h = createHarness(platform)
		const pending = Promise.allSettled([h.http.authorizedRequest('/one'), h.http.authorizedRequest('/two')])
		await flush()
		h.respond(0, 401, 'UNRECOGNIZED_AUTH_FAILURE')
		await flush()
		h.respond(1, 401)
		const results = await pending
		assert.equal(results[0].reason.code, 'UNRECOGNIZED_AUTH_FAILURE')
		assert.equal(results[1].reason.code, 'HTTP_401')
		assert.equal(h.clears, 1)
		assert.equal(h.navigations.length, 1)
	})

	test(`${platform}: requests waiting for PreAuth cannot dispatch after termination`, async () => {
		const wait = deferred()
		const h = createHarness(platform, { preAuth: () => wait.promise })
		const pending = h.http.authorizedRequest('/protected').catch(error => error)
		await flush()
		h.http.handleTerminalSessionError({ code: 'REFRESH_TOKEN_INVALID', statusCode: 401 })
		wait.resolve()
		assert.equal((await pending).code, 'SESSION_TERMINATED')
		assert.equal(h.requests.length, 0)
	})

	test(`${platform}: old responses cannot clear or renew a new login`, async () => {
		const h = createHarness(platform)
		const oldFailure = h.http.authorizedRequest('/old-failure').catch(error => error)
		const oldSuccess = h.http.authorizedRequest('/old-success').catch(error => error)
		await flush()
		h.login()
		h.respond(0, 401, 'REFRESH_TOKEN_INVALID')
		h.respond(1, 200, '', {}, { 'X-Session-Renewed': 'true', 'X-New-Access-Token': 'old-test-access' })
		assert.equal((await oldFailure).code, 'SESSION_GENERATION_STALE')
		assert.equal((await oldSuccess).code, 'SESSION_GENERATION_STALE')
		assert.equal(h.saved.length, 0)
		assert.equal(h.clears, 0)
		assert.equal(h.navigations.length, 0)
		const fresh = h.http.authorizedRequest('/new').catch(error => error)
		await flush()
		h.respond(2, 401, 'REFRESH_TOKEN_REQUIRED')
		assert.equal((await fresh).code, 'REFRESH_TOKEN_REQUIRED')
		assert.equal(h.clears, 1)
		assert.equal(h.navigations.length, 1)
	})

	test(`${platform}: public login 401 stays with the form`, async () => {
		const h = createHarness(platform)
		const pending = h.http.publicRequest('/api/auth/login').catch(error => error)
		await flush()
		h.respond(0, 401, 'CSRF_INVALID')
		assert.equal((await pending).code, 'CSRF_INVALID')
		assert.equal(h.clears, 0)
		assert.equal(h.navigations.length, 0)
	})

	test(`${platform}: final dispatch guard stops requests queued after header preparation`, async () => {
		const queued = deferred()
		let migrations = 0
		const h = createHarness(platform, { migration: () => ++migrations === 2 ? queued.promise : Promise.resolve({}) })
		const pending = h.http.authorizedRequest('/queued').catch(error => error)
		await flush()
		h.http.handleTerminalSessionError({ statusCode: 401 })
		queued.resolve({})
		assert.equal((await pending).code, 'SESSION_TERMINATED')
		assert.equal(h.requests.length, 0)
	})
}

test('Android valid AT renewal preserves the request generation', async () => {
	const h = createHarness('ANDROID')
	const generation = h.state.runtimeSessionRequestGeneration()
	const pending = h.http.authorizedRequest('/protected')
	await flush()
	h.respond(0, 200, '', {}, { 'X-Session-Renewed': 'true', 'X-New-Access-Token': 'renewed-test-access' })
	await pending
	assert.equal(h.saved[0].accessToken, 'renewed-test-access')
	assert.equal(h.state.runtimeSessionRequestGeneration(), generation)
	assert.equal(h.clears, 0)
})

test('H5 recoverable PreAuth 428 retries once and 503 preserves the session', async () => {
	const h = createHarness()
	const pending = h.http.authorizedRequest('/protected').catch(error => error)
	await flush()
	h.respond(0, 428, 'PREAUTH_REQUIRED')
	await flush()
	assert.equal(h.requests.length, 2)
	h.respond(1, 503, 'SERVICE_UNAVAILABLE')
	assert.equal((await pending).code, 'SERVICE_UNAVAILABLE')
	assert.equal(h.clears, 0)
	assert.equal(h.navigations.length, 0)
})

test('navigation failure releases its claim without an automatic retry', async () => {
	const h = createHarness('H5', { autoNavigate: false })
	h.http.handleTerminalSessionError({ statusCode: 401 })
	h.navigations[0].fail()
	await flush()
	assert.equal(h.navigations.length, 1)
	h.http.redirectTerminalSessionToLogin({ code: 'SESSION_TERMINATED' })
	assert.equal(h.navigations.length, 2)
	assert.equal(h.clears, 1)
	assert.ok(h.events.some(event => event.name === 'LOGIN_REDIRECT_FAILED'))
})

test('an old bootstrap finally cannot clear the next generation shared promise', async () => {
	const h = createHarness('H5', { csrf: '' })
	const old = h.http.restoreBrowserSession().catch(error => error)
	await flush()
	h.login()
	const fresh = h.http.restoreBrowserSession()
	await flush()
	h.respond(0, 401, 'REFRESH_TOKEN_INVALID')
	assert.equal((await old).code, 'SESSION_GENERATION_STALE')
	assert.equal(h.http.restoreBrowserSession(), fresh)
	h.respond(1, 200, '', { restored: true })
	await fresh
	assert.equal(h.clears, 0)
})

test('stream preparation bootstrap 401 redirects and recovery cannot reopen it', async () => {
	const h = createHarness('H5', { csrf: '' })
	const pending = h.http.prepareAuthorizedStreamingRequest('/events').catch(error => error)
	await flush()
	h.respond(0, 401, 'REFRESH_TOKEN_REQUIRED')
	const error = await pending
	assert.equal(error.code, 'REFRESH_TOKEN_REQUIRED')
	assert.equal(h.navigations.length, 1)
	assert.equal(await h.http.recoverAuthorizedStreamingSession(error), false)
	assert.equal(h.requests.length, 1)
})

test('only the first H5 stream CSRF rejection permits bootstrap recovery', async () => {
	const h = createHarness()
	const csrfError = { code: 'CSRF_INVALID', statusCode: 401 }
	h.http.handleAuthorizedStreamingFailure(csrfError, { sessionGeneration: 0, allowCsrfRecovery: true })
	assert.equal(h.clears, 0)
	const recovering = h.http.recoverAuthorizedStreamingSession(csrfError, { sessionGeneration: 0 })
	await flush()
	h.respond(0, 200, '', { restored: true })
	assert.equal(await recovering, true)
	assert.equal(await h.http.recoverAuthorizedStreamingSession(csrfError, { sessionGeneration: 0, alreadyRetried: true }), false)
	assert.equal(h.clears, 1)
	assert.equal(h.requests.length, 1)
})

test('page guards stop restoring a terminated session and ignore an old profile completion', async () => {
	const h = createHarness('ANDROID')
	const profile = deferred()
	const page = h.load('page-guard.js', {
		isProtectedRoute: () => true, normalizeRoutePath: value => value,
		loadCurrentUserProfile: () => profile.promise
	})
	const pending = page.requireAuthenticatedPage('/protected')
	await flush()
	h.http.handleTerminalSessionError({ statusCode: 401 })
	const before = h.preAuthCalls
	assert.equal(await page.requireAuthenticatedPage('/protected'), false)
	assert.equal(h.preAuthCalls, before)
	h.login()
	profile.resolve({})
	assert.equal(await pending, false)
	assert.equal(h.state.isRuntimeSessionAuthenticated(), true)
	assert.equal(h.clears, 1)
})

test('session gate bootstrap 401 clears and redirects instead of repeating recovery', async () => {
	const h = createHarness('H5', { csrf: '', autoNavigate: false })
	const component = h.load('../../pages/launch/session-gate.vue', {
		loadCurrentUserProfile: async () => ({}), clearCurrentUserProfile() {},
		dismissNativeSplashAfterPaint() {}, getNativeSplashCycleOffsetMillis: () => 0
	}).__default
	const gate = { ...component.data(), ...component.methods, $nextTick: task => task() }
	const pending = gate.restoreSession()
	await flush()
	h.respond(0, 401, 'REFRESH_TOKEN_REQUIRED')
	await pending
	assert.equal(h.navigations.length, 1)
	assert.equal(h.clears, 1)
	h.navigations[0].fail()
	assert.equal(gate.routing, false)
	await gate.restoreSession()
	assert.equal(h.requests.length, 1)
	assert.equal(h.navigations.length, 2)
})

test('profile cache ignores the prior login result without releasing the new profile task', async () => {
	const h = createHarness('ANDROID')
	const oldResult = deferred(), newResult = deferred()
	let calls = 0, cached = null
	const profile = h.load('../user/current-user-profile.js', {
		currentUserApi: { me: () => (++calls === 1 ? oldResult.promise : newResult.promise) },
		clearProfileVault() { cached = null }, readProfileVault: () => cached,
		writeProfileVault: value => { cached = value; return value }, recordAiConversationProfileRefresh() {}
	})
	const old = profile.loadCurrentUserProfile().catch(error => error)
	h.login()
	const fresh = profile.loadCurrentUserProfile()
	oldResult.resolve({ displayName: 'old test user' })
	assert.equal((await old).code, 'SESSION_GENERATION_STALE')
	assert.equal(cached, null)
	assert.equal(profile.loadCurrentUserProfile(), fresh)
	newResult.resolve({ displayName: 'new test user' })
	assert.equal((await fresh).displayName, 'new test user')
})

test('a synchronously failed redirect does not retry while the same error unwinds', async () => {
	const h = createHarness('H5', { autoNavigate: false })
	h.bindings.uni.reLaunch = navigation => { h.navigations.push(navigation); navigation.fail() }
	const error = { code: 'REFRESH_TOKEN_REQUIRED', statusCode: 401 }
	h.http.handleTerminalSessionError(error)
	h.http.handleTerminalSessionError(error)
	await flush()
	assert.equal(h.navigations.length, 1)
	assert.equal(h.clears, 1)
})

test('401 received during a PreAuth recovery still exits through the outer handler', async () => {
	let preAuthCalls = 0
	const failure = Object.assign(new Error('test recovery failure'), { statusCode: 401 })
	const h = createHarness('H5', { preAuth: () => ++preAuthCalls === 1 ? Promise.resolve() : Promise.reject(failure) })
	const pending = h.http.authorizedRequest('/protected').catch(error => error)
	await flush()
	h.respond(0, 428, 'PREAUTH_REQUIRED')
	assert.equal(await pending, failure)
	assert.equal(h.clears, 1)
	assert.equal(h.navigations.length, 1)
})

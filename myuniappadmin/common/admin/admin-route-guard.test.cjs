const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'admin-route-guard.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('administrator route policy protects every business page and leaves security flow pages public', async () => {
	const {
		ADMIN_ENTRY_ROUTE,
		isAdminProtectedRoute,
		isAdminPublicRoute,
		normalizeAdminPageRoute
	} = await loadModule()

	assert.equal(ADMIN_ENTRY_ROUTE, '/pages/index/index')
	for (const route of [
		'/pages/admin/workspace',
		'/pages/admin/workspace#/ai-models',
		'/pages/admin/workspace#/ai-models/new',
		'/pages/admin/workspace#/ai-models/discovery',
		'/pages/admin/workspace#/ai-models/AAAAAAAAAAA',
		'/pages/admin/workspace#/ai-model-icons',
		'/pages/admin/workspace#/ip2location/keys',
		'/pages/admin/workspace#/mail-inspection/openai',
		'/pages/admin/workspace#/mail-inspection/kiro',
		'/pages/admin/workspace#/mail-inspection/ip2location/registration',
		'/pages/admin/workspace#/mail-inspection/ip2location/verify-link',
		'/pages/risk/ip2location-keys',
		'/pages/ai-models/index',
		'/pages/ai-models/create',
		'/pages/ai-models/detail?publicId=AAAAAAAAAAA',
		'/pages/ai-model-icons/index',
		'/pages/mail-inspection/openai/index',
		'/pages/mail-inspection/kiro/index',
		'/pages/mail-inspection/ip2location/index'
	]) {
		assert.equal(isAdminProtectedRoute(route), true, route)
	}
	for (const route of [
		'/pages/index/index',
		'/pages/risk/challenge-complete',
		'/pages/risk/challenge-failed',
		'/pages/risk/blocked',
		'/pages/risk/webrtc-failed'
	]) {
		assert.equal(isAdminPublicRoute(route), true, route)
	}
	assert.equal(
		normalizeAdminPageRoute('pages/ai-models/detail?publicId=AAAAAAAAAAA#top'),
		'/pages/ai-models/detail'
	)
	assert.equal(isAdminProtectedRoute('/pages/admin/workspace-invalid'), false)
	assert.equal(isAdminProtectedRoute('/pages/admin/workspace/ai-models'), false)
	assert.equal(isAdminProtectedRoute('/pages/risk/ip2location-keys/'), true)
})

test('administrator route guard shares one session bootstrap for concurrent callers', async () => {
	const { createAdminRouteGuard } = await loadModule()
	let bootstrapCalls = 0
	let releaseBootstrap
	const bootstrap = new Promise(resolve => { releaseBootstrap = resolve })
	const guard = createAdminRouteGuard({
		validateSession: async () => {
			bootstrapCalls += 1
			await bootstrap
			return { authenticated: true }
		},
		navigate: () => undefined,
		onSessionInvalid: () => undefined
	})

	const first = guard.ensureAdminSession()
	const second = guard.ensureAdminSession()
	await Promise.resolve()
	assert.equal(bootstrapCalls, 1)
	releaseBootstrap()
	assert.equal(await first, true)
	assert.equal(await second, true)
})

test('administrator route guard blocks protected navigation after session invalidation and deduplicates redirect', async () => {
	const { createAdminRouteGuard } = await loadModule()
	const invalid = Object.assign(new Error('session expired'), {
		code: 'ADMIN_SESSION_INVALID',
		statusCode: 401
	})
	let redirects = 0
	let navigations = 0
	const guard = createAdminRouteGuard({
		validateSession: async () => { throw invalid },
		navigate: () => { navigations += 1 },
		onSessionInvalid: () => { redirects += 1 }
	})

	assert.equal(await guard.guardAdminPage('/pages/mail-inspection/openai/index'), false)
	assert.equal(await guard.guardedAdminNavigate('/pages/ai-models/index'), false)
	assert.equal(redirects, 1)
	assert.equal(navigations, 0)
})

test('administrator route guard leaves public security pages usable without a session bootstrap', async () => {
	const { createAdminRouteGuard } = await loadModule()
	let bootstrapCalls = 0
	let navigations = 0
	const guard = createAdminRouteGuard({
		validateSession: async () => {
			bootstrapCalls += 1
			return { authenticated: true }
		},
		navigate: () => { navigations += 1 },
		onSessionInvalid: () => { throw new Error('public route must not redirect') }
	})

	assert.equal(await guard.guardAdminPage('/pages/risk/challenge-failed'), true)
	assert.equal(bootstrapCalls, 0)
	assert.equal(navigations, 1)
})

test('administrator session errors identify both the controlled code and protected 401 responses', async () => {
	const { isAdminSessionInvalidError } = await loadModule()

	assert.equal(isAdminSessionInvalidError({ code: 'ADMIN_SESSION_INVALID' }), true)
	assert.equal(isAdminSessionInvalidError({ statusCode: 401 }), true)
	assert.equal(isAdminSessionInvalidError({ statusCode: 403 }), false)
	assert.equal(isAdminSessionInvalidError({ code: 'ADMIN_LOGIN_FAILED', statusCode: 401 }), false)
})

test('successful workspace validation is reused for thirty seconds and can be forced after resume', async () => {
	const { createAdminRouteGuard } = await loadModule()
	let now = 1_000
	let bootstrapCalls = 0
	const guard = createAdminRouteGuard({
		validateSession: async () => { bootstrapCalls += 1 },
		navigate: () => undefined,
		onSessionInvalid: () => undefined,
		now: () => now,
		sessionValidationMaxAgeMs: 30_000
	})

	assert.equal(await guard.ensureAdminSession(), true)
	now += 29_999
	assert.equal(await guard.ensureAdminSession(), true)
	assert.equal(bootstrapCalls, 1)
	now += 2
	assert.equal(await guard.ensureAdminSession(), true)
	assert.equal(bootstrapCalls, 2)
	assert.equal(await guard.ensureAdminSession({ force: true }), true)
	assert.equal(bootstrapCalls, 3)
})

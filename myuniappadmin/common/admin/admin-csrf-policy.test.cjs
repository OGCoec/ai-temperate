const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'admin-csrf-policy.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('administrator routes select only their purpose-specific CSRF cookie', async () => {
	const { adminCsrfCookieName } = await loadModule()

	assert.equal(
		adminCsrfCookieName('/api/admin/auth/register/hcaptcha'),
		'admin_register_csrf'
	)
	assert.equal(
		adminCsrfCookieName('/api/admin/auth/login/complete'),
		'admin_login_csrf'
	)
	assert.equal(adminCsrfCookieName('/api/admin/auth/logout'), 'ADMIN-XSRF-TOKEN')
	assert.notEqual(adminCsrfCookieName('/api/admin/auth/logout'), 'XSRF-TOKEN')
})

test('safe requests and administrator flow entrypoints do not require an existing CSRF cookie', async () => {
	const { requiresAdminCsrf } = await loadModule()

	for (const [method, requestPath] of [
		['GET', '/api/admin/me'],
		['HEAD', '/api/admin/me'],
		['OPTIONS', '/api/admin/auth/login/complete'],
		['POST', '/api/admin/auth/register/start'],
		['POST', '/api/admin/auth/login/start'],
		['POST', '/api/admin/auth/session/bootstrap']
	]) {
		assert.equal(requiresAdminCsrf(requestPath, method), false, `${method} ${requestPath}`)
	}

	assert.equal(
		requiresAdminCsrf('/api/admin/auth/register/hcaptcha', 'POST'),
		true
	)
	assert.equal(requiresAdminCsrf('/api/admin/auth/login/complete', 'POST'), true)
	assert.equal(requiresAdminCsrf('/api/admin/auth/logout', 'POST'), true)
})

test('missing required administrator CSRF cookie fails before a request can be created', async () => {
	const { requiredAdminCsrfToken } = await loadModule()
	let lookupCount = 0

	assert.throws(
		() => requiredAdminCsrfToken(
			'/api/admin/auth/register/hcaptcha',
			'POST',
			() => {
				lookupCount += 1
				return ''
			}
		),
		error => error.code === 'ADMIN_CSRF_COOKIE_UNAVAILABLE'
	)
	assert.equal(lookupCount, 1)
	assert.equal(
		requiredAdminCsrfToken('/api/admin/auth/register/start', 'POST', () => {
			throw new Error('start must not read an existing CSRF cookie')
		}),
		''
	)
})

test('successful flow and session bootstrap responses require the new readable cookie', async () => {
	const { expectedAdminCsrfCookieAfterSuccess } = await loadModule()

	assert.equal(
		expectedAdminCsrfCookieAfterSuccess('/api/admin/auth/register/start'),
		'admin_register_csrf'
	)
	assert.equal(
		expectedAdminCsrfCookieAfterSuccess('/api/admin/auth/login/start'),
		'admin_login_csrf'
	)
	assert.equal(
		expectedAdminCsrfCookieAfterSuccess('/api/admin/auth/login/complete'),
		'ADMIN-XSRF-TOKEN'
	)
	assert.equal(
		expectedAdminCsrfCookieAfterSuccess('/api/admin/auth/session/bootstrap'),
		'ADMIN-XSRF-TOKEN'
	)
	assert.equal(expectedAdminCsrfCookieAfterSuccess('/api/admin/auth/logout'), '')
})

test('registration status is restored only when its readable flow cookie exists', async () => {
	const { hasReadableAdminFlowCsrf } = await loadModule()
	const lookups = []

	assert.equal(hasReadableAdminFlowCsrf('register', name => {
		lookups.push(name)
		return ''
	}), false)
	assert.equal(hasReadableAdminFlowCsrf('register', name => {
		lookups.push(name)
		return 'flow-csrf'
	}), true)
	assert.deepEqual(lookups, ['admin_register_csrf', 'admin_register_csrf'])
})

test('CSRF availability errors never expose cookie values or authentication tokens', async () => {
	const { adminCsrfCookieUnavailableError } = await loadModule()
	const error = adminCsrfCookieUnavailableError()

	assert.equal(error.code, 'ADMIN_CSRF_COOKIE_UNAVAILABLE')
	assert.equal(
		error.message,
		'管理员安全 Cookie 无法读取，请刷新页面；如持续出现请检查站点 Cookie 设置。'
	)
	assert.doesNotMatch(error.message, /token|csrf|cookie=/i)
})

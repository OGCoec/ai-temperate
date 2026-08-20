const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.resolve(__dirname, 'protected-routes.js'), 'utf8')
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(sourceUrl)
}

test('normalizes H5 direct URLs and uni-app route paths', async () => {
	const { normalizeRoutePath } = await loadModule()

	assert.equal(
		normalizeRoutePath('https://localhost:3000/pages/account/profile?from=direct'),
		'/pages/account/profile'
	)
	assert.equal(
		normalizeRoutePath('pages/account/profile?from=direct'),
		'/pages/account/profile'
	)
	assert.equal(
		normalizeRoutePath('/pages/account/profile#section'),
		'/pages/account/profile'
	)
})

test('allows authentication bootstrap and pre-auth security pages without login', async () => {
	const { isPublicRoute, isProtectedRoute } = await loadModule()

	const anonymousRoutes = [
		'/pages/launch/session-gate',
		'/pages/auth/login',
		'/pages/auth/totp-login',
		'/pages/auth/register',
		'/pages/auth/password-reset',
		'/pages/auth/oauth-return',
		'/pages/auth/oauth-phone',
		'/pages/risk/blocked',
		'/pages/risk/challenge-complete',
		'/pages/risk/challenge-failed',
		'/pages/risk/webrtc-failed'
	]
	for (const route of anonymousRoutes) {
		assert.equal(isPublicRoute(route), true)
		assert.equal(isProtectedRoute(route), false)
	}
})

test('keeps OAuth flow pages public without broadly allowing authentication routes', async () => {
	const { isPublicRoute, isProtectedRoute } = await loadModule()

	assert.equal(isPublicRoute('/pages/auth/oauth-return?provider=github'), true)
	assert.equal(isProtectedRoute('/pages/auth/oauth-phone#challenge'), false)
	assert.equal(isProtectedRoute('/pages/auth/security-settings'), true)
})

test('protects account and future internal pages by default', async () => {
	const { isProtectedRoute } = await loadModule()

	assert.equal(isProtectedRoute('/pages/account/profile'), true)
	assert.equal(isProtectedRoute('/pages/account/api-keys'), true)
	assert.equal(isProtectedRoute('/pages/account/profile-edit'), true)
	assert.equal(isProtectedRoute('/pages/internal/dashboard'), true)
})

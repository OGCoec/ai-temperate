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

test('allows only authentication bootstrap and public auth pages without login', async () => {
	const { isPublicRoute, isProtectedRoute } = await loadModule()

	assert.equal(isPublicRoute('/pages/launch/session-gate'), true)
	assert.equal(isPublicRoute('/pages/auth/login'), true)
	assert.equal(isPublicRoute('/pages/auth/register'), true)
	assert.equal(isPublicRoute('/pages/auth/password-reset'), true)
	assert.equal(isProtectedRoute('/pages/auth/login'), false)
})

test('protects account and future internal pages by default', async () => {
	const { isProtectedRoute } = await loadModule()

	assert.equal(isProtectedRoute('/pages/account/profile'), true)
	assert.equal(isProtectedRoute('/pages/account/profile-edit'), true)
	assert.equal(isProtectedRoute('/pages/internal/dashboard'), true)
})

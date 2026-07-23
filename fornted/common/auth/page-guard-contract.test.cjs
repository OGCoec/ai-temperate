const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function read(relativePath) {
	return fs.readFileSync(path.resolve(__dirname, '..', '..', relativePath), 'utf8')
}

const nativeTabKey = 'tab' + 'Bar'
const legacyTopPanelKey = 'top' + 'Window'
const legacyLeftPanelKey = 'left' + 'Window'
const legacyTabNavigationMethod = 'switch' + 'Tab'

test('page guard verifies protected routes through the backend-backed session flow', () => {
	const source = read('common/auth/page-guard.js')

	assert.match(source, /restorePersistedSession/)
	assert.match(source, /loadCurrentUserProfile\(\{\s*force:\s*true\s*\}\)/)
	assert.match(source, /clearSession\(\)/)
	assert.match(source, /uni\.reLaunch\(\{[\s\S]*url:\s*AUTH_ROUTES\.login/)
	assert.match(source, /authenticationInFlight/)
	assert.match(source, /isProtectedRoute/)
})

test('auth UI preview restores and exits without backend requests', () => {
	const httpClient = read('common/auth/http-client.js')

	assert.match(httpClient, /isAuthUiPreviewEnabled/)
	assert.match(httpClient, /restorePersistedSession\(\)[\s\S]*preview:\s*true/)
	assert.match(httpClient, /logoutSession\(\)[\s\S]*clearAuthUiPreviewSession\(\)/)
})

test('navigation guard intercepts all uni-app navigation methods', () => {
	const source = read('common/auth/navigation-guard.js')

	for (const method of ['navigateTo', 'redirectTo', 'reLaunch']) {
		assert.match(source, new RegExp(`'${method}'`))
	}
	assert.equal(source.includes(`'${legacyTabNavigationMethod}'`), false)
	assert.match(source, /uni\.addInterceptor\(method/)
	assert.match(source, /requireAuthenticatedPage/)
	assert.match(source, /invokeWithoutGuard/)
})

test('application startup installs navigation and page lifecycle guards', () => {
	const source = read('main.js')

	assert.match(source, /installAuthenticatedNavigationGuard\(\)/)
	assert.match(source, /authPageMixin/)
	assert.match(source, /Vue\.mixin\(authPageMixin\)/)
	assert.match(source, /app\.mixin\(authPageMixin\)/)
})

test('application routes do not register the removed example tab shell', () => {
	const pages = read('pages.json')

	assert.match(pages, /pages\/account\/profile/)
	assert.equal(pages.includes(`"${nativeTabKey}"`), false)
	assert.equal(pages.includes(`"${legacyLeftPanelKey}"`), false)
	assert.equal(pages.includes(`"${legacyTopPanelKey}"`), false)
	for (const route of ['component', 'API', 'extUI', 'template']) {
		assert.equal(pages.includes(`pages/${route}`), false)
	}
})

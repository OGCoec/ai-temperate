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
	assert.match(source, /isRuntimeSessionAuthenticated/)
	assert.match(source, /markRuntimeSessionAuthenticated/)
	assert.match(source, /clearSession\(\)/)
	assert.match(source, /uni\.reLaunch\(\{[\s\S]*url:\s*AUTH_ROUTES\.login/)
	assert.match(source, /authenticationInFlight/)
	assert.match(source, /isProtectedRoute/)
})

test('clearing a session also removes runtime model and conversation state', () => {
	const source = read('common/auth/session-vault.js')

	assert.match(source, /clearRuntimeSessionAuthentication\(\)/)
	assert.match(source, /clearAiModelCatalog\(\)/)
	assert.match(source, /clearAiConversationStore\(\)/)
	assert.match(source, /clearGenerationManager\(\)/)
})

test('risk blocking clears runtime-only account state before it replaces the current page', () => {
	const source = read('common/auth/risk-block-navigation.js')

	assert.match(source, /clearRuntimeSessionAuthentication\(\)/)
	assert.match(source, /clearAiModelCatalog\(\)/)
	assert.match(source, /clearAiConversationStore\(\)/)
	assert.match(source, /clearGenerationManager\(\)/)
})

test('authenticated page lifecycle reuses the confirmed runtime session and deduplicates load plus show', () => {
	const source = read('common/auth/auth-page-mixin.js')

	assert.match(source, /runtimeAuthenticationVersion/)
	assert.match(source, /__aitAuthenticationInFlight/)
	assert.match(source, /if \(this\.authReady && this\.__aitAuthenticationVersion === version\)/)
	assert.match(source, /onLoad\(\)/)
	assert.match(source, /onShow\(\)/)
})

test('protected authentication flows contain no local preview bypass', () => {
	const httpClient = read('common/auth/http-client.js')
	const sessionGate = read('pages/launch/session-gate.vue')
	const currentUserApi = read('common/user/current-user-api.js')
	const cookieMigration = read('common/auth/cookie-scope-migration.js')
	const combined = [httpClient, sessionGate, currentUserApi, cookieMigration].join('\n')
	const previewModule = path.resolve(
		__dirname,
		'..',
		'..',
		'common/auth/ui-preview-session.js'
	)

	assert.doesNotMatch(
		combined,
		/AuthUiPreview|authUiPreview|ui-preview-session|preview:\s*true/
	)
	assert.equal(fs.existsSync(previewModule), false)
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
	assert.match(pages, /pages\/ai-chat\/index/)
	assert.equal(pages.includes(`"${nativeTabKey}"`), false)
	assert.equal(pages.includes(`"${legacyLeftPanelKey}"`), false)
	assert.equal(pages.includes(`"${legacyTopPanelKey}"`), false)
	for (const route of ['component', 'API', 'extUI', 'template']) {
		assert.equal(pages.includes(`pages/${route}`), false)
	}
})

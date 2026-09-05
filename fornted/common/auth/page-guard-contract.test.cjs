const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

function read(relativePath) {
	return fs.readFileSync(path.resolve(__dirname, '..', '..', relativePath), 'utf8')
}

function createNavigationGuardHarness({ authenticated = false, allowed = true } = {}) {
	const source = read('common/auth/navigation-guard.js')
		.replace(/^import .*$/gm, '')
		.replace(
			'export function installAuthenticatedNavigationGuard',
			'function installAuthenticatedNavigationGuard'
		)
	const calls = []
	const authenticationChecks = []
	const interceptors = new Map()
	const uni = {
		addInterceptor(method, interceptor) {
			interceptors.set(method, interceptor)
		}
	}

	for (const method of ['navigateTo', 'redirectTo', 'reLaunch']) {
		uni[method] = (options) => {
			const interceptor = interceptors.get(method)
			if (!interceptor || interceptor.invoke(options) !== false) {
				calls.push({ method, options })
			}
		}
	}

	const context = vm.createContext({
		uni,
		isRuntimeSessionAuthenticated: () => authenticated,
		isRuntimeTerminalSessionActive: () => false,
		runtimeSessionRequestGeneration: () => 0,
		isProtectedRoute: (route) => route === '/pages/user/user-workspace',
		normalizeRoutePath: (url) => String(url || '').split(/[?#]/, 1)[0],
		requireAuthenticatedPage: (route) => {
			authenticationChecks.push(route)
			return Promise.resolve(allowed)
		}
	})
	vm.runInContext(
		`${source}\nglobalThis.__installGuard = installAuthenticatedNavigationGuard`,
		context
	)
	context.__installGuard()
	return { authenticationChecks, calls, uni }
}

const nativeTabKey = 'tab' + 'Bar'
const legacyTopPanelKey = 'top' + 'Window'
const legacyLeftPanelKey = 'left' + 'Window'
const legacyTabNavigationMethod = 'switch' + 'Tab'

test('page guard verifies protected routes through the backend-backed session flow', () => {
	const source = read('common/auth/page-guard.js')
	const sessionGate = read('pages/launch/session-gate.vue')

	assert.match(source, /restorePersistedSession/)
	assert.match(source, /loadCurrentUserProfile\(\{\s*force:\s*true\s*\}\)/)
	assert.match(source, /isRuntimeSessionAuthenticated/)
	assert.match(source, /markRuntimeSessionAuthenticated/)
	assert.match(source, /clearSession\(\)/)
	assert.match(source, /redirectTerminalSessionToLogin/)
	assert.match(source, /authenticationInFlight/)
	assert.match(source, /isProtectedRoute/)
	assert.match(source, /beginRuntimeTerminalSessionTransition/)
	assert.match(source, /isRuntimeTerminalSessionActive/)
	assert.match(sessionGate, /beginRuntimeTerminalSessionTransition/)
	assert.match(sessionGate, /redirectTerminalSessionToLogin/)
})

test('clearing a session also removes runtime state and pending API Key create intent', () => {
	const source = read('common/auth/session-vault.js')

	assert.match(source, /clearRuntimeSessionAuthentication\(\)/)
	assert.match(source, /clearAiModelCatalog\(\)/)
	assert.match(source, /clearAiConversationStore\(\)/)
	assert.match(source, /clearGenerationManager\(\)/)
	assert.match(source, /clearApiKeyCreateIntent\(\)/)
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
	assert.match(source, /!isRuntimeTerminalSessionActive\(\) && this\.authReady && this\.__aitAuthenticationVersion === version/)
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

test('navigation guard synchronously preserves the original protected navigation after login', () => {
	const harness = createNavigationGuardHarness({ authenticated: true })
	const success = () => {}
	const fail = () => {}

	harness.uni.reLaunch({
		url: '/pages/user/user-workspace',
		success,
		fail
	})

	assert.deepEqual(harness.authenticationChecks, [])
	assert.equal(harness.calls.length, 1)
	assert.equal(harness.calls[0].method, 'reLaunch')
	assert.equal(harness.calls[0].options.success, success)
	assert.equal(harness.calls[0].options.fail, fail)
})

test('navigation guard keeps the existing asynchronous recovery for a cold protected navigation', async () => {
	const harness = createNavigationGuardHarness({ authenticated: false, allowed: true })
	const complete = () => {}

	harness.uni.reLaunch({
		url: '/pages/user/user-workspace',
		complete
	})

	assert.equal(harness.calls.length, 0)
	assert.deepEqual(harness.authenticationChecks, ['/pages/user/user-workspace'])
	await Promise.resolve()
	await Promise.resolve()
	assert.equal(harness.calls.length, 1)
	assert.equal(harness.calls[0].method, 'reLaunch')
	assert.equal(harness.calls[0].options.complete, complete)
})

test('navigation guard leaves public navigation outside authentication recovery', () => {
	const harness = createNavigationGuardHarness({ authenticated: false })

	harness.uni.navigateTo({ url: '/pages/auth/login' })

	assert.deepEqual(harness.authenticationChecks, [])
	assert.equal(harness.calls.length, 1)
	assert.equal(harness.calls[0].method, 'navigateTo')
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

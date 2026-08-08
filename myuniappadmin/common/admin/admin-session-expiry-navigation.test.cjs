const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const projectRoot = path.resolve(__dirname, '../..')
const source = relativePath => fs.readFileSync(path.join(projectRoot, relativePath), 'utf8')
let moduleSequence = 0

async function loadNavigationModule() {
	const mocks = `
const clearAdminMailInspectionSession = () => globalThis.__adminSessionExpiryTest.clear('mail')
const invalidateAdminPreAuth = () => globalThis.__adminSessionExpiryTest.clear('preauth')
const clearAdminSession = () => globalThis.__adminSessionExpiryTest.clear('session')
const invalidateAdminWebRtcVerification = () => globalThis.__adminSessionExpiryTest.clear('webrtc')
const ADMIN_ENTRY_ROUTE = '/pages/index/index'
const normalizeAdminPageRoute = value => {
	const raw = String(value || '').split(/[?#]/, 1)[0]
	if (!raw) return ''
	return (raw.startsWith('/') ? raw : \`/\${raw}\`).replace(/\\/+/g, '/')
}
const isAdminProtectedRoute = value => {
	const route = normalizeAdminPageRoute(value)
	return route === '/pages/risk/ip2location-keys'
		|| route === '/pages/ai-model-icons/index'
		|| route.startsWith('/pages/ai-models/')
		|| route.startsWith('/pages/mail-inspection/')
}
`
	const navigation = source('common/admin/admin-session-expiry-navigation.js')
		.replace(/import[\s\S]*?from\s+['"][^'"]+['"]\r?\n/g, '')
	const moduleSource = `${mocks}\n${navigation}\n// test-module-${moduleSequence += 1}`
	return import(`data:text/javascript;base64,${Buffer.from(moduleSource).toString('base64')}`)
}

test('session expiry navigation excludes login, registration, risk edge and public bootstrap endpoints', () => {
	const navigation = source('common/admin/admin-session-expiry-navigation.js')

	assert.match(navigation, /ADMIN_SESSION_INVALID/)
	assert.match(navigation, /statusCode === 401/)
	assert.match(navigation, /auth\/login/)
	assert.match(navigation, /auth\/register/)
	assert.match(navigation, /auth\/state/)
	assert.match(navigation, /auth\/hcaptcha\/config/)
	assert.match(navigation, /_edge/)
})

test('only protected administrator APIs promote an unclassified 401 to session expiry', async () => {
	globalThis.__adminSessionExpiryTest = { clear: () => undefined }
	globalThis.uni = { reLaunch: () => undefined }
	try {
		const navigation = await loadNavigationModule()
		for (const path of [
			'/api/admin/auth/state',
			'/api/admin/auth/login/complete',
			'/api/admin/auth/register/start',
			'/api/admin/_edge/pre-auth'
		]) {
			assert.equal(navigation.isAdminSessionExpiryError({ statusCode: 401 }, path), false, path)
		}
		assert.equal(navigation.isAdminSessionExpiryError(
			{ statusCode: 401 }, '/api/admin/ai-models?pageNum=1'), true)
		assert.equal(navigation.isAdminSessionExpiryError(
			{ code: 'HTTP_401', statusCode: 401 }, '/api/admin/ai-models'), true)
		assert.equal(navigation.isAdminSessionExpiryError(
			{ code: 'ADMIN_ACCESS_DENIED', statusCode: 401 }, '/api/admin/ai-models'), false)
	} finally {
		delete globalThis.uni
		delete globalThis.__adminSessionExpiryTest
	}
})

test('session expiry navigation clears every frontend administrator credential boundary before redirecting', () => {
	const navigation = source('common/admin/admin-session-expiry-navigation.js')

	assert.match(navigation, /clearAdminMailInspectionSession\(\)/)
	assert.match(navigation, /clearAdminSession\(\)/)
	assert.match(navigation, /invalidateAdminPreAuth\(\)/)
	assert.match(navigation, /invalidateAdminWebRtcVerification\(\)/)
	assert.match(navigation, /uni\.reLaunch\(/)
	assert.match(navigation, /redirectInFlight/)
	assert.match(navigation, /redirectIssuedForInvalidSession/)
	assert.match(navigation, /markAdminSessionExpiryRecovered/)
})

test('session expiry notice is one-shot and the login entry is the only redirect target', () => {
	const navigation = source('common/admin/admin-session-expiry-navigation.js')
	const runtime = source('common/admin/admin-route-guard-runtime.js')

	assert.match(navigation, /takeAdminSessionExpiryNotice/)
	assert.match(navigation, /pendingNotice = false/)
	assert.match(navigation, /ADMIN_ENTRY_ROUTE/)
	assert.match(runtime, /forceRedirect:\s*true/)
	assert.doesNotMatch(navigation, /window\.location\.(assign|replace)/)
	assert.doesNotMatch(`${navigation}\n${runtime}`, /returnUrl|redirectUrl|fromRoute/)
})

test('one persistent workspace guards business panels while legacy pages only replace their URL', () => {
	const legacyPages = [
		'pages/risk/ip2location-keys.vue',
		'pages/ai-models/index.vue',
		'pages/ai-models/create.vue',
		'pages/ai-models/detail.vue',
		'pages/ai-model-icons/index.vue',
		'pages/mail-inspection/openai/index.vue',
		'pages/mail-inspection/kiro/index.vue',
		'pages/mail-inspection/ip2location/index.vue'
	]

	for (const page of legacyPages) {
		const pageSource = source(page)
		assert.match(pageSource, /redirectLegacyAdminWorkspace/)
		assert.doesNotMatch(pageSource, /adminApi|adminRouteReady|runAfterAdminRouteGuard/)
	}
	const workspace = source('pages/admin/workspace.vue')
	assert.match(workspace, /ensureAdminSession/)
	assert.match(workspace, /VERIFYING_SESSION/)
	assert.match(workspace, /TRANSIENT_FAILURE/)
	assert.doesNotMatch(workspace, /v-if="adminRouteReady"/)
})

test('workspace navigation switches view state without using the uni page stack', () => {
	const workspace = source('pages/admin/workspace.vue')
	const navigation = source('components/admin/admin-side-navigation.vue')

	assert.match(navigation, /\$emit\('navigate', item\.location \|\| \{ view: item\.view \}\)/)
	assert.doesNotMatch(`${workspace}\n${navigation}`, /uni\.(?:navigateTo|redirectTo)\(/)
})

test('mail panel mounts only in the READY branch and pauses when the workspace deactivates it', () => {
	const workspace = source('pages/admin/workspace.vue')
	const panel = source('components/admin/workspace/mail-inspection-panel.vue')

	assert.match(workspace, /v-else-if="sessionState === 'READY'"/)
	assert.match(workspace, /import MailInspectionPanel from/)
	assert.match(panel, /mounted\(\)[\s\S]{0,480}refreshRecoveredJobs/)
	assert.match(panel, /onWorkspaceDeactivated\(\)[\s\S]{0,160}this\.pause\(\)/)
})

test('initial unauthenticated bootstrap on the login entry neither loops nor queues an expiry notice', async () => {
	const cleared = []
	let redirects = 0
	globalThis.__adminSessionExpiryTest = { clear: value => cleared.push(value) }
	globalThis.uni = { reLaunch: () => { redirects += 1 } }
	try {
		const navigation = await loadNavigationModule()
		const handled = navigation.handleAdminSessionInvalid(
			{ code: 'ADMIN_SESSION_INVALID', statusCode: 401 },
			{ path: '/api/admin/auth/session/bootstrap', currentRoute: '/pages/index/index' })

		assert.equal(handled, true)
		assert.equal(redirects, 0)
		assert.equal(navigation.takeAdminSessionExpiryNotice(), '')
		assert.deepEqual(cleared, ['mail', 'session', 'preauth', 'webrtc'])
	} finally {
		delete globalThis.uni
		delete globalThis.__adminSessionExpiryTest
	}
})

test('guarded dashboard navigation forces one redirect and concurrent 401 responses cannot relaunch twice', async () => {
	let redirects = 0
	globalThis.__adminSessionExpiryTest = { clear: () => undefined }
	globalThis.uni = {
		reLaunch(options) {
			redirects += 1
			options.complete?.()
		}
	}
	try {
		const navigation = await loadNavigationModule()
		const invalid = { code: 'ADMIN_SESSION_INVALID', statusCode: 401 }
		navigation.handleAdminSessionInvalid(invalid, {
			currentRoute: '/pages/index/index',
			forceRedirect: true
		})
		navigation.handleAdminSessionInvalid(invalid, {
			path: '/api/admin/mail-inspection/recovered-jobs',
			currentRoute: '/pages/index/index'
		})

		assert.equal(redirects, 1)
		assert.equal(navigation.takeAdminSessionExpiryNotice(), '管理员会话已失效，请重新登录。')
		assert.equal(navigation.takeAdminSessionExpiryNotice(), '')

		navigation.markAdminSessionExpiryRecovered()
		navigation.handleAdminSessionInvalid(invalid, {
			currentRoute: '/pages/ai-models/index'
		})
		assert.equal(redirects, 2)
	} finally {
		delete globalThis.uni
		delete globalThis.__adminSessionExpiryTest
	}
})

test('risk flow pages clear expired state without being intercepted by the administrator page guard', async () => {
	let redirects = 0
	globalThis.__adminSessionExpiryTest = { clear: () => undefined }
	globalThis.uni = { reLaunch: () => { redirects += 1 } }
	try {
		const navigation = await loadNavigationModule()
		navigation.handleAdminSessionInvalid(
			{ code: 'ADMIN_SESSION_INVALID', statusCode: 401 },
			{ currentRoute: '/pages/risk/webrtc-failed' })
		assert.equal(redirects, 0)
	} finally {
		delete globalThis.uni
		delete globalThis.__adminSessionExpiryTest
	}
})

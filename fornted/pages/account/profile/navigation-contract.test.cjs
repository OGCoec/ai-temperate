const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const frontendRoot = path.resolve(__dirname, '../../..')
const nativeTabKey = 'tab' + 'Bar'
const legacyTopPanelKey = 'top' + 'Window'
const legacyLeftPanelKey = 'left' + 'Window'
const legacyTabNavigationMethod = 'switch' + 'Tab'

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

test('login success and local preview enter the account profile page', () => {
	const login = read('pages/auth/login.vue')
	const config = read('common/auth/config.js')

	assert.match(login, /completeLogin\(\)/)
	assert.equal(login.includes(`uni.${legacyTabNavigationMethod}({`), false)
	assert.match(login, /uni\.reLaunch\(\{/)
	assert.match(login, /url:\s*AUTH_ROUTES\.home/)
	assert.match(login, /previewAuthenticatedPages/)
	assert.match(login, /url:\s*AUTH_ROUTES\.profile/)
	assert.match(config, /home:\s*'\/pages\/account\/profile'/)
	assert.match(config, /profile:\s*'\/pages\/account\/profile'/)
})

test('session gate restores authenticated sessions into the profile page', () => {
	const gate = read('pages/launch/session-gate.vue')

	assert.match(gate, /restorePersistedSession\(\)/)
	assert.match(gate, /loadCurrentUserProfile\(\{ force: true \}\)/)
	assert.match(gate, /AUTH_ROUTES\.profile/)
	assert.doesNotMatch(gate, /AUTH_ROUTES\.home/)
	assert.equal(gate.includes(legacyTabNavigationMethod), false)
})

test('pages config keeps only auth, launch, and account profile routes', () => {
	const pages = read('pages.json')
	const parsed = JSON.parse(pages)
	const routes = parsed.pages.map(page => page.path)

	assert.deepEqual(routes, [
		'pages/launch/session-gate',
		'pages/auth/login',
		'pages/auth/register',
		'pages/auth/password-reset',
		'pages/account/profile'
	])
	assert.equal(Object.prototype.hasOwnProperty.call(parsed, nativeTabKey), false)
	assert.equal(Object.prototype.hasOwnProperty.call(parsed, legacyTopPanelKey), false)
	assert.equal(Object.prototype.hasOwnProperty.call(parsed, legacyLeftPanelKey), false)
})

test('profile page shows account fields and a single centered bottom profile item', () => {
	const profile = read('pages/account/profile.vue')

	assert.match(profile, /profile-bottom-nav/)
	assert.match(profile, /profile-bottom-item/)
	assert.match(profile, />\s*个人\s*</)
	assert.match(profile, /profile\.displayName/)
	assert.match(profile, /profile\.email/)
	assert.match(profile, /phone\.displayNumber/)
	assert.match(profile, /退出登录/)
	assert.match(profile, /\.profile-logout[\s\S]*border:\s*1px solid #c9822f/)
	assert.match(profile, /\.profile-logout[\s\S]*color:\s*#f2a24d/)
	assert.match(profile, /\.profile-retry,\s*\.profile-logout[\s\S]*align-items:\s*center/)
	assert.match(profile, /\.profile-retry,\s*\.profile-logout[\s\S]*justify-content:\s*center/)
	assert.doesNotMatch(profile, /#e1a19e|#53403f/)
})

test('profile page exposes a confirmed all-device logout action below current logout', () => {
	const profile = read('pages/account/profile.vue')

	assert.match(profile, /profile-action-group/)
	assert.match(profile, /@click="logout"/)
	assert.match(profile, /@click="confirmLogoutAll"/)
	assert.match(profile, /uni\.showModal/)
	assert.match(profile, /正在退出所有设备/)
	assert.match(profile, /:disabled="logoutBusy"/)
	assert.match(profile, /退出所有设备/)
	assert.match(profile, /catch \(error\) \{[\s\S]*uni\.showToast\(/)
})

test('all-device logout client uses the dedicated endpoint without user or refresh-token payloads', () => {
	const httpClient = read('common/auth/http-client.js')

	assert.match(httpClient, /export async function logoutAllSessions\(\)/)
	assert.match(httpClient, /\/api\/auth\/session\/logout-all/)
	assert.match(httpClient, /clearSession\(\)/)
	assert.match(httpClient, /preserveSessionOnFailure:\s*true/)
	assert.doesNotMatch(httpClient, /logoutAllSessions[\s\S]*data\s*=\s*\{\s*userId/)
	assert.doesNotMatch(httpClient, /logoutAllSessions[\s\S]*refreshToken/)
})

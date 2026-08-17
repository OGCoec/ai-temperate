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

function parseUniPages(source) {
	return JSON.parse(source.replace(/^\s*\/\/\s*#(?:ifn?def|endif).*$/gm, ''))
}

test('login success enters the chat page without a local preview bypass', () => {
	const login = read('pages/auth/login.vue')
	const config = read('common/auth/config.js')

	assert.match(login, /completeLogin\(\)/)
	assert.equal(login.includes(`uni.${legacyTabNavigationMethod}({`), false)
	assert.match(login, /uni\.reLaunch\(\{/)
	assert.match(login, /url:\s*AUTH_ROUTES\.home/)
	assert.doesNotMatch(login, /previewAuthenticatedPages|authUiPreview|ui-preview-session/)
	assert.match(config, /home:\s*'\/pages\/ai-chat\/index'/)
	assert.match(config, /chat:\s*'\/pages\/ai-chat\/index'/)
	assert.match(config, /profile:\s*'\/pages\/account\/profile'/)
	assert.match(config, /apiKeys:\s*'\/pages\/account\/api-keys'/)
})

test('session gate restores authenticated sessions into the chat page', () => {
	const gate = read('pages/launch/session-gate.vue')

	assert.match(gate, /restorePersistedSession\(\)/)
	assert.match(gate, /loadCurrentUserProfile\(\{ force: true \}\)/)
	assert.match(gate, /AUTH_ROUTES\.home/)
	assert.equal(gate.includes(legacyTabNavigationMethod), false)
})

test('pages config keeps auth, profile, and protected model catalog routes without a native tab bar', () => {
	const pages = read('pages.json')
	const config = read('common/auth/config.js')
	const parsed = parseUniPages(pages)
	const routes = parsed.pages.map(page => page.path)

	for (const route of [
		'pages/launch/session-gate',
		'pages/auth/login',
		'pages/auth/register',
		'pages/auth/password-reset',
		'pages/ai-chat/index',
		'pages/account/profile',
		'pages/account/api-keys',
		'pages/ai-models/catalog',
		'pages/ai-models/detail'
	]) {
		assert.ok(routes.includes(route))
	}
	assert.match(config, /models:\s*'\/pages\/ai-models\/catalog'/)
	assert.equal(Object.prototype.hasOwnProperty.call(parsed, nativeTabKey), false)
	assert.equal(Object.prototype.hasOwnProperty.call(parsed, legacyTopPanelKey), false)
	assert.equal(Object.prototype.hasOwnProperty.call(parsed, legacyLeftPanelKey), false)
})

test('profile page shows account fields, quota presentation, and the shared primary navigation', () => {
	const entry = read('pages/account/profile.vue')
	const profile = read('components/user/workspace/user-profile-panel.vue')

	assert.match(entry, /<user-workspace[\s\S]*initial-destination="profile"/)
	assert.match(profile, />\s*个人\s*</)
	assert.match(profile, /profile\.displayName/)
	assert.match(profile, /profile\.email/)
	assert.match(profile, /phone\.displayNumber/)
	assert.match(profile, /profile\?\.membershipTier/)
	assert.match(profile, /profile\?\.quotaBalance/)
	assert.match(profile, /formatQuotaResetAt/)
	assert.match(profile, /formatLocalDateTimeZhCn/)
	assert.match(profile, /首次使用后开始计算/)
	assert.match(profile, /暂不可用/)
	assert.doesNotMatch(profile, /new Intl\.DateTimeFormat/)
	assert.match(profile, /@click="refreshProfile\(true\)"/)
	assert.match(profile, /退出登录/)
	assert.match(profile, /\.profile-logout[\s\S]*border:\s*1px solid #c9822f/)
	assert.match(profile, /\.profile-logout[\s\S]*color:\s*#f2a24d/)
	assert.match(profile, /\.profile-retry,\s*\.profile-logout[\s\S]*align-items:\s*center/)
	assert.match(profile, /\.profile-retry,\s*\.profile-logout[\s\S]*justify-content:\s*center/)
	assert.doesNotMatch(profile, /profile-bottom-nav/)
	assert.doesNotMatch(profile, /#e1a19e|#53403f/)
})

test('shared primary navigation switches top-level pages and adapts from bottom bar to side rail', () => {
	const navigation = read('components/user/user-primary-navigation.vue')
	const workspace = read('components/user/user-workspace.vue')
	const material = read('common/ui/user-material.scss')

	assert.match(navigation, /activeDestination/)
	assert.doesNotMatch(navigation, /uni\.reLaunch\(/)
	assert.doesNotMatch(navigation, /getCurrentPages\(\)/)
	assert.doesNotMatch(navigation, /uni\.navigateBack\(/)
	assert.doesNotMatch(navigation, /uni\.navigateTo\(/)
	assert.match(workspace, /selectDestination\(destination\)/)
	assert.match(navigation, /@include user-frosted-navigation/)
	assert.match(material, /@mixin user-frosted-navigation[\s\S]*backdrop-filter:\s*blur/)
	assert.match(navigation, /min-width:\s*768px/)
	assert.match(navigation, /min-width:\s*1100px/)
	assert.match(navigation,
		/@media screen and \(min-width:\s*768px\)[\s\S]*\.user-primary-navigation\.is-chat-sidebar\s*\{[^}]*width:\s*240px/)
	assert.match(navigation,
		/@media screen and \(min-width:\s*1100px\)[\s\S]*\.user-primary-navigation\.is-chat-sidebar\s*\{[^}]*width:\s*272px/)
	assert.match(navigation, /prefers-reduced-motion:\s*reduce/)
})

test('profile page exposes a confirmed all-device logout action below current logout', () => {
	const profile = read('components/user/workspace/user-profile-panel.vue')

	assert.match(profile, /profile-action-group/)
	assert.match(profile, /@click="logout"/)
	assert.match(profile, /@click="confirmLogoutAll"/)
	assert.match(profile, /uni\.showModal/)
	assert.match(profile, /正在退出所有设备/)
	assert.match(profile, /:disabled="logoutBusy"/)
	assert.match(profile, /退出所有设备/)
	assert.match(profile, /catch \(error\) \{[\s\S]*uni\.showToast\(/)
})

test('profile settings workbench uses accessible tabs and preserves every account destination', () => {
	const profile = read('components/user/workspace/user-profile-panel.vue')

	assert.match(profile, /activeProfileSection:\s*'account'/)
	assert.match(profile, /profileSections\(\)[\s\S]*key:\s*'account'[\s\S]*key:\s*'quota'[\s\S]*key:\s*'security'[\s\S]*key:\s*'developer'[\s\S]*key:\s*'sessions'/)
	assert.match(profile, /class="profile-workbench-navigation"[\s\S]*role="tablist"[\s\S]*aria-label="个人中心分区"/)
	assert.match(profile, /<button[\s\S]*:id="`profile-tab-\$\{section\.key\}`"[\s\S]*class="profile-workbench-nav-item"[\s\S]*role="tab"[\s\S]*:aria-selected="activeProfileSection === section\.key"[\s\S]*:aria-controls="`profile-panel-\$\{section\.key\}`"/)
	assert.match(profile, /@click="selectProfileSection\(section\.key\)"/)
	assert.match(profile, /@keydown="handleProfileSectionKeydown\(\$event, section\.key\)"/)
	assert.match(profile, /handleProfileSectionKeydown\(event, key\)[\s\S]*ArrowRight[\s\S]*ArrowLeft[\s\S]*Home[\s\S]*End[\s\S]*preventDefault\(\)[\s\S]*selectProfileSection\(nextKey\)[\s\S]*focus/)
	for (const key of ['account', 'quota', 'security', 'developer', 'sessions']) {
		assert.match(profile, new RegExp(
			`v-show="profileSectionVisible\\('${key}'\\)"[\\s\\S]*?`
			+ `id="profile-panel-${key}"[\\s\\S]*?`
			+ `:role="androidClient \\? 'region' : 'tabpanel'"[\\s\\S]*?`
			+ `:aria-labelledby="androidClient \\? undefined : 'profile-tab-${key}'"`
		))
	}
	assert.match(profile, /profileSectionVisible\(key\)[\s\S]*this\.androidClient\s*\|\|\s*this\.activeProfileSection === key/)
	assert.match(profile, /@click="openTotpSecurity"/)
	assert.match(profile, /@click="openApiKeys"/)
	assert.match(profile, /@click="logout"/)
	assert.match(profile, /@click="confirmLogoutAll"/)
	assert.match(profile, /<image v-if="phone\.flag" class="profile-phone-flag" :src="phone\.flag"/)
	assert.doesNotMatch(profile, /parts\.push\(`\$\{this\.phone\.flag/)
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

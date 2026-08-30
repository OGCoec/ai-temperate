const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const frontendRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

test('native splash handoff requires DOM readiness and the App WebView show signal', () => {
	const bridge = read('common/launch/eagle-native-splash.js')

	assert.match(bridge, /export function createNativeSplashHandoff/)
	assert.match(bridge, /\$getAppWebview/)
	assert.match(bridge, /addEventListener\(['"]show['"]/)
	assert.match(bridge, /addEventListener\(['"]loaded['"]/)
	assert.match(bridge, /isVisible\?\.\(\)/)
	assert.match(bridge, /!domReady\s*\|\|\s*!webviewShown/)
	assert.match(bridge, /HANDOFF_FALLBACK_MILLIS\s*=\s*1200/)
	assert.match(bridge, /requestAnimationFrame/)
	assert.match(bridge, /removeEventListener/)
})

test('login and authenticated home use the handoff instead of direct timed dismissal', () => {
	const login = read('pages/auth/login.vue')
	const home = read('pages/ai-chat/index.vue')

	assert.match(login, /createNativeSplashHandoff\(this,\s*['"]login-ready['"]\)/)
	assert.match(login, /nativeSplashHandoff\?\.markDomReady\(\)/)
	assert.match(login, /nativeSplashHandoff\?\.bindWebview\(\)/)
	assert.match(login, /nativeSplashHandoff\?\.dispose\(\)/)
	assert.doesNotMatch(login, /setTimeout\(\(\)\s*=>\s*dismissNativeSplash\(['"]login-ready['"]/)

	assert.match(home, /createNativeSplashHandoff\(this,\s*['"]home-ready['"]\)/)
	assert.match(home, /nativeSplashHandoff\?\.markDomReady\(\)/)
	assert.match(home, /nativeSplashHandoff\?\.bindWebview\(\)/)
	assert.match(home, /nativeSplashHandoff\?\.dispose\(\)/)
	assert.doesNotMatch(home, /setTimeout\(\(\)\s*=>\s*dismissNativeSplash\(['"]home-ready['"]/)
})

test('same-page startup errors dismiss only after two paint frames', () => {
	const bridge = read('common/launch/eagle-native-splash.js')
	const sessionGate = read('pages/launch/session-gate.vue')

	assert.match(bridge, /export function dismissNativeSplashAfterPaint/)
	assert.match(sessionGate, /dismissNativeSplashAfterPaint\(reason\)/)
	assert.doesNotMatch(sessionGate, /setTimeout\(\(\)\s*=>\s*dismissNativeSplash\(reason\),\s*34\)/)
})

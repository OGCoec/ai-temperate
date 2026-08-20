const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')

test('Android OAuth Flow uses the existing encrypted flow vault while Google ID Token never enters storage', () => {
	const vault = read('common/auth/android-flow-keystore.js')
	const api = read('common/auth/auth-api.js')
	const flow = read('common/auth/oauth-flow.js')

	assert.match(vault, /saveAndroidOAuthFlow/)
	assert.match(vault, /phoneFlowToken/)
	assert.match(vault, /turnstileChallenge/)
	assert.match(vault, /updateAndroidOAuthFlowExpiry/)
	assert.doesNotMatch(vault, /idToken|googleIdToken/i)
	assert.match(api, /X-OAuth-Flow-Token/)
	assert.match(api, /X-OAuth-Phone-Flow-Token/)
	assert.doesNotMatch(api, /setStorageSync\([^\n]*idToken/i)
	assert.doesNotMatch(flow, /setStorage|saveAndroidOAuthFlow\([^\n]*idToken/i)
	assert.match(flow, /if \(!idToken\)[\s\S]*oauthCancel/)
})

test('Google native plugin disables auto selection and retries with all device accounts', () => {
	const plugin = read('uni_modules/ait-google-signin/utssdk/app-android/index.uts')
	const config = JSON.parse(read('uni_modules/ait-google-signin/utssdk/app-android/config.json'))

	assert.match(plugin, /setAutoSelectEnabled\(false\)/)
	assert.match(plugin, /setFilterByAuthorizedAccounts\(filterAuthorized\)/)
	assert.match(plugin, /requestCredential\(this\.serverClientId, this\.nonce, false/)
	assert.match(plugin, /setNonce\(nonce\)/)
	assert.deepEqual(config.dependencies, [
		'androidx.credentials:credentials:1.6.0',
		'androidx.credentials:credentials-play-services-auth:1.6.0',
		'com.google.android.libraries.identity.googleid:googleid:1.2.0'
	])
})

test('Android Google login is strictly native and never exposes browser fallback', () => {
	const login = read('pages/auth/login.vue')
	const flow = read('common/auth/oauth-flow.js')
	const config = read('common/auth/config.js')

	assert.doesNotMatch(login, /googleBrowserFallback/)
	assert.doesNotMatch(login, /改用浏览器登录 Google/)
	assert.doesNotMatch(login, /forceBrowser/)
	assert.match(flow, /provider === 'GOOGLE'[\s\S]*GOOGLE_NATIVE/)
	assert.doesNotMatch(flow, /nativeUnavailable[\s\S]*interactionMode:\s*['"]BROWSER['"]/
	)
	assert.match(config, /#ifdef APP-PLUS[\s\S]*uni\.getSystemInfoSync\(\)/)
	assert.doesNotMatch(config, /#ifdef APP-(?:ANDROID|IOS)/)
})

test('native Google plugin is compiled for the plus runtime and guarded by Android detection', () => {
	const flow = read('common/auth/oauth-flow.js')

	assert.match(flow, /#ifdef APP-PLUS[\s\S]*ait-google-signin[\s\S]*#endif/)
	assert.match(flow, /clientPlatform\(\) !== ['"]ANDROID['"]|clientPlatform\(\) === ['"]ANDROID['"]/)
	assert.doesNotMatch(flow, /#ifdef APP-ANDROID/)
	assert.doesNotMatch(flow, /#ifdef APP-IOS/)
})

test('login, App onShow and OAuth phone page expose the required recovery paths', () => {
	const login = read('pages/auth/login.vue')
	const app = read('App.vue')
	const phone = read('pages/auth/oauth-phone.vue')

	assert.match(login, /使用 Google 登录/)
	assert.match(login, /使用 GitHub 登录/)
	assert.doesNotMatch(login, /改用浏览器登录 Google/)
	assert.match(app, /resumePendingOAuth/)
	assert.match(phone, /action="oauth_phone"/)
	assert.match(phone, /phone-delivery-method/)
	assert.match(phone, /60/)
})

test('H5 OAuth flow pages never enter authenticated session bootstrap', () => {
	const protectedRoutes = read('common/auth/protected-routes.js')
	const returnPage = read('pages/auth/oauth-return.vue')
	const phonePage = read('pages/auth/oauth-phone.vue')

	assert.match(protectedRoutes, /'\/pages\/auth\/oauth-return'/)
	assert.match(protectedRoutes, /'\/pages\/auth\/oauth-phone'/)
	assert.doesNotMatch(returnPage, /restorePersistedSession|session\/bootstrap/)
	assert.doesNotMatch(phonePage, /restorePersistedSession|session\/bootstrap/)
})

test('H5 OAuth return completion is page-idempotent and delegates state transitions once', () => {
	const returnPage = read('pages/auth/oauth-return.vue')
	const flow = read('common/auth/oauth-flow.js')

	assert.match(returnPage, /completionPromise/)
	assert.match(returnPage, /if \(this\.completionPromise\) return this\.completionPromise/)
	assert.match(returnPage, /this\.completionPromise = this\.runCompletion\(\)/)
	assert.match(flow, /\['PHONE_REQUIRED', 'HUMAN_VERIFICATION_REQUIRED', 'CODE_READY'\]/)
	assert.match(flow, /status\.state === 'READY_TO_COMPLETE'/)
	assert.match(flow, /status\.state === 'TOTP_REQUIRED'/)
	assert.match(flow, /status\.state === 'AUTHENTICATED'/)
	assert.match(flow, /result\?\.status === 'TOTP_REQUIRED'/)
	assert.match(flow, /result\?\.status === 'AUTHENTICATED'/)
	assert.match(flow, /\['FAILED', 'EXPIRED'\]/)
})

test('login renders two-column OAuth buttons with packaged provider icons', () => {
	const login = read('pages/auth/login.vue')
	const googleIconPath = path.join(root, 'static/icons/auth/google.svg')
	const githubIconPath = path.join(root, 'static/icons/auth/github.svg')

	assert.match(login, /\.oauth-actions\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/)
	assert.match(login, /class="oauth-icon"\s+src="\/static\/icons\/auth\/google\.svg"\s+mode="aspectFit"\s+aria-hidden="true"/)
	assert.match(login, /class="oauth-icon"\s+src="\/static\/icons\/auth\/github\.svg"\s+mode="aspectFit"\s+aria-hidden="true"/)
	assert.doesNotMatch(login, /\.oauth-fallback\s*\{[^}]*grid-column:\s*1\s*\/\s*-1/)
	assert.doesNotMatch(
		login,
		/@media\s+screen\s+and\s+\(max-width:\s*359px\)[\s\S]*?\.oauth-actions\s*\{[^}]*grid-template-columns:\s*1fr/
	)
	assert.equal(fs.existsSync(googleIconPath), true)
	assert.equal(fs.existsSync(githubIconPath), true)
	assert.match(fs.readFileSync(googleIconPath, 'utf8'), /#4285F4[\s\S]*#34A853[\s\S]*#FBBC05[\s\S]*#EA4335/)
	assert.match(fs.readFileSync(githubIconPath, 'utf8'), /fill="white"/)
})

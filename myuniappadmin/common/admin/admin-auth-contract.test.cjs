const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const projectRoot = path.resolve(__dirname, '../..')
const source = file => fs.readFileSync(path.join(projectRoot, file), 'utf8')

test('administrator API exposes the complete initialization login and session surface', () => {
	const api = source('common/admin/admin-api.js')
	for (const endpoint of [
		'/api/admin/auth/state',
		'/api/admin/auth/register/start',
		'/api/admin/auth/register/hcaptcha',
		'/api/admin/auth/register/codes/email/send',
		'/api/admin/auth/register/codes/phone/send',
		'/api/admin/auth/register/codes/verify',
		'/api/admin/auth/register/complete',
		'/api/admin/auth/login/start',
		'/api/admin/auth/login/complete',
		'/api/admin/auth/session/bootstrap',
		'/api/admin/auth/logout',
		'/api/admin/auth/logout-all'
	]) {
		assert.match(api, new RegExp(endpoint.replace(/[/.]/g, '\\$&')))
	}
})

test('H5 hCaptcha uses explicit rendering and never persists its one-time token', () => {
	const hcaptcha = source('common/admin/admin-hcaptcha.js')
	assert.match(hcaptcha, /render=explicit&recaptchacompat=off&onload=/)
	assert.match(hcaptcha, /HCAPTCHA_READY_CALLBACK/)
	assert.match(hcaptcha, /SDK_READY_TIMEOUT_MS\s*=\s*15_000/)
	assert.doesNotMatch(hcaptcha, /script\.onload\s*=\s*\(\)\s*=>\s*resolve/)
	assert.match(hcaptcha, /scriptPromise\s*=\s*undefined/)
	assert.match(hcaptcha, /script\.remove\(\)/)
	assert.match(hcaptcha, /api\.render/)
	assert.match(hcaptcha, /api\.reset/)
	assert.match(hcaptcha, /'error-callback':\s*code\s*=>/)
	assert.match(hcaptcha, /'close-callback':/)
	assert.match(hcaptcha, /autoRetryCount\s*<\s*MAX_AUTO_RETRIES/)
	assert.match(hcaptcha, /retryButton/)
	assert.match(hcaptcha, /cancelButton/)
	assert.doesNotMatch(hcaptcha, /setStorage|localStorage|sessionStorage/)
	assert.doesNotMatch(hcaptcha, /console\.(?:log|info|warn|error)\([^\n]*token/)
})

test('Android hCaptcha passes only public bootstrap data through a non-request fragment', () => {
	const hcaptcha = source('common/admin/admin-hcaptcha.js')

	assert.match(hcaptcha, /requestAndroidToken\(siteKey, challengeId\)/)
	assert.match(hcaptcha, /#siteKey=\$\{encodeURIComponent\(siteKey\)\}&challenge=\$\{encodeURIComponent\(challengeId\)\}/)
	assert.doesNotMatch(hcaptcha, /[?&]siteKey=/)
})

test('H5 administrator mutations copy purpose-specific CSRF cookies into the request header', () => {
	const http = source('common/admin/admin-http.js')
	const policy = source('common/admin/admin-csrf-policy.js')
	assert.match(policy, /admin_register_csrf/)
	assert.match(policy, /admin_login_csrf/)
	assert.match(policy, /ADMIN-XSRF-TOKEN/)
	assert.doesNotMatch(policy, /['"]XSRF-TOKEN['"]/)
	assert.match(http, /requiredAdminCsrfToken/)
	assert.match(http, /expectedAdminCsrfCookieAfterSuccess/)
	assert.match(http, /headers\['X-Admin-CSRF-Token'\]\s*=\s*csrf/)
	assert.match(http, /withCredentials:\s*true/)
	assert.ok(http.indexOf('requiredAdminCsrfToken') < http.indexOf('new Promise'))
})

test('fresh administrator registration skips status recovery without a readable flow', () => {
	const api = source('common/admin/admin-api.js')
	const page = source('pages/index/index.vue')

	assert.match(api, /hasRegistrationFlow\(\)/)
	assert.match(page, /if \(adminApi\.hasRegistrationFlow\(\)\)/)
})

test('Android administrator credentials are encrypted through AndroidKeyStore', () => {
	const vault = source('common/admin/admin-secure-vault.js')
	assert.match(vault, /AndroidKeyStore/)
	assert.match(vault, /AES\/GCM\/NoPadding/)
	assert.match(vault, /ciphertext/)
	assert.doesNotMatch(vault, /uni\.setStorageSync\(STORAGE_KEY,\s*JSON\.stringify\(state\)/)
})

test('administrator page implements all required UI states without password recovery entry', () => {
	const page = source('pages/index/index.vue')
	for (const state of [
		'LOADING',
		'UNINITIALIZED',
		'ACTIVE',
		'CORRUPT',
		'DISABLED',
		'AUTHENTICATED'
	]) {
		assert.match(page, new RegExp(state))
	}
	assert.match(page, /退出当前设备/)
	assert.match(page, /退出所有设备/)
	assert.doesNotMatch(page, /忘记密码|重置密码|注册第二/)
})

test('ordinary and administrator frontends resolve the same shared validation source', () => {
	const ordinaryVite = source('../fornted/vite.config.js')
	const adminVite = source('vite.config.js')
	assert.match(ordinaryVite, /\.\/common\/shared-auth/)
	assert.match(adminVite, /\.\.\/fornted\/common\/shared-auth/)
	assert.equal(fs.existsSync(path.resolve(projectRoot, '../fornted/common/shared-auth/password-policy.js')), true)
})

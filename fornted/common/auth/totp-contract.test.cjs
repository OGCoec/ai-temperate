const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')

function read(relativePath) {
	return fs.readFileSync(path.join(root, relativePath), 'utf8')
}

test('first factor stores no session while TOTP is still required', () => {
	const api = read('common/auth/auth-api.js')
	const login = read('pages/auth/login.vue')

	assert.match(api, /status === 'TOTP_REQUIRED'[\s\S]*beginTotpLoginFlow/)
	assert.match(api, /status === 'AUTHENTICATED'[\s\S]*saveSession/)
	assert.match(login, /status === 'TOTP_REQUIRED'[\s\S]*AUTH_ROUTES\.totpLogin/)
	assert.match(login, /status === 'AUTHENTICATED'[\s\S]*completeLogin/)
})

test('H5 keeps the raw login challenge in HttpOnly cookie while Android uses KeyStore', () => {
	const flow = read('common/auth/totp-login-flow.js')
	const android = read('common/auth/android-flow-keystore.js')

	assert.doesNotMatch(flow, /totpFlowToken[\s\S]*setStorageSync/)
	assert.match(flow, /clientPlatform\(\) === 'ANDROID'[\s\S]*saveAndroidTotpLoginFlow/)
	assert.match(android, /totpLogin[\s\S]*totpFlowToken/)
	assert.match(android, /AES\/GCM\/NoPadding/)
})

test('management client covers step-up, ten-minute setup confirmation, and disable', () => {
	const api = read('common/auth/totp-api.js')
	const page = read('pages/account/totp-security.vue')

	for (const endpoint of [
		'/reverification/password',
		'/reverification/code/start',
		'/reverification/code/verify',
		'/setup/start',
		'/setup/confirm',
		'/disable'
	]) assert.match(api, new RegExp(endpoint.replaceAll('/', '\\/')))

	assert.match(page, /import qrcode from 'qrcode-generator'/)
	assert.match(page, /qrcode\(0, 'M'\)[\s\S]*addData\(setup\.otpauthUri\)[\s\S]*createSvgTag/)
	assert.doesNotMatch(page, /https?:\/\/[\w.-]*(?:qr|chart)/i)
	assert.match(page, /Base32 密钥/)
	assert.match(page, /setupRemainingSeconds/)
})

test('TOTP login page is public but management page remains protected by default', async () => {
	const source = read('common/auth/protected-routes.js')
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	const routes = await import(sourceUrl)

	assert.equal(routes.isPublicRoute('/pages/auth/totp-login'), true)
	assert.equal(routes.isProtectedRoute('/pages/account/totp-security'), true)
})

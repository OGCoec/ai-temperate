const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const httpClient = fs.readFileSync(path.resolve(__dirname, 'http-client.js'), 'utf8')
const nativeSse = fs.readFileSync(
	path.resolve(__dirname, '../aichat/ai-conversation-sse-app.js'),
	'utf8'
)
const nativeSseInterface = fs.readFileSync(
	path.resolve(__dirname, '../../uni_modules/ait-sse/utssdk/interface.uts'),
	'utf8'
)

test('ordinary requests send RT-first credentials without calling the removed refresh endpoint', () => {
	assert.doesNotMatch(httpClient, /\/api\/auth\/session\/refresh/)
	assert.match(httpClient, /X-Refresh-Token/)
	assert.match(httpClient, /X-CSRF-Token/)
	assert.doesNotMatch(httpClient, /return authorizedRequest\(path, options, true\)/)
	const authorizedRequest = httpClient.slice(
		httpClient.indexOf('export async function authorizedRequest'),
		httpClient.indexOf('function handleAuthorizedSecurityFailure')
	)
	assert.match(authorizedRequest, /requestTask\(/)
	assert.doesNotMatch(authorizedRequest, /publicRequest\(/)
})

test('successful responses apply an Android access-token renewal before returning business data', () => {
	assert.match(httpClient, /X-Session-Renewed/i)
	assert.match(httpClient, /X-New-Access-Token/i)
	assert.match(httpClient, /saveSession\(\{ accessToken:/)
})

test('Android SSE exposes and applies renewal headers before consuming chunks', () => {
	assert.match(nativeSseInterface, /newAccessToken/)
	assert.match(nativeSseInterface, /sessionRenewed/)
	assert.match(nativeSse, /applySessionRenewalHeaders/)
	assert.match(nativeSse, /onOpen\(renewal\)/)
})

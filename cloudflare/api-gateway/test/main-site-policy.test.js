import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
	H5_PAGE_PATHS,
	matchRootApiRoute
} from '../src/main-site-policy.js'

test('Worker H5 page allowlist matches every page declared for the ordinary app', () => {
	const source = readFileSync(
		new URL('../../../fornted/pages.json', import.meta.url), 'utf8')
	const pages = JSON.parse(source.replace(/^\s*\/\/.*$/gm, ''))
		.pages
		.map(page => `/${page.path}`)
		.sort()
	const workerPaths = H5_PAGE_PATHS.filter(path => path !== '/').toSorted()

	assert.deepEqual(workerPaths, pages)
	assert.equal(H5_PAGE_PATHS[0], '/')
})

test('API route contracts expose methods, platform scope, parameter type, and error envelope', () => {
	const login = matchRootApiRoute('/api/auth/login/password')
	const apiKeySdk = matchRootApiRoute('/v1/models')
	const chat = matchRootApiRoute('/v1/chat/completions')
	const responses = matchRootApiRoute('/v1/responses')
	const model = matchRootApiRoute('/api/ai-models/AAABi0VWeJ8')

	assert.deepEqual(login.allowedMethods, ['POST'])
	assert.deepEqual(login.clientPlatforms, ['H5', 'ANDROID'])
	assert.equal(login.errorResponseType, 'API_JSON')
	assert.deepEqual(apiKeySdk.clientPlatforms, ['API_KEY_CLIENT'])
	assert.equal(apiKeySdk.errorResponseType, 'OPENAI_JSON')
	assert.equal(chat.responseMode, 'adaptive')
	assert.equal(chat.protocol, 'chat_completions')
	assert.deepEqual(responses.allowedMethods, ['POST'])
	assert.equal(responses.responseMode, 'adaptive')
	assert.equal(responses.protocol, 'responses')
	assert.equal(responses.apiKeySdk, true)
	assert.equal(model.parameterType, 'PUBLIC_LONG_BASE64URL_11')
})

test('only exact Turnstile subresources are credentialless verification assets', () => {
	const page = matchRootApiRoute('/api/auth/turnstile/page')
	const config = matchRootApiRoute('/api/auth/turnstile/config')
	const style = matchRootApiRoute('/api/auth/turnstile/page.css')
	const script = matchRootApiRoute('/api/auth/turnstile/page.js')
	const source = readFileSync(
		new URL('../src/main-site-policy.js', import.meta.url), 'utf8')

	assert.equal(page.credentiallessVerificationAsset, undefined)
	assert.equal(config.credentiallessVerificationAsset, undefined)
	assert.equal(style.credentiallessVerificationAsset, true)
	assert.equal(script.credentiallessVerificationAsset, true)
	assert.deepEqual(style.allowedMethods, ['GET'])
	assert.deepEqual(script.allowedMethods, ['GET'])
	assert.equal(
		(source.match(/credentiallessVerificationAsset:\s*true/g) || []).length,
		2
	)
	assert.doesNotMatch(source, /turnstile\/\*|turnstile\/page\.\*/)
})

test('every current ordinary frontend API contract is admitted by an exact edge policy', () => {
	const longId = 'AAABi0VWeJ8'
	const publicId = 'AZ-vpV3kfag70-0EMMUETQ'
	const preuploadId = 'A'.repeat(24)
	const contracts = [
		['POST', '/api/_edge/cookie-scope'],
		['GET', '/api/_edge/risk-challenge'],
		['POST', '/api/_edge/pre-auth'],
		['GET', '/api/_edge/webrtc/start'],
		['POST', '/api/_edge/webrtc/report'],
		['GET', '/api/auth/csrf'],
		['GET', '/api/auth/phone-country'],
		['GET', '/api/auth/turnstile/config'],
		['GET', '/api/auth/turnstile/page'],
		['GET', '/api/auth/turnstile/page.css'],
		['GET', '/api/auth/turnstile/page.js'],
		['POST', '/api/auth/login/password'],
		['POST', '/api/auth/login/code/start'],
		['POST', '/api/auth/login/code/turnstile'],
		['POST', '/api/auth/login/code/send'],
		['POST', '/api/auth/login/code/verify'],
		['POST', '/api/auth/login/totp/verify'],
		['POST', '/api/auth/register/start'],
		['GET', '/api/auth/register/status'],
		['POST', '/api/auth/register/turnstile'],
		['POST', '/api/auth/register/codes/email/send'],
		['POST', '/api/auth/register/codes/sms/send'],
		['POST', '/api/auth/register/codes/phone/send'],
		['POST', '/api/auth/register/codes/verify'],
		['POST', '/api/auth/register/complete'],
		['POST', '/api/auth/password-reset/start'],
		['POST', '/api/auth/password-reset/turnstile'],
		['POST', '/api/auth/password-reset/send'],
		['POST', '/api/auth/password-reset/verify'],
		['POST', '/api/auth/password-reset/complete'],
		['POST', '/api/auth/session/bootstrap'],
		['POST', '/api/auth/session/logout'],
		['POST', '/api/auth/session/logout-all'],
		['GET', '/api/users/me'],
		['POST', '/api/users/me/voice/session-tickets'],
		['POST', '/api/users/me/avatar/preuploads'],
		['DELETE', `/api/users/me/avatar/preuploads/${preuploadId}`],
		['POST', `/api/users/me/avatar/preuploads/${preuploadId}/confirm`],
		['GET', '/api/users/me/security/totp'],
		['POST', '/api/users/me/security/totp/reverification/password'],
		['POST', '/api/users/me/security/totp/reverification/code/start'],
		['POST', '/api/users/me/security/totp/reverification/code/turnstile'],
		['POST', '/api/users/me/security/totp/reverification/code/send'],
		['POST', '/api/users/me/security/totp/reverification/code/verify'],
		['POST', '/api/users/me/security/totp/setup/start'],
		['POST', '/api/users/me/security/totp/setup/confirm'],
		['POST', '/api/users/me/security/totp/disable'],
		['GET', '/api/users/me/api-keys'],
		['POST', '/api/users/me/api-keys'],
		['GET', `/api/users/me/api-keys/${longId}`],
		['PUT', `/api/users/me/api-keys/${longId}`],
		['DELETE', `/api/users/me/api-keys/${longId}`],
		['PUT', `/api/users/me/api-keys/${longId}/models`],
		['GET', '/api/ai-models'],
		['GET', `/api/ai-models/${longId}`],
		['GET', '/api/ai/conversations'],
		['GET', `/api/ai/conversations/${publicId}/messages`],
		['POST', '/api/ai/conversations/responses'],
		['POST', `/api/ai/conversations/${publicId}/responses`],
		['POST', '/api/ai/conversations/responses/cancel'],
		['GET', `/api/ai/conversations/${publicId}/context-usage`],
		['POST', `/api/ai/conversations/${publicId}/compactions`],
		['GET', `/api/ai/conversations/${publicId}/context/events`],
		['POST', '/api/ai/conversation-attachments/preuploads'],
		['GET', '/api/ai/conversations/generations'],
		['GET', '/api/ai/conversations/generations/by-idempotency'],
		['GET', `/api/ai/conversations/generations/${publicId}`],
		['GET', `/api/ai/conversations/generations/${publicId}/events`],
		['POST', `/api/ai/conversations/generations/${publicId}/cancel`],
		['POST', `/api/ai/conversations/generations/${publicId}/stream-diagnostics`]
	]

	for (const [method, path] of contracts) {
		const route = matchRootApiRoute(path)
		assert.ok(route && route.allowed !== false, `${method} ${path}`)
		assert.ok(route.allowedMethods.includes(method), `${method} ${path}`)
	}
})

test('main-site policy source does not contain API family wildcard fallbacks', () => {
	const source = readFileSync(
		new URL('../src/main-site-policy.js', import.meta.url), 'utf8')

	assert.doesNotMatch(source, /\/api\/auth\/\*\*|\/api\/users\/\*\*/)
	assert.doesNotMatch(source, /pathWithin\([^)]*['"]\/api\/(?:auth|users)['"]\)/)
})

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'browser-cookies.js'),
		'utf8'
	)
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(sourceUrl)
}

test('reads only the exact XSRF cookie value', async () => {
	const { cookieValue } = await loadModule()
	const cookies = 'prefix-XSRF-TOKEN=wrong; XSRF-TOKEN=csrf-value; theme=dark'

	assert.equal(cookieValue(cookies, 'XSRF-TOKEN'), 'csrf-value')
	assert.equal(cookieValue(cookies, 'missing'), '')
})

test('classifies every unsafe HTTP method as CSRF protected', async () => {
	const { requiresCsrf } = await loadModule()

	for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) {
		assert.equal(requiresCsrf(method), true, method)
	}
	for (const method of ['GET', 'HEAD', 'OPTIONS']) {
		assert.equal(requiresCsrf(method), false, method)
	}
})

test('reads the latest browser CSRF cookie for every request', async () => {
	globalThis.document = { cookie: 'XSRF-TOKEN=first-token' }
	const { browserCsrfToken } = await loadModule()

	assert.equal(browserCsrfToken(), 'first-token')
	globalThis.document.cookie = 'XSRF-TOKEN=rotated-token'
	assert.equal(browserCsrfToken(), 'rotated-token')

	delete globalThis.document
})

test('injects the CSRF header only for unsafe requests', async () => {
	const { applyBrowserCsrfHeader } = await loadModule()

	assert.deepEqual(
		applyBrowserCsrfHeader({}, 'POST', 'csrf-value'),
		{ 'X-CSRF-Token': 'csrf-value' }
	)
	assert.deepEqual(applyBrowserCsrfHeader({}, 'GET', 'csrf-value'), {})
})

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function source(relativePath) {
	return fs.readFileSync(path.resolve(__dirname, relativePath), 'utf8')
}

function methodBody(text, start, next) {
	const startIndex = text.indexOf(start)
	const endIndex = text.indexOf(next, startIndex + start.length)
	assert.notEqual(startIndex, -1, start)
	assert.notEqual(endIndex, -1, next)
	return text.slice(startIndex, endIndex)
}

test('API wrappers reject invalid passwords before starting network requests', () => {
	const api = source('auth-api.js')
	const register = methodBody(api, 'async registerComplete(', 'async passwordLogin(')
	const login = methodBody(api, 'async passwordLogin(', 'loginCodeStart(')
	const reset = methodBody(api, 'passwordResetComplete(', '\n\t}')

	assert.ok(register.indexOf('assertPasswordWriteAllowed') < register.indexOf('registrationFlowRequest'))
	assert.ok(login.indexOf('assertPasswordLoginAllowed') < login.indexOf('publicRequest'))
	assert.ok(reset.indexOf('assertPasswordWriteAllowed') < reset.indexOf('publicRequest'))
})

test('registration and reset pages synchronously reassess before calling authApi', () => {
	const register = source('../../pages/auth/register.vue')
	const reset = source('../../pages/auth/password-reset.vue')

	for (const page of [register, reset]) {
		const complete = page.slice(page.indexOf('async complete()'))
		assert.ok(complete.indexOf('passwordError(') < complete.indexOf('authApi.'))
	}
})

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const validatorIsEmail = require('validator/lib/isEmail')

let emailValidationModulePromise

async function loadEmailValidationModule() {
	if (emailValidationModulePromise) return emailValidationModulePromise
	const source = fs.readFileSync(
		path.resolve(__dirname, '../../../shared-frontend/auth/email-validation.js'),
		'utf8'
	).replace(
		"import isEmail from 'validator/es/lib/isEmail'",
		'const isEmail = globalThis.__authValidatorIsEmail'
	)
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	globalThis.__authValidatorIsEmail = validatorIsEmail
	emailValidationModulePromise = import(sourceUrl).finally(() => {
		delete globalThis.__authValidatorIsEmail
	})
	return emailValidationModulePromise
}

test('exports an ES module that accepts supported email formats', async () => {
	const { isValidEmailAddress } = await loadEmailValidationModule()
	for (const value of [
		'person@example.com',
		' Person.Name+alerts@example.test ',
		'user_name@example-domain.test'
	]) {
		assert.equal(isValidEmailAddress(value), true, value)
	}
})

test('does not expose frozen validation options to validator defaults', async () => {
	const { isValidEmailAddress } = await loadEmailValidationModule()

	assert.doesNotThrow(() => isValidEmailAddress('person@example.com'))
	assert.equal(isValidEmailAddress('person@example.com'), true)
})

test('rejects malformed email formats', async () => {
	const { isValidEmailAddress } = await loadEmailValidationModule()
	for (const value of [
		'',
		'person',
		'person@',
		'@example.com',
		'2848129@qq',
		'person@example.c',
		'person@example.123',
		'person@exa_mple.com',
		'person@[192.168.1.1]',
		`${'a'.repeat(65)}@example.com`,
		'.person@example.com',
		'person.@example.com',
		'person..name@example.com',
		'person@-example.com',
		'person@example-.com',
		'person@example..com',
		'person\t@example.com'
	]) {
		assert.equal(isValidEmailAddress(value), false, value)
	}
})

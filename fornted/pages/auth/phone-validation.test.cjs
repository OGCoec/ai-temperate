const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

let phoneValidationModulePromise

async function loadPhoneValidationModule() {
	if (phoneValidationModulePromise) return phoneValidationModulePromise
	const source = fs.readFileSync(
		path.resolve(__dirname, '../../../shared-frontend/auth/phone-validation.js'),
		'utf8'
	).replace(
		"import { AsYouType, getCountryCallingCode, parsePhoneNumberFromString } from 'libphonenumber-js/max'",
		'const { AsYouType, getCountryCallingCode, parsePhoneNumberFromString } = globalThis.__authPhoneNumberLib'
	)
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	globalThis.__authPhoneNumberLib = require('libphonenumber-js/max')
	phoneValidationModulePromise = import(sourceUrl).finally(() => {
		delete globalThis.__authPhoneNumberLib
	})
	return phoneValidationModulePromise
}

test('accepts valid local numbers for the selected country', async () => {
	const { isValidLocalPhoneNumber } = await loadPhoneValidationModule()

	assert.equal(isValidLocalPhoneNumber('13800138000', 'CN'), true)
	assert.equal(isValidLocalPhoneNumber('2025550123', 'US'), true)
	assert.equal(isValidLocalPhoneNumber('02079460018', 'GB'), true)
})

test('rejects invalid, extracted, and country-mismatched numbers', async () => {
	const { isValidLocalPhoneNumber } = await loadPhoneValidationModule()

	assert.equal(isValidLocalPhoneNumber('', 'CN'), false)
	assert.equal(isValidLocalPhoneNumber('12345', 'CN'), false)
	assert.equal(isValidLocalPhoneNumber('call 2025550123', 'US'), false)
	assert.equal(isValidLocalPhoneNumber('202 555 0123', 'US'), false)
	assert.equal(isValidLocalPhoneNumber('(202) 555-0123', 'US'), false)
	assert.equal(isValidLocalPhoneNumber('+442079460018', 'US'), false)
	assert.equal(isValidLocalPhoneNumber('2025550123', 'CN'), false)
	assert.equal(isValidLocalPhoneNumber('2025550123', ''), false)
})

test('keeps phone input state as ascii digits only', async () => {
	const { digitsOnlyPhoneInput } = await loadPhoneValidationModule()

	assert.equal(digitsOnlyPhoneInput('abc中文123-45'), '12345')
	assert.equal(digitsOnlyPhoneInput('(415) 555-2671'), '4155552671')
	assert.equal(digitsOnlyPhoneInput('+1 415 555 2671'), '14155552671')
})

test('formats local phone input using the selected country metadata', async () => {
	const { formatLocalPhoneNumberInput } = await loadPhoneValidationModule()

	assert.equal(formatLocalPhoneNumberInput('4155552671', 'US'), '(415) 555-2671')
	assert.equal(formatLocalPhoneNumberInput('9025550123', 'CA'), '(902) 555-0123')
	assert.equal(formatLocalPhoneNumberInput('11912345678', 'BR'), '(11) 91234-5678')
	assert.equal(formatLocalPhoneNumberInput('13800138000', 'CN'), '138 0013 8000')
	assert.equal(formatLocalPhoneNumberInput('02079460018', 'GB'), '020 7946 0018')
})

test('formats short nanp values as local area-code input', async () => {
	const { normalizePhoneInputForCountry } = await loadPhoneValidationModule()

	assert.deepEqual(normalizePhoneInputForCountry('155', 'US'), {
		digits: '155',
		display: '(155)'
	})
	assert.deepEqual(normalizePhoneInputForCountry('586', 'US'), {
		digits: '586',
		display: '(586)'
	})
	assert.deepEqual(normalizePhoneInputForCountry('145975', 'US'), {
		digits: '145975',
		display: '(145) 975'
	})
})

test('lets users delete generated nanp parentheses', async () => {
	const { normalizePhoneInputForCountry } = await loadPhoneValidationModule()

	assert.deepEqual(normalizePhoneInputForCountry('(586', 'US', '(586)'), {
		digits: '58',
		display: '58'
	})
	assert.deepEqual(normalizePhoneInputForCountry('586)', 'US', '(586)'), {
		digits: '58',
		display: '58'
	})
	assert.deepEqual(normalizePhoneInputForCountry('(586)', 'US', '(586) 7'), {
		digits: '586',
		display: '(586)'
	})
})

test('normalizes pasted international phone values back to local digits and display', async () => {
	const { normalizePhoneInputForCountry } = await loadPhoneValidationModule()

	assert.deepEqual(normalizePhoneInputForCountry('+14155552671', 'US'), {
		digits: '4155552671',
		display: '(415) 555-2671'
	})
	assert.deepEqual(normalizePhoneInputForCountry('1 415 555 2671', 'US'), {
		digits: '4155552671',
		display: '(415) 555-2671'
	})
	assert.deepEqual(normalizePhoneInputForCountry('+5511912345678', 'BR'), {
		digits: '11912345678',
		display: '(11) 91234-5678'
	})
})

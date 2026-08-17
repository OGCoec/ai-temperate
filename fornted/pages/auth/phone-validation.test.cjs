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
	assert.equal(isValidLocalPhoneNumber('13800138000', 'US'), false)
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
		display: '(155)',
		detectedCountryIso2: ''
	})
	assert.deepEqual(normalizePhoneInputForCountry('586', 'US'), {
		digits: '586',
		display: '(586)',
		detectedCountryIso2: ''
	})
	assert.deepEqual(normalizePhoneInputForCountry('145975', 'US'), {
		digits: '145975',
		display: '(145) 975',
		detectedCountryIso2: ''
	})
})

test('lets users delete generated nanp parentheses', async () => {
	const { normalizePhoneInputForCountry } = await loadPhoneValidationModule()

	assert.deepEqual(normalizePhoneInputForCountry('(586', 'US', '(586)'), {
		digits: '58',
		display: '58',
		detectedCountryIso2: ''
	})
	assert.deepEqual(normalizePhoneInputForCountry('586)', 'US', '(586)'), {
		digits: '58',
		display: '58',
		detectedCountryIso2: ''
	})
	assert.deepEqual(normalizePhoneInputForCountry('(586)', 'US', '(586) 7'), {
		digits: '586',
		display: '(586)',
		detectedCountryIso2: ''
	})
})

test('normalizes pasted international phone values back to local digits and display', async () => {
	const { normalizePhoneInputForCountry } = await loadPhoneValidationModule()

	assert.deepEqual(normalizePhoneInputForCountry('+14155552671', 'US'), {
		digits: '4155552671',
		display: '(415) 555-2671',
		detectedCountryIso2: 'US'
	})
	assert.deepEqual(normalizePhoneInputForCountry('1 415 555 2671', 'US'), {
		digits: '4155552671',
		display: '(415) 555-2671',
		detectedCountryIso2: 'US'
	})
	assert.deepEqual(normalizePhoneInputForCountry('+5511912345678', 'BR'), {
		digits: '11912345678',
		display: '(11) 91234-5678',
		detectedCountryIso2: ''
	})
})

test('keeps incomplete nanp local input on the current country', async () => {
	const { normalizePhoneInputForCountry } = await loadPhoneValidationModule()

	assert.deepEqual(normalizePhoneInputForCountry('4165550', 'US'), {
		digits: '4165550',
		display: '(416) 555-0',
		detectedCountryIso2: ''
	})
})

test('detects the nanp country only after a local number is complete and valid', async () => {
	const { normalizePhoneInputForCountry } = await loadPhoneValidationModule()

	assert.deepEqual(normalizePhoneInputForCountry('4165550123', 'US'), {
		digits: '4165550123',
		display: '(416) 555-0123',
		detectedCountryIso2: 'CA'
	})
	assert.deepEqual(normalizePhoneInputForCountry('3658978788', 'US'), {
		digits: '3658978788',
		display: '(365) 897-8788',
		detectedCountryIso2: 'CA'
	})
	assert.deepEqual(normalizePhoneInputForCountry('2025550123', 'CA'), {
		digits: '2025550123',
		display: '(202) 555-0123',
		detectedCountryIso2: 'US'
	})
	assert.deepEqual(normalizePhoneInputForCountry('2423001234', 'US'), {
		digits: '2423001234',
		display: '(242) 300-1234',
		detectedCountryIso2: 'BS'
	})
})

test('detects an eleven-digit nanp number without a leading plus', async () => {
	const { normalizePhoneInputForCountry } = await loadPhoneValidationModule()

	assert.deepEqual(normalizePhoneInputForCountry('14165550123', 'US'), {
		digits: '4165550123',
		display: '(416) 555-0123',
		detectedCountryIso2: 'CA'
	})
})

test('does not switch country for invalid nanp or non-nanp local input', async () => {
	const { normalizePhoneInputForCountry } = await loadPhoneValidationModule()

	assert.deepEqual(normalizePhoneInputForCountry('0000000000', 'US'), {
		digits: '0000000000',
		display: '(000) 000-0000',
		detectedCountryIso2: ''
	})

	const nonNanpResult = normalizePhoneInputForCountry('4165550123', 'GB')
	assert.equal(nonNanpResult.digits, '4165550123')
	assert.equal(nonNanpResult.detectedCountryIso2, '')
})

// ── 国际手机号自动识别测试 ──────────────────────────────────

test('returns null for non-international input (no leading +)', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	assert.equal(normalizeInternationalPhoneInput('2025550123'), null)
	assert.equal(normalizeInternationalPhoneInput('13800138000'), null)
	assert.equal(normalizeInternationalPhoneInput(''), null)
	assert.equal(normalizeInternationalPhoneInput(null), null)
	assert.equal(normalizeInternationalPhoneInput(undefined), null)
})

test('bare + stays pending', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	const plusOnly = normalizeInternationalPhoneInput('+')
	assert.equal(plusOnly.pendingInternational, true)
	assert.equal(plusOnly.detectedCountryIso2, '')
	assert.equal(plusOnly.localDigits, '')
})

test('incomplete prefix +4 stays pending (no country determinable)', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	const plus4 = normalizeInternationalPhoneInput('+4')
	assert.equal(plus4.pendingInternational, true)
	assert.equal(plus4.detectedCountryIso2, '')
})

test('+1 alone stays pending — never defaults to US', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	const plus1 = normalizeInternationalPhoneInput('+1')
	assert.equal(plus1.pendingInternational, true)
	assert.equal(plus1.detectedCountryIso2, '')
	assert.equal(plus1.localDigits, '')
})

test('+120 stays pending because an incomplete NANP number cannot choose a country', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	const plus120 = normalizeInternationalPhoneInput('+120')
	assert.equal(plus120.pendingInternational, true)
	assert.equal(plus120.detectedCountryIso2, '')
})

test('incomplete international prefixes stay pending and never lock a country', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()
	const incompleteValues = [
		'+44',
		'+86',
		'+861',
		'+1202',
		'+1365',
		'+1416',
		'+1416555012',
		'+44756829236',
		'+861380013800',
		'+7',
		'+8'
	]

	for (const value of incompleteValues) {
		const result = normalizeInternationalPhoneInput(value)
		assert.equal(result.pendingInternational, true, `${value} must remain pending`)
		assert.equal(result.detectedCountryIso2, '', `${value} must not lock a country`)
		assert.equal(result.localDigits, '', `${value} must not become canonical local digits`)
		assert.equal(result.sanitized, value)
	}
})

test('detects complete US number from +1 prefix', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	const result = normalizeInternationalPhoneInput('+12025550123')
	assert.equal(result.pendingInternational, false)
	assert.equal(result.detectedCountryIso2, 'US')
	assert.equal(result.localDigits, '2025550123')
	assert.ok(result.display.length > 0)
})

test('detects complete Canadian number from +1 prefix — not US', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	const result = normalizeInternationalPhoneInput('+14165550123')
	assert.equal(result.pendingInternational, false)
	assert.equal(result.detectedCountryIso2, 'CA')
	assert.equal(result.localDigits, '4165550123')
})

test('detects GB number from complete +44 number', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	const result = normalizeInternationalPhoneInput('+447568292362')
	assert.equal(result.pendingInternational, false)
	assert.equal(result.detectedCountryIso2, 'GB')
	assert.equal(result.localDigits, '7568292362')
})

test('detects BR number from complete +55 number', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	const result = normalizeInternationalPhoneInput('+5511912345678')
	assert.equal(result.pendingInternational, false)
	assert.equal(result.detectedCountryIso2, 'BR')
	assert.equal(result.localDigits, '11912345678')
})

test('detects CN number from complete +86 number', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	const result = normalizeInternationalPhoneInput('+8613800138000')
	assert.equal(result.pendingInternational, false)
	assert.equal(result.detectedCountryIso2, 'CN')
	assert.equal(result.localDigits, '13800138000')
})

test('sanitizes spaces and non-digit characters while preserving leading +', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	const result = normalizeInternationalPhoneInput('+44 7568 292 362')
	assert.equal(result.pendingInternational, false)
	assert.equal(result.detectedCountryIso2, 'GB')
	assert.equal(result.localDigits, '7568292362')
	assert.equal(result.sanitized, '+447568292362')
})

test('non-first-position + is stripped (not treated as international)', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	assert.equal(normalizeInternationalPhoneInput('44+7568292362'), null)
	assert.equal(normalizeInternationalPhoneInput('1+2025550123'), null)
})

test('invalid but complete-length international numbers stay pending', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	// +10000000000 — NANP doesn't have area code 000
	const result = normalizeInternationalPhoneInput('+10000000000')
	assert.equal(result.pendingInternational, true)
	assert.equal(result.detectedCountryIso2, '')
})

test('detects Bahamas (+1242) separately from US', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	const result = normalizeInternationalPhoneInput('+12423001234')
	assert.equal(result.pendingInternational, false)
	assert.equal(result.detectedCountryIso2, 'BS')
	assert.ok(result.localDigits.length > 0)
})

test('+13658978788 resolves as CA only after the full number is valid', async () => {
	const { normalizeInternationalPhoneInput } = await loadPhoneValidationModule()

	const result = normalizeInternationalPhoneInput('+13658978788')
	assert.equal(result.pendingInternational, false)
	assert.equal(result.detectedCountryIso2, 'CA')
	assert.equal(result.localDigits, '3658978788')
})

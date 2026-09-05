const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadPhonePresentationModule() {
	const authDirectory = path.resolve(__dirname, '../../../common/shared-auth')
	const countriesSource = fs.readFileSync(path.join(authDirectory, 'phone-countries.js'), 'utf8')
	const countriesUrl = sourceUrl(countriesSource)
	const countrySearchSource = fs.readFileSync(path.join(authDirectory, 'phone-country-search.js'), 'utf8')
		.replace("from './phone-countries.js'", `from '${countriesUrl}'`)
	const countrySearchUrl = sourceUrl(countrySearchSource)
	const source = fs.readFileSync(
		path.resolve(__dirname, '../../../common/user/phone-presentation.js'),
		'utf8'
	)
		.replace(
			"import { parsePhoneNumberFromString } from 'libphonenumber-js/max'",
			'const parsePhoneNumberFromString = globalThis.__profileParsePhoneNumber'
		)
		.replace(
			"from '@shared-auth/phone-country-search.js'",
			`from '${countrySearchUrl}'`
		)
	globalThis.__profileParsePhoneNumber = require('libphonenumber-js/max').parsePhoneNumberFromString
	return import(sourceUrl(source)).finally(() => {
		delete globalThis.__profileParsePhoneNumber
	})
}

test('derives country, calling code, and national number from E.164 values', async () => {
	const { derivePhonePresentation } = await loadPhonePresentationModule()

	const cn = derivePhonePresentation('+8613800138000')
	assert.equal(cn.countryIso2, 'CN')
	assert.equal(cn.countryName, 'China')
	assert.equal(cn.dialCode, '+86')
	assert.equal(cn.nationalDisplay, '138 0013 8000')

	const gb = derivePhonePresentation('+442079460018')
	assert.equal(gb.countryIso2, 'GB')
	assert.equal(gb.countryName, 'United Kingdom')
	assert.equal(gb.dialCode, '+44')
	assert.equal(gb.nationalDisplay, '020 7946 0018')
})

test('distinguishes shared calling-code countries by parsed country metadata', async () => {
	const { derivePhonePresentation } = await loadPhonePresentationModule()

	const us = derivePhonePresentation('+12025550123')
	const ca = derivePhonePresentation('+14165550123')

	assert.equal(us.countryIso2, 'US')
	assert.equal(us.countryName, 'United States')
	assert.equal(ca.countryIso2, 'CA')
	assert.equal(ca.countryName, 'Canada')
	assert.equal(us.dialCode, '+1')
	assert.equal(ca.dialCode, '+1')
	assert.equal(us.nationalDisplay, '(202) 555-0123')
	assert.equal(ca.nationalDisplay, '(416) 555-0123')
})

test('handles missing, invalid, and non-unique numbers without crashing', async () => {
	const { derivePhonePresentation } = await loadPhonePresentationModule()

	assert.equal(derivePhonePresentation(null).bound, false)
	assert.equal(derivePhonePresentation(null).displayNumber, '未绑定')

	const invalid = derivePhonePresentation('not-a-phone')
	assert.equal(invalid.bound, true)
	assert.equal(invalid.valid, false)
	assert.equal(invalid.displayNumber, 'not-a-phone')
	assert.equal(invalid.nationalDisplay, 'not-a-phone')
	assert.equal(invalid.countryName, '未知国家或地区')

	const shared = derivePhonePresentation('+1')
	assert.equal(shared.countryResolved, false)
	assert.equal(shared.countryName, '未知国家或地区')
})

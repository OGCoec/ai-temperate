const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadCountrySearchModule() {
	const authDirectory = path.resolve(__dirname, '../../common/auth')
	const countriesSource = fs.readFileSync(path.join(authDirectory, 'phone-countries.js'), 'utf8')
	const countriesUrl = sourceUrl(countriesSource)
	const searchSource = fs.readFileSync(path.join(authDirectory, 'phone-country-search.js'), 'utf8')
		.replace("from './phone-countries.js'", `from '${countriesUrl}'`)
	return import(sourceUrl(searchSource))
}

test('sorts the default country list by English name', async () => {
	const { normalizeAndSortCountries } = await loadCountrySearchModule()
	const countries = normalizeAndSortCountries([
		{ id: 'zw-263', name: 'Zimbabwe', iso2: 'zw', dialCode: '+263' },
		{ id: 'al-355', name: 'Albania', iso2: 'al', dialCode: '+355' }
	])

	assert.deepEqual(countries.map(country => country.name), ['Albania', 'Zimbabwe'])
})

test('prioritizes exact ISO2 and exact dial-code matches', async () => {
	const { filterPhoneCountries } = await loadCountrySearchModule()

	const isoMatches = filterPhoneCountries('US')
	assert.equal(isoMatches[0].id, 'us-1')
	const nameMatches = filterPhoneCountries('United')
	assert.ok(nameMatches.length > 0)
	assert.ok(nameMatches.every(country => country.name.includes('United')))

	const dialMatches = filterPhoneCountries('+1')
	assert.equal(dialMatches[0].id, 'us-1')
	assert.equal(dialMatches[0].dialCode, '+1')
	assert.ok(dialMatches.findIndex(country => country.dialCode === '+1268') > 0)

	const ukDialMatches = filterPhoneCountries('+44')
	assert.equal(ukDialMatches[0].id, 'gb-44')
})

test('resolves shared dial-code countries by stable country id', async () => {
	const { findPhoneCountryById, getPhoneCountryById, getPhoneCountryByIso2 } = await loadCountrySearchModule()

	assert.equal(getPhoneCountryById('ca-1').iso2, 'ca')
	assert.equal(getPhoneCountryById('us-1').iso2, 'us')
	assert.equal(getPhoneCountryById('gb-44').iso2, 'gb')
	assert.equal(findPhoneCountryById('missing-country'), null)
	assert.equal(getPhoneCountryByIso2('US').id, 'us-1')
	assert.equal(getPhoneCountryByIso2('ca').id, 'ca-1')
	assert.equal(getPhoneCountryByIso2('GB').id, 'gb-44')
	assert.equal(getPhoneCountryByIso2('unknown'), null)
})

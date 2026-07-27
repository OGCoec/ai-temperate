import PHONE_COUNTRIES, { DEFAULT_PHONE_COUNTRY_ID } from './phone-countries.js'

const SHARED_DIAL_CODE_PREFERRED_ISO2 = {
	'+1': 'us',
	'+44': 'gb'
}

export function normalizeDialCode(rawDialCode) {
	if (typeof rawDialCode !== 'string') return ''
	const compact = rawDialCode.replace(/[^\d+]/g, '').trim()
	if (!compact) return ''
	return compact.startsWith('+') ? compact : `+${compact}`
}

export function compareCountriesByDialCode(firstCountry, secondCountry) {
	const dialOrder = firstCountry.dialCode.localeCompare(secondCountry.dialCode, 'en')
	if (dialOrder !== 0) return dialOrder

	const preferredIso2 = SHARED_DIAL_CODE_PREFERRED_ISO2[firstCountry.dialCode]
	if (preferredIso2) {
		if (firstCountry.iso2 === preferredIso2 && secondCountry.iso2 !== preferredIso2) return -1
		if (secondCountry.iso2 === preferredIso2 && firstCountry.iso2 !== preferredIso2) return 1
	}

	const nameOrder = firstCountry.name.localeCompare(secondCountry.name, 'en')
	if (nameOrder !== 0) return nameOrder
	return firstCountry.iso2.localeCompare(secondCountry.iso2, 'en')
}

export function compareCountriesByName(firstCountry, secondCountry) {
	const nameOrder = firstCountry.name.localeCompare(secondCountry.name, 'en', { sensitivity: 'base' })
	if (nameOrder !== 0) return nameOrder
	const isoOrder = firstCountry.iso2.localeCompare(secondCountry.iso2, 'en')
	if (isoOrder !== 0) return isoOrder
	return firstCountry.dialCode.localeCompare(secondCountry.dialCode, 'en')
}

function getDialCodeDigits(country) {
	return (country?.dialCode || '').replace(/\D/g, '')
}

function compareDigitStringsByPriority(firstDigits, secondDigits) {
	const maxLength = Math.max(firstDigits.length, secondDigits.length)
	for (let index = 0; index < maxLength; index += 1) {
		const firstDigit = firstDigits.charCodeAt(index) || 0
		const secondDigit = secondDigits.charCodeAt(index) || 0
		if (firstDigit !== secondDigit) return firstDigit - secondDigit
	}
	return 0
}

export function compareCountriesByDialSearch(firstCountry, secondCountry, queryDigits) {
	const firstDigits = getDialCodeDigits(firstCountry)
	const secondDigits = getDialCodeDigits(secondCountry)
	const firstExact = firstDigits === queryDigits
	const secondExact = secondDigits === queryDigits
	if (firstExact !== secondExact) return firstExact ? -1 : 1

	const firstStartsWith = firstDigits.startsWith(queryDigits)
	const secondStartsWith = secondDigits.startsWith(queryDigits)
	if (firstStartsWith !== secondStartsWith) return firstStartsWith ? -1 : 1

	const lengthOrder = firstDigits.length - secondDigits.length
	if (lengthOrder !== 0) return lengthOrder

	const digitOrder = compareDigitStringsByPriority(firstDigits, secondDigits)
	if (digitOrder !== 0) return digitOrder
	return compareCountriesByDialCode(firstCountry, secondCountry)
}

export function normalizeAndSortCountries(countries = PHONE_COUNTRIES) {
	const dedupedCountries = new Map()
	;(Array.isArray(countries) ? countries : []).forEach((country) => {
		const iso2 = String(country?.iso2 || '').trim().toLowerCase()
		const name = String(country?.name || '').trim()
		const dialCode = normalizeDialCode(String(country?.dialCode || ''))
		if (!/^[a-z]{2}$/.test(iso2) || !name || !dialCode) return

		const key = `${iso2}|${dialCode}`
		if (dedupedCountries.has(key)) return
		dedupedCountries.set(key, {
			...country,
			iso2,
			name,
			dialCode
		})
	})

	return Array.from(dedupedCountries.values()).sort(compareCountriesByName)
}

export const SORTED_PHONE_COUNTRIES = normalizeAndSortCountries()

export function filterPhoneCountries(keyword, countries = SORTED_PHONE_COUNTRIES) {
	const query = String(keyword || '').trim().toLowerCase()
	if (!query) return [...countries]

	const queryDigits = query.replace(/\D/g, '')
	const filteredCountries = countries.filter((country) => {
		if (queryDigits && getDialCodeDigits(country).includes(queryDigits)) return true
		return country.name.toLowerCase().includes(query) ||
			country.iso2.toLowerCase().includes(query) ||
			country.dialCode.toLowerCase().includes(query)
	})

	if (queryDigits) {
		filteredCountries.sort((firstCountry, secondCountry) =>
			compareCountriesByDialSearch(firstCountry, secondCountry, queryDigits)
		)
	} else {
		filteredCountries.sort((firstCountry, secondCountry) => {
			const firstIsoExact = firstCountry.iso2.toLowerCase() === query
			const secondIsoExact = secondCountry.iso2.toLowerCase() === query
			if (firstIsoExact !== secondIsoExact) return firstIsoExact ? -1 : 1

			const firstNameStarts = firstCountry.name.toLowerCase().startsWith(query)
			const secondNameStarts = secondCountry.name.toLowerCase().startsWith(query)
			if (firstNameStarts !== secondNameStarts) return firstNameStarts ? -1 : 1

			return compareCountriesByName(firstCountry, secondCountry)
		})
	}

	return filteredCountries
}

export function getPhoneCountryById(countryId, countries = SORTED_PHONE_COUNTRIES) {
	return findPhoneCountryById(countryId, countries) ||
		countries.find((country) => country.id === DEFAULT_PHONE_COUNTRY_ID) ||
		countries[0] || null
}

export function findPhoneCountryById(countryId, countries = SORTED_PHONE_COUNTRIES) {
	const normalizedId = String(countryId || '').trim()
	if (!normalizedId) return null
	return countries.find((country) => country.id === normalizedId) || null
}

export function getPhoneCountryByIso2(iso2, countries = SORTED_PHONE_COUNTRIES) {
	const normalizedIso2 = String(iso2 || '').trim().toLowerCase()
	if (!/^[a-z]{2}$/.test(normalizedIso2)) return null
	return countries.find((country) => country.iso2 === normalizedIso2) || null
}

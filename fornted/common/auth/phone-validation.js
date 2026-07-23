import { AsYouType, getCountryCallingCode, parsePhoneNumberFromString } from 'libphonenumber-js/max'

function normalizeCountryIso2(countryIso2) {
	if (typeof countryIso2 !== 'string') return ''
	const country = countryIso2.trim().toUpperCase()
	return /^[A-Z]{2}$/.test(country) ? country : ''
}

export function digitsOnlyPhoneInput(phoneNumber) {
	const value = typeof phoneNumber === 'string' ? phoneNumber : ''
	return value.replace(/[^0-9]/g, '')
}

function countryCallingCode(country) {
	try {
		return getCountryCallingCode(country)
	} catch (error) {
		return ''
	}
}

function parseInternationalDigits(digits, country) {
	if (!digits) return null
	try {
		const parsed = parsePhoneNumberFromString(`+${digits}`, { extract: false })
		return parsed?.country === country && parsed.isValid() ? parsed : null
	} catch (error) {
		return null
	}
}

function stripMatchingCallingCode(digits, country) {
	const callingCode = countryCallingCode(country)
	if (!callingCode || !digits.startsWith(callingCode) || digits.length <= callingCode.length) {
		return digits
	}
	const parsed = parseInternationalDigits(digits, country)
	return parsed?.nationalNumber || digits
}

function shouldKeepPlainDigits(digits, country) {
	const callingCode = countryCallingCode(country)
	if (!callingCode || !digits.startsWith(callingCode) || digits.length <= callingCode.length) {
		return false
	}
	return !parseInternationalDigits(digits, country)
}

function formatNanpLocalDigits(digits) {
	if (digits.length <= 2) return digits
	const area = digits.slice(0, 3)
	if (digits.length === 3) return `(${area})`

	const prefix = digits.slice(3, 6)
	if (digits.length <= 6) return `(${area}) ${prefix}`

	const line = digits.slice(6, 10)
	const overflow = digits.slice(10)
	return `(${area}) ${prefix}-${line}${overflow}`
}

function normalizeGeneratedFormatDeletion(digits, previousDisplay, currentDisplay) {
	const previousValue = typeof previousDisplay === 'string' ? previousDisplay : ''
	const currentValue = typeof currentDisplay === 'string' ? currentDisplay : ''
	if (!previousValue || currentValue.length >= previousValue.length) return digits

	const previousDigits = digitsOnlyPhoneInput(previousValue)
	if (!previousDigits || digits !== previousDigits) return digits
	return previousDigits.slice(0, -1)
}

export function formatLocalPhoneNumberInput(phoneNumber, countryIso2) {
	const country = normalizeCountryIso2(countryIso2)
	const digits = digitsOnlyPhoneInput(phoneNumber)
	const value = country ? stripMatchingCallingCode(digits, country) : digits
	if (!value || !country) return value
	if (countryCallingCode(country) === '1' && value.length <= 15) {
		return formatNanpLocalDigits(value)
	}
	if (shouldKeepPlainDigits(value, country)) return value

	try {
		return new AsYouType(country).input(value)
	} catch (error) {
		return value
	}
}

export function normalizePhoneInputForCountry(phoneNumber, countryIso2, previousDisplay = '') {
	const country = normalizeCountryIso2(countryIso2)
	const digits = normalizeGeneratedFormatDeletion(
		digitsOnlyPhoneInput(phoneNumber),
		previousDisplay,
		typeof phoneNumber === 'string' ? phoneNumber : ''
	)
	if (!digits || !country) return { digits, display: digits }
	const localDigits = stripMatchingCallingCode(digits, country)
	return {
		digits: localDigits,
		display: formatLocalPhoneNumberInput(localDigits, country)
	}
}

export function isValidLocalPhoneNumber(phoneNumber, countryIso2) {
	if (typeof phoneNumber !== 'string' || typeof countryIso2 !== 'string') return false
	const value = phoneNumber.trim()
	const country = normalizeCountryIso2(countryIso2)
	if (!value || !country || /[^0-9]/.test(value)) return false
	if (stripMatchingCallingCode(value, country) !== value) return false

	try {
		const parsed = parsePhoneNumberFromString(value, {
			defaultCountry: country,
			extract: false
		})
		return Boolean(parsed && parsed.country === country && parsed.isValid())
	} catch (error) {
		return false
	}
}

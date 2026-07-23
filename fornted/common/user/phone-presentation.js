import { parsePhoneNumberFromString } from 'libphonenumber-js/max'
import { getPhoneCountryByIso2 } from '../auth/phone-country-search.js'

const UNKNOWN_COUNTRY = '未知国家或地区'

function emptyPresentation() {
	return {
		bound: false,
		valid: false,
		e164: '',
		displayNumber: '未绑定',
		nationalNumber: '',
		nationalDisplay: '',
		dialCode: '',
		countryIso2: '',
		countryId: '',
		countryName: UNKNOWN_COUNTRY,
		flag: '',
		countryResolved: false
	}
}

export function derivePhonePresentation(e164) {
	const raw = String(e164 || '').trim()
	if (!raw) return emptyPresentation()
	try {
		const parsed = parsePhoneNumberFromString(raw)
		if (!parsed || !parsed.isValid()) {
			return {
				...emptyPresentation(),
				bound: true,
				displayNumber: raw,
				nationalDisplay: raw
			}
		}
		const country = parsed.country ? getPhoneCountryByIso2(parsed.country) : null
		return {
			bound: true,
			valid: true,
			e164: parsed.number || raw,
			displayNumber: parsed.number || raw,
			nationalNumber: parsed.nationalNumber || '',
			nationalDisplay: parsed.formatNational(),
			dialCode: parsed.countryCallingCode ? `+${parsed.countryCallingCode}` : '',
			countryIso2: country ? country.iso2.toUpperCase() : '',
			countryId: country?.id || '',
			countryName: country?.name || UNKNOWN_COUNTRY,
			flag: country?.flag || '',
			countryResolved: Boolean(country)
		}
	} catch (error) {
		return {
			...emptyPresentation(),
			bound: true,
			displayNumber: raw,
			nationalDisplay: raw
		}
	}
}

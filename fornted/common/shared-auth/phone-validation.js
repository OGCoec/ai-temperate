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

/**
 * 保留首位 `+` 并清除其余非数字字符。
 * 返回 `+` 加上纯数字部分，例如 `+44 7568` → `+447568`。
 * 如果首字符不是 `+` 或输入为空，返回空字符串。
 */
function sanitizeInternationalRaw(rawValue) {
	const value = typeof rawValue === 'string' ? rawValue : ''
	if (!value.startsWith('+')) return ''
	const digits = value.slice(1).replace(/[^0-9]/g, '')
	return `+${digits}`
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

/**
 * 只在当前选择属于北美编号计划时识别完整的无 `+` 号码。
 * 十位输入按 `+1` 本地号码解析，十一位输入必须明确以国家码 `1` 开头；
 * 未完成、无效或无法确定具体国家的号码一律返回空，避免输入过程中提前锁定国家。
 */
function detectCompleteNanpPhone(digits, selectedCountryIso2) {
	if (countryCallingCode(selectedCountryIso2) !== '1') return null

	let e164 = ''
	if (digits.length === 10) {
		e164 = `+1${digits}`
	} else if (digits.length === 11 && digits.startsWith('1')) {
		e164 = `+${digits}`
	} else {
		return null
	}

	try {
		const parsed = parsePhoneNumberFromString(e164, { extract: false })
		if (!parsed || !parsed.isValid() || !parsed.country) return null
		return countryCallingCode(parsed.country) === '1' ? parsed : null
	} catch (error) {
		return null
	}
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
	if (!digits || !country) return { digits, display: digits, detectedCountryIso2: '' }

	// 必须先识别完整 NANP 号码，再按当前国家剥离区号；否则美国状态下的加拿大号码无法去掉国家码。
	const detectedNanpPhone = detectCompleteNanpPhone(digits, country)
	if (detectedNanpPhone) {
		const detectedCountryIso2 = detectedNanpPhone.country.toUpperCase()
		const localDigits = detectedNanpPhone.nationalNumber || digits
		return {
			digits: localDigits,
			display: formatLocalPhoneNumberInput(localDigits, detectedCountryIso2),
			detectedCountryIso2
		}
	}

	const localDigits = stripMatchingCallingCode(digits, country)
	return {
		digits: localDigits,
		display: formatLocalPhoneNumberInput(localDigits, country),
		detectedCountryIso2: ''
	}
}

/**
 * 识别以 `+` 开头的国际手机号输入，并区分草稿态与已确认态。
 *
 * `+`、国家区号以及未输完或无效的号码都只作为草稿原样展示，不推断国家；
 * 只有完整号码同时通过严格解析、有效性校验并得到明确国家时，才返回正式本地号码。
 * 这条规则也适用于共享区号：`+1` 不预设美国，`+7` 不预设俄罗斯。
 *
 * 关键不变量：归属国家完全由完整有效号码的 libphonenumber-js 解析结果决定，
 * 不使用当前已选国家、IP 推断国家或国家列表的默认排序偏好。
 */
export function normalizeInternationalPhoneInput(rawValue) {
	const sanitized = sanitizeInternationalRaw(rawValue)
	if (!sanitized) return null
	const pendingResult = {
		pendingInternational: true,
		sanitized,
		localDigits: '',
		display: '',
		detectedCountryIso2: ''
	}

	try {
		// 仅完整有效号码可以提交国家和本地号码；任何中间输入都不得提前锁定国家。
		const parsed = parsePhoneNumberFromString(sanitized, { extract: false })
		if (parsed && parsed.isValid() && parsed.country) {
			const detectedCountryIso2 = parsed.country.toUpperCase()
			const localDigits = parsed.nationalNumber || ''
			const display = formatLocalPhoneNumberInput(localDigits, detectedCountryIso2)
			return { pendingInternational: false, sanitized, localDigits, display, detectedCountryIso2 }
		}
	} catch (error) {
		return pendingResult
	}

	return pendingResult
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

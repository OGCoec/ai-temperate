import isEmail from 'validator/es/lib/isEmail'

const EMAIL_VALIDATION_OPTIONS = {
	allow_display_name: false,
	require_display_name: false,
	allow_utf8_local_part: false,
	require_tld: true,
	allow_ip_domain: false,
	allow_underscores: false,
	domain_specific_validation: false,
	ignore_max_length: false,
	blacklisted_chars: '',
	host_blacklist: [],
	host_whitelist: []
}

function emailValidationOptions() {
	return { ...EMAIL_VALIDATION_OPTIONS }
}

export function isValidEmailAddress(value) {
	if (typeof value !== 'string') return false
	const normalized = value.trim()
	if (!normalized || !isEmail(normalized, emailValidationOptions())) return false
	const topLevelDomain = normalized.slice(normalized.lastIndexOf('.') + 1)
	return topLevelDomain.length >= 2
}

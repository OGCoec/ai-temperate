export const PASSWORD_MIN_LENGTH = 8
export const PASSWORD_MAX_LENGTH = 16
export const PASSWORD_SPECIAL_CHARACTERS = '~!@#$%^&*_-+=`|\\(){}[]:;"\' <>,.?/'.replace(' ', '')

function isAsciiLetter(character) {
	return /^[A-Za-z]$/.test(character)
}

export function classifyPassword(password) {
	const value = typeof password === 'string' ? password : ''
	let categories = 0
	let hasLetter = false
	let hasDigit = false
	let hasSpecial = false
	let allowed = true
	for (const character of value) {
		if (isAsciiLetter(character)) hasLetter = true
		else if (/^[0-9]$/.test(character)) hasDigit = true
		else if (PASSWORD_SPECIAL_CHARACTERS.includes(character)) hasSpecial = true
		else allowed = false
	}
	categories = Number(hasLetter) + Number(hasDigit) + Number(hasSpecial)
	const lengthValid = value.length >= PASSWORD_MIN_LENGTH && value.length <= PASSWORD_MAX_LENGTH
	const valid = allowed && lengthValid && categories >= 2
	let level = '弱'
	if (valid && categories === 2) level = '中'
	if (valid && categories === 3 && value.length >= 12) level = '强'
	return { valid, allowed, lengthValid, categories, level }
}

export function passwordError(password, confirmation) {
	const result = classifyPassword(password)
	if (!result.lengthValid) return '密码长度必须为 8–16 个字符。'
	if (!result.allowed) return '密码只能包含 ASCII 字母、数字和允许的特殊字符。'
	if (result.categories < 2) return '请至少组合字母、数字、特殊字符中的两类。'
	if (password !== confirmation) return '两次输入的密码不一致。'
	return ''
}

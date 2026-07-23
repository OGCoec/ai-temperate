export const PASSWORD_POLICY_NAME = 'SHOPPING_V1'
export const PASSWORD_POLICY_VERSION = 1
export const PASSWORD_MAX_UTF8_BYTES = 72
export const PASSWORD_MINIMUM_SCORE = 2
export const PASSWORD_SPECIAL_CHARACTERS = '!@#$%^&*(),.?":{}|<>'

const LEVELS = Object.freeze({
	NONE: Object.freeze({ level: 'NONE', score: 0, label: '无', className: 'none' }),
	WEAK: Object.freeze({ level: 'WEAK', score: 1, label: '弱', className: 'weak' }),
	MEDIUM: Object.freeze({ level: 'MEDIUM', score: 2, label: '中', className: 'medium' }),
	STRONG: Object.freeze({ level: 'STRONG', score: 3, label: '强', className: 'strong' }),
	VERY_STRONG: Object.freeze({ level: 'VERY_STRONG', score: 4, label: '很强', className: 'very-strong' })
})

export function utf8ByteLength(value) {
	const text = typeof value === 'string' ? value : ''
	if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(text).length
	let bytes = 0
	for (const character of text) {
		const codePoint = character.codePointAt(0)
		if (codePoint <= 0x7f) bytes += 1
		else if (codePoint <= 0x7ff) bytes += 2
		else if (codePoint <= 0xffff) bytes += 3
		else bytes += 4
	}
	return bytes
}

function categoryCount(value) {
	return Number(/[a-z]/.test(value))
		+ Number(/[A-Z]/.test(value))
		+ Number(/[0-9]/.test(value))
		+ Number(/[!@#$%^&*(),.?":{}|<>]/.test(value))
}

function levelFor(value) {
	if (!value || value.length <= 6) return LEVELS.NONE
	if (/^[0-9]+$/.test(value) || /^[a-z]+$/.test(value) || /^[A-Z]+$/.test(value)) {
		return LEVELS.WEAK
	}
	const categories = categoryCount(value)
	if (value.length >= 9 && categories === 4) return LEVELS.VERY_STRONG
	if (value.length >= 9 && categories === 3) return LEVELS.STRONG
	// SHOPPING_V1 刻意保留其他非单类密码回落为“中”的原始行为。
	return LEVELS.MEDIUM
}

export function classifyPassword(password) {
	const value = typeof password === 'string' ? password : ''
	const classification = levelFor(value)
	const utf8Bytes = utf8ByteLength(value)
	return {
		...classification,
		categories: categoryCount(value),
		utf8Bytes,
		acceptable: classification.score >= PASSWORD_MINIMUM_SCORE
			&& utf8Bytes <= PASSWORD_MAX_UTF8_BYTES,
		policyName: PASSWORD_POLICY_NAME,
		policyVersion: PASSWORD_POLICY_VERSION
	}
}

export function passwordError(password, confirmation) {
	const result = classifyPassword(password)
	if (result.score < PASSWORD_MINIMUM_SCORE) return '密码强度至少必须达到中等。'
	if (result.utf8Bytes > PASSWORD_MAX_UTF8_BYTES) return '密码不得超过 72 个 UTF-8 字节。'
	if (password !== confirmation) return '两次输入的密码不一致。'
	return ''
}

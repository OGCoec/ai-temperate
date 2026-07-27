const HCAPTCHA_CODES = new Set([
	'network-error',
	'challenge-error',
	'internal-error',
	'invalid-data',
	'rate-limited',
	'script-error'
])
const RETRYABLE_HCAPTCHA_CODES = new Set([
	'network-error',
	'challenge-error',
	'internal-error',
	'script-error'
])

export function normalizeHcaptchaErrorCode(value) {
	const code = String(value ?? '')
	return HCAPTCHA_CODES.has(code) ? code : 'unknown'
}

export function hcaptchaErrorPolicy(value) {
	const code = normalizeHcaptchaErrorCode(value)
	return {
		code,
		retryable: RETRYABLE_HCAPTCHA_CODES.has(code),
		message: `管理员安全验证失败（代码：${code}）。`
	}
}

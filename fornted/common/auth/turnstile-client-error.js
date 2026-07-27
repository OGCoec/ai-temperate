const RETRYABLE_TURNSTILE_CODES = new Set(['110600', '110620', '200500'])

export function normalizeTurnstileErrorCode(value) {
	const code = String(value ?? '')
	return /^\d{6}$/.test(code) ? code : 'unknown'
}

export function turnstileErrorPolicy(value) {
	const code = normalizeTurnstileErrorCode(value)
	return {
		code,
		retryable: RETRYABLE_TURNSTILE_CODES.has(code) || /^(?:300|600)\d{3}$/.test(code),
		message: `安全验证失败（代码：${code}）。`
	}
}

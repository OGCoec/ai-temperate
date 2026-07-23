const DEFAULT_AUTH_ERROR_MESSAGE = '请求未完成，请稍后重试。'

export function authErrorMessage(error, fallback = DEFAULT_AUTH_ERROR_MESSAGE) {
	const message = typeof error?.message === 'string' ? error.message.trim() : ''
	const hasStableCode = typeof error?.code === 'string' && error.code.trim().length > 0
	return hasStableCode && message ? message : fallback
}

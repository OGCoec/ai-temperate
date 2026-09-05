/**
 * 集中裁决会话失败后的处理策略：哪些认证错误必须结束登录会话，哪些还允许一次有界的会话恢复。
 * 请求层与流式恢复入口共用同一份判断，避免多处各自维护错误码列表而产生偏差。
 * 本模块只做纯判断，不执行清理、导航或任何网络请求。
 */

export const SessionRenewalMode = Object.freeze({
	NONE: '',
	BOOTSTRAP: 'BOOTSTRAP'
})

/**
 * 请求用途。终止兜底只适用于受保护业务与会话恢复接口；
 * 登录、注册、验证码等公开认证流程必须保留自己的表单错误处理，不能被兜底踢出。
 */
export const SessionRequestPurpose = Object.freeze({
	PROTECTED: 'PROTECTED',
	SESSION_RECOVERY: 'SESSION_RECOVERY',
	PUBLIC_AUTH: 'PUBLIC_AUTH'
})

export const SESSION_TERMINAL_ERROR_CODES = Object.freeze([
	'AT_REQUIRED',
	'AT_INVALID',
	'REFRESH_TOKEN_REQUIRED',
	'REFRESH_TOKEN_INVALID',
	'SESSION_MISMATCH',
	'DEVICE_MISMATCH',
	'CSRF_INVALID',
	'ACCOUNT_UNAVAILABLE',
	'WEBRTC_VERIFICATION_TIMEOUT',
	'SESSION_RESPONSE_INVALID'
])

const TERMINAL_ERROR_CODE_SET = new Set(SESSION_TERMINAL_ERROR_CODES)

export function sessionRenewalMode(platform, errorCode, alreadyRetried) {
	if (alreadyRetried) return SessionRenewalMode.NONE
	if (platform === 'H5' && errorCode === 'CSRF_INVALID') {
		return SessionRenewalMode.BOOTSTRAP
	}
	return SessionRenewalMode.NONE
}

export function isTerminalSessionErrorCode(errorCode) {
	return TERMINAL_ERROR_CODE_SET.has(errorCode)
}

/**
 * 判断一个失败是否必须结束当前登录会话。
 * 除已知终止错误码外，受保护业务与会话恢复接口上缺少可识别业务码的 401 也按会话失效处理：
 * 这类响应走不到任何恢复分支，继续重试就没有退出出口，旧凭据会一直留在业务页。
 * 边缘挑战可能携带代理产生的 401，必须先按响应分类排除，不能当成源站会话裁决。
 */
export function isTerminalSessionError(error, purpose = SessionRequestPurpose.PROTECTED) {
	if (purpose === SessionRequestPurpose.PUBLIC_AUTH) return false
	if (error?.code === 'EDGE_CHALLENGE'
		|| error?.responseClassification === 'EDGE_CHALLENGE'
		|| String(error?.cfMitigated || '').toLowerCase() === 'challenge') return false
	// 会话恢复流程（bootstrap）或已登录会话的 PreAuth 解绑无法通过匿名凭据修复，必须判定为会话终止。
	if (error?.code === 'PREAUTH_REQUIRED'
		&& (purpose === SessionRequestPurpose.SESSION_RECOVERY
			|| String(error?.message || '').includes('no longer bound'))) {
		return true
	}
	if (isTerminalSessionErrorCode(error?.code)) return true
	return Number(error?.statusCode) === 401
}

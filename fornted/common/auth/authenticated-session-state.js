/**
 * 维护登录会话在当前 JavaScript 运行时内的权威状态：是否已确认登录、页面缓存代次、
 * 会话是否已因终止性认证错误进入退出流程，以及用于识别过期回调的请求代次。
 * 本模块只保存进程内状态，不接触 Cookie、Token 或任何持久化凭据。
 */

let authenticated = false
let version = 0
let terminalSessionTransitionActive = false
let terminalSessionRedirectClaimed = false
let requestGeneration = 0

export function isRuntimeSessionAuthenticated() {
	return authenticated
}

export function runtimeAuthenticationVersion() {
	return version
}

/**
 * 会话是否已经进入终止流程。请求层用它区分“尚未确认登录”与“已确认会话失效”：
 * 后者必须停止旧会话的受保护工作，前者仍允许首次恢复会话。
 */
export function isRuntimeTerminalSessionActive() {
	return terminalSessionTransitionActive
}

/**
 * 当前请求代次。只在明确的新登录成功后推进，页面确认和 AT 续签均复用当前代次。
 */
export function runtimeSessionRequestGeneration() {
	return requestGeneration
}

export function markRuntimeSessionAuthenticated({ newSession = false } = {}) {
	// 页面恢复不是新登录，不能让迟到的页面回调重新打开已经终止的会话。
	if (newSession) {
		requestGeneration += 1
		terminalSessionTransitionActive = false
		terminalSessionRedirectClaimed = false
	} else if (terminalSessionTransitionActive) {
		return version
	}
	if (!authenticated || newSession) {
		authenticated = true
		version += 1
	}
	return version
}

export function beginRuntimeTerminalSessionTransition() {
	if (terminalSessionTransitionActive) return false
	terminalSessionTransitionActive = true
	terminalSessionRedirectClaimed = false
	return true
}

export function claimRuntimeTerminalSessionRedirect() {
	if (!terminalSessionTransitionActive || terminalSessionRedirectClaimed) return false
	terminalSessionRedirectClaimed = true
	return true
}

/**
 * 导航失败时释放本代次的跳转占用，让后续明确触发的导航还能重试。
 * 这里只释放占用、不自动重试，避免导航持续失败时形成无界循环。
 */
export function releaseRuntimeTerminalSessionRedirect(expectedGeneration = requestGeneration) {
	if (expectedGeneration !== requestGeneration || !terminalSessionTransitionActive) return false
	terminalSessionRedirectClaimed = false
	return true
}

export function clearRuntimeSessionAuthentication() {
	// 清理只废弃当前会话，禁止推进请求代次：
	// 否则 bootstrap 清理后抛出的同一个错误传到外层时会被代次门禁挡住，登录页跳转又会丢失。
	if (authenticated) {
		authenticated = false
		version += 1
	}
	return version
}

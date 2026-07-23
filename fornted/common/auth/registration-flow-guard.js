import { clearAndroidRegisterFlow } from './android-flow-keystore.js'
import { AUTH_ROUTES, clientPlatform } from './config.js'
import { isTerminalRegistrationFlowCode } from './registration-flow-policy.js'

const REDIRECT_HANDLED_PROPERTY = 'registrationFlowRedirectHandled'

let redirectInFlight = false

export function clearRegistrationFlowState() {
	if (clientPlatform() !== 'ANDROID') return
	try {
		clearAndroidRegisterFlow()
	} catch (_) {
		// Android 安全存储损坏或不可访问时仍要继续跳转，后续读取会按无效流程处理。
	}
}

export function beginRegistrationFlow() {
	redirectInFlight = false
	// 新流程启动成功后先丢弃 Android 旧材料，再由调用页面保存服务端刚签发的新流程材料。
	clearRegistrationFlowState()
}

function redirectToLogin() {
	if (redirectInFlight) return
	redirectInFlight = true
	try {
		uni.reLaunch({
			url: AUTH_ROUTES.login,
			fail: () => { redirectInFlight = false }
		})
	} catch (error) {
		redirectInFlight = false
		throw error
	}
}

export function handleRegistrationFlowError(error) {
	if (!isTerminalRegistrationFlowCode(error?.code)) return false
	clearRegistrationFlowState()
	if (error && typeof error === 'object') {
		error[REDIRECT_HANDLED_PROPERTY] = true
	}
	redirectToLogin()
	return true
}

export function isRegistrationRedirectHandled(error) {
	return error?.[REDIRECT_HANDLED_PROPERTY] === true
}

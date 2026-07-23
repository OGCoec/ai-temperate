const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const frontendRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

function method(source, start, end) {
	return source.slice(source.indexOf(start), source.indexOf(end))
}

test('registration flow guard does not persist browser flow state', () => {
	const source = read('common/auth/registration-flow-guard.js')

	assert.doesNotMatch(source, /sessionStorage|localStorage|setStorageSync|getStorageSync/)
	assert.doesNotMatch(source, /markRegistrationFlowStarted|hasStartedRegistrationFlow/)
})

test('registration flow guard clears platform state and performs one silent relaunch', () => {
	const source = read('common/auth/registration-flow-guard.js')
	const redirect = method(source, 'function redirectToLogin()', 'export function handleRegistrationFlowError')

	assert.match(source, /clearAndroidRegisterFlow/)
	assert.match(source, /redirectInFlight/)
	assert.match(redirect, /if \(redirectInFlight\) return/)
	assert.match(redirect, /uni\.reLaunch\(\{[\s\S]*url:\s*AUTH_ROUTES\.login/)
	assert.match(redirect, /fail:\s*\(\)\s*=>\s*\{\s*redirectInFlight = false\s*\}/)
	assert.doesNotMatch(redirect, /complete:/)
	assert.doesNotMatch(source, /showToast|showModal/)
})

test('registration api applies the flow guard to every protected registration operation', () => {
	const source = read('common/auth/auth-api.js')

	for (const methodName of [
		'registerStatus',
		'registerTurnstile',
		'registerSend',
		'registerVerify',
		'registerComplete'
	]) {
		const start = source.indexOf(`${methodName}(`)
		const next = source.indexOf('\n\t},', start)
		assert.notEqual(start, -1, methodName)
		assert.match(source.slice(start, next), /registrationFlowRequest/)
	}

	assert.match(source, /registerStart\(data\)[\s\S]*publicRequest\('\/api\/auth\/register\/start'/)
	assert.doesNotMatch(source, /allowFreshStart|markRegistrationFlowStarted/)
	assert.match(source, /registerComplete[\s\S]*clearRegistrationFlowState\(\)/)
})

test('registration page requests status only after the user enters the verification flow', () => {
	const source = read('pages/auth/register.vue')
	const onLoad = method(source, 'onLoad()', 'onShow()')
	const start = method(source, 'async start()', 'async verifyHuman(token)')
	const run = method(source, 'async run(action)', 'async start()')
	const verifyHuman = method(source, 'async verifyHuman(token)', 'async sendCode(channel)')

	assert.doesNotMatch(onLoad, /registerStatus|restoreExistingFlow/)
	assert.doesNotMatch(source, /restoreExistingFlow/)
	assert.ok(start.indexOf('registerStart') < start.indexOf('this.step = 2'))
	assert.match(verifyHuman, /registerStatus\(this\.flow/)
	assert.match(run, /isRegistrationRedirectHandled\(error\)[\s\S]*return null[\s\S]*this\.error/)
	assert.match(verifyHuman, /isRegistrationRedirectHandled\(error\)[\s\S]*return/)
	assert.match(source, /goLogin\(\)[\s\S]*clearRegistrationFlowState\(\)[\s\S]*uni\.reLaunch/)
})

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'registration-flow-policy.js'),
		'utf8'
	)
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(sourceUrl)
}

test('classifies only registration failures that make the flow unusable', async () => {
	const { isTerminalRegistrationFlowCode } = await loadModule()

	for (const code of [
		'REGISTRATION_FLOW_NOT_FOUND',
		'REGISTRATION_FLOW_EXPIRED',
		'REGISTRATION_FLOW_FORBIDDEN',
		'REGISTRATION_PERSISTENCE_FAILED'
	]) {
		assert.equal(isTerminalRegistrationFlowCode(code), true, code)
	}

	for (const code of [
		'HTTP_401',
		'HTTP_403',
		'HTTP_410',
		'TURNSTILE_REJECTED',
		'VERIFICATION_CODE_INVALID',
		'VERIFICATION_SEND_LIMIT',
		'NETWORK_ERROR'
	]) {
		assert.equal(isTerminalRegistrationFlowCode(code), false, code)
	}
})

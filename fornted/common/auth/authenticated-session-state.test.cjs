const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'authenticated-session-state.js'),
		'utf8'
	)
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('runtime authentication state is reused until the session is explicitly cleared', async () => {
	const state = await loadModule()

	assert.equal(state.isRuntimeSessionAuthenticated(), false)
	const authenticatedVersion = state.markRuntimeSessionAuthenticated()
	assert.equal(state.isRuntimeSessionAuthenticated(), true)
	assert.equal(state.runtimeAuthenticationVersion(), authenticatedVersion)

	const clearedVersion = state.clearRuntimeSessionAuthentication()
	assert.equal(state.isRuntimeSessionAuthenticated(), false)
	assert.ok(clearedVersion > authenticatedVersion)
})

test('terminal failures coalesce cleanup and login redirect until authentication succeeds', async () => {
	const state = await loadModule()

	assert.equal(state.beginRuntimeTerminalSessionTransition(), true)
	assert.equal(state.beginRuntimeTerminalSessionTransition(), false)
	assert.equal(state.claimRuntimeTerminalSessionRedirect(), true)
	assert.equal(state.claimRuntimeTerminalSessionRedirect(), false)

	state.markRuntimeSessionAuthenticated()
	assert.equal(state.beginRuntimeTerminalSessionTransition(), true)
	assert.equal(state.claimRuntimeTerminalSessionRedirect(), true)
})

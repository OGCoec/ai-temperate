const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'authenticated-session-state.js'),
		'utf8'
	)
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}#${Math.random()}`)
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

	state.markRuntimeSessionAuthenticated({ newSession: true })
	assert.equal(state.beginRuntimeTerminalSessionTransition(), true)
	assert.equal(state.claimRuntimeTerminalSessionRedirect(), true)
})

test('only explicit login advances the request generation and reopens a terminated session', async () => {
	const state = await loadModule()
	const generation = state.runtimeSessionRequestGeneration()
	state.markRuntimeSessionAuthenticated()
	state.beginRuntimeTerminalSessionTransition()
	state.clearRuntimeSessionAuthentication()
	state.markRuntimeSessionAuthenticated()
	assert.equal(state.runtimeSessionRequestGeneration(), generation)
	assert.equal(state.isRuntimeTerminalSessionActive(), true)
	assert.equal(state.isRuntimeSessionAuthenticated(), false)
	state.markRuntimeSessionAuthenticated({ newSession: true })
	assert.equal(state.runtimeSessionRequestGeneration(), generation + 1)
	assert.equal(state.isRuntimeTerminalSessionActive(), false)
})

test('an old navigation failure cannot release the next session redirect claim', async () => {
	const state = await loadModule()
	const oldGeneration = state.runtimeSessionRequestGeneration()
	state.beginRuntimeTerminalSessionTransition()
	state.claimRuntimeTerminalSessionRedirect()
	state.markRuntimeSessionAuthenticated({ newSession: true })
	state.beginRuntimeTerminalSessionTransition()
	state.claimRuntimeTerminalSessionRedirect()
	assert.equal(state.releaseRuntimeTerminalSessionRedirect(oldGeneration), false)
	assert.equal(state.claimRuntimeTerminalSessionRedirect(), false)
	assert.equal(state.releaseRuntimeTerminalSessionRedirect(state.runtimeSessionRequestGeneration()), true)
	assert.equal(state.claimRuntimeTerminalSessionRedirect(), true)
})

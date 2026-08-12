const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

test('instantiates the App Oniguruma WASM synchronously and reuses the compiled module', async () => {
	const { createSynchronousAppWasmInstantiator } = await loadEsmModule(
		path.join(__dirname, 'ai-code-wasm-app.js'))
	const calls = {
		modules: [],
		instances: [],
		asyncInstantiate: 0
	}
	class FakeModule {
		constructor(binary) {
			calls.modules.push(binary)
		}
	}
	class FakeInstance {
		constructor(module, imports) {
			this.exports = { module, imports }
			calls.instances.push(this)
		}
	}
	const runtime = {
		Module: FakeModule,
		Instance: FakeInstance,
		instantiate() {
			calls.asyncInstantiate += 1
			return new Promise(() => {})
		}
	}
	const binary = new Uint8Array([0, 97, 115, 109])
	const events = []
	let now = 100
	const instantiateAppOniguruma = createSynchronousAppWasmInstantiator(
		binary,
		{
			runtimeProvider: () => runtime,
			now: () => now++,
			report: event => events.push(event)
		}
	)

	const firstImports = { env: { first: true } }
	const secondImports = { env: { second: true } }

	const first = instantiateAppOniguruma(firstImports)
	const second = instantiateAppOniguruma(secondImports)

	assert.equal(calls.asyncInstantiate, 0)
	assert.equal(calls.modules.length, 1)
	assert.strictEqual(calls.modules[0], binary)
	assert.equal(calls.instances.length, 2)
	assert.strictEqual(first.module, calls.instances[0].exports.module)
	assert.strictEqual(first.imports, firstImports)
	assert.strictEqual(second.module, calls.instances[1].exports.module)
	assert.strictEqual(second.imports, secondImports)
	assert.deepEqual(events.map(event => ({
		code: event.code,
		stage: event.stage
	})), [
		{ code: 'AI_CODE_STAGE_START', stage: 'WASM_MODULE' },
		{ code: 'AI_CODE_STAGE_READY', stage: 'WASM_MODULE' },
		{ code: 'AI_CODE_STAGE_START', stage: 'WASM_INSTANCE' },
		{ code: 'AI_CODE_STAGE_READY', stage: 'WASM_INSTANCE' },
		{ code: 'AI_CODE_STAGE_START', stage: 'WASM_INSTANCE' },
		{ code: 'AI_CODE_STAGE_READY', stage: 'WASM_INSTANCE' }
	])
})

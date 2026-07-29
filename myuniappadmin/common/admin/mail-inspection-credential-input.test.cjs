const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

function loadComponentOptions() {
	const filePath = path.resolve(__dirname, '..', '..', 'components', 'admin', 'mail-inspection-credential-input.vue')
	const source = fs.readFileSync(filePath, 'utf8')
	const script = source.match(/<script>\s*([\s\S]*?)<\/script>/)?.[1]
	if (!script) throw new Error('credential input component script was not found')

	const executable = script
		.replace(/^import AdminActionButton.*$/m, '')
		.replace(/^import \{ formatCredentialByteCount \}.*$/m, '')
		.replace('export default', 'globalThis.component =')
	const context = {
		AdminActionButton: {},
		formatCredentialByteCount: value => String(value),
		component: null
	}
	vm.runInNewContext(executable, context)
	return context.component
}

function interactionContext(overrides = {}) {
	const emitted = []
	return {
		emitted,
		context: {
			busy: false,
			concurrencyLocked: false,
			$emit: (name, value) => emitted.push([name, value]),
			...overrides
		}
	}
}

test('business concurrency presets emit normalized numeric values', () => {
	const component = loadComponentOptions()

	for (const value of [1, 4, 8, 16, 32, 64]) {
		const { context, emitted } = interactionContext()
		component.methods.selectBusinessConcurrency.call(context, String(value))
		assert.deepEqual(emitted, [['update:business-concurrency', value]])
	}
})

test('business concurrency controls ignore locked, busy, and invalid values', () => {
	const component = loadComponentOptions()
	const cases = [
		{ value: 4, overrides: { busy: true } },
		{ value: 4, overrides: { concurrencyLocked: true } },
		{ value: 0, overrides: {} },
		{ value: 65, overrides: {} },
		{ value: 1.5, overrides: {} }
	]

	for (const item of cases) {
		const { context, emitted } = interactionContext(item.overrides)
		component.methods.selectBusinessConcurrency.call(context, item.value)
		assert.deepEqual(emitted, [])
	}
})

test('numeric input uses the same normalized concurrency path as presets', () => {
	const component = loadComponentOptions()
	const { context, emitted } = interactionContext()
	context.selectBusinessConcurrency = value => component.methods.selectBusinessConcurrency.call(context, value)

	component.methods.handleBusinessConcurrencyInput.call(context, { detail: { value: '32' } })

	assert.deepEqual(emitted, [['update:business-concurrency', 32]])
})

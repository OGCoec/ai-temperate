const assert = require('node:assert/strict')
const { spawnSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { pathToFileURL } = require('node:url')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

const runtimePath = path.join(__dirname, 'ai-code-runtime-app.js')

function appPlusSource(source) {
	return source
		.replace(/\/\/ #ifndef APP-PLUS[\s\S]*?\/\/ #endif/g, '')
		.replace(/\/\/ #ifdef APP-PLUS\s*([\s\S]*?)\/\/ #endif/g, '$1')
}

test('installs the Array methods required by Shiki without replacing native methods', async () => {
	assert.equal(fs.existsSync(runtimePath), true, 'Android Shiki runtime compatibility module is missing')
	const { ensureAppAiCodeRuntimeCompatibility } = await loadEsmModule(runtimePath)
	const prototype = {}

	const installed = ensureAppAiCodeRuntimeCompatibility(prototype)

	assert.deepEqual(installed, { flatMap: true, at: true })
	assert.deepEqual(prototype.flatMap.call([1, , 3], value => [value, value * 2]), [1, 2, 3, 6])
	assert.equal(prototype.at.call(['first', 'last'], -1), 'last')
	assert.equal(prototype.at.call(['first', 'last'], 4), undefined)

	const nativeFlatMap = () => 'native-flat-map'
	const nativeAt = () => 'native-at'
	const nativePrototype = { flatMap: nativeFlatMap, at: nativeAt }
	assert.deepEqual(ensureAppAiCodeRuntimeCompatibility(nativePrototype), {
		flatMap: false,
		at: false
	})
	assert.strictEqual(nativePrototype.flatMap, nativeFlatMap)
	assert.strictEqual(nativePrototype.at, nativeAt)
})

test('restores real Java tokenization when the App runtime starts without flatMap and at', () => {
	assert.equal(fs.existsSync(runtimePath), true, 'Android Shiki runtime compatibility module is missing')
	const script = `
		const [runtime, core, oniguruma, wasmModule, javaModule, themeModule] = await Promise.all([
			import(${JSON.stringify(pathToFileURL(runtimePath).href)}),
			import('shiki/core'),
			import('shiki/engine/oniguruma'),
			import('shiki/wasm'),
			import('@shikijs/langs/java'),
			import('@shikijs/themes/dark-plus')
		])
		Array.prototype.flatMap = undefined
		Array.prototype.at = undefined
		runtime.ensureAppAiCodeRuntimeCompatibility()
		const highlighter = await core.createHighlighterCore({
			langs: [javaModule.default],
			themes: [themeModule.default],
			engine: oniguruma.createOnigurumaEngine(wasmModule.default)
		})
		const result = highlighter.codeToTokens('if (value) { return 42; }', {
			lang: 'java',
			theme: 'dark-plus',
			includeExplanation: 'scopeName'
		})
		const colors = new Set(result.tokens[0].map(token => token.color?.toUpperCase()))
		if (!colors.has('#C586C0') || !colors.has('#B5CEA8')) process.exitCode = 2
	`
	const result = spawnSync(process.execPath, ['--input-type=module'], {
		cwd: path.join(__dirname, '..', '..'),
		input: script,
		encoding: 'utf8'
	})

	assert.equal(result.status, 0, result.stderr || result.stdout)
})

test('initializes the App compatibility layer before creating the Oniguruma engine', () => {
	const source = fs.readFileSync(path.join(__dirname, 'ai-code-highlighter.js'), 'utf8')
	const androidSource = appPlusSource(source)
	const compatibilityCall = androidSource.indexOf('ensureAppAiCodeRuntimeCompatibility()')
	const engineCall = androidSource.indexOf('createOnigurumaEngine(instantiateAppOniguruma)')

	assert.ok(compatibilityCall >= 0, 'App highlighter does not install runtime compatibility')
	assert.ok(engineCall >= 0, 'App highlighter does not create the Oniguruma engine')
	assert.ok(compatibilityCall < engineCall, 'runtime compatibility must be installed before Shiki starts')
})

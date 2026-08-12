const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

test('reports only bounded code, language, and platform metadata', async () => {
	const { reportAiCodeHighlightError } = await loadEsmModule(
		path.join(__dirname, 'ai-code-diagnostics.js'))
	const lines = []
	const entry = reportAiCodeHighlightError({
		code: 'AI_CODE_ENGINE_INIT_TIMEOUT',
		languageId: 'java',
		stage: 'ONIGURUMA_BINDING',
		elapsedMs: 5000,
		codeText: 'private-token',
		url: 'https://secret.example/path'
	}, {
		platform: 'ANDROID',
		sink: line => lines.push(line)
	})

	assert.deepEqual(entry, {
		code: 'AI_CODE_ENGINE_INIT_TIMEOUT',
		languageId: 'java',
		platform: 'ANDROID',
		stage: 'ONIGURUMA_BINDING',
		elapsedMs: 5000
	})
	assert.deepEqual(lines, [
		'[ai-code-highlight] code=AI_CODE_ENGINE_INIT_TIMEOUT languageId=java platform=ANDROID stage=ONIGURUMA_BINDING elapsedMs=5000'
	])
	assert.equal(lines[0].includes('private-token'), false)
	assert.equal(lines[0].includes('secret.example'), false)
})

test('normalizes untrusted diagnostic metadata to bounded fallback values', async () => {
	const { reportAiCodeHighlightError } = await loadEsmModule(
		path.join(__dirname, 'ai-code-diagnostics.js'))
	const lines = []
	const entry = reportAiCodeHighlightError({
		code: '<script>',
		languageId: '../secret',
		stage: '<bad>',
		elapsedMs: Number.POSITIVE_INFINITY
	}, {
		platform: 'unknown-client',
		sink: line => lines.push(line)
	})

	assert.deepEqual(entry, {
		code: 'AI_CODE_HIGHLIGHT_UNAVAILABLE',
		languageId: 'text',
		platform: 'UNKNOWN',
		stage: 'UNKNOWN',
		elapsedMs: 0
	})
	assert.deepEqual(lines, [
		'[ai-code-highlight] code=AI_CODE_HIGHLIGHT_UNAVAILABLE languageId=text platform=UNKNOWN stage=UNKNOWN elapsedMs=0'
	])
})

test('preserves bounded Android WASM initialization error codes', async () => {
	const { reportAiCodeHighlightError } = await loadEsmModule(
		path.join(__dirname, 'ai-code-diagnostics.js'))
	const entries = []
	for (const code of ['AI_CODE_WASM_UNAVAILABLE', 'AI_CODE_ENGINE_INIT_TIMEOUT']) {
		entries.push(reportAiCodeHighlightError({ code, languageId: 'java' }, {
			platform: 'ANDROID',
			sink: () => {}
		}))
	}

	assert.deepEqual(entries.map(entry => entry.code), [
		'AI_CODE_WASM_UNAVAILABLE',
		'AI_CODE_ENGINE_INIT_TIMEOUT'
	])
})

test('preserves only fixed stage event codes and stage names', async () => {
	const { reportAiCodeHighlightError } = await loadEsmModule(
		path.join(__dirname, 'ai-code-diagnostics.js'))
	const lines = []
	reportAiCodeHighlightError({
		code: 'AI_CODE_STAGE_READY',
		languageId: 'java',
		stage: 'LANGUAGE_GRAMMAR',
		elapsedMs: 17.6
	}, {
		platform: 'ANDROID',
		sink: line => lines.push(line)
	})

	assert.deepEqual(lines, [
		'[ai-code-highlight] code=AI_CODE_STAGE_READY languageId=java platform=ANDROID stage=LANGUAGE_GRAMMAR elapsedMs=18'
	])
})

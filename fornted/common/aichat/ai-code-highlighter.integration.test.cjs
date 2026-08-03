const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

test('loads real Shiki grammars and produces Antigravity-colored Java tokens', async () => {
	const { createAiCodeTokenizer, resolveAiCodeLanguage } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlighter.js')
	)
	const language = resolveAiCodeLanguage('java')
	const prepared = await createAiCodeTokenizer(language)
	const result = await prepared.tokenizer.enqueue(
		'// comment\npublic final class Main { String value = "warm"; }'
	)
	const colors = new Set([...result.stable, ...result.unstable].map(token => token.color?.toUpperCase()))

	assert.equal(language.canonicalId, 'java')
	assert.equal(colors.has('#6A9955'), true)
	assert.equal(colors.has('#569CD6'), true)
	assert.equal(colors.has('#4EC9B0'), true)
	assert.equal(colors.has('#CE9178'), true)
})

test('supports representative backend and frontend language aliases', async () => {
	const { resolveAiCodeLanguage } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlighter.js')
	)
	const cases = new Map([
		['c++', 'cpp'], ['cs', 'csharp'], ['go', 'go'], ['php', 'php'],
		['py', 'python'], ['rs', 'rust'], ['js', 'javascript'], ['ts', 'typescript'],
		['vue', 'vue'], ['sql', 'sql'], ['sh', 'shellscript']
	])

	for (const [input, expected] of cases) {
		assert.equal(resolveAiCodeLanguage(input).canonicalId, expected)
	}
})

test('recognizes every language id in the fixed Shiki bundled registry', async () => {
	const { bundledLanguagesInfo } = await import('shiki')
	const { resolveAiCodeLanguage } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlighter.js')
	)

	for (const info of bundledLanguagesInfo) {
		const resolved = resolveAiCodeLanguage(info.id)
		assert.equal(resolved.supported, true, info.id)
		assert.equal(resolved.canonicalId, info.id, info.id)
	}
})

test('loads representative backend and frontend grammars without changing source text', async () => {
	const { createAiCodeTokenizer } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlighter.js')
	)
	const samples = new Map([
		['cpp', 'int main() { return 0; }'],
		['csharp', 'public sealed class Main { }'],
		['go', 'package main\nfunc main() {}'],
		['php', '<?php echo "warm";'],
		['python', 'def main():\n    return 42'],
		['rust', 'fn main() { println!("warm"); }'],
		['javascript', 'const value = () => 42'],
		['typescript', 'const value: number = 42'],
		['vue', '<template><main>{{ value }}</main></template>'],
		['sql', 'SELECT id FROM users WHERE active = true;'],
		['shellscript', 'printf "%s\\n" "warm"']
	])

	for (const [language, code] of samples) {
		const prepared = await createAiCodeTokenizer(language)
		const result = await prepared.tokenizer.enqueue(code)
		const tokens = [...result.stable, ...result.unstable]
		assert.equal(tokens.map(token => token.content).join(''), code)
		assert.equal(tokens.some(token => token.color), true)
	}
})

test('preserves Unicode, emoji, CRLF and every random-size streamed chunk', async () => {
	const { createAiCodeTokenizer } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlighter.js')
	)
	const code = '// 中文 😀\r\npublic class Main {\r\n  String value = "温度";\r\n}'
	const prepared = await createAiCodeTokenizer('java')
	const stable = []
	let unstable = []
	let offset = 0
	const chunkSizes = [1, 2, 5, 3, 8, 1, 13, 4]
	let sizeIndex = 0
	while (offset < code.length) {
		const nextOffset = Math.min(code.length, offset + chunkSizes[sizeIndex % chunkSizes.length])
		const result = await prepared.tokenizer.enqueue(code.slice(offset, nextOffset))
		stable.push(...result.stable)
		unstable = result.unstable
		offset = nextOffset
		sizeIndex += 1
		assert.equal([...stable, ...unstable].map(token => token.content).join(''), code.slice(0, offset))
	}
	stable.push(...prepared.tokenizer.close().stable)
	assert.equal(stable.map(token => token.content).join(''), code)
})

test('falls back from Oniguruma to the JavaScript engine and reports total engine failure', async () => {
	const { prepareAiCodeHighlighterWithFallback } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlighter.js')
	)
	const fallbackHighlighter = {
		getLoadedLanguages: () => [],
		loadLanguage: async () => {}
	}
	const language = { requestedId: 'java', canonicalId: 'java', label: 'Java', supported: true }
	const fallback = await prepareAiCodeHighlighterWithFallback(language, {
		createPrimary: async () => { throw new Error('wasm unavailable') },
		createFallback: async () => fallbackHighlighter
	})

	assert.equal(fallback.engine, 'javascript')
	assert.equal(fallback.highlighter, fallbackHighlighter)
	await assert.rejects(() => prepareAiCodeHighlighterWithFallback(language, {
		createPrimary: async () => { throw new Error('wasm unavailable') },
		createFallback: async () => { throw new Error('javascript unavailable') }
	}))
})

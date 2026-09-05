const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

function appPlusSource(source) {
	return source
		.replace(/\/\/ #ifndef APP-PLUS[\s\S]*?\/\/ #endif/g, '')
		.replace(/\/\/ #ifdef APP-PLUS\s*([\s\S]*?)\/\/ #endif/g, '$1')
}

test('WeChat Mini Program highlighter selects the existing plain-text fallback', async () => {
	const miniProgramHighlighter = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlighter-mp-weixin.js')
	)
	const language = miniProgramHighlighter.resolveAiCodeLanguage({
		id: 'java',
		label: 'Java'
	})

	assert.deepEqual(language, {
		requestedId: 'java',
		canonicalId: 'text',
		label: 'Java',
		supported: false
	})
	assert.deepEqual(await miniProgramHighlighter.prewarmAiCodeHighlighter(), {
		ready: false,
		reason: 'MP_WEIXIN_PLAIN_TEXT'
	})
	await assert.rejects(
		() => miniProgramHighlighter.createAiCodeTokenizer('java'),
		error => error.code === 'AI_CODE_LANGUAGE_UNSUPPORTED'
			&& error.stage === 'MP_WEIXIN_PLAIN_TEXT'
	)
})

test('uses the complete generated Shiki registry for App and the full H5 registry', async () => {
	const highlighterSource = fs.readFileSync(path.join(__dirname, 'ai-code-highlighter-shiki.js'), 'utf8')
	const appRegistrySource = fs.readFileSync(path.join(__dirname, 'ai-code-languages-app.js'), 'utf8')
	const generatedRegistrySource = fs.readFileSync(path.join(__dirname,
		'ai-code-languages-app.generated.js'), 'utf8')
	const packageJson = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'package.json'), 'utf8'))
	const appRegistry = await loadEsmModule(path.join(__dirname, 'ai-code-languages-app.js'))
	const { bundledLanguagesInfo: fullLanguagesInfo } = await import('shiki/langs')

	assert.equal(packageJson.dependencies['@shikijs/langs'], '4.4.1')
	assert.equal(packageJson.scripts['generate:ai-code-languages-app'],
		'node scripts/generate-ai-code-languages-app.mjs')
	assert.equal(packageJson.scripts['verify:ai-code-languages-app'],
		'node scripts/generate-ai-code-languages-app.mjs --check')
	assert.match(highlighterSource, /#ifdef APP-PLUS[\s\S]*from '\.\/ai-code-languages-app\.js'[\s\S]*#endif/)
	assert.match(highlighterSource, /#ifndef APP-PLUS[\s\S]*from 'shiki\/langs'[\s\S]*#endif/)
	assert.match(highlighterSource,
		/#ifdef APP-PLUS[\s\S]*instantiateAppOniguruma[\s\S]*from '\.\/ai-code-wasm-app\.js'[\s\S]*#endif/)
	assert.match(highlighterSource, /#ifndef APP-PLUS[\s\S]*createOnigurumaEngine\(import\('shiki\/wasm'\)\)[\s\S]*#endif/)
	assert.doesNotMatch(appRegistrySource, /import\s*\(/)
	assert.doesNotMatch(generatedRegistrySource, /import\s*\(/)
	assert.equal(appRegistry.bundledLanguagesInfo.length, fullLanguagesInfo.length)
	for (const info of fullLanguagesInfo) {
		const id = info.id
		assert.ok(appRegistry.bundledLanguages[id], id)
		const serializableInfo = JSON.parse(JSON.stringify(info))
		assert.deepEqual(appRegistry.bundledLanguagesInfo.find(language => language.id === id),
			serializableInfo)
	}
	for (const id of ['java', 'json', 'xml']) assert.ok(appRegistry.bundledLanguages[id], id)
})

test('keeps the unsupported JavaScript regex fallback out of the App bundle', () => {
	const source = fs.readFileSync(path.join(__dirname, 'ai-code-highlighter-shiki.js'), 'utf8')
	const androidSource = appPlusSource(source)

	assert.doesNotMatch(androidSource, /shiki\/engine\/javascript/)
	assert.doesNotMatch(androidSource, /createJavaScriptRegexEngine/)
	assert.doesNotMatch(androidSource, /javascriptHighlighter/)
	assert.doesNotMatch(androidSource, /import shikiWasm from 'shiki\/wasm'/)
	assert.match(androidSource, /createOnigurumaEngine\(instantiateAppOniguruma\)/)
})

test('marks every App highlighter initialization boundary with bounded diagnostics', () => {
	const source = fs.readFileSync(path.join(__dirname, 'ai-code-highlighter-shiki.js'), 'utf8')
	const androidSource = appPlusSource(source)

	for (const stage of [
		'ONIGURUMA_BINDING',
		'HIGHLIGHTER_CORE',
		'LANGUAGE_GRAMMAR',
		'TOKENIZER'
	]) {
		assert.match(androidSource, new RegExp(`['"]${stage}['"]`), stage)
	}
	assert.match(androidSource, /AI_CODE_STAGE_START/)
	assert.match(androidSource, /AI_CODE_STAGE_READY/)
})

test('keeps browser TransformStream code out of App while preserving the H5 Shiki stream path', () => {
	const highlighterSource = fs.readFileSync(path.join(__dirname, 'ai-code-highlighter-shiki.js'), 'utf8')
	const appTokenizerSource = fs.readFileSync(path.join(__dirname, 'ai-code-stream-tokenizer-app.js'), 'utf8')
	const androidSource = appPlusSource(highlighterSource)

	assert.match(androidSource, /from '\.\/ai-code-stream-tokenizer-app\.js'/)
	assert.doesNotMatch(androidSource, /@shikijs\/stream/)
	assert.doesNotMatch(appTokenizerSource, /\b(?:TransformStream|ReadableStream|WritableStream)\b/)
	assert.doesNotMatch(appTokenizerSource, /@shikijs\/stream/)
	assert.match(highlighterSource, /#ifndef APP-PLUS[\s\S]*from '@shikijs\/stream'[\s\S]*#endif/)
})

test('App tokenizer preserves stable lines, recalls unfinished tokens, closes, clears and clones independently', async () => {
	const { AppShikiStreamTokenizer } = await loadEsmModule(
		path.join(__dirname, 'ai-code-stream-tokenizer-app.js')
	)
	const grammarStates = []
	const highlighter = {
		codeToTokens(code, options) {
			grammarStates.push(options.grammarState)
			return {
				tokens: [[{ content: code, color: '#FFFFFF', offset: 0 }]],
				grammarState: 'state:' + code
			}
		}
	}
	const tokenizer = new AppShikiStreamTokenizer({ highlighter, lang: 'javascript' })
	const first = await tokenizer.enqueue('const value = 1\nret')
	const clone = tokenizer.clone()
	const second = await tokenizer.enqueue('urn value')

	assert.equal(first.recall, 0)
	assert.equal(first.stable.map(token => token.content).join(''), 'const value = 1\n')
	assert.equal(first.unstable.map(token => token.content).join(''), 'ret')
	assert.equal(second.recall, first.unstable.length)
	assert.equal(second.stable.length, 0)
	assert.equal(second.unstable.map(token => token.content).join(''), 'return value')
	assert.equal(tokenizer.close().stable.map(token => token.content).join(''), 'return value')
	assert.equal(clone.close().stable.map(token => token.content).join(''), 'ret')
	assert.equal(tokenizer.close().stable.length, 0)
	assert.equal(grammarStates[0], undefined)
	assert.equal(grammarStates[1], 'state:const value = 1')

	await tokenizer.enqueue('discarded')
	tokenizer.clear()
	const afterClear = await tokenizer.enqueue('fresh')
	assert.equal(afterClear.recall, 0)
	assert.equal(afterClear.unstable.map(token => token.content).join(''), 'fresh')
})

test('loads real Shiki grammars and produces Antigravity-colored Java tokens', async () => {
	const { createAiCodeTokenizer, resolveAiCodeLanguage } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlighter.js')
	)
	const language = resolveAiCodeLanguage('java')
	const prepared = await createAiCodeTokenizer(language)
	const result = await prepared.tokenizer.enqueue(
		'// comment\npublic final class Main { private int count = 42; String run() { String value = "warm"; return value; } }'
	)
	const colors = new Set([...result.stable, ...result.unstable].map(token => token.color?.toUpperCase()))

	assert.equal(language.canonicalId, 'java')
	assert.equal(colors.has('#6A9955'), true)
	assert.equal(colors.has('#569CD6'), true)
	assert.equal(colors.has('#4EC9B0'), true)
	assert.equal(colors.has('#DCDCAA'), true)
	assert.equal(colors.has('#9CDCFE'), true)
	assert.equal(colors.has('#CE9178'), true)
	assert.equal(colors.has('#B5CEA8'), true)
})

test('supports representative backend and frontend language aliases', async () => {
	const { resolveAiCodeLanguage } = await loadEsmModule(
		path.join(__dirname, 'ai-code-highlighter.js')
	)
	const cases = new Map([
		['c++', 'cpp'], ['cs', 'csharp'], ['go', 'go'], ['php', 'php'],
		['py', 'python'], ['rs', 'rust'], ['js', 'javascript'], ['ts', 'typescript'],
		['vue', 'vue'], ['sql', 'sql'], ['sh', 'shellscript'], ['xml', 'xml']
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
		for (const alias of info.aliases || []) {
			assert.equal(resolveAiCodeLanguage(alias).canonicalId, info.id, alias)
		}
	}
})

test('clears failed shared engine promises so later code blocks can retry', () => {
	const source = fs.readFileSync(path.join(__dirname, 'ai-code-highlighter-shiki.js'), 'utf8')
	assert.match(source, /onigurumaHighlighterPromise = operation\.catch\(error => \{[\s\S]*?onigurumaHighlighterPromise = null/)
	assert.match(source, /javascriptHighlighterPromise = operation\.catch\(error => \{[\s\S]*?javascriptHighlighterPromise = null/)
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

test('produces identical H5 and App token colors for streamed Java, JSON, and XML', async () => {
	const { ShikiStreamTokenizer } = await import('@shikijs/stream')
	const {
		AppShikiStreamTokenizer
	} = await loadEsmModule(path.join(__dirname, 'ai-code-stream-tokenizer-app.js'))
	const {
		prepareAiCodeHighlighterWithFallback,
		resolveAiCodeLanguage
	} = await loadEsmModule(path.join(__dirname, 'ai-code-highlighter.js'))
	const { AI_CODE_THEME_NAME } = await loadEsmModule(
		path.join(__dirname, 'ai-code-theme-antigravity.js'))
	const fixtures = new Map([
		['java', '@Deprecated\npublic final class Main<T> {\n  // comment\n  private int count = 42;\n  String run(T value) { return "warm" + value; }\n}'],
		['json', '{"name":"warm","count":42,"enabled":true}'],
		['xml', '<root enabled="true"><name>warm</name></root>']
	])

	for (const [id, code] of fixtures) {
		const language = resolveAiCodeLanguage(id)
		const prepared = await prepareAiCodeHighlighterWithFallback(language)
		const options = {
			highlighter: prepared.highlighter,
			lang: language.canonicalId,
			theme: AI_CODE_THEME_NAME,
			includeExplanation: 'scopeName'
		}
		const browser = new ShikiStreamTokenizer(options)
		const app = new AppShikiStreamTokenizer(options)
		const browserTokens = []
		const appTokens = []
		for (const chunk of [code.slice(0, 7), code.slice(7, 19), code.slice(19)]) {
			const browserResult = await browser.enqueue(chunk)
			const appResult = await app.enqueue(chunk)
			browserTokens.splice(browserTokens.length - browserResult.recall,
				browserResult.recall, ...browserResult.stable, ...browserResult.unstable)
			appTokens.splice(appTokens.length - appResult.recall,
				appResult.recall, ...appResult.stable, ...appResult.unstable)
		}
		const view = token => ({
			content: token.content,
			color: String(token.color || '').toUpperCase(),
			fontStyle: token.fontStyle || 0
		})
		assert.deepEqual(appTokens.map(view), browserTokens.map(view), id)
	}
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

import darkPlus from '@shikijs/themes/dark-plus'
import { createHighlighterCore } from 'shiki/core'
// #ifndef APP-PLUS
import { createJavaScriptRegexEngine } from 'shiki/engine/javascript'
// #endif
import { createOnigurumaEngine } from 'shiki/engine/oniguruma'
// #ifdef APP-PLUS
import { AppShikiStreamTokenizer } from './ai-code-stream-tokenizer-app.js'
// #endif
// #ifndef APP-PLUS
import { ShikiStreamTokenizer as BrowserShikiStreamTokenizer } from '@shikijs/stream'
// #endif
import { createAiCodeLanguageResolver } from './ai-code-language.js'
import {
	AI_CODE_THEME_NAME,
	createAntigravityCodeTheme
} from './ai-code-theme-antigravity.js'
// #ifdef APP-PLUS
import {
	bundledLanguages as appBundledLanguages,
	bundledLanguagesInfo as appBundledLanguagesInfo
} from './ai-code-languages-app.js'
import shikiWasm from 'shiki/wasm'
// #endif
// #ifndef APP-PLUS
import {
	bundledLanguages as fullBundledLanguages,
	bundledLanguagesInfo as fullBundledLanguagesInfo
} from 'shiki/langs'
// #endif

let bundledLanguages
let bundledLanguagesInfo
let PlatformShikiStreamTokenizer
// #ifdef APP-PLUS
bundledLanguages = appBundledLanguages
bundledLanguagesInfo = appBundledLanguagesInfo
PlatformShikiStreamTokenizer = AppShikiStreamTokenizer
// #endif
// #ifndef APP-PLUS
bundledLanguages = fullBundledLanguages
bundledLanguagesInfo = fullBundledLanguagesInfo
PlatformShikiStreamTokenizer = BrowserShikiStreamTokenizer
// #endif

const DEFAULT_PREWARM_LANGUAGES = Object.freeze([
	'java', 'javascript', 'typescript', 'python', 'cpp', 'go',
	'php', 'json', 'html', 'css', 'vue', 'sql'
])
const resolveLanguage = createAiCodeLanguageResolver(bundledLanguagesInfo)

let themePromise = null
let onigurumaHighlighterPromise = null
// #ifndef APP-PLUS
let javascriptHighlighterPromise = null
// #endif
let prewarmLanguagesStarted = false

async function antigravityTheme() {
	if (!themePromise) {
		themePromise = Promise.resolve(createAntigravityCodeTheme(darkPlus))
	}
	return themePromise
}

async function onigurumaHighlighter() {
	if (!onigurumaHighlighterPromise) {
		let engine
		// #ifdef APP-PLUS
		engine = createOnigurumaEngine(shikiWasm)
		// #endif
		// #ifndef APP-PLUS
		engine = createOnigurumaEngine(import('shiki/wasm'))
		// #endif
		onigurumaHighlighterPromise = antigravityTheme().then(theme => createHighlighterCore({
			themes: [theme],
			langs: [],
			engine
		}))
	}
	return onigurumaHighlighterPromise
}

// #ifndef APP-PLUS
async function javascriptHighlighter() {
	if (!javascriptHighlighterPromise) {
		javascriptHighlighterPromise = antigravityTheme().then(theme => createHighlighterCore({
			themes: [theme],
			langs: [],
			engine: createJavaScriptRegexEngine({ target: 'auto' })
		}))
	}
	return javascriptHighlighterPromise
}
// #endif

async function unavailableFallbackHighlighter() {
	throw new Error('AI_CODE_FALLBACK_UNAVAILABLE')
}

let defaultFallbackHighlighter = unavailableFallbackHighlighter
// #ifndef APP-PLUS
defaultFallbackHighlighter = javascriptHighlighter
// #endif

async function loadLanguage(highlighter, canonicalId) {
	const loaded = new Set(highlighter.getLoadedLanguages?.() || [])
	if (!loaded.has(canonicalId)) {
		const languageRegistration = bundledLanguages[canonicalId]
		if (!languageRegistration) throw new Error('AI_CODE_LANGUAGE_UNSUPPORTED')
		await highlighter.loadLanguage(languageRegistration)
	}
	return highlighter
}

export async function prepareAiCodeHighlighterWithFallback(language, services = {}) {
	const createPrimary = services.createPrimary || onigurumaHighlighter
	const createFallback = services.createFallback || defaultFallbackHighlighter
	try {
		const highlighter = await createPrimary()
		await loadLanguage(highlighter, language.canonicalId)
		return { highlighter, engine: 'oniguruma' }
	} catch {
		const highlighter = await createFallback()
		await loadLanguage(highlighter, language.canonicalId)
		return { highlighter, engine: 'javascript' }
	}
}

function scheduleIdle(callback) {
	if (typeof requestIdleCallback === 'function') {
		requestIdleCallback(callback, { timeout: 1200 })
		return
	}
	setTimeout(callback, 0)
}

function prewarmLanguages(languages, index = 0) {
	if (index >= languages.length) return
	scheduleIdle(() => {
		const resolved = resolveAiCodeLanguage(languages[index])
		const operation = resolved.supported
			? prepareAiCodeHighlighterWithFallback(resolved)
			: Promise.resolve()
		void operation.catch(() => {}).finally(() => prewarmLanguages(languages, index + 1))
	})
}

export function resolveAiCodeLanguage(language) {
	return resolveLanguage(language)
}

export async function createAiCodeTokenizer(language) {
	// 即使调用方传入了解析后的对象，也必须重新经过本地注册表校验，避免模型伪造 grammar 标识或加载路径。
	const resolved = resolveAiCodeLanguage(language)
	if (!resolved.supported || resolved.canonicalId === 'text') {
		throw new Error('AI_CODE_LANGUAGE_UNSUPPORTED')
	}
	const prepared = await prepareAiCodeHighlighterWithFallback(resolved)
	return {
		language: resolved,
		engine: prepared.engine,
		tokenizer: new PlatformShikiStreamTokenizer({
			highlighter: prepared.highlighter,
			lang: resolved.canonicalId,
			theme: AI_CODE_THEME_NAME,
			includeExplanation: 'scopeName'
		})
	}
}

export function prewarmAiCodeHighlighter(options = {}) {
	const languages = Array.isArray(options.languages)
		? options.languages
		: DEFAULT_PREWARM_LANGUAGES
	const corePromise = onigurumaHighlighter().catch(() => defaultFallbackHighlighter())
	void corePromise.then(() => {
		if (prewarmLanguagesStarted) return
		prewarmLanguagesStarted = true
		prewarmLanguages(languages)
	}).catch(() => {})
	return corePromise.then(() => ({ ready: true }))
}

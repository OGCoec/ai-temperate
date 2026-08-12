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
import { reportAiCodeHighlightError } from './ai-code-diagnostics.js'
import {
	AI_CODE_THEME_NAME,
	createAntigravityCodeTheme
} from './ai-code-theme-antigravity.js'
// #ifdef APP-PLUS
import {
	bundledLanguages as appBundledLanguages,
	bundledLanguagesInfo as appBundledLanguagesInfo
} from './ai-code-languages-app.js'
import { ensureAppAiCodeRuntimeCompatibility } from './ai-code-runtime-app.js'
import { instantiateAppOniguruma } from './ai-code-wasm-app.js'
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
	'php', 'json', 'xml', 'html', 'css', 'vue', 'sql'
])
const resolveLanguage = createAiCodeLanguageResolver(bundledLanguagesInfo)

let themePromise = null
let onigurumaHighlighterPromise = null
// #ifndef APP-PLUS
let javascriptHighlighterPromise = null
// #endif
let prewarmLanguagesStarted = false

function reportAppHighlightStage(event) {
	// #ifdef APP-PLUS
	if (typeof plus !== 'undefined') reportAiCodeHighlightError(event)
	// #endif
}

function stageError(error, code, stage, elapsedMs) {
	const failure = error instanceof Error ? error : new Error(code)
	failure.code = code
	failure.stage = stage
	failure.elapsedMs = elapsedMs
	return failure
}

async function antigravityTheme() {
	if (!themePromise) {
		themePromise = Promise.resolve(createAntigravityCodeTheme(darkPlus))
	}
	return themePromise
}

async function createOnigurumaHighlighter() {
	// #ifdef APP-PLUS
	ensureAppAiCodeRuntimeCompatibility()
	// #endif
	let stage = 'ONIGURUMA_BINDING'
	let startedAt = Date.now()
	reportAppHighlightStage({
		code: 'AI_CODE_STAGE_START',
		languageId: 'text',
		stage,
		elapsedMs: 0
	})
	try {
		let enginePromise
		// #ifdef APP-PLUS
		enginePromise = createOnigurumaEngine(instantiateAppOniguruma)
		// #endif
		// #ifndef APP-PLUS
		enginePromise = createOnigurumaEngine(import('shiki/wasm'))
		// #endif
		const engine = await enginePromise
		reportAppHighlightStage({
			code: 'AI_CODE_STAGE_READY',
			languageId: 'text',
			stage,
			elapsedMs: Date.now() - startedAt
		})

		stage = 'HIGHLIGHTER_CORE'
		startedAt = Date.now()
		reportAppHighlightStage({
			code: 'AI_CODE_STAGE_START',
			languageId: 'text',
			stage,
			elapsedMs: 0
		})
		const theme = await antigravityTheme()
		const highlighter = await createHighlighterCore({
			themes: [theme],
			langs: [],
			engine
		})
		reportAppHighlightStage({
			code: 'AI_CODE_STAGE_READY',
			languageId: 'text',
			stage,
			elapsedMs: Date.now() - startedAt
		})
		return highlighter
	} catch (error) {
		const failure = stageError(
			error,
			'AI_CODE_ENGINE_INIT_FAILED',
			stage,
			Date.now() - startedAt
		)
		reportAppHighlightStage(failure)
		throw failure
	}
}

async function onigurumaHighlighter() {
	if (!onigurumaHighlighterPromise) {
		const operation = createOnigurumaHighlighter()
		onigurumaHighlighterPromise = operation.catch(error => {
			onigurumaHighlighterPromise = null
			throw error
		})
	}
	return onigurumaHighlighterPromise
}

// #ifndef APP-PLUS
async function javascriptHighlighter() {
	if (!javascriptHighlighterPromise) {
		const operation = antigravityTheme().then(theme => createHighlighterCore({
			themes: [theme],
			langs: [],
			engine: createJavaScriptRegexEngine({ target: 'auto' })
		}))
		javascriptHighlighterPromise = operation.catch(error => {
			javascriptHighlighterPromise = null
			throw error
		})
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
		const startedAt = Date.now()
		reportAppHighlightStage({
			code: 'AI_CODE_STAGE_START',
			languageId: canonicalId,
			stage: 'LANGUAGE_GRAMMAR',
			elapsedMs: 0
		})
		try {
			await highlighter.loadLanguage(languageRegistration)
		} catch (error) {
			throw stageError(
				error,
				'AI_CODE_LANGUAGE_LOAD_FAILED',
				'LANGUAGE_GRAMMAR',
				Date.now() - startedAt
			)
		}
		reportAppHighlightStage({
			code: 'AI_CODE_STAGE_READY',
			languageId: canonicalId,
			stage: 'LANGUAGE_GRAMMAR',
			elapsedMs: Date.now() - startedAt
		})
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
	} catch (primaryError) {
		try {
			const highlighter = await createFallback()
			await loadLanguage(highlighter, language.canonicalId)
			return { highlighter, engine: 'javascript' }
		} catch (fallbackError) {
			if (fallbackError?.message === 'AI_CODE_FALLBACK_UNAVAILABLE') throw primaryError
			throw fallbackError
		}
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
		void operation.catch(error => reportAiCodeHighlightError({
			code: error?.message === 'AI_CODE_LANGUAGE_UNSUPPORTED'
				? 'AI_CODE_LANGUAGE_UNSUPPORTED' : 'AI_CODE_LANGUAGE_LOAD_FAILED',
			languageId: resolved.canonicalId,
			stage: error?.stage,
			elapsedMs: error?.elapsedMs
		})).finally(() => prewarmLanguages(languages, index + 1))
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
	const tokenizerStartedAt = Date.now()
	reportAppHighlightStage({
		code: 'AI_CODE_STAGE_START',
		languageId: resolved.canonicalId,
		stage: 'TOKENIZER',
		elapsedMs: 0
	})
	let tokenizer
	try {
		tokenizer = new PlatformShikiStreamTokenizer({
			highlighter: prepared.highlighter,
			lang: resolved.canonicalId,
			theme: AI_CODE_THEME_NAME,
			includeExplanation: 'scopeName'
		})
	} catch (error) {
		throw stageError(
			error,
			'AI_CODE_HIGHLIGHT_UNAVAILABLE',
			'TOKENIZER',
			Date.now() - tokenizerStartedAt
		)
	}
	reportAppHighlightStage({
		code: 'AI_CODE_STAGE_READY',
		languageId: resolved.canonicalId,
		stage: 'TOKENIZER',
		elapsedMs: Date.now() - tokenizerStartedAt
	})
	return {
		language: resolved,
		engine: prepared.engine,
		tokenizer
	}
}

export function prewarmAiCodeHighlighter(options = {}) {
	const languages = Array.isArray(options.languages)
		? options.languages
		: DEFAULT_PREWARM_LANGUAGES
	const corePromise = onigurumaHighlighter().catch(() => defaultFallbackHighlighter())
	return corePromise.then(() => {
		if (!prewarmLanguagesStarted) {
			prewarmLanguagesStarted = true
			prewarmLanguages(languages)
		}
		return { ready: true }
	})
}

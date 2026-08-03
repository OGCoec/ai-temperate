import { createAiCodeTokenizer } from './ai-code-highlighter.js'
import { createAiCodeBracketState } from './ai-code-bracket-state.js'
import {
	buildPlainAiCodeLines,
	buildSafeAiCodeLines
} from './ai-code-theme-antigravity.js'

const DEFAULT_LANGUAGE_TIMEOUT_MS = 1000

function defaultScheduleFrame(callback) {
	if (typeof requestAnimationFrame === 'function') return requestAnimationFrame(callback)
	return setTimeout(callback, 0)
}

function initialLanguage(language) {
	const requestedId = String(language?.id || language || '').trim().toLowerCase()
	return {
		requestedId,
		canonicalId: requestedId || 'text',
		label: String(language?.label || requestedId || 'Plain text'),
		supported: Boolean(requestedId && requestedId !== 'plain' && requestedId !== 'text')
	}
}

function withTimeout(promise, timeoutMs, setTimer, clearTimer) {
	let timer = null
	const timeout = new Promise((resolve, reject) => {
		timer = setTimer(() => reject(new Error('AI_CODE_LANGUAGE_TIMEOUT')), timeoutMs)
	})
	return Promise.race([promise, timeout]).finally(() => clearTimer(timer))
}

export function createAiCodeHighlightSessionFactory(services = {}) {
	const createTokenizer = services.createTokenizer || createAiCodeTokenizer
	const languageTimeoutMs = Number(services.languageTimeoutMs) || DEFAULT_LANGUAGE_TIMEOUT_MS
	const setTimer = services.setTimer || setTimeout
	const clearTimer = services.clearTimer || clearTimeout

	return function createSession(options = {}) {
		const onSnapshot = typeof options.onSnapshot === 'function' ? options.onSnapshot : () => {}
		const onError = typeof options.onError === 'function' ? options.onError : () => {}
		const scheduleFrame = options.scheduleFrame || defaultScheduleFrame
		const sourceLanguage = options.language

		let revision = 1
		let language = initialLanguage(sourceLanguage)
		let status = 'loading'
		let closed = false
		let plainLocked = false
		let initialized = false
		let tokenizer = null
		let bracketState = createAiCodeBracketState(language.canonicalId)
		let stableLines = []
		let unstableTokens = []
		let processedCode = ''
		let requestedCode = ''
		let completionRequested = false
		let tokenizerDirty = false
		let processingPromise = null
		let pendingSnapshot = null
		let framePending = false

		function publish(snapshot) {
			pendingSnapshot = snapshot
			if (framePending) return
			framePending = true
			scheduleFrame(() => {
				framePending = false
				if (closed || !pendingSnapshot) return
				const next = pendingSnapshot
				pendingSnapshot = null
				if (next.revision !== revision) return
				onSnapshot(next)
			})
		}

		function createViewSnapshot(currentStableLines, currentUnstableLines, snapshotLanguage = language) {
			const snapshot = {
				revision,
				status,
				language: { ...snapshotLanguage },
				stableLines: currentStableLines,
				unstableLines: currentUnstableLines
			}
			// 保留公开 lines 契约，但仅在测试或兼容调用方读取时合并；生产 Vue 分区消费，不在每个 chunk 展开稳定前缀。
			Object.defineProperty(snapshot, 'lines', {
				enumerable: true,
				get: () => [...currentStableLines, ...currentUnstableLines]
			})
			return snapshot
		}

		function publishTokens() {
			const unstableLines = buildSafeAiCodeLines(unstableTokens).map((line, index) => ({
				...line,
				index: stableLines.length + index
			}))
			publish(createViewSnapshot(stableLines, unstableLines))
		}

		function appendStableLines(tokens, removeTrailingEmptyLine) {
			const nextLines = buildSafeAiCodeLines(tokens)
			if (removeTrailingEmptyLine && nextLines.at(-1)?.tokens.length === 0) nextLines.pop()
			const firstIndex = stableLines.length
			const appendedLines = nextLines.map((line, index) => ({
				...line,
				index: firstIndex + index
			}))
			if (appendedLines.length) stableLines = stableLines.concat(appendedLines)
		}

		function lockPlain(errorCode = 'AI_CODE_HIGHLIGHT_UNAVAILABLE') {
			const firstFailure = !plainLocked
			plainLocked = true
			status = 'plain'
			if (firstFailure) onError({ code: errorCode, languageId: language.canonicalId })
			publish(createViewSnapshot(
				buildPlainAiCodeLines(requestedCode),
				[],
				{ ...language, canonicalId: 'text', supported: false }
			))
		}

		async function initialize() {
			if (initialized || plainLocked) return
			try {
				const prepared = await withTimeout(
					createTokenizer(sourceLanguage),
					languageTimeoutMs,
					setTimer,
					clearTimer
				)
				if (closed || plainLocked) return
				language = prepared.language
				tokenizer = prepared.tokenizer
				bracketState = createAiCodeBracketState(language.canonicalId)
				initialized = true
				status = 'ready'
			} catch (error) {
				lockPlain(error?.message === 'AI_CODE_LANGUAGE_TIMEOUT'
					? 'AI_CODE_LANGUAGE_TIMEOUT'
					: 'AI_CODE_HIGHLIGHT_UNAVAILABLE')
			}
		}

		async function resetTokenizer(incrementRevision = true) {
			if (incrementRevision) revision += 1
			const resetRevision = revision
			const prepared = await createTokenizer(sourceLanguage)
			if (closed || resetRevision !== revision) return false
			language = prepared.language
			tokenizer = prepared.tokenizer
			bracketState = createAiCodeBracketState(language.canonicalId)
			stableLines = []
			unstableTokens = []
			processedCode = ''
			tokenizerDirty = false
			return true
		}

		function replaceRequestedCode(code) {
			const nextCode = String(code || '')
			// 服务端 snapshot 若改写了既有前缀，立即提升 revision，让仍在运行的旧 token 结果无法进入下一帧。
			if (initialized && requestedCode && !nextCode.startsWith(requestedCode)) {
				revision += 1
				tokenizerDirty = true
			}
			requestedCode = nextCode
		}

		async function appendCode(chunk, activeRevision) {
			if (!chunk || !tokenizer) return
			const result = await tokenizer.enqueue(chunk)
			if (closed || activeRevision !== revision) return
			appendStableLines(bracketState.appendStable(result.stable), true)
			unstableTokens = bracketState.replaceUnstable(result.unstable)
			publishTokens()
		}

		async function drain() {
			if (processingPromise) return processingPromise
			processingPromise = (async () => {
				await initialize()
				if (closed || plainLocked) return
				while (!closed && processedCode !== requestedCode) {
					const targetCode = requestedCode
					if (tokenizerDirty && !await resetTokenizer(false)) continue
					if (!tokenizerDirty && !targetCode.startsWith(processedCode) && !await resetTokenizer()) continue
					if (closed) return
					if (!targetCode) {
						processedCode = ''
						publishTokens()
						continue
					}
					const activeRevision = revision
					const chunk = targetCode.slice(processedCode.length)
					await appendCode(chunk, activeRevision)
					if (activeRevision !== revision) continue
					processedCode = targetCode
				}
				if (completionRequested && tokenizer && !closed) {
					const finalTokens = tokenizer.close().stable
					appendStableLines(bracketState.closeUnstable(finalTokens), false)
					unstableTokens = []
					completionRequested = false
					publishTokens()
				}
			})().catch(() => lockPlain()).finally(() => {
				processingPromise = null
			})
			return processingPromise
		}

		publish(createViewSnapshot([], []))

		return {
			update({ code } = {}) {
				if (closed) return Promise.resolve()
				replaceRequestedCode(code)
				if (plainLocked) {
					lockPlain('AI_CODE_PLAIN_LOCKED')
					return Promise.resolve()
				}
				return drain()
			},
			complete({ finalCode } = {}) {
				if (closed) return Promise.resolve()
				replaceRequestedCode(finalCode)
				completionRequested = true
				if (plainLocked) {
					lockPlain('AI_CODE_PLAIN_LOCKED')
					return Promise.resolve()
				}
				return drain()
			},
			close() {
				closed = true
				pendingSnapshot = null
				stableLines = []
				unstableTokens = []
			}
		}
	}
}

const defaultCreateAiCodeHighlightSession = createAiCodeHighlightSessionFactory()

export function createAiCodeHighlightSession(options) {
	return defaultCreateAiCodeHighlightSession(options)
}

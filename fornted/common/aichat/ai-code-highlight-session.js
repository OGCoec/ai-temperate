import { createAiCodeTokenizer } from './ai-code-highlighter.js'
import { createAiCodeBracketState } from './ai-code-bracket-state.js'
import {
	buildPlainAiCodeLines,
	buildSafeAiCodeLines
} from './ai-code-theme-antigravity.js'

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

export function createAiCodeHighlightSessionFactory(services = {}) {
	const createTokenizer = services.createTokenizer || createAiCodeTokenizer
	const languageTimeoutMs = Number.isFinite(services.languageTimeoutMs)
		&& services.languageTimeoutMs > 0
		? services.languageTimeoutMs
		: 5000
	const scheduleTimeout = services.scheduleTimeout || setTimeout
	const cancelTimeout = services.cancelTimeout || clearTimeout

	return function createSession(options = {}) {
		const onSnapshot = typeof options.onSnapshot === 'function' ? options.onSnapshot : () => {}
		const onError = typeof options.onError === 'function' ? options.onError : () => {}
		const scheduleFrame = options.scheduleFrame || defaultScheduleFrame
		const sourceLanguage = options.language

		let revision = 1
		let language = initialLanguage(sourceLanguage)
		let status = language.supported ? 'loading' : 'plain'
		let closed = false
		let plainLocked = !language.supported
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
		const reportedErrors = new Set()

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

		function publishPlain() {
			publish(createViewSnapshot(
				buildPlainAiCodeLines(requestedCode),
				[],
				{ ...language, canonicalId: 'text', supported: false }
			))
		}

		function handleFailure(error) {
			const failureCode = String(error?.code || error?.message || '')
			const unsupported = failureCode === 'AI_CODE_LANGUAGE_UNSUPPORTED'
			const knownFailureCodes = new Set([
				'AI_CODE_ENGINE_INIT_FAILED',
				'AI_CODE_ENGINE_INIT_TIMEOUT',
				'AI_CODE_LANGUAGE_LOAD_FAILED',
				'AI_CODE_WASM_COMPILE_FAILED',
				'AI_CODE_WASM_INSTANTIATE_FAILED',
				'AI_CODE_WASM_UNAVAILABLE'
			])
			const errorCode = unsupported
				? 'AI_CODE_LANGUAGE_UNSUPPORTED'
				: (knownFailureCodes.has(failureCode)
					? failureCode
					: 'AI_CODE_HIGHLIGHT_UNAVAILABLE')
			if (!reportedErrors.has(errorCode)) {
				reportedErrors.add(errorCode)
				try {
					onError({
						code: errorCode,
						languageId: language.canonicalId,
						...(error?.stage ? { stage: error.stage } : {}),
						...(Number.isFinite(error?.elapsedMs)
							? { elapsedMs: error.elapsedMs }
							: {})
					})
				} catch (_) {
					// 诊断输出失败不能阻断纯文本降级或后续重试。
				}
			}
			plainLocked = unsupported
			initialized = false
			tokenizer = null
			stableLines = []
			unstableTokens = []
			processedCode = ''
			tokenizerDirty = false
			status = 'plain'
			revision += 1
			publishPlain()
		}

		function createTokenizerWithDeadline() {
			return new Promise((resolve, reject) => {
				let settled = false
				const timer = scheduleTimeout(() => {
					if (settled) return
					settled = true
					const error = new Error('AI_CODE_ENGINE_INIT_TIMEOUT')
					error.code = 'AI_CODE_ENGINE_INIT_TIMEOUT'
					error.stage = 'TOKENIZER'
					error.elapsedMs = languageTimeoutMs
					reject(error)
				}, languageTimeoutMs)
				Promise.resolve()
					.then(() => createTokenizer(sourceLanguage))
					.then(value => {
						if (settled) return
						settled = true
						cancelTimeout(timer)
						resolve(value)
					}, error => {
						if (settled) return
						settled = true
						cancelTimeout(timer)
						reject(error)
					})
			})
		}

		async function initialize() {
			if (initialized) return true
			if (plainLocked) return false
			try {
				const prepared = await createTokenizerWithDeadline()
				if (closed || plainLocked) return false
				language = prepared.language
				tokenizer = prepared.tokenizer
				bracketState = createAiCodeBracketState(language.canonicalId)
				initialized = true
				status = 'ready'
				return true
			} catch (error) {
				handleFailure(error)
				return false
			}
		}

		async function resetTokenizer(incrementRevision = true) {
			if (incrementRevision) revision += 1
			const resetRevision = revision
			const prepared = await createTokenizerWithDeadline()
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
				if (!await initialize() || closed || plainLocked) return
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
			})().catch(error => handleFailure(error)).finally(() => {
				processingPromise = null
			})
			return processingPromise
		}

		publish(createViewSnapshot([], []))

		return {
			update({ code } = {}) {
				if (closed) return Promise.resolve()
				completionRequested = false
				replaceRequestedCode(code)
				if (plainLocked) {
					publishPlain()
					return Promise.resolve()
				}
				if (!initialized) {
					status = 'loading'
					publishPlain()
				}
				return drain()
			},
			complete({ finalCode } = {}) {
				if (closed) return Promise.resolve()
				replaceRequestedCode(finalCode)
				completionRequested = true
				if (plainLocked) {
					publishPlain()
					return Promise.resolve()
				}
				if (!initialized) {
					status = 'loading'
					publishPlain()
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

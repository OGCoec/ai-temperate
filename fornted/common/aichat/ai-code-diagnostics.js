const ERROR_CODES = new Set([
	'AI_CODE_ENGINE_INIT_FAILED',
	'AI_CODE_ENGINE_INIT_TIMEOUT',
	'AI_CODE_HIGHLIGHT_UNAVAILABLE',
	'AI_CODE_LANGUAGE_LOAD_FAILED',
	'AI_CODE_LANGUAGE_UNSUPPORTED',
	'AI_CODE_STAGE_READY',
	'AI_CODE_STAGE_START',
	'AI_CODE_WASM_COMPILE_FAILED',
	'AI_CODE_WASM_INSTANTIATE_FAILED',
	'AI_CODE_WASM_UNAVAILABLE'
])
const PLATFORMS = new Set(['ANDROID', 'H5', 'UNKNOWN'])
const STAGES = new Set([
	'HIGHLIGHTER_CORE',
	'LANGUAGE_GRAMMAR',
	'ONIGURUMA_BINDING',
	'TOKENIZER',
	'UNKNOWN',
	'WASM_INSTANCE',
	'WASM_MODULE'
])

let runtimePlatform = 'UNKNOWN'
// #ifdef H5
runtimePlatform = 'H5'
// #endif
// #ifdef APP-PLUS
runtimePlatform = 'ANDROID'
// #endif

function normalizedCode(value) {
	const code = String(value || '')
	return ERROR_CODES.has(code) ? code : 'AI_CODE_HIGHLIGHT_UNAVAILABLE'
}

function normalizedLanguageId(value) {
	const languageId = String(value || '').trim().toLowerCase()
	return /^[a-z0-9+#._-]{1,64}$/.test(languageId) ? languageId : 'text'
}

function normalizedStage(value) {
	const stage = String(value || '').trim().toUpperCase()
	return STAGES.has(stage) ? stage : 'UNKNOWN'
}

function normalizedElapsedMs(value) {
	const elapsedMs = Number(value)
	if (!Number.isFinite(elapsedMs) || elapsedMs < 0) return 0
	return Math.min(600000, Math.round(elapsedMs))
}

function diagnosticLine(entry) {
	return '[ai-code-highlight]'
		+ ` code=${entry.code}`
		+ ` languageId=${entry.languageId}`
		+ ` platform=${entry.platform}`
		+ ` stage=${entry.stage}`
		+ ` elapsedMs=${entry.elapsedMs}`
}

/**
 * 只输出定位高亮边界所需的低基数元数据，禁止把用户代码或外链带入日志。
 */
export function reportAiCodeHighlightError(failure = {}, options = {}) {
	const requestedPlatform = String(options.platform || runtimePlatform).toUpperCase()
	const entry = {
		code: normalizedCode(failure.code || failure.message),
		languageId: normalizedLanguageId(failure.languageId),
		platform: PLATFORMS.has(requestedPlatform) ? requestedPlatform : 'UNKNOWN',
		stage: normalizedStage(failure.stage),
		elapsedMs: normalizedElapsedMs(failure.elapsedMs)
	}
	const sink = typeof options.sink === 'function' ? options.sink
		: line => globalThis.console?.warn?.(line)
	try { sink?.(diagnosticLine(entry)) } catch (_) {}
	return entry
}

import { wasmBinary } from 'shiki/wasm'
import { reportAiCodeHighlightError } from './ai-code-diagnostics.js'

function appWebAssemblyRuntime() {
	if (typeof WebAssembly === 'undefined'
		|| typeof WebAssembly.Module !== 'function'
		|| typeof WebAssembly.Instance !== 'function') {
		throw new Error('AI_CODE_WASM_UNAVAILABLE')
	}
	return WebAssembly
}

function stageFailure(code, stage, elapsedMs) {
	const error = new Error(code)
	error.stage = stage
	error.elapsedMs = elapsedMs
	return error
}

export function createSynchronousAppWasmInstantiator(binary, services = {}) {
	const runtimeProvider = services.runtimeProvider || appWebAssemblyRuntime
	const now = services.now || Date.now
	const report = services.report || reportAiCodeHighlightError
	let compiledModule = null
	return importObject => {
		const runtime = runtimeProvider()
		if (!compiledModule) {
			const moduleStartedAt = now()
			report({
				code: 'AI_CODE_STAGE_START',
				languageId: 'text',
				stage: 'WASM_MODULE',
				elapsedMs: 0
			})
			try {
				compiledModule = new runtime.Module(binary)
			} catch (_) {
				const elapsedMs = now() - moduleStartedAt
				report({
					code: 'AI_CODE_WASM_COMPILE_FAILED',
					languageId: 'text',
					stage: 'WASM_MODULE',
					elapsedMs
				})
				throw stageFailure('AI_CODE_WASM_COMPILE_FAILED', 'WASM_MODULE', elapsedMs)
			}
			report({
				code: 'AI_CODE_STAGE_READY',
				languageId: 'text',
				stage: 'WASM_MODULE',
				elapsedMs: now() - moduleStartedAt
			})
		}

		const instanceStartedAt = now()
		report({
			code: 'AI_CODE_STAGE_START',
			languageId: 'text',
			stage: 'WASM_INSTANCE',
			elapsedMs: 0
		})
		try {
			const exports = new runtime.Instance(compiledModule, importObject).exports
			report({
				code: 'AI_CODE_STAGE_READY',
				languageId: 'text',
				stage: 'WASM_INSTANCE',
				elapsedMs: now() - instanceStartedAt
			})
			return exports
		} catch (_) {
			const elapsedMs = now() - instanceStartedAt
			report({
				code: 'AI_CODE_WASM_INSTANTIATE_FAILED',
				languageId: 'text',
				stage: 'WASM_INSTANCE',
				elapsedMs
			})
			throw stageFailure('AI_CODE_WASM_INSTANTIATE_FAILED', 'WASM_INSTANCE', elapsedMs)
		}
	}
}

/**
 * App-Plus 的异步 WebAssembly.instantiate 在部分 Android 云打包运行时会永久 pending。
 * 这里同步编译并实例化同一份 Shiki WASM，只缓存与 imports 无关的 Module，避免代码块重复编译。
 */
export const instantiateAppOniguruma = createSynchronousAppWasmInstantiator(wasmBinary)

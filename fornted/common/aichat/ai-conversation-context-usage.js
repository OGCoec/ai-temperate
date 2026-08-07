export function formatAiConversationContextTokens(value) {
	const tokens = Math.max(0, Number(value) || 0)
	if (tokens >= 1_000_000) {
		const millions = Math.round(tokens / 100_000) / 10
		return `${Number.isInteger(millions) ? millions.toFixed(0) : millions}M`
	}
	if (tokens >= 1_000) {
		const thousands = Math.round(tokens / 100) / 10
		return `${Number.isInteger(thousands) ? thousands.toFixed(0) : thousands}K`
	}
	return String(Math.round(tokens))
}

export function formatAiConversationContextPercent(value) {
	const percentage = Math.max(0, Number(value) || 0)
	return Number(percentage.toFixed(1)).toString()
}

/**
 * 模型切换时只复用后端给出的 estimatedContextTokens，并按新模型窗口即时重算展示；
 * 该值不是权威快照，调用方必须随后请求后端覆盖。
 */
export function recalculateAiConversationContextUsage(usage, model) {
	if (!usage || !model) return null
	const estimated = Number(usage.estimatedContextTokens)
	const window = Number(model.contextWindowTokens)
	const maximumOutput = Number(model.maxOutputTokens)
	if (!Number.isSafeInteger(estimated) || estimated < 0
		|| !Number.isSafeInteger(window) || window <= 0
		|| !Number.isSafeInteger(maximumOutput) || maximumOutput <= 0) return null
	const threshold = Number(usage.thresholdPercent || 80)
	return Object.freeze({
		...usage,
		modelPublicId: model.publicId,
		contextWindowTokens: window,
		contextWindowK: Math.ceil(window / 1000),
		usagePercent: Math.round((estimated * 1000) / window) / 10,
		thresholdReached: estimated * 100 >= window * threshold,
		hardLimitExceeded: estimated + maximumOutput > window
	})
}

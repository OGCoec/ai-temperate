const TEXT_LABELS = Object.freeze({
	openai: Object.freeze(['', 'Minimal', 'Low', 'Medium', 'High', 'XHigh']),
	xai: Object.freeze(['', 'Low', 'Medium', 'High']),
	anthropic: Object.freeze(['', 'Low', 'Medium', 'High', 'XHigh', 'Max']),
	google: Object.freeze(['', 'Minimal', 'Low', 'Medium', 'High'])
})

const IMAGE_LABELS = Object.freeze({
	openai: Object.freeze(['', 'Low', 'Medium', 'High']),
	xai: Object.freeze(['', '1K', '', '2K']),
	google: Object.freeze(['', '0.5K', '1K', '2K', '4K'])
})

/**
 * 根据服务端可信 vendor 和已返回的数字档位生成标签，前端不参与供应商协议选择。
 */
export function aiConversationModelLevelOptions(model, imageGeneration = false) {
	const vendor = String(model?.vendor || '').trim().toLowerCase()
	const levels = imageGeneration
		? model?.supportedImageGenerationLevels || []
		: model?.supportedReasoningEffortLevels || []
	const labels = (imageGeneration ? IMAGE_LABELS : TEXT_LABELS)[vendor] || []
	return Object.freeze(levels.map(level => Object.freeze({
		value: level,
		label: labels[level] || `档位 ${level}`
	})))
}

export const AI_CONVERSATION_IMAGE_ASPECT_OPTIONS = Object.freeze([
	Object.freeze({ value: 'SQUARE', label: '正方形' }),
	Object.freeze({ value: 'LANDSCAPE', label: '横图' }),
	Object.freeze({ value: 'PORTRAIT', label: '竖图' })
])

export function imageGenerationProfileLevels(model) {
	const levels = Array.from(model?.supportedImageGenerationLevels || [])
	return levels.filter((level, index) =>
		Number.isSafeInteger(level)
		&& level >= 1
		&& level <= 3
		&& levels.indexOf(level) === index)
}

export function modelSupportsImageGeneration(model) {
	return new Set(model?.capabilities || []).has('IMAGE_GENERATION')
		&& imageGenerationProfileLevels(model).length > 0
}

export function supportedImageAspectOptions(model) {
	const supported = new Set(model?.supportedImageAspects || [])
	return AI_CONVERSATION_IMAGE_ASPECT_OPTIONS.filter(option =>
		supported.has(option.value))
}

export function normalizeImageGenerationAspect(model, candidate = 'SQUARE') {
	const options = supportedImageAspectOptions(model)
	const normalized = String(candidate || '').trim().toUpperCase()
	return options.some(option => option.value === normalized)
		? normalized
		: options[0]?.value || 'SQUARE'
}

export function imageGenerationRequest(model, aspect) {
	if (!modelSupportsImageGeneration(model)) return null
	return Object.freeze({
		aspect: normalizeImageGenerationAspect(model, aspect)
	})
}

export function imagePreviewAttachment(value) {
	const contentType = String(value?.contentType || 'image/webp').toLowerCase()
	const base64 = String(value?.base64 || '').trim()
	if (!new Set(['image/webp', 'image/png', 'image/jpeg']).has(contentType)
		|| !base64) return null
	return Object.freeze({
		attachmentId: String(value?.imageId || `image-preview-${Number(value?.index || 0)}`),
		fileName: `image-preview-${Number(value?.index || 0)}.webp`,
		contentType,
		sizeBytes: '0',
		category: 'IMAGE',
		url: `data:${contentType};base64,${base64}`,
		state: 'AVAILABLE',
		phase: String(value?.phase || 'PARTIAL'),
		width: Number(value?.width || 0),
		height: Number(value?.height || 0),
		volatilePreview: true
	})
}

export function persistedImageAttachments(value) {
	return Array.isArray(value?.attachments) ? [...value.attachments] : []
}

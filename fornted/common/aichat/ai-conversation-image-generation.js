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
		&& level <= 4
		&& levels.indexOf(level) === index)
}

export function modelSupportsImageGeneration(model) {
	return new Set(model?.capabilities || []).has('IMAGE_GENERATION')
		&& imageGenerationProfileLevels(model).length > 0
}

export function modelSupportsMultipleImageOutputs(model) {
	const capabilities = new Set(model?.capabilities || [])
	return capabilities.has('IMAGE_GENERATION')
		&& capabilities.has('IMAGE_EDIT')
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

export function parseImageOutputCount(candidate) {
	const text = String(candidate ?? '').trim()
	if (!/^(?:[1-9]|10)$/.test(text)) return null
	return Number(text)
}

export function normalizeImageOutputCount(candidate, fallback = 1) {
	return parseImageOutputCount(candidate) ?? parseImageOutputCount(fallback) ?? 1
}

export function imageGenerationRequest(model, aspect, outputCount = 1) {
	if (!modelSupportsImageGeneration(model)) return null
	return Object.freeze({
		aspect: normalizeImageGenerationAspect(model, aspect),
		outputCount: modelSupportsMultipleImageOutputs(model)
			? normalizeImageOutputCount(outputCount)
			: 1
	})
}

export function createImageOutputSlots(outputCount) {
	const count = normalizeImageOutputCount(outputCount)
	return Array.from({ length: count }, (_, outputIndex) => Object.freeze({
		attachmentId: `image-output-${outputIndex}`,
		fileName: `image-output-${outputIndex + 1}.webp`,
		contentType: 'image/webp',
		sizeBytes: '0',
		category: 'IMAGE',
		url: '',
		state: 'PENDING',
		phase: 'QUEUED',
		status: 'QUEUED',
		outputIndex,
		partialImageIndex: null,
		volatilePreview: true,
		imageSlot: true
	}))
}

export function imagePreviewAttachment(value) {
	const contentType = String(value?.contentType || 'image/webp').toLowerCase()
	const base64 = String(value?.base64 || '').trim()
	if (!new Set(['image/webp', 'image/png', 'image/jpeg']).has(contentType)
		|| !base64) return null
	const outputIndex = Number(value?.outputIndex)
	const phase = String(value?.phase || 'PARTIAL').toUpperCase()
	const partialImageIndex = value?.partialImageIndex == null
		? null
		: Number(value.partialImageIndex)
	if (!Number.isSafeInteger(outputIndex) || outputIndex < 0 || outputIndex > 9
		|| !new Set(['PARTIAL', 'FINAL']).has(phase)
		|| (phase === 'PARTIAL' && partialImageIndex == null)
		|| (phase === 'FINAL' && partialImageIndex != null)
		|| (partialImageIndex != null
			&& (!Number.isSafeInteger(partialImageIndex)
				|| partialImageIndex < 0 || partialImageIndex > 2))) return null
	return Object.freeze({
		attachmentId: `image-output-${outputIndex}`,
		imageId: String(value?.imageId || `image-${outputIndex}`),
		fileName: `image-output-${outputIndex + 1}.webp`,
		contentType,
		sizeBytes: '0',
		category: 'IMAGE',
		url: `data:${contentType};base64,${base64}`,
		state: 'AVAILABLE',
		phase,
		status: phase === 'FINAL' ? 'FINALIZING' : 'GENERATING',
		outputIndex,
		partialImageIndex,
		width: Number(value?.width || 0),
		height: Number(value?.height || 0),
		volatilePreview: true,
		imageSlot: true
	})
}

export function upsertImageOutputAttachment(attachments, next) {
	const outputIndex = Number(next?.outputIndex)
	if (!Number.isSafeInteger(outputIndex) || outputIndex < 0 || outputIndex > 9) {
		return [...(attachments || [])]
	}
	const values = [...(attachments || [])]
	const existingIndex = values.findIndex(item =>
		Number(item?.outputIndex) === outputIndex)
	if (existingIndex >= 0) values.splice(existingIndex, 1, next)
	else values.push(next)
	return values.sort((left, right) =>
		Number(left?.outputIndex ?? 99) - Number(right?.outputIndex ?? 99))
}

export function failImageOutputAttachment(attachments, value) {
	const outputIndex = Number(value?.outputIndex)
	if (!Number.isSafeInteger(outputIndex) || outputIndex < 0 || outputIndex > 9) {
		return [...(attachments || [])]
	}
	const current = [...(attachments || [])].find(item =>
		Number(item?.outputIndex) === outputIndex)
		|| createImageOutputSlots(outputIndex + 1)[outputIndex]
	return upsertImageOutputAttachment(attachments, Object.freeze({
		...current,
		url: '',
		state: 'FAILED',
		phase: 'FAILED',
		status: 'FAILED',
		reasonCode: String(value?.reasonCode || 'AI_UPSTREAM_STREAM_FAILED')
	}))
}

export function persistedImageAttachments(value) {
	if (!Array.isArray(value?.attachments)) return []
	let fallbackImageIndex = 0
	return value.attachments.map(attachment => {
		const category = String(attachment?.category || '').toUpperCase()
		const contentType = String(attachment?.contentType || '').toLowerCase()
		const imageAttachment = category === 'IMAGE'
			|| contentType.startsWith('image/')
		if (!imageAttachment) return Object.freeze({ ...attachment })

		const explicitIndex = Number(attachment?.outputIndex)
		const fileNameMatch = /^generated-(10|[1-9])\./i.exec(
			String(attachment?.fileName || ''))
		const fileNameIndex = fileNameMatch ? Number(fileNameMatch[1]) - 1 : null
		const outputIndex = Number.isSafeInteger(explicitIndex)
			&& explicitIndex >= 0 && explicitIndex <= 9
			? explicitIndex
			: Number.isSafeInteger(fileNameIndex)
				? fileNameIndex
				: fallbackImageIndex
		fallbackImageIndex++
		return Object.freeze({
			...attachment,
			outputIndex,
			phase: 'FINAL',
			status: 'COMPLETED',
			volatilePreview: false,
			imageSlot: true
		})
	})
}

export function mergeCompletedImageOutputs(current, persisted, requestedCount = 0) {
	const count = parseImageOutputCount(requestedCount)
	if (count == null) return [...(persisted || [])]

	let merged = [...(current || [])]
		.filter(attachment => attachment?.status === 'FAILED')
	for (const attachment of persisted || []) {
		merged = upsertImageOutputAttachment(merged, attachment)
	}
	for (let outputIndex = 0; outputIndex < count; outputIndex++) {
		if (!merged.some(attachment =>
			Number(attachment?.outputIndex) === outputIndex)) {
			merged = failImageOutputAttachment(merged, {
				outputIndex,
				reasonCode: 'IMAGE_OUTPUT_MISSING'
			})
		}
	}
	return merged
}

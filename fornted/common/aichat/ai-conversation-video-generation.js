export const AI_CONVERSATION_VIDEO_MODE_OPTIONS = Object.freeze([
	Object.freeze({ value: 'TEXT_TO_VIDEO', label: '文生视频' }),
	Object.freeze({ value: 'IMAGE_TO_VIDEO', label: '图片生成视频' }),
	Object.freeze({ value: 'REFERENCE_TO_VIDEO', label: '参考图生成' }),
	Object.freeze({ value: 'VIDEO_EDIT', label: '视频编辑' }),
	Object.freeze({ value: 'VIDEO_EXTEND', label: '视频延长' })
])

const RESOLUTION_OPTIONS = Object.freeze([
	Object.freeze({ value: 'P480', label: '480p' }),
	Object.freeze({ value: 'P720', label: '720p' }),
	Object.freeze({ value: 'P1080', label: '1080p' })
])

const ASPECT_OPTIONS = Object.freeze([
	Object.freeze({ value: 'RATIO_1_1', label: '1:1' }),
	Object.freeze({ value: 'RATIO_16_9', label: '16:9' }),
	Object.freeze({ value: 'RATIO_9_16', label: '9:16' }),
	Object.freeze({ value: 'RATIO_4_3', label: '4:3' }),
	Object.freeze({ value: 'RATIO_3_4', label: '3:4' }),
	Object.freeze({ value: 'RATIO_3_2', label: '3:2' }),
	Object.freeze({ value: 'RATIO_2_3', label: '2:3' })
])

const VIDEO_ATTACHMENT_ID_PATTERN = /^[A-Za-z0-9_-]{38}$/

function attachmentKind(attachment) {
	const contentType = String(attachment?.contentType || '').toLowerCase()
	if (contentType.startsWith('image/')) return 'IMAGE'
	if (contentType === 'video/mp4') return 'MP4'
	return 'OTHER'
}

export function isVideoAttachmentCompatible(mode, attachment) {
	const kind = attachmentKind(attachment)
	if (mode === 'IMAGE_TO_VIDEO' || mode === 'REFERENCE_TO_VIDEO') {
		return kind === 'IMAGE'
	}
	if (mode === 'VIDEO_EDIT' || mode === 'VIDEO_EXTEND') {
		return kind === 'MP4'
	}
	return false
}

export function modelSupportsVideoGeneration(model) {
	return Array.isArray(model?.supportedVideoModes)
		&& model.supportedVideoModes.length > 0
}

export function supportedVideoModeOptions(model) {
	const supported = new Set(model?.supportedVideoModes || [])
	return AI_CONVERSATION_VIDEO_MODE_OPTIONS.filter(option => supported.has(option.value))
}

export function normalizeVideoMode(model, value) {
	const options = supportedVideoModeOptions(model)
	return options.some(option => option.value === value)
		? value : options[0]?.value || ''
}

export function supportedVideoResolutionOptions(model, mode) {
	if (mode === 'VIDEO_EDIT' || mode === 'VIDEO_EXTEND') return []
	const supported = new Set(model?.supportedVideoResolutions || [])
	return RESOLUTION_OPTIONS.filter(option => supported.has(option.value)
		&& !(mode === 'REFERENCE_TO_VIDEO' && option.value === 'P1080'))
}

export function supportedVideoAspectOptions(model, mode) {
	if (mode === 'VIDEO_EDIT' || mode === 'VIDEO_EXTEND') return []
	const supported = new Set(model?.supportedVideoAspectRatios || [])
	return ASPECT_OPTIONS.filter(option => supported.has(option.value))
}

export function normalizeVideoDuration(model, mode, value) {
	if (mode === 'VIDEO_EDIT') return null
	const minimum = mode === 'VIDEO_EXTEND'
		? 2 : Number(model?.videoDuration?.minimumSeconds || 1)
	const maximum = mode === 'VIDEO_EXTEND'
		? 10 : Number(model?.videoDuration?.maximumSeconds || 15)
	const duration = Number(value)
	return Number.isSafeInteger(duration)
		? Math.min(maximum, Math.max(minimum, duration))
		: minimum
}

export function supportedVideoDurationOptions(model, mode) {
	if (mode === 'VIDEO_EDIT') return []
	const minimum = mode === 'VIDEO_EXTEND'
		? 2 : Number(model?.videoDuration?.minimumSeconds || 1)
	const maximum = mode === 'VIDEO_EXTEND'
		? 10 : Number(model?.videoDuration?.maximumSeconds || 15)
	return Array.from({ length: maximum - minimum + 1 }, (_, index) => {
		const value = minimum + index
		return Object.freeze({ value, label: `${value} 秒` })
	})
}

export function videoSendGate({ model, mode, text, attachments }) {
	if (!String(text || '').trim()) {
		return Object.freeze({ allowed: false, reason: '请输入视频生成提示词。' })
	}
	if (!modelSupportsVideoGeneration(model)
		|| !model.supportedVideoModes.includes(mode)) {
		return Object.freeze({ allowed: false, reason: '所选模型不支持当前视频模式。' })
	}
	const inputs = Array.from(attachments || [])
	if (mode === 'TEXT_TO_VIDEO' && inputs.length !== 0) {
		return Object.freeze({ allowed: false, reason: '文生视频不能添加输入附件。' })
	}
	if (mode === 'IMAGE_TO_VIDEO'
		&& (inputs.length !== 1 || !isVideoAttachmentCompatible(mode, inputs[0]))) {
		return Object.freeze({ allowed: false, reason: '图片生成视频需要恰好一张图片。' })
	}
	if (mode === 'REFERENCE_TO_VIDEO'
		&& (inputs.length < 1 || inputs.length > 7
			|| inputs.some(input => !isVideoAttachmentCompatible(mode, input)))) {
		return Object.freeze({ allowed: false, reason: '参考图生成需要 1～7 张图片。' })
	}
	if ((mode === 'VIDEO_EDIT' || mode === 'VIDEO_EXTEND')
		&& (inputs.length !== 1 || !isVideoAttachmentCompatible(mode, inputs[0]))) {
		return Object.freeze({ allowed: false, reason: '视频编辑或延长需要恰好一个 MP4 视频。' })
	}
	return Object.freeze({ allowed: true, reason: '' })
}

export function videoGenerationRequest({
	model,
	mode,
	durationSeconds,
	resolution,
	aspectRatio,
	attachments
}) {
	if (!modelSupportsVideoGeneration(model)
		|| !model.supportedVideoModes.includes(mode)) {
		throw new Error('所选模型不支持当前视频模式。')
	}
	const inputAttachmentPublicIds = (attachments || []).map(attachment => {
		const value = String(attachment?.attachmentId || '')
		if (!VIDEO_ATTACHMENT_ID_PATTERN.test(value)) {
			throw new Error('视频输入附件标识无效。')
		}
		return value
	})
	const request = { mode, inputAttachmentPublicIds }
	if (mode !== 'VIDEO_EDIT') {
		request.durationSeconds = normalizeVideoDuration(model, mode, durationSeconds)
	}
	if (mode !== 'VIDEO_EDIT' && mode !== 'VIDEO_EXTEND') {
		const resolutions = supportedVideoResolutionOptions(model, mode)
		const aspects = supportedVideoAspectOptions(model, mode)
		if (!resolutions.some(option => option.value === resolution)
			|| !aspects.some(option => option.value === aspectRatio)) {
			throw new Error('视频清晰度或画幅不受所选模型支持。')
		}
		request.resolution = resolution
		request.aspectRatio = aspectRatio
	}
	return Object.freeze(request)
}

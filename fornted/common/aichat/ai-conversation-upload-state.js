export const ATTACHMENT_UPLOAD_STATES = Object.freeze({
	PREPARING: 'PREPARING',
	UPLOADING: 'UPLOADING',
	UPLOADED: 'UPLOADED',
	FAILED: 'FAILED'
})

export const MAX_ATTACHMENT_COUNT = 8
export const MAX_ATTACHMENT_FILE_BYTES = 100 * 1024 * 1024
export const MAX_ATTACHMENT_TOTAL_BYTES = 200 * 1024 * 1024

function stateError(code, message) {
	return Object.assign(new Error(message), { code })
}

export function attachmentCategory(file) {
	const contentType = String(file?.contentType || '').toLowerCase()
	const fileName = String(file?.fileName || '').toLowerCase()
	if (contentType.startsWith('image/')) return 'IMAGE'
	if (contentType.startsWith('audio/')) return 'AUDIO'
	if (contentType.startsWith('video/')) return 'VIDEO'
	if (/\.(zip|rar|7z|tar|gz|bz2|xz)$/.test(fileName)) return 'ARCHIVE'
	if (/^(text\/|application\/(pdf|msword|vnd\.|rtf|json|xml))/.test(contentType)) return 'DOCUMENT'
	return 'OTHER'
}

export function requiredMediaCapability(file) {
	const category = attachmentCategory(file)
	if (category === 'IMAGE') return 'IMAGE_INPUT'
	if (category === 'AUDIO') return 'AUDIO_INPUT'
	if (category === 'VIDEO') return 'VIDEO_INPUT'
	return null
}

export function isAttachmentCompatible(file, model) {
	const required = requiredMediaCapability(file)
	if (!required || !model) return true
	return new Set(model.capabilities || []).has(required)
}

export function isImageEditAttachmentCompatible(file, model) {
	const capabilities = new Set(model?.capabilities || [])
	return capabilities.has('IMAGE_GENERATION')
		&& capabilities.has('IMAGE_EDIT')
		&& new Set(['image/png', 'image/jpeg', 'image/webp'])
			.has(String(file?.contentType || '').toLowerCase())
}

export function validateAttachmentSelection(existingFiles, newFiles) {
	const existing = Array.from(existingFiles || [])
	const selected = Array.from(newFiles || [])
	if (existing.length + selected.length > MAX_ATTACHMENT_COUNT) {
		throw stateError('AI_ATTACHMENT_COUNT_EXCEEDED', '每条消息最多添加 8 个附件。')
	}
	let totalBytes = 0
	for (const file of [...existing, ...selected]) {
		const sizeBytes = Number(file?.sizeBytes || file?.size || 0)
		if (!Number.isSafeInteger(sizeBytes) || sizeBytes <= 0) {
			throw stateError('AI_ATTACHMENT_SIZE_INVALID', '无法读取文件大小。')
		}
		if (sizeBytes > MAX_ATTACHMENT_FILE_BYTES) {
			throw stateError('AI_ATTACHMENT_TOO_LARGE', '单个附件不能超过 100 MB。')
		}
		totalBytes += sizeBytes
	}
	if (!Number.isSafeInteger(totalBytes) || totalBytes > MAX_ATTACHMENT_TOTAL_BYTES) {
		throw stateError('AI_ATTACHMENT_TOTAL_SIZE_EXCEEDED', '单条消息的附件总大小不能超过 200 MB。')
	}
	return Object.freeze({ count: existing.length + selected.length, totalBytes })
}

export function createPendingAttachment(file, localId) {
	return {
		localId,
		fileName: String(file?.fileName || 'attachment.bin'),
		contentType: String(file?.contentType || 'application/octet-stream'),
		sizeBytes: Number(file?.sizeBytes || 0),
		path: file?.path || '',
		raw: file?.raw || null,
		state: ATTACHMENT_UPLOAD_STATES.PREPARING,
		progress: 0,
		retrying: false,
		error: '',
		uploaded: null,
		uploadTask: null
	}
}

export function deriveSendGate({
	model,
	text,
	attachments,
	generating,
	imageEditing = false,
	mediaOperation = false
}) {
	if (generating) return Object.freeze({ allowed: false, reason: '正在生成回答。' })
	if (!String(text || '').trim() && !attachments.length) {
		return Object.freeze({ allowed: false, reason: '请输入消息或添加附件。' })
	}
	const preparing = attachments.find(file => file.state === ATTACHMENT_UPLOAD_STATES.PREPARING)
	if (preparing) return Object.freeze({ allowed: false, reason: `${preparing.fileName} 正在准备上传。` })
	const uploading = attachments.find(file => file.state === ATTACHMENT_UPLOAD_STATES.UPLOADING)
	if (uploading) return Object.freeze({
		allowed: false,
		reason: uploading.retrying
			? `${uploading.fileName} 正在自动重试上传。`
			: `${uploading.fileName} 正在上传。`
	})
	const failed = attachments.find(file => file.state === ATTACHMENT_UPLOAD_STATES.FAILED)
	if (failed) return Object.freeze({ allowed: false, reason: `${failed.fileName} 上传失败，请重试或删除。` })
	const incomplete = attachments.find(file => file.state !== ATTACHMENT_UPLOAD_STATES.UPLOADED)
	if (incomplete) return Object.freeze({ allowed: false, reason: `${incomplete.fileName} 尚未上传完成。` })
	if (!model) return Object.freeze({ allowed: false, reason: '请先选择可用模型。' })
	if (imageEditing) {
		const incompatibleEditInput = attachments.find(file =>
			!isImageEditAttachmentCompatible(file, model))
		if (incompatibleEditInput) return Object.freeze({
			allowed: false,
			reason: '图片编辑只支持 PNG、JPEG 和 WebP 输入图片。'
		})
	}
	const incompatible = attachments.find(file => !isAttachmentCompatible(file, model))
	if (incompatible && !imageEditing && !mediaOperation) {
		return Object.freeze({
			allowed: false,
			reason: `${incompatible.fileName} 不受当前模型支持。`
		})
	}
	return Object.freeze({ allowed: true, reason: '' })
}

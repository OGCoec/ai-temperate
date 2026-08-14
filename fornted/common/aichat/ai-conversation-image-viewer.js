const MAXIMUM_OUTPUT_INDEX = 9
const SAFE_HTTPS_SOURCE = /^https:\/\/[^\s]+$/i
const SAFE_BLOB_SOURCE = /^blob:[^\s]+$/i
const SAFE_DATA_IMAGE_SOURCE = /^data:image\/(?:png|jpe?g|webp);base64,[a-z0-9+/=\s]+$/i

function normalizedOutputIndex(value) {
	const outputIndex = Number(value)
	return Number.isSafeInteger(outputIndex)
		&& outputIndex >= 0
		&& outputIndex <= MAXIMUM_OUTPUT_INDEX
		? outputIndex : null
}

function safeImageSource(value) {
	const raw = String(value || '')
	if (!raw || /[\u0000-\u001f\u007f]/.test(raw)) return ''
	const source = raw.trim()
	return SAFE_HTTPS_SOURCE.test(source)
		|| SAFE_BLOB_SOURCE.test(source)
		|| SAFE_DATA_IMAGE_SOURCE.test(source)
		? source : ''
}

function generatedImageSource(attachment) {
	return safeImageSource(attachment?.persistedUrl)
		|| safeImageSource(attachment?.url)
}

function viewableGeneratedImage(attachment) {
	const contentType = String(attachment?.contentType || '').trim().toLowerCase()
	return attachment?.imageSlot === true
		&& String(attachment?.state || '').toUpperCase() === 'AVAILABLE'
		&& String(attachment?.status || '').toUpperCase() !== 'FAILED'
		&& contentType.startsWith('image/')
		&& contentType !== 'image/svg+xml'
		&& normalizedOutputIndex(attachment?.outputIndex) != null
		&& Boolean(generatedImageSource(attachment))
}

function imageEvidenceScore(attachment) {
	let score = 0
	if (String(attachment?.phase || '').toUpperCase() === 'FINAL') score += 16
	if (safeImageSource(attachment?.persistedUrl)) score += 8
	if (SAFE_HTTPS_SOURCE.test(safeImageSource(attachment?.url))) score += 4
	if (attachment?.galleryExiting !== true) score += 1
	return score
}

function messageOwnerId(message) {
	return String(message?.messagePublicId || message?.localId || '').trim()
}

function messageTimestamp(message) {
	const value = Date.parse(String(message?.createdAt || message?.completedAt || ''))
	return Number.isFinite(value) ? value : null
}

export function generatedImageIdentity(message, attachment) {
	const ownerId = messageOwnerId(message)
	const outputIndex = normalizedOutputIndex(attachment?.outputIndex)
	return ownerId && outputIndex != null ? `${ownerId}:${outputIndex}` : ''
}

function generatedImageViewerItem(message, attachment) {
	const identity = generatedImageIdentity(message, attachment)
	if (!identity || !viewableGeneratedImage(attachment)) return null
	const ownerId = messageOwnerId(message)
	return Object.freeze({
		identity,
		ownerId,
		messagePublicId: String(message?.messagePublicId || ''),
		localId: String(message?.localId || ''),
		outputIndex: normalizedOutputIndex(attachment.outputIndex),
		attachment,
		displaySrc: generatedImageSource(attachment),
		createdAt: String(message?.createdAt || message?.completedAt || '')
	})
}

function preferredViewerItem(current, candidate) {
	if (!current) return candidate
	return imageEvidenceScore(candidate?.attachment)
		>= imageEvidenceScore(current?.attachment)
		? candidate : current
}

function deduplicatedViewerItems(items) {
	const orderedIdentities = []
	const byIdentity = new Map()
	for (const item of Array.isArray(items) ? items : []) {
		if (!item?.identity) continue
		if (!byIdentity.has(item.identity)) orderedIdentities.push(item.identity)
		byIdentity.set(item.identity, preferredViewerItem(
			byIdentity.get(item.identity), item))
	}
	return Object.freeze(orderedIdentities.map(identity => byIdentity.get(identity)))
}

/**
 * 将当前已加载消息转换为会话级生成图片清单；消息与槽位顺序都保持稳定，
 * 避免流式事件先后和历史分页前插改变用户正在查看的图片。
 */
export function conversationGeneratedImages(messages) {
	const orderedMessages = (Array.isArray(messages) ? messages : [])
		.map((message, index) => ({ message, index, timestamp: messageTimestamp(message) }))
		.sort((left, right) => {
			if (left.timestamp == null || right.timestamp == null
				|| left.timestamp === right.timestamp) return left.index - right.index
			return left.timestamp - right.timestamp
		})
	const items = []
	for (const entry of orderedMessages) {
		const attachments = (Array.isArray(entry.message?.responseAttachments)
			? entry.message.responseAttachments : [])
			.slice()
			.sort((left, right) => {
				const leftIndex = normalizedOutputIndex(left?.outputIndex)
				const rightIndex = normalizedOutputIndex(right?.outputIndex)
				return (leftIndex ?? Number.MAX_SAFE_INTEGER)
					- (rightIndex ?? Number.MAX_SAFE_INTEGER)
			})
		for (const attachment of attachments) {
			const item = generatedImageViewerItem(entry.message, attachment)
			if (item) items.push(item)
		}
	}
	return deduplicatedViewerItems(items)
}

export function mergeConversationGeneratedImages(currentItems, olderMessages) {
	return deduplicatedViewerItems([
		...conversationGeneratedImages(olderMessages),
		...(Array.isArray(currentItems) ? currentItems : [])
	])
}

export function activeGeneratedImageIndex(items, activeIdentity) {
	return (Array.isArray(items) ? items : [])
		.findIndex(item => item?.identity === activeIdentity)
}

export function reconcileGeneratedImageIdentity(items, activeIdentity, previousItem = null) {
	const source = Array.isArray(items) ? items : []
	if (source.some(item => item?.identity === activeIdentity)) return activeIdentity
	if (previousItem) {
		const replacement = source.find(item => item?.outputIndex === previousItem.outputIndex
			&& ((previousItem.localId && item?.localId === previousItem.localId)
				|| (previousItem.messagePublicId
					&& item?.messagePublicId === previousItem.messagePublicId)))
		if (replacement?.identity) return replacement.identity
	}
	return source[0]?.identity || ''
}

export function adjacentGeneratedImageItems(items, activeIdentity, radius = 1) {
	const source = Array.isArray(items) ? items : []
	const activeIndex = activeGeneratedImageIndex(source, activeIdentity)
	if (activeIndex < 0) return Object.freeze([])
	const normalizedRadius = Number.isSafeInteger(Number(radius))
		? Math.max(0, Math.min(5, Number(radius))) : 1
	return Object.freeze(source.slice(
		Math.max(0, activeIndex - normalizedRadius),
		Math.min(source.length, activeIndex + normalizedRadius + 1)
	))
}

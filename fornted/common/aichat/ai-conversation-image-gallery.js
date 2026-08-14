const MAXIMUM_IMAGE_OUTPUTS = 10
const DEFAULT_VISIBLE_SECONDARY_OUTPUTS = 3
const VALID_OUTPUT_INDEX = value => Number.isSafeInteger(value)
	&& value >= 0 && value <= 9

function outputIndexOf(value) {
	const outputIndex = Number(
		typeof value === 'object' ? value?.outputIndex : value
	)
	return VALID_OUTPUT_INDEX(outputIndex) ? outputIndex : null
}

function renderableImage(value) {
	return value?.imageSlot === true
		&& String(value?.state || '').toUpperCase() === 'AVAILABLE'
		&& String(value?.status || '').toUpperCase() !== 'FAILED'
		&& String(value?.url || '').trim() !== ''
}

function finalImageEvidence(value) {
	return renderableImage(value)
		&& String(value?.phase || '').toUpperCase() === 'FINAL'
}

function imageEvidenceScore(value) {
	let score = 0
	if (String(value?.phase || '').toUpperCase() === 'FINAL') score += 8
	if (/^https:\/\/[^\s]+$/i.test(String(value?.persistedUrl || '').trim())) score += 4
	if (/^https:\/\/[^\s]+$/i.test(String(value?.url || '').trim())) score += 2
	if (value?.galleryExiting !== true) score += 1
	return score
}

function normalizedOrder(order) {
	const unique = []
	for (const value of Array.isArray(order) ? order : []) {
		const outputIndex = outputIndexOf(value)
		if (outputIndex == null || unique.includes(outputIndex)) continue
		unique.push(outputIndex)
	}
	return unique
}

/**
 * 记录图片第一次进入可见生成流程的顺序；重复事件只能更新原槽位，不能改变视觉位置。
 */
export function recordImagePresentationOrder(order, value) {
	const current = normalizedOrder(order)
	const outputIndex = outputIndexOf(value)
	if (outputIndex == null || typeof value === 'number'
		|| !renderableImage(value) || current.includes(outputIndex)) return current
	return [...current, outputIndex]
}

export function appendMissingImagePresentationOrder(order, attachments) {
	let next = normalizedOrder(order)
	for (const attachment of Array.isArray(attachments) ? attachments : []) {
		next = recordImagePresentationOrder(next, attachment)
	}
	return next
}

/**
 * 把请求画幅转换为拼图卡片使用的宽高比，保证同一批图片在模板中不被无故裁切。
 */
export function imageGalleryAspectRatio(aspect) {
	return ({
		SQUARE: 1,
		LANDSCAPE: 3 / 2,
		PORTRAIT: 2 / 3
	})[String(aspect || '').toUpperCase()] || 1
}

export function imageGalleryLayout(visibleCount) {
	if (visibleCount <= 0) return 'EMPTY'
	if (visibleCount === 1) return 'SINGLE'
	if (visibleCount === 2) return 'PAIR'
	if (visibleCount === 3) return 'HERO_TWO'
	if (visibleCount === 4) return 'HERO_THREE'
	return 'DUAL_WITH_RAIL'
}

/**
 * 生成渲染层所需的稳定视图模型；后端 outputIndex 同时负责身份和稳定版位，
 * presentationOrder 仅保留为流式诊断数据，不能改变已经展示的图片位置。
 */
export function createImageGalleryPresentation({
	attachments = [],
	presentationOrder = [],
	requestedCount = 0
} = {}) {
	const candidates = (Array.isArray(attachments) ? attachments : [])
		.filter(renderableImage)
	const byOutputIndex = new Map()
	for (const attachment of candidates) {
		const outputIndex = outputIndexOf(attachment)
		if (outputIndex == null) continue
		const current = byOutputIndex.get(outputIndex)
		if (!current || imageEvidenceScore(attachment) >= imageEvidenceScore(current)) {
			byOutputIndex.set(outputIndex, attachment)
		}
	}
	// 流式事件到达顺序只用于诊断；稳定版位必须由服务端 outputIndex 决定。
	void normalizedOrder(presentationOrder)
	const allItems = [...byOutputIndex.entries()]
		.sort(([left], [right]) => left - right)
		.slice(0, MAXIMUM_IMAGE_OUTPUTS)
		.map(([, attachment]) => attachment)
	const layout = imageGalleryLayout(allItems.length)
	const primaryCount = allItems.length >= 5
		? 2
		: allItems.length >= 3 ? 1 : allItems.length
	const primaryItems = allItems.slice(0, primaryCount)
	const secondaryItems = allItems.slice(primaryCount)
	const visibleSecondaryItems = secondaryItems.slice(
		0, DEFAULT_VISIBLE_SECONDARY_OUTPUTS)
	const orderedOutputIndexes = allItems.map(item => outputIndexOf(item))
	const normalizedRequestedCount = Number.isSafeInteger(Number(requestedCount))
		? Math.max(0, Math.min(MAXIMUM_IMAGE_OUTPUTS, Number(requestedCount)))
		: 0
	const completedCount = allItems.filter(finalImageEvidence).length
	const pendingCount = Math.max(0, normalizedRequestedCount - completedCount)
	const hiddenSecondaryCount = Math.max(0,
		secondaryItems.length - visibleSecondaryItems.length)

	return Object.freeze({
		layout,
		allItems: Object.freeze(allItems),
		primaryItems: Object.freeze(primaryItems),
		secondaryItems: Object.freeze(secondaryItems),
		visibleSecondaryItems: Object.freeze(visibleSecondaryItems),
		orderedOutputIndexes: Object.freeze(orderedOutputIndexes),
		heroOutputIndex: orderedOutputIndexes[0] ?? null,
		overflowOutputIndex: hiddenSecondaryCount > 0
			? secondaryItems[visibleSecondaryItems.length]?.outputIndex ?? null
			: null,
		hiddenSecondaryCount,
		hiddenCount: hiddenSecondaryCount,
		completedCount,
		pendingCount,
		requestedCount: normalizedRequestedCount,
		progressLabel: normalizedRequestedCount > 0
			&& pendingCount > 0
			? `正在生成图片 · ${completedCount}/${normalizedRequestedCount}`
			: ''
	})
}

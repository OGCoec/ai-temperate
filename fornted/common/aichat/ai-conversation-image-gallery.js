const MAX_VISIBLE_IMAGE_OUTPUTS = 4
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
	return 'HERO_THREE'
}

/**
 * 生成渲染层所需的稳定视图模型；后端 outputIndex 只负责身份，presentationOrder 负责排版。
 */
export function createImageGalleryPresentation({
	attachments = [],
	presentationOrder = [],
	requestedCount = 0
} = {}) {
	const candidates = (Array.isArray(attachments) ? attachments : [])
		.filter(renderableImage)
	const byOutputIndex = new Map(
		candidates.map(attachment => [outputIndexOf(attachment), attachment])
	)
	const order = normalizedOrder(presentationOrder)
	const orderedIndexes = order.filter(outputIndex =>
		byOutputIndex.has(outputIndex))
	for (const attachment of candidates) {
		const outputIndex = outputIndexOf(attachment)
		if (outputIndex != null && !orderedIndexes.includes(outputIndex)) {
			orderedIndexes.push(outputIndex)
		}
	}
	const visibleIndexes = orderedIndexes.slice(0, MAX_VISIBLE_IMAGE_OUTPUTS)
	const visibleItems = visibleIndexes.map(outputIndex =>
		byOutputIndex.get(outputIndex))
	const normalizedRequestedCount = Number.isSafeInteger(Number(requestedCount))
		? Math.max(0, Math.min(10, Number(requestedCount)))
		: 0
	const completedCount = candidates.filter(finalImageEvidence).length
	const pendingCount = Math.max(0, normalizedRequestedCount - completedCount)
	const hiddenCount = Math.max(0,
		orderedIndexes.length - visibleItems.length)
	const layout = imageGalleryLayout(visibleItems.length)

	return Object.freeze({
		layout,
		visibleItems: Object.freeze(visibleItems),
		visibleOutputIndexes: Object.freeze(visibleIndexes),
		heroOutputIndex: visibleIndexes[0] ?? null,
		overflowOutputIndex: hiddenCount > 0
			? visibleIndexes[MAX_VISIBLE_IMAGE_OUTPUTS - 1] ?? null
			: null,
		hiddenCount,
		completedCount,
		pendingCount,
		requestedCount: normalizedRequestedCount,
		progressLabel: normalizedRequestedCount > 0
			&& pendingCount > 0
			? `正在生成图片 · ${completedCount}/${normalizedRequestedCount}`
			: ''
	})
}

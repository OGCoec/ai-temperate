const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'ai-conversation-image-gallery.js'),
		'utf8'
	)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function image(outputIndex, phase = 'FINAL', status = phase === 'FINAL' ? 'FINALIZING' : 'GENERATING') {
	return {
		attachmentId: `image-output-${outputIndex}`,
		outputIndex,
		contentType: 'image/webp',
		category: 'IMAGE',
		state: 'AVAILABLE',
		url: `data:image/webp;base64,image-${outputIndex}`,
		phase,
		status,
		imageSlot: true
	}
}

test('orders generated images by outputIndex regardless of streaming arrival', async () => {
	const module = await loadModule()
	let order = []
	order = module.recordImagePresentationOrder(order, image(7, 'PARTIAL'))
	order = module.recordImagePresentationOrder(order, image(2, 'PARTIAL'))
	order = module.recordImagePresentationOrder(order, image(7, 'FINAL'))

	assert.deepEqual(order, [7, 2])
	const view = module.createImageGalleryPresentation({
		attachments: [image(7, 'FINAL'), image(2, 'PARTIAL'), image(0, 'FINAL')],
		presentationOrder: order,
		requestedCount: 3
	})
	assert.deepEqual(view.allItems.map(item => item.outputIndex), [0, 2, 7])
	assert.equal(view.layout, 'HERO_TWO')
	assert.deepEqual(view.primaryItems.map(item => item.outputIndex), [0])
	assert.deepEqual(view.secondaryItems.map(item => item.outputIndex), [2, 7])
	assert.equal(view.heroOutputIndex, 0)
})

test('appends terminal-only images after the observed presentation order', async () => {
	const module = await loadModule()
	const order = module.appendMissingImagePresentationOrder(
		[7], [image(2), image(7), image(4)])
	assert.deepEqual(order, [7, 2, 4])
})

test('replaces preview evidence with final evidence without changing the slot', async () => {
	const module = await loadModule()
	const preview = { ...image(3, 'PARTIAL'), attachmentId: 'preview-3' }
	const final = {
		...image(3, 'FINAL', 'COMPLETED'),
		attachmentId: 'final-3',
		persistedUrl: 'https://media.example.test/generated-4.webp'
	}
	const view = module.createImageGalleryPresentation({
		attachments: [preview, final],
		presentationOrder: [3],
		requestedCount: 1
	})

	assert.equal(view.allItems.length, 1)
	assert.equal(view.allItems[0].attachmentId, 'final-3')
	assert.equal(view.allItems[0].outputIndex, 3)
})

test('selects stable layouts and two primary images for batches of five through ten', async () => {
	const module = await loadModule()
	const cases = [
		[0, 'EMPTY', 0, 0],
		[1, 'SINGLE', 1, 0],
		[2, 'PAIR', 2, 0],
		[3, 'HERO_TWO', 1, 2],
		[4, 'HERO_THREE', 1, 3],
		[5, 'DUAL_WITH_RAIL', 2, 3],
		[6, 'DUAL_WITH_RAIL', 2, 4],
		[7, 'DUAL_WITH_RAIL', 2, 5],
		[8, 'DUAL_WITH_RAIL', 2, 6],
		[9, 'DUAL_WITH_RAIL', 2, 7],
		[10, 'DUAL_WITH_RAIL', 2, 8]
	]
	for (const [count, layout, primaryCount, secondaryCount] of cases) {
		const attachments = Array.from({ length: count }, (_, outputIndex) => image(outputIndex))
		const view = module.createImageGalleryPresentation({
			attachments,
			presentationOrder: attachments.map(item => item.outputIndex).reverse(),
			requestedCount: 10
		})
		assert.equal(view.layout, layout)
		assert.equal(view.primaryItems.length, primaryCount)
		assert.equal(view.secondaryItems.length, secondaryCount)
		assert.equal(view.allItems.length, count)
		assert.deepEqual(view.allItems.map(item => item.outputIndex),
			attachments.map(item => item.outputIndex))
	}

	const attachments = Array.from({ length: 10 }, (_, outputIndex) => image(outputIndex))
	const view = module.createImageGalleryPresentation({
		attachments,
		presentationOrder: [9, 8, 7, 6, 5, 4, 3, 2, 1, 0],
		requestedCount: 10
	})
	assert.equal(view.layout, 'DUAL_WITH_RAIL')
	assert.deepEqual(view.primaryItems.map(item => item.outputIndex), [0, 1])
	assert.deepEqual(view.visibleSecondaryItems.map(item => item.outputIndex), [2, 3, 4])
	assert.equal(view.hiddenSecondaryCount, 5)
	assert.equal(view.hiddenCount, 5)
	assert.equal(view.overflowOutputIndex, 5)
})

test('removes failed tiles from the visual set and promotes the earliest surviving image', async () => {
	const module = await loadModule()
	const attachments = [
		image(4, 'FINAL'),
		{ ...image(1, 'FAILED', 'FAILED'), url: '' },
		image(8, 'FINAL')
	]
	const view = module.createImageGalleryPresentation({
		attachments,
		presentationOrder: [4, 1, 8],
		requestedCount: 3
	})
	assert.equal(view.heroOutputIndex, 4)
	assert.deepEqual(view.allItems.map(item => item.outputIndex), [4, 8])
})

test('counts only final evidence for progress while partial previews remain visible', async () => {
	const module = await loadModule()
	const view = module.createImageGalleryPresentation({
		attachments: [image(0, 'PARTIAL'), image(1, 'FINAL'), image(2, 'FINAL', 'FINALIZING')],
		presentationOrder: [0, 1, 2],
		requestedCount: 5
	})
	assert.equal(view.completedCount, 2)
	assert.equal(view.pendingCount, 3)
	assert.equal(view.progressLabel, '正在生成图片 · 2/5')
})

test('maps requested aspect ratios to numeric CSS-safe values', async () => {
	const module = await loadModule()
	assert.equal(module.imageGalleryAspectRatio('SQUARE'), 1)
	assert.equal(module.imageGalleryAspectRatio('LANDSCAPE'), 1.5)
	assert.equal(module.imageGalleryAspectRatio('PORTRAIT'), 2 / 3)
	assert.equal(module.imageGalleryAspectRatio('unknown'), 1)
})

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

test('locks the first visible partial image as the hero and never reorders it', async () => {
	const module = await loadModule()
	let order = []
	order = module.recordImagePresentationOrder(order, image(7, 'PARTIAL'))
	order = module.recordImagePresentationOrder(order, image(2, 'PARTIAL'))
	order = module.recordImagePresentationOrder(order, image(7, 'FINAL'))

	assert.deepEqual(order, [7, 2])
	const view = module.createImageGalleryPresentation({
		attachments: [image(2, 'PARTIAL'), image(7, 'FINAL')],
		presentationOrder: order,
		requestedCount: 5
	})
	assert.equal(view.heroOutputIndex, 7)
	assert.deepEqual(view.visibleItems.map(item => item.outputIndex), [7, 2])
	assert.equal(view.visibleItems[0].phase, 'FINAL')
})

test('appends terminal-only images after the observed presentation order', async () => {
	const module = await loadModule()
	const order = module.appendMissingImagePresentationOrder(
		[7], [image(2), image(7), image(4)])
	assert.deepEqual(order, [7, 2, 4])
})

test('selects stable layouts for one through four visible images and caps five-plus at four tiles', async () => {
	const module = await loadModule()
	for (const [count, layout] of [[0, 'EMPTY'], [1, 'SINGLE'], [2, 'PAIR'], [3, 'HERO_TWO'], [4, 'HERO_THREE']]) {
		const attachments = Array.from({ length: count }, (_, outputIndex) => image(outputIndex))
		const view = module.createImageGalleryPresentation({
			attachments,
			presentationOrder: attachments.map(item => item.outputIndex),
			requestedCount: 10
		})
		assert.equal(view.layout, layout)
		assert.equal(view.visibleItems.length, Math.min(count, 4))
	}

	const attachments = Array.from({ length: 6 }, (_, outputIndex) => image(outputIndex))
	const view = module.createImageGalleryPresentation({
		attachments,
		presentationOrder: [5, 4, 3, 2, 1, 0],
		requestedCount: 6
	})
	assert.equal(view.layout, 'HERO_THREE')
	assert.deepEqual(view.visibleItems.map(item => item.outputIndex), [5, 4, 3, 2])
	assert.equal(view.hiddenCount, 2)
	assert.equal(view.overflowOutputIndex, 2)
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
	assert.deepEqual(view.visibleItems.map(item => item.outputIndex), [4, 8])
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

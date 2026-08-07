const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'ai-conversation-image-generation.js'),
		'utf8'
	)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

test('keeps image models limited to Low Medium and High', async () => {
	const module = await loadModule()
	const model = {
		capabilities: ['IMAGE_GENERATION'],
		supportedImageGenerationLevels: [1, 2, 3],
		supportedImageAspects: ['SQUARE', 'LANDSCAPE', 'PORTRAIT']
	}

	assert.equal(module.modelSupportsImageGeneration(model), true)
	assert.deepEqual(module.imageGenerationProfileLevels(model), [1, 2, 3])
	assert.equal(module.normalizeImageGenerationAspect(model, 'PORTRAIT'), 'PORTRAIT')
	assert.deepEqual(module.imageGenerationRequest(model, 'LANDSCAPE', 1), {
		aspect: 'LANDSCAPE',
		outputCount: 1
	})
})

test('enables multi output only from generation and edit capabilities', async () => {
	const module = await loadModule()
	const dual = {
		capabilities: ['IMAGE_GENERATION', 'IMAGE_EDIT'],
		supportedImageGenerationLevels: [1, 2, 3],
		supportedImageAspects: ['SQUARE']
	}
	const generationOnly = { ...dual, capabilities: ['IMAGE_GENERATION'] }

	assert.equal(module.modelSupportsMultipleImageOutputs(dual), true)
	assert.equal(module.modelSupportsMultipleImageOutputs(generationOnly), false)
	assert.deepEqual(module.imageGenerationRequest(dual, 'SQUARE', 10), {
		aspect: 'SQUARE', outputCount: 10
	})
	assert.deepEqual(module.imageGenerationRequest(generationOnly, 'SQUARE', 10), {
		aspect: 'SQUARE', outputCount: 1
	})
})

test('validates decimal output count boundaries without coercing invalid input', async () => {
	const module = await loadModule()
	for (const value of ['1', '9', '10']) {
		assert.equal(module.parseImageOutputCount(value), Number(value))
	}
	for (const value of ['', '0', '11', '-1', '1.5', 'a', '01']) {
		assert.equal(module.parseImageOutputCount(value), null)
	}
})

test('creates bounded slots and replaces only the matching output index', async () => {
	const module = await loadModule()
	const slots = module.createImageOutputSlots(4)
	const preview = module.imagePreviewAttachment({
		outputIndex: 2,
		partialImageIndex: 1,
		phase: 'PARTIAL',
		contentType: 'image/webp',
		base64: 'YWJj'
	})
	const updated = module.upsertImageOutputAttachment(slots, preview)

	assert.equal(slots.length, 4)
	assert.equal(updated.length, 4)
	assert.equal(updated[0].status, 'QUEUED')
	assert.equal(updated[2].status, 'GENERATING')
	assert.equal(updated[2].partialImageIndex, 1)
	const replayedFailure = module.failImageOutputAttachment([], {
		outputIndex: 3,
		reasonCode: 'AI_UPSTREAM_STREAM_FAILED'
	})
	assert.equal(replayedFailure.length, 1)
	assert.equal(replayedFailure[0].outputIndex, 3)
	assert.equal(replayedFailure[0].status, 'FAILED')
})

test('accepts Google Extra High and filters unsupported fifth image level', async () => {
	const module = await loadModule()
	const model = {
		capabilities: ['IMAGE_GENERATION'],
		supportedImageGenerationLevels: [1, 2, 3, 4, 5],
		supportedImageAspects: ['SQUARE']
	}

	assert.deepEqual(module.imageGenerationProfileLevels(model), [1, 2, 3, 4])
})

test('keeps GPT Image 1.5 limited to its three exposed profiles', async () => {
	const module = await loadModule()
	const model = {
		capabilities: ['RESPONSES', 'IMAGE_GENERATION'],
		supportedImageGenerationLevels: [1, 2, 3],
		supportedImageAspects: ['SQUARE', 'LANDSCAPE', 'PORTRAIT']
	}

	assert.deepEqual(module.imageGenerationProfileLevels(model), [1, 2, 3])
})

test('turns a complete partial image into a volatile browser attachment', async () => {
	const module = await loadModule()
	const attachment = module.imagePreviewAttachment({
		imageId: 'preview-1',
		phase: 'PARTIAL',
		outputIndex: 3,
		partialImageIndex: 1,
		contentType: 'image/webp',
		width: 1280,
		height: 720,
		base64: 'YWJj'
	})

	assert.equal(attachment.attachmentId, 'image-output-3')
	assert.equal(attachment.imageId, 'preview-1')
	assert.equal(attachment.url, 'data:image/webp;base64,YWJj')
	assert.equal(attachment.volatilePreview, true)
	assert.equal(attachment.outputIndex, 3)
	assert.equal(attachment.partialImageIndex, 1)
})

test('replaces matching preview slots with persisted URLs in output order', async () => {
	const module = await loadModule()
	const attachments = [{
		attachmentId: 'final-1',
		fileName: 'generated-3.webp',
		category: 'IMAGE',
		contentType: 'image/webp',
		url: 'https://oss.example.test/final.webp',
		state: 'AVAILABLE'
	}]
	const failed = module.failImageOutputAttachment(
		module.createImageOutputSlots(4),
		{ outputIndex: 1, reasonCode: 'AI_UPSTREAM_STREAM_FAILED' })
		.filter(item => item.status === 'FAILED')
	const persisted = module.persistedImageAttachments({ attachments })
	const merged = module.mergeCompletedImageOutputs(failed, persisted, 4)

	assert.equal(persisted[0].outputIndex, 2)
	assert.equal(persisted[0].status, 'COMPLETED')
	assert.deepEqual(merged.map(item => item.outputIndex), [0, 1, 2, 3])
	assert.equal(merged[0].status, 'FAILED')
	assert.equal(merged[1].status, 'FAILED')
	assert.equal(merged[2].url, 'https://oss.example.test/final.webp')
	assert.equal(merged[3].status, 'FAILED')
	assert.deepEqual(module.persistedImageAttachments({ attachments: null }), [])
})

test('preserves non-image terminal attachments outside image generations', async () => {
	const module = await loadModule()
	const attachment = {
		attachmentId: 'document-1',
		category: 'DOCUMENT',
		contentType: 'application/pdf',
		fileName: 'report.pdf',
		url: 'https://oss.example.test/report.pdf'
	}
	const persisted = module.persistedImageAttachments({ attachments: [attachment] })
	const merged = module.mergeCompletedImageOutputs([], persisted, 0)

	assert.deepEqual(merged, [attachment])
	assert.equal(merged[0].imageSlot, undefined)
	assert.equal(merged[0].outputIndex, undefined)
})

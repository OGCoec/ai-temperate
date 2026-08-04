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

test('keeps GPT Image 2 limited to Low Medium and High', async () => {
	const module = await loadModule()
	const model = {
		capabilities: ['IMAGE_GENERATION'],
		supportedImageGenerationLevels: [1, 2, 3],
		supportedImageAspects: ['SQUARE', 'LANDSCAPE', 'PORTRAIT']
	}

	assert.equal(module.modelSupportsImageGeneration(model), true)
	assert.deepEqual(module.imageGenerationProfileLevels(model), [1, 2, 3])
	assert.equal(module.normalizeImageGenerationAspect(model, 'PORTRAIT'), 'PORTRAIT')
	assert.deepEqual(module.imageGenerationRequest(model, 'LANDSCAPE'), {
		aspect: 'LANDSCAPE'
	})
})

test('filters stale Extra High and Ultra image levels', async () => {
	const module = await loadModule()
	const model = {
		capabilities: ['IMAGE_GENERATION'],
		supportedImageGenerationLevels: [1, 2, 3, 4, 5],
		supportedImageAspects: ['SQUARE']
	}

	assert.deepEqual(module.imageGenerationProfileLevels(model), [1, 2, 3])
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
		index: 1,
		contentType: 'image/webp',
		width: 1280,
		height: 720,
		base64: 'YWJj'
	})

	assert.equal(attachment.attachmentId, 'preview-1')
	assert.equal(attachment.url, 'data:image/webp;base64,YWJj')
	assert.equal(attachment.volatilePreview, true)
})

test('uses only persisted URL attachments from the terminal event', async () => {
	const module = await loadModule()
	const attachments = [{
		attachmentId: 'final-1',
		url: 'https://oss.example.test/final.webp',
		state: 'AVAILABLE'
	}]

	assert.deepEqual(module.persistedImageAttachments({ attachments }), attachments)
	assert.deepEqual(module.persistedImageAttachments({ attachments: null }), [])
})

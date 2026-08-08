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
		previewKind: 'THUMBNAIL',
		requiresUpgrade: true,
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
		previewKind: 'THUMBNAIL',
		requiresUpgrade: true,
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
	assert.equal(attachment.previewKind, 'THUMBNAIL')
	assert.equal(attachment.requiresUpgrade, true)
})

test('keeps a small full preview visible after OSS persistence without reloading it', async () => {
	const module = await loadModule()
	const preview = module.imagePreviewAttachment({
		imageId: 'small-final',
		phase: 'FINAL',
		outputIndex: 0,
		partialImageIndex: null,
		contentType: 'image/png',
		previewKind: 'FULL',
		requiresUpgrade: false,
		width: 512,
		height: 512,
		base64: 'YWJj'
	})
	const persisted = module.persistedImageAttachments({ attachments: [{
		attachmentId: 'stored-small',
		fileName: 'generated-1.png',
		category: 'IMAGE',
		contentType: 'image/png',
		url: 'https://oss.example.test/small.png',
		state: 'AVAILABLE'
	}] })

	const [merged] = module.mergeCompletedImageOutputs([preview], persisted, 1)

	assert.equal(merged.url, 'data:image/png;base64,YWJj')
	assert.equal(merged.persistedUrl, 'https://oss.example.test/small.png')
	assert.equal(merged.requiresUpgrade, false)
	assert.equal(merged.status, 'COMPLETED')
})

test('keeps a large thumbnail until its persisted original is preloaded', async () => {
	const module = await loadModule()
	const preview = module.imagePreviewAttachment({
		imageId: 'large-final',
		phase: 'FINAL',
		outputIndex: 0,
		partialImageIndex: null,
		contentType: 'image/jpeg',
		previewKind: 'THUMBNAIL',
		requiresUpgrade: true,
		width: 768,
		height: 512,
		base64: 'REVG'
	})
	const persisted = module.persistedImageAttachments({ attachments: [{
		attachmentId: 'stored-large',
		fileName: 'generated-1.png',
		category: 'IMAGE',
		contentType: 'image/png',
		url: 'https://oss.example.test/large.png',
		state: 'AVAILABLE'
	}] })

	const [merged] = module.mergeCompletedImageOutputs([preview], persisted, 1)

	assert.equal(merged.url, 'data:image/jpeg;base64,REVG')
	assert.equal(merged.persistedUrl, 'https://oss.example.test/large.png')
	assert.equal(merged.requiresUpgrade, true)
	assert.equal(merged.status, 'UPGRADING')
})

test('merges an early persisted event into a small preview without changing its source', async () => {
	const module = await loadModule()
	const preview = module.imagePreviewAttachment({
		imageId: 'small-final', phase: 'FINAL', outputIndex: 0,
		partialImageIndex: null, contentType: 'image/png',
		previewKind: 'FULL', requiresUpgrade: false,
		width: 512, height: 512, base64: 'YWJj'
	})
	const persisted = module.persistedImageOutputAttachment({
		outputIndex: 0,
		attachment: {
			schemaVersion: 1,
			attachmentId: 'stored-small', fileName: 'generated-1.png',
			category: 'IMAGE', contentType: 'image/png', sizeBytes: '3',
			url: 'https://oss.example.test/small.png', state: 'AVAILABLE'
		}
	})

	const [merged] = module.mergePersistedImageOutput([preview], persisted)

	assert.equal(merged.url, 'data:image/png;base64,YWJj')
	assert.equal(merged.persistedUrl, 'https://oss.example.test/small.png')
	assert.equal(merged.status, 'COMPLETED')
})

test('merges an early persisted event into a large thumbnail for one-time upgrade', async () => {
	const module = await loadModule()
	const preview = module.imagePreviewAttachment({
		imageId: 'large-final', phase: 'FINAL', outputIndex: 2,
		partialImageIndex: null, contentType: 'image/jpeg',
		previewKind: 'THUMBNAIL', requiresUpgrade: true,
		width: 768, height: 512, base64: 'REVG'
	})
	const persisted = module.persistedImageOutputAttachment({
		outputIndex: 2,
		attachment: {
			schemaVersion: 1,
			attachmentId: 'stored-large', fileName: 'generated-3.png',
			category: 'IMAGE', contentType: 'image/png', sizeBytes: '999',
			url: 'https://oss.example.test/large.png', state: 'AVAILABLE'
		}
	})

	const [merged] = module.mergePersistedImageOutput([preview], persisted)

	assert.equal(merged.url, 'data:image/jpeg;base64,REVG')
	assert.equal(merged.persistedUrl, 'https://oss.example.test/large.png')
	assert.equal(merged.status, 'UPGRADING')
	assert.equal(merged.requiresUpgrade, true)
})

test('rejects unsafe early persisted image URLs', async () => {
	const module = await loadModule()

	assert.equal(module.persistedImageOutputAttachment({
		outputIndex: 0,
		attachment: {
			schemaVersion: 1,
			attachmentId: 'unsafe', fileName: 'generated-1.svg',
			category: 'IMAGE', contentType: 'image/svg+xml', sizeBytes: '1',
			url: 'javascript:alert(1)', state: 'AVAILABLE'
		}
	}), null)
})

test('does not let a late preview replace an already persisted slot', async () => {
	const module = await loadModule()
	const persisted = {
		attachmentId: 'stored', fileName: 'generated-1.png',
		category: 'IMAGE', contentType: 'image/png', sizeBytes: '3',
		url: 'https://oss.example.test/final.png', state: 'AVAILABLE',
		outputIndex: 0, phase: 'FINAL', status: 'COMPLETED',
		volatilePreview: false, imageSlot: true
	}
	const preview = module.imagePreviewAttachment({
		phase: 'FINAL', outputIndex: 0, partialImageIndex: null,
		contentType: 'image/png', previewKind: 'FULL',
		requiresUpgrade: false, width: 10, height: 10, base64: 'YWJj'
	})

	const [merged] = module.mergeImagePreviewOutput([persisted], preview)

	assert.equal(merged.url, 'https://oss.example.test/final.png')
	assert.equal(merged.volatilePreview, false)
})

test('shows an early persisted image directly when no base64 preview exists', async () => {
	const module = await loadModule()
	const persisted = module.persistedImageOutputAttachment({
		outputIndex: 4,
		attachment: {
			schemaVersion: 1,
			attachmentId: 'stored-direct', fileName: 'generated-5.webp',
			category: 'IMAGE', contentType: 'image/webp', sizeBytes: '42',
			url: 'https://oss.example.test/direct.webp', state: 'AVAILABLE'
		}
	})

	const [merged] = module.mergePersistedImageOutput([], persisted)

	assert.equal(merged.outputIndex, 4)
	assert.equal(merged.url, 'https://oss.example.test/direct.webp')
	assert.equal(merged.status, 'COMPLETED')
	assert.equal(merged.volatilePreview, false)
})

test('does not restart a finished or failed large-image upgrade for duplicate evidence', async () => {
	const module = await loadModule()
	const persisted = module.persistedImageOutputAttachment({
		outputIndex: 0,
		attachment: {
			schemaVersion: 1,
			attachmentId: 'stored-large', fileName: 'generated-1.png',
			category: 'IMAGE', contentType: 'image/png', sizeBytes: '999',
			url: 'https://oss.example.test/large.png', state: 'AVAILABLE'
		}
	})
	const upgraded = {
		...persisted,
		persistedUrl: persisted.url,
		requiresUpgrade: false,
		upgradeFailed: false
	}
	const failed = {
		...persisted,
		url: 'data:image/jpeg;base64,REVG',
		persistedUrl: persisted.url,
		requiresUpgrade: true,
		upgradeFailed: true
	}

	assert.deepEqual(module.mergePersistedImageOutput([upgraded], persisted), [upgraded])
	assert.deepEqual(module.mergePersistedImageOutput([failed], persisted), [failed])
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

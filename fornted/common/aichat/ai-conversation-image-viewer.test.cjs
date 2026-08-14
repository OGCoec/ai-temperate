const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'ai-conversation-image-viewer.js'),
		'utf8'
	)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function attachment(outputIndex, overrides = {}) {
	return {
		attachmentId: `image-${outputIndex}`,
		outputIndex,
		contentType: 'image/webp',
		state: 'AVAILABLE',
		status: 'FINAL',
		phase: 'FINAL',
		imageSlot: true,
		url: `https://media.example.test/generated-${outputIndex}.webp`,
		...overrides
	}
}

function message(messagePublicId, minute, count, overrides = {}) {
	return {
		messagePublicId,
		localId: '',
		createdAt: `2026-08-14T12:${String(minute).padStart(2, '0')}:00Z`,
		responseAttachments: Array.from({ length: count }, (_, outputIndex) =>
			attachment(outputIndex)),
		...overrides
	}
}

test('aggregates every generated image in the loaded conversation', async () => {
	const module = await loadModule()
	const items = module.conversationGeneratedImages([
		message('message-1', 0, 10),
		message('message-2', 1, 10)
	])

	assert.equal(items.length, 20)
	assert.equal(items[0].identity, 'message-1:0')
	assert.equal(items[9].identity, 'message-1:9')
	assert.equal(items[10].identity, 'message-2:0')
	assert.equal(items[19].identity, 'message-2:9')
})

test('sorts messages chronologically and images by outputIndex', async () => {
	const module = await loadModule()
	const older = message('older', 0, 3, {
		responseAttachments: [attachment(2), attachment(0), attachment(1)]
	})
	const newer = message('newer', 10, 2)
	const items = module.conversationGeneratedImages([newer, older])

	assert.deepEqual(items.map(item => item.identity), [
		'older:0', 'older:1', 'older:2', 'newer:0', 'newer:1'
	])
})

test('deduplicates preview and final evidence by stable image identity', async () => {
	const module = await loadModule()
	const itemMessage = message('message-1', 0, 1, {
		responseAttachments: [
			attachment(0, {
				attachmentId: 'preview',
				phase: 'PARTIAL',
				status: 'GENERATING',
				url: 'data:image/webp;base64,cHJldmlldw=='
			}),
			attachment(0, {
				attachmentId: 'final',
				persistedUrl: 'https://media.example.test/final.webp'
			})
		]
	})
	const items = module.conversationGeneratedImages([itemMessage])

	assert.equal(items.length, 1)
	assert.equal(items[0].attachment.attachmentId, 'final')
	assert.equal(items[0].displaySrc, 'https://media.example.test/final.webp')
})

test('filters failed, svg, empty and non-generated attachments', async () => {
	const module = await loadModule()
	const items = module.conversationGeneratedImages([message('message-1', 0, 5, {
		responseAttachments: [
			attachment(0),
			attachment(1, { status: 'FAILED' }),
			attachment(2, { contentType: 'image/svg+xml' }),
			attachment(3, { url: '', persistedUrl: '' }),
			attachment(4, { imageSlot: false })
		]
	})])

	assert.deepEqual(items.map(item => item.identity), ['message-1:0'])
})

test('keeps the active image after older messages are prepended', async () => {
	const module = await loadModule()
	const current = module.conversationGeneratedImages([message('newer', 10, 2)])
	const activeIdentity = current[1].identity
	const merged = module.mergeConversationGeneratedImages(
		current,
		[message('older', 0, 10)]
	)

	assert.equal(merged.length, 12)
	assert.equal(
		merged[module.activeGeneratedImageIndex(merged, activeIdentity)].identity,
		activeIdentity
	)
})

test('migrates the active identity from localId to messagePublicId after persistence', async () => {
	const module = await loadModule()
	const localItems = module.conversationGeneratedImages([{
		...message('', 0, 2),
		messagePublicId: '',
		localId: 'local-generation'
	}])
	const persistedItems = module.conversationGeneratedImages([{
		...message('saved-message', 0, 2),
		localId: 'local-generation'
	}])
	const previous = localItems[1]

	assert.equal(module.reconcileGeneratedImageIdentity(
		persistedItems,
		previous.identity,
		previous
	), 'saved-message:1')
})

test('returns only the active image and its nearest neighbours', async () => {
	const module = await loadModule()
	const items = module.conversationGeneratedImages([message('message-1', 0, 6)])
	const adjacent = module.adjacentGeneratedImageItems(items, 'message-1:3', 1)

	assert.deepEqual(adjacent.map(item => item.identity), [
		'message-1:2', 'message-1:3', 'message-1:4'
	])
})

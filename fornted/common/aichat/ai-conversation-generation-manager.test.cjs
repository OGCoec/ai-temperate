const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadManager() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'ai-conversation-generation-manager.js'),
		'utf8'
	)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

test('keeps generations from different conversations active at the same time', async () => {
	const manager = await loadManager()
	manager.clearGenerationManager()
	manager.registerGeneration({ generationPublicId: 'generation-a', conversationPublicId: 'conversation-a' })
	manager.registerGeneration({ generationPublicId: 'generation-b', conversationPublicId: 'conversation-b' })

	assert.deepEqual(
		manager.listActiveGenerations().map(item => item.generationPublicId),
		['generation-a', 'generation-b']
	)
})

test('detaching UI never changes a generation into cancelled', async () => {
	const manager = await loadManager()
	manager.clearGenerationManager()
	manager.registerGeneration({ generationPublicId: 'generation-a', conversationPublicId: 'conversation-a' })

	manager.detachGenerationObserver('generation-a')

	assert.equal(manager.getGeneration('generation-a').status, 'RUNNING')
	assert.equal(manager.getGeneration('generation-a').observerAttached, false)
})

test('persists idempotency key until a terminal state is observed', async () => {
	const manager = await loadManager()
	manager.clearGenerationManager()
	manager.registerGeneration({
		generationPublicId: 'generation-a',
		conversationPublicId: 'conversation-a',
		idempotencyKey: 'c0277ffb-45bb-4c1f-9874-9b415f8f4a10'
	})

	assert.equal(manager.getGeneration('generation-a').idempotencyKey.length, 36)
	manager.markGenerationTerminal('generation-a', 'COMPLETED')
	assert.equal(manager.getGeneration('generation-a').idempotencyKey, null)
})

test('retains a pending idempotency key before headers and removes it after generation binding', async () => {
	const manager = await loadManager()
	manager.clearGenerationManager()
	const idempotencyKey = 'ce4878a8-f5c2-4325-b8df-eb499cb17c65'
	manager.registerPendingGeneration({ idempotencyKey, localId: 'local-a' })

	assert.equal(manager.listPendingGenerationRequests().length, 1)
	manager.registerGeneration({ generationPublicId: 'generation-a', idempotencyKey })
	assert.equal(manager.listPendingGenerationRequests().length, 0)
})

test('keeps image previews in page memory without persisting Base64 to session storage', async () => {
	const values = new Map()
	globalThis.sessionStorage = {
		getItem: key => values.get(key) || null,
		setItem: (key, value) => values.set(key, value)
	}
	const manager = await loadManager()
	manager.clearGenerationManager()
	manager.registerGeneration({ generationPublicId: 'generation-image' })
	manager.updateGeneration('generation-image', {
		previewImages: [
			{ outputIndex: 0, url: 'data:image/webp;base64,YWJj', volatilePreview: true },
			{ outputIndex: 1, url: 'data:image/webp;base64,REVG', volatilePreview: true }
		]
	})

	assert.equal(manager.getGeneration('generation-image').previewImages.length, 2)
	assert.equal([...values.values()].some(value => value.includes('YWJj') || value.includes('REVG')), false)
	delete globalThis.sessionStorage
})

test('clears terminal Base64 previews while preserving canonical OSS attachments', async () => {
	const manager = await loadManager()
	manager.clearGenerationManager()
	manager.registerGeneration({ generationPublicId: 'generation-image-terminal' })
	manager.updateGeneration('generation-image-terminal', {
		imagePresentationOrder: [3, 0],
		previewImages: [{
			outputIndex: 0,
			url: 'data:image/png;base64,YWJj',
			persistedUrl: 'https://oss.example.test/image.png'
		}],
		responseAttachments: [{
			outputIndex: 0,
			url: 'https://oss.example.test/image.png'
		}]
	})

	manager.markGenerationTerminal('generation-image-terminal', 'COMPLETED')

	const terminal = manager.getGeneration('generation-image-terminal')
	assert.deepEqual(terminal.previewImages, [])
	assert.deepEqual(terminal.imagePresentationOrder, [3, 0])
	assert.equal(
		terminal.responseAttachments[0].url,
		'https://oss.example.test/image.png'
	)
})

test('persists research sources and restores them for the completed message', async () => {
	const values = new Map()
	globalThis.sessionStorage = {
		getItem: key => values.get(key) || null,
		setItem: (key, value) => values.set(key, value)
	}
	const manager = await loadManager()
	manager.clearGenerationManager()
	manager.registerGeneration({
		generationPublicId: 'generation-research',
		conversationPublicId: 'conversation-research',
		messagePublicId: 'message-research'
	})
	const notifications = []
	const unsubscribe = manager.subscribeGeneration(
		'generation-research',
		task => notifications.push(task?.researchSources || [])
	)
	manager.updateGeneration('generation-research', {
		researchSources: [{
			sequence: 2,
			title: 'Documentation',
			url: 'https://example.com/docs',
			role: 'CONSULTED'
		}]
	})
	manager.markGenerationTerminal('generation-research', 'COMPLETED')
	unsubscribe()

	assert.equal(notifications.at(-1).length, 1)
	const restoredManager = await loadManager()
	assert.equal(
		restoredManager.findGenerationResearchSources({
			conversationPublicId: 'conversation-research',
			messagePublicId: 'message-research'
		}).length,
		1
	)
	assert.deepEqual(
		restoredManager.findGenerationResearchSources({
			conversationPublicId: 'different-conversation',
			messagePublicId: 'message-research'
		}),
		[]
	)
	restoredManager.clearGenerationManager()
	assert.deepEqual(
		restoredManager.findGenerationResearchSources({
			conversationPublicId: 'conversation-research',
			messagePublicId: 'message-research'
		}),
		[]
	)
	delete globalThis.sessionStorage
})

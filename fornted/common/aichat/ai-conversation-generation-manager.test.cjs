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
		previewImage: { url: 'data:image/webp;base64,YWJj', volatilePreview: true }
	})

	assert.equal(manager.getGeneration('generation-image').previewImage.volatilePreview, true)
	assert.equal([...values.values()].some(value => value.includes('YWJj')), false)
	delete globalThis.sessionStorage
})

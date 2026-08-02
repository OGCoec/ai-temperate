const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

let moduleSequence = 0

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'ai-model-catalog-store.js'),
		'utf8'
	)
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}#${++moduleSequence}`)
}

function page(models, pageNum, hasNext) {
	return { models, total: models.length, pageNum, hasNext }
}

test('catalog store keeps the first page in memory and shares an in-flight initial request', async () => {
	const store = await loadModule()
	let resolveRequest
	let calls = 0
	const loader = () => {
		calls += 1
		return new Promise(resolve => { resolveRequest = resolve })
	}

	const first = store.refreshAiModelCatalog(loader)
	const second = store.refreshAiModelCatalog(loader)
	assert.equal(calls, 1)
	assert.equal(store.readAiModelCatalog().initialLoading, true)

	resolveRequest(page([{ publicId: 'AAABi0VWeJ8' }], 1, false))
	await Promise.all([first, second])

	const snapshot = store.readAiModelCatalog()
	assert.equal(snapshot.hasLoaded, true)
	assert.equal(snapshot.models.length, 1)
	assert.equal(snapshot.initialLoading, false)
})

test('clearing the catalog prevents an old in-flight response from restoring a previous session snapshot', async () => {
	const store = await loadModule()
	let resolveRequest
	const request = store.refreshAiModelCatalog(
		() => new Promise(resolve => { resolveRequest = resolve })
	)

	store.clearAiModelCatalog()
	resolveRequest(page([{ publicId: 'AAABi0VWeJ8' }], 1, false))
	await request

	assert.deepEqual(store.readAiModelCatalog().models, [])
})

test('changing the applied keyword resets pagination and ignores the old request', async () => {
	const store = await loadModule()
	let resolveRequest
	const request = store.refreshAiModelCatalog(
		() => new Promise(resolve => { resolveRequest = resolve })
	)

	const changed = store.setAiModelCatalogKeyword('  Mini  ')
	assert.equal(changed.activeKeyword, 'Mini')
	assert.equal(changed.pageNum, 0)
	assert.deepEqual(changed.models, [])

	resolveRequest(page([{ publicId: 'AAABi0VWeJ8' }], 1, false))
	await request

	const snapshot = store.readAiModelCatalog()
	assert.equal(snapshot.activeKeyword, 'Mini')
	assert.deepEqual(snapshot.models, [])
})

test('clearing the catalog also clears the applied keyword', async () => {
	const store = await loadModule()
	store.setAiModelCatalogKeyword('mini')

	const cleared = store.clearAiModelCatalog()

	assert.equal(cleared.activeKeyword, '')
})

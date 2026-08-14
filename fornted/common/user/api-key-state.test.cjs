const assert = require('node:assert/strict')
const path = require('node:path')
const { pathToFileURL } = require('node:url')
const test = require('node:test')

async function loadModule() {
	const url = pathToFileURL(path.resolve(__dirname, 'api-key-state.js'))
	url.searchParams.set('test', `${Date.now()}-${Math.random()}`)
	return import(url.href)
}

test('derives expired disabled and enabled presentation in the required priority', async () => {
	const { apiKeyStatusPresentation } = await loadModule()

	assert.equal(apiKeyStatusPresentation({ expired: true, status: 'ENABLED' }).label, '已过期')
	assert.equal(apiKeyStatusPresentation({ expired: false, status: 'DISABLED' }).label, '已停用')
	assert.equal(apiKeyStatusPresentation({ expired: false, status: 'ENABLED' }).label, '已启用')
	assert.throws(
		() => apiKeyStatusPresentation({ expired: false, status: 'DELETED' }),
		error => error.code === 'API_KEY_RESPONSE_INVALID')
})

test('merges cursor pages by public ID without duplicating existing items', async () => {
	const { mergeApiKeyPageItems } = await loadModule()
	const first = [{ id: 'AAAAAAAAAAE', maskedKey: 'sk-…aaaa' }]
	const merged = mergeApiKeyPageItems(first, [
		{ id: 'AAAAAAAAAAE', maskedKey: 'sk-…aaaa' },
		{ id: 'AAAAAAAAAAI', maskedKey: 'sk-…bbbb' }
	])

	assert.deepEqual(merged.map(item => item.id), ['AAAAAAAAAAE', 'AAAAAAAAAAI'])
	assert.equal(Object.isFrozen(merged), true)
})

test('removes the one-time secret before a created key enters list state', async () => {
	const { summaryFromCreatedKey } = await loadModule()
	const summary = summaryFromCreatedKey({
		id: 'AAAAAAAAAAE',
		maskedKey: 'sk-…aaaa',
		status: 'ENABLED',
		expiresAt: null,
		expired: false,
		lastUsedAt: null,
		createdAt: '2026-08-14T00:00:00Z',
		updatedAt: '2026-08-14T00:00:00Z',
		rowVersion: 0,
		models: [],
		apiKey: `sk-${'A'.repeat(86)}`
	})

	assert.equal(Object.hasOwn(summary, 'apiKey'), false)
	assert.equal(Object.hasOwn(summary, 'models'), false)
	assert.equal(Object.isFrozen(summary), true)
})

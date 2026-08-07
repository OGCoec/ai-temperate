const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadModule() {
	const source = fs.readFileSync(path.resolve(
		__dirname, 'ai-conversation-context-usage.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}#${Math.random()}`)
}

test('model switch uses an inclusive 80 percent threshold', async () => {
	const module = await loadModule()
	const model = {
		publicId: 'AAAAAAAAAAE',
		contextWindowTokens: 1000000,
		maxOutputTokens: 128000
	}
	const base = { thresholdPercent: 80 }

	assert.equal(module.recalculateAiConversationContextUsage({
		...base, estimatedContextTokens: 799000
	}, model).thresholdReached, false)
	assert.equal(module.recalculateAiConversationContextUsage({
		...base, estimatedContextTokens: 800000
	}, model).thresholdReached, true)
})

test('formats every context token value in K and keeps percentages concise', async () => {
	const module = await loadModule()
	assert.equal(module.formatAiConversationContextTokens(0), '0K')
	assert.equal(module.formatAiConversationContextTokens(500), '0.5K')
	assert.equal(module.formatAiConversationContextTokens(3000), '3K')
	assert.equal(module.formatAiConversationContextTokens(200000), '200K')
	assert.equal(module.formatAiConversationContextTokens(1000000), '1000K')
	assert.equal(module.formatAiConversationContextTokens(1100000), '1100K')
	assert.equal(module.formatAiConversationContextPercent(20), '20')
	assert.equal(module.formatAiConversationContextPercent(20.04), '20')
	assert.equal(module.formatAiConversationContextPercent(20.06), '20.1')
})

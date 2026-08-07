const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function loadModule() {
	const source = fs.readFileSync(path.join(__dirname,
		'ai-conversation-model-levels.js'), 'utf8')
		.replaceAll('export function ', 'function ')
	return new Function(`${source}; return { aiConversationModelLevelOptions }`)()
}

test('provider-specific text labels preserve Anthropic five levels and Google four', () => {
	const { aiConversationModelLevelOptions } = loadModule()
	assert.deepEqual(aiConversationModelLevelOptions({
		vendor: 'anthropic', supportedReasoningEffortLevels: [1, 2, 3, 4, 5]
	}).map(option => option.label), ['Low', 'Medium', 'High', 'XHigh', 'Max'])
	assert.deepEqual(aiConversationModelLevelOptions({
		vendor: 'google', supportedReasoningEffortLevels: [1, 2, 3, 4]
	}).map(option => option.label), ['Minimal', 'Low', 'Medium', 'High'])
})

test('Google image labels expose 0.5K through 4K without changing request values', () => {
	const { aiConversationModelLevelOptions } = loadModule()
	const options = aiConversationModelLevelOptions({
		vendor: 'google', supportedImageGenerationLevels: [1, 2, 3, 4]
	}, true)
	assert.deepEqual(options.map(option => option.value), [1, 2, 3, 4])
	assert.deepEqual(options.map(option => option.label), ['0.5K', '1K', '2K', '4K'])
})

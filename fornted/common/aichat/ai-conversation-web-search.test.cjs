const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function loadModule(enabled) {
	const source = fs.readFileSync(path.join(__dirname,
		'ai-conversation-web-search.js'), 'utf8')
		.replaceAll('export const ', 'const ')
		.replaceAll('export function ', 'function ')
	const factory = new Function('__AI_CONVERSATION_WEB_SEARCH_ENABLED__',
		`${source}; return { aiConversationWebSearchEnabled,
		modelSupportsAiConversationWebSearch,
		normalizeAiConversationWebSearchMode,
		defaultAiConversationWebSearchPreference }`)
	return factory(enabled)
}

test('web search requires the feature flag plus RESPONSES and WEB_SEARCH', () => {
	const enabled = loadModule(true)
	const disabled = loadModule(false)
	const capable = { capabilities: ['CHAT_COMPLETIONS', 'RESPONSES', 'WEB_SEARCH'] }

	assert.equal(enabled.modelSupportsAiConversationWebSearch(capable), true)
	assert.equal(enabled.modelSupportsAiConversationWebSearch({
		capabilities: ['RESPONSES']
	}), false)
	assert.equal(enabled.modelSupportsAiConversationWebSearch({
		capabilities: ['RESPONSES', 'WEB_SEARCH', 'IMAGE_GENERATION']
	}), false)
	assert.equal(disabled.modelSupportsAiConversationWebSearch(capable), false)
	assert.equal(enabled.normalizeAiConversationWebSearchMode('required', capable),
		'REQUIRED')
	assert.equal(enabled.normalizeAiConversationWebSearchMode('AUTO', {
		capabilities: ['CHAT_COMPLETIONS']
	}), 'OFF')
	assert.equal(enabled.normalizeAiConversationWebSearchMode('REQUIRED', {
		capabilities: ['RESPONSES', 'WEB_SEARCH', 'IMAGE_GENERATION']
	}), 'OFF')
})

test('Android defaults to AUTO while unsupported media models only force the effective mode off', () => {
	const enabled = loadModule(true)
	const capable = { capabilities: ['CHAT_COMPLETIONS', 'RESPONSES', 'WEB_SEARCH'] }
	const media = { capabilities: ['RESPONSES', 'WEB_SEARCH', 'IMAGE_GENERATION'] }

	const preference = enabled.defaultAiConversationWebSearchPreference('ANDROID')
	assert.equal(preference, 'AUTO')
	assert.equal(enabled.defaultAiConversationWebSearchPreference('H5'), 'OFF')
	assert.equal(enabled.normalizeAiConversationWebSearchMode(preference, media), 'OFF')
	assert.equal(enabled.normalizeAiConversationWebSearchMode(preference, capable), 'AUTO')
})

test('ordinary frontend builds expose web search unless explicitly disabled', () => {
	const vite = fs.readFileSync(path.join(__dirname, '..', '..', 'vite.config.js'),
		'utf8')

	assert.match(vite,
		/process\.env\.AI_CONVERSATION_WEB_SEARCH_ENABLED !== 'false'/)
})

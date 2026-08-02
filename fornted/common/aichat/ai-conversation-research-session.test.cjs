const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function loadModule() {
	const source = fs.readFileSync(path.join(__dirname,
		'ai-conversation-research-session.js'), 'utf8')
		.replaceAll('export const ', 'const ')
		.replaceAll('export function ', 'function ')
	const factory = new Function(`${source}; return {
		createAiConversationResearchSession,
		findAiConversationResearchSession,
		clearAiConversationResearchSessions }`)
	return factory()
}

test('research session persists ordered safe events and rejects unsafe sources', () => {
	const values = new Map()
	globalThis.sessionStorage = {
		getItem(key) { return values.get(key) || null },
		setItem(key, value) { values.set(key, value) },
		removeItem(key) { values.delete(key) }
	}
	const module = loadModule()
	const session = module.createAiConversationResearchSession({
		localId: 'local-1',
		idempotencyKey: '123e4567-e89b-42d3-a456-426614174000',
		webSearchMode: 'REQUIRED'
	})

	assert.equal(session.appendActivity({
		sequence: 1, activityId: 'search-1', phase: 'WEB_SEARCH',
		status: 'IN_PROGRESS', query: 'safe', occurredAt: 'now'
	}), true)
	assert.equal(session.appendSource({
		sequence: 2, activityId: 'search-1', sourceId: 'source-1',
		title: '<script>', url: 'javascript:alert(1)', domain: 'bad',
		role: 'CONSULTED', occurredAt: 'now'
	}), false)
	assert.equal(session.appendSource({
		sequence: 3, activityId: 'search-1', sourceId: 'source-2',
		title: '<script>', url: 'https://openai.com/docs', domain: 'openai.com',
		role: 'CONSULTED', occurredAt: 'now'
	}), true)
	session.markTerminal('COMPLETED')

	const restored = module.findAiConversationResearchSession({ localId: 'local-1' })
	assert.equal(restored.activities.length, 1)
	assert.equal(restored.sources.length, 1)
	assert.equal(restored.sources[0].url, 'https://openai.com/docs')
	assert.equal(restored.terminalState, 'COMPLETED')
	module.clearAiConversationResearchSessions()
	delete globalThis.sessionStorage
})

test('corrupt storage is ignored without breaking a new session', () => {
	globalThis.sessionStorage = {
		getItem() { return '{broken' }, setItem() {}, removeItem() {}
	}
	const module = loadModule()
	assert.equal(module.findAiConversationResearchSession({
		conversationPublicId: 'conversation'
	}), null)
	delete globalThis.sessionStorage
})

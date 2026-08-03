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
		conversationPublicId: 'conversation-public-id',
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
	session.bindMessage('message-public-id')
	session.markTerminal('COMPLETED')

	const restored = module.findAiConversationResearchSession({
		messagePublicId: 'message-public-id'
	})
	assert.equal(restored.activities.length, 1)
	assert.equal(restored.sources.length, 1)
	assert.equal(restored.sources[0].url, 'https://openai.com/docs')
	assert.equal(restored.messagePublicId, 'message-public-id')
	assert.equal(restored.terminalState, 'COMPLETED')
	assert.equal(module.findAiConversationResearchSession({
		messagePublicId: 'different-message'
	}), null)
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

test('activity idempotency rejects exact duplicates and preserves exact status history', () => {
	const values = new Map()
	globalThis.sessionStorage = {
		getItem(key) { return values.get(key) || null },
		setItem(key, value) { values.set(key, value) },
		removeItem(key) { values.delete(key) }
	}
	const module = loadModule()
	const session = module.createAiConversationResearchSession({
		localId: 'local-activity-idempotency',
		idempotencyKey: '123e4567-e89b-42d3-a456-426614174001',
		webSearchMode: 'REQUIRED'
	})

	assert.equal(session.appendActivity({
		sequence: 1, eventId: `act_v1_${'a'.repeat(43)}`,
		activityId: 'search-1', phase: 'WEB_SEARCH', status: 'STARTED',
		query: 'Java Thread', occurredAt: '2026-08-03T00:00:00Z'
	}), true)
	assert.equal(session.appendActivity({
		sequence: 2, eventId: `act_v1_${'a'.repeat(43)}`,
		activityId: 'search-1', phase: 'WEB_SEARCH', status: 'STARTED',
		query: 'Java Thread', occurredAt: '2026-08-03T00:00:01Z'
	}), false)
	assert.equal(session.appendActivity({
		sequence: 3, eventId: `act_v1_${'b'.repeat(43)}`,
		activityId: 'search-1', phase: 'WEB_SEARCH', status: 'IN_PROGRESS',
		query: 'Java Thread', occurredAt: '2026-08-03T00:00:02Z'
	}), true)
	assert.equal(session.appendActivity({
		sequence: 4, eventId: `act_v1_${'c'.repeat(43)}`,
		activityId: 'search-1', phase: 'WEB_SEARCH', status: 'COMPLETED',
		query: 'Java Thread', occurredAt: '2026-08-03T00:00:03Z'
	}), true)
	assert.equal(session.appendActivity({
		sequence: 5, eventId: `act_v1_${'d'.repeat(43)}`,
		activityId: 'search-1', phase: 'WEB_SEARCH', status: 'IN_PROGRESS',
		query: 'Java Virtual Thread', occurredAt: '2026-08-03T00:00:04Z'
	}), true)

	const snapshot = session.snapshot()
	assert.deepEqual(snapshot.activities.map(item => item.status), [
		'STARTED', 'IN_PROGRESS', 'COMPLETED', 'IN_PROGRESS'
	])
	assert.equal(snapshot.activities.at(-1).query, 'Java Virtual Thread')
	session.close()
	module.clearAiConversationResearchSessions()
	delete globalThis.sessionStorage
})

test('activity idempotency derives a compatibility key when eventId is absent', () => {
	globalThis.sessionStorage = {
		getItem() { return null }, setItem() {}, removeItem() {}
	}
	const module = loadModule()
	const session = module.createAiConversationResearchSession({
		localId: 'local-legacy-server',
		idempotencyKey: '123e4567-e89b-42d3-a456-426614174002',
		webSearchMode: 'REQUIRED'
	})
	const value = {
		activityId: 'search-legacy', phase: 'WEB_SEARCH',
		status: 'IN_PROGRESS', query: 'same-query'
	}

	assert.equal(session.appendActivity({
		...value, sequence: 1, occurredAt: 'first'
	}), true)
	assert.equal(session.appendActivity({
		...value, sequence: 2, occurredAt: 'second'
	}), false)
	assert.equal(session.snapshot().activities.length, 1)
	session.close()
	module.clearAiConversationResearchSessions()
	delete globalThis.sessionStorage
})

test('schema v1 research storage migrates and removes only exact activity duplicates', () => {
	const storageKey = 'ait.user.ai.research.v1'
	const idempotencyKey = '123e4567-e89b-42d3-a456-426614174003'
	const values = new Map([[storageKey, JSON.stringify([{
		schemaVersion: 1,
		conversationPublicId: 'conversation-public-id',
		messagePublicId: 'message-public-id',
		localId: 'local-v1',
		idempotencyKey,
		webSearchMode: 'REQUIRED',
		activities: [
			{ sequence: 1, activityId: 'search-1', phase: 'WEB_SEARCH', status: 'STARTED', query: 'query', occurredAt: 'first' },
			{ sequence: 2, activityId: 'search-1', phase: 'WEB_SEARCH', status: 'STARTED', query: 'query', occurredAt: 'second' },
			{ sequence: 3, activityId: 'search-1', phase: 'WEB_SEARCH', status: 'IN_PROGRESS', query: 'query', occurredAt: 'third' }
		],
		sources: [], reasoningSummaries: [], updatedAt: 'now', terminalState: 'ACTIVE'
	}])]])
	globalThis.sessionStorage = {
		getItem(key) { return values.get(key) || null },
		setItem(key, value) { values.set(key, value) },
		removeItem(key) { values.delete(key) }
	}
	const module = loadModule()

	const restored = module.findAiConversationResearchSession({ idempotencyKey })

	assert.equal(restored.schemaVersion, 2)
	assert.deepEqual(restored.activities.map(item => item.status), [
		'STARTED', 'IN_PROGRESS'
	])
	assert.equal(restored.activities[0].occurredAt, 'first')
	module.clearAiConversationResearchSessions()
	delete globalThis.sessionStorage
})

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadStore() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'ai-conversation-store.js'),
		'utf8'
	)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

test('drops interrupted local output when leaving the page but retains persisted messages', async () => {
	const store = await loadStore()
	store.appendLocalMessage({ localId: 'temporary', responseText: 'partial', stopped: true })
	store.appendLocalMessage({ localId: 'completed', messagePublicId: 'AAAAAAAAAAE', responseText: 'done' })

	const snapshot = store.discardTransientMessages()

	assert.deepEqual(snapshot.messages.map(message => message.localId), ['completed'])
})

test('clears all account-scoped conversation state on session replacement', async () => {
	const store = await loadStore()
	store.setConversationPage({
		conversations: [{ conversationPublicId: 'AAAAAAAAAAAAAAAAAAAAAQ' }],
		nextCursor: 'cursor',
		hasMore: true
	})

	const snapshot = store.clearAiConversationStore()

	assert.equal(snapshot.conversations.length, 0)
	assert.equal(snapshot.conversationsLoaded, false)
	assert.equal(snapshot.currentConversationPublicId, null)
})

test('keeps a memory-only stale marker until PostgreSQL history is resynchronized', async () => {
	const store = await loadStore()

	assert.equal(store.markAiConversationHistoryStale().historyStale, true)
	assert.equal(store.readAiConversationStore().historyStale, true)
	assert.equal(store.clearAiConversationHistoryStale().historyStale, false)
})

test('keeps the accepted conversation id while stale transient output is discarded', async () => {
	const store = await loadStore()
	store.setAcceptedConversation('AAAAAAAAAAAAAAAAAAAAAQ')
	store.appendLocalMessage({ localId: 'temporary', responseText: 'partial' })
	store.markAiConversationHistoryStale()

	const snapshot = store.discardTransientMessages()

	assert.equal(snapshot.messages.length, 0)
	assert.equal(snapshot.currentConversationPublicId, 'AAAAAAAAAAAAAAAAAAAAAQ')
})

test('appends cursor pages without duplicating conversations', async () => {
	const store = await loadStore()
	const firstId = 'AAAAAAAAAAAAAAAAAAAAAQ'
	const secondId = 'AAAAAAAAAAAAAAAAAAAAAg'
	const thirdId = 'AAAAAAAAAAAAAAAAAAAAAw'
	store.setConversationPage({
		conversations: [
			{ conversationPublicId: firstId, title: 'first' },
			{ conversationPublicId: secondId, title: 'old second' }
		],
		nextCursor: 'first-cursor',
		hasMore: true
	})

	const snapshot = store.setConversationPage({
		conversations: [
			{ conversationPublicId: secondId, title: 'updated second' },
			{ conversationPublicId: thirdId, title: 'third' }
		],
		nextCursor: 'second-cursor',
		hasMore: false
	}, true)

	assert.deepEqual(
		snapshot.conversations.map(item => [item.conversationPublicId, item.title]),
		[
			[firstId, 'first'],
			[secondId, 'updated second'],
			[thirdId, 'third']
		]
	)
})

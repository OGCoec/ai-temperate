const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const test = require('node:test')

function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'ai-conversation-stopped-draft.js'), 'utf8')
		.replace(/export function /g, 'function ')
		.replace(/export const /g, 'const ')
	const context = {
		sessionStorage: {
			values: new Map(),
			getItem(key) { return this.values.get(key) || null },
			setItem(key, value) { this.values.set(key, value) },
			removeItem(key) { this.values.delete(key) }
		}
	}
	vm.runInNewContext(`${source}\nthis.api = { saveAiConversationStoppedDraft, findAiConversationStoppedDraft, removeAiConversationStoppedDraft, clearAiConversationStoppedDrafts }`, context)
	return context.api
}

test('stopped drafts are bounded, reloadable and removable without database history', () => {
	const api = loadModule()
	assert.equal(api.saveAiConversationStoppedDraft({
		conversationPublicId: 'AAAAAAAAAAAAAAAAAAAAAA',
		localId: 'local-1',
		idempotencyKey: '4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6',
		inputText: 'hello',
		responseText: 'partial',
		stoppedAt: '2026-08-02T12:00:00.000Z'
	}), true)
	assert.equal(api.findAiConversationStoppedDraft('AAAAAAAAAAAAAAAAAAAAAA').responseText, 'partial')
	api.removeAiConversationStoppedDraft('4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6')
	assert.equal(api.findAiConversationStoppedDraft('AAAAAAAAAAAAAAAAAAAAAA'), null)
})

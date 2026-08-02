const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadRenderState() {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-markdown-render-state.js'),
		'utf8'
	)
	const url = 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
	return import(url + '#' + Date.now() + '-' + Math.random())
}

test('replaces snapshots and ignores stale revisions', async () => {
	const { createAiMarkdownRenderState } = await loadRenderState()
	const texts = []
	const state = createAiMarkdownRenderState({ onText: text => texts.push(text) })

	state.applySnapshot({ revision: 2, text: 'new' })
	state.applySnapshot({ revision: 1, text: 'old' })

	assert.equal(state.getText(), 'new')
	assert.deepEqual(texts, ['new'])
})

test('deduplicates event ids and lower sequences without dropping repeated content', async () => {
	const { createAiMarkdownRenderState } = await loadRenderState()
	const texts = []
	const state = createAiMarkdownRenderState({ onText: text => texts.push(text) })

	state.applySnapshot({ revision: 1, text: 'A' })
	state.applyDelta({ sequence: 1, eventId: 'one', text: 'AA' })
	state.applyDelta({ sequence: 1, eventId: 'one', text: 'AA' })
	state.applyDelta({ sequence: 0, eventId: 'zero', text: 'B' })
	state.applyDelta({ sequence: 2, eventId: 'two', text: 'A' })

	assert.equal(state.getText(), 'AAAA')
	assert.deepEqual(texts, ['A', 'AA', 'A'])
})

test('complete flushes the authoritative final text exactly once', async () => {
	const { createAiMarkdownRenderState } = await loadRenderState()
	const completed = []
	const state = createAiMarkdownRenderState({
		onComplete: text => completed.push(text)
	})

	state.applySnapshot({ revision: 1, text: 'partial' })
	state.complete({ finalText: 'final answer' })
	state.complete({ finalText: 'ignored duplicate' })

	assert.equal(state.getText(), 'final answer')
	assert.deepEqual(completed, ['final answer'])
})

test('close prevents late events from mutating the visible text', async () => {
	const { createAiMarkdownRenderState } = await loadRenderState()
	const texts = []
	const state = createAiMarkdownRenderState({ onText: text => texts.push(text) })

	state.applySnapshot({ revision: 1, text: 'visible' })
	state.close()
	state.applyDelta({ sequence: 2, eventId: 'late', text: 'late' })

	assert.equal(state.getText(), 'visible')
	assert.deepEqual(texts, ['visible'])
})

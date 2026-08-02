const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadReducer() {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-conversation-ui-block.js'),
		'utf8'
	)
	const url = 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
	return import(url + '#' + Date.now() + '-' + Math.random())
}

test('accepts one allowlisted dialog block and exposes safe text actions', async () => {
	const { createAiConversationUiBlockReducer } = await loadReducer()
	const reducer = createAiConversationUiBlockReducer()

	assert.equal(reducer.apply({
		type: 'ui_block',
		blockId: 'dialog-1',
		blockType: 'dialog',
		sequence: 1,
		payload: {
			title: 'Confirm',
			body: '<script>blocked</script>',
			actions: [{ id: 'confirm', label: 'Confirm', commandId: 'confirm' }]
		}
	}), true)
	assert.deepEqual(reducer.list(), [{
		blockId: 'dialog-1',
		blockType: 'dialog',
		sequence: 1,
		payload: {
			title: 'Confirm',
			body: '<script>blocked</script>',
			actions: [{ id: 'confirm', label: 'Confirm', commandId: 'confirm' }]
		}
	}])
})

test('rejects unknown block types, duplicate sequences, and unsafe action commands', async () => {
	const { createAiConversationUiBlockReducer } = await loadReducer()
	const reducer = createAiConversationUiBlockReducer()

	assert.equal(reducer.apply({ type: 'ui_block', blockId: 'unknown', blockType: 'script', sequence: 1, payload: {} }), false)
	assert.equal(reducer.apply({
		type: 'ui_block',
		blockId: 'dialog-1',
		blockType: 'dialog',
		sequence: 2,
		payload: { actions: [{ id: 'run', label: 'Run', commandId: 'javascript:alert(1)' }] }
	}), false)
	assert.equal(reducer.apply({
		type: 'ui_block',
		blockId: 'dialog-1',
		blockType: 'dialog',
		sequence: 1,
		payload: { title: 'Accepted' }
	}), true)
	assert.equal(reducer.apply({
		type: 'ui_block',
		blockId: 'dialog-1',
		blockType: 'dialog',
		sequence: 1,
		payload: { title: 'Old' }
	}), false)
})

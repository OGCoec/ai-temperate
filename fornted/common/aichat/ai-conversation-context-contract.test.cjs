const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

test('context stream reconnects with eventRevision and stop subscribes before cancel', () => {
	const stream = fs.readFileSync(path.resolve(
		__dirname, 'ai-conversation-context-stream.js'), 'utf8')
	const panel = fs.readFileSync(path.resolve(
		__dirname, '../../components/user/workspace/user-chat-panel.vue'), 'utf8')
	const stop = panel.slice(panel.indexOf('async stop()'))

	assert.match(stream, /afterRevision:\s*lastRevision/)
	assert.match(stream, /compaction_completed/)
	assert.match(stream, /compaction_failed/)
	assert.match(stream, /timeout/)
	assert.match(stop, /await this\.openContextObserver/)
	assert.match(stop, /cancelDirectResponseWithRetry/)
	assert.ok(stop.indexOf('await this.openContextObserver')
		< stop.indexOf('cancelDirectResponseWithRetry'))
})

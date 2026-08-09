const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

async function loadPresentation() {
	return loadEsmModule(path.join(__dirname, 'ai-activity-presentation.js'))
}

function source(url, activityId, sequence) {
	return { url, activityId, sequence, role: 'CONSULTED' }
}

test('maps model phases to the approved Orb states', async () => {
	const api = await loadPresentation()
	assert.equal(api.presentAiActivity({ phase: 'PROCESSING', status: 'IN_PROGRESS' }).state, 'working')
	assert.equal(api.presentAiActivity({ phase: 'REASONING', status: 'IN_PROGRESS' }).state, 'solving')
	assert.equal(api.presentAiActivity({ phase: 'WEB_SEARCH', status: 'IN_PROGRESS' }).state, 'searching')
	assert.equal(api.presentAiActivity({ phase: 'WEB_SEARCH', status: 'COMPLETED' }).state, 'weaving')
	assert.equal(api.presentAiActivity({ phase: 'GENERATING', status: 'IN_PROGRESS' }).state, 'composing')
	assert.equal(api.presentAiActivity({ phase: 'FINALIZING', status: 'IN_PROGRESS' }).state, 'shaping')
})

test('uses the newest source for the current search activity', async () => {
	const api = await loadPresentation()
	const presentation = api.presentAiActivity({
		activityId: 'search-1',
		phase: 'WEB_SEARCH',
		status: 'IN_PROGRESS',
		query: 'Chinese web search'
	}, [
		source('https://older.example.com', 'search-1', 12),
		source('https://newer.example.com', 'search-1', 14),
		source('https://other.example.com', 'search-2', 15)
	])

	assert.equal(presentation.sourcePresentation.domain, 'newer.example.com')
	assert.equal(presentation.sourcePresentation.source.url, 'https://newer.example.com/')
})

test('does not invent a source domain for a generic search', async () => {
	const api = await loadPresentation()
	const presentation = api.presentAiActivity({
		phase: 'WEB_SEARCH', status: 'IN_PROGRESS', query: 'latest Android rendering guidance'
	}, [])
	assert.equal(presentation.sourcePresentation, null)
	assert.equal(presentation.label, '正在联网搜索：latest Android rendering guidance')
})

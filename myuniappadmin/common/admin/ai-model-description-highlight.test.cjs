const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'ai-model-description-highlight.js'),
		'utf8'
	)
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}#${Date.now()}`)
}

test('creates safe literal model-name segments for English, versions, and Chinese', async () => {
	const { buildTextHighlightSegments } = await loadModule()

	assert.deepEqual(
		buildTextHighlightSegments('gpt-5.4-mini', ['5.4', 'mini']),
		[
			{ text: 'gpt-', matched: false },
			{ text: '5.4', matched: true },
			{ text: '-', matched: false },
			{ text: 'mini', matched: true }
		]
	)
})

test('never interprets model description content as markup', async () => {
	const { buildTextHighlightSegments } = await loadModule()

	assert.deepEqual(
		buildTextHighlightSegments('<img src=x> C++', ['c++']),
		[
			{ text: '<img src=x> ', matched: false },
			{ text: 'C++', matched: true }
		]
	)
})

test('keeps a nonmatching description as one ordinary segment', async () => {
	const { buildTextHighlightSegments } = await loadModule()

	assert.deepEqual(buildTextHighlightSegments('普通描述', []), [
		{ text: '普通描述', matched: false }
	])
})

test('uses a fallback only when the caller supplies one for an empty description', async () => {
	const { buildTextHighlightSegments } = await loadModule()

	assert.deepEqual(buildTextHighlightSegments('', ['mini'], '暂无模型说明。'), [
		{ text: '暂无模型说明。', matched: false }
	])
})

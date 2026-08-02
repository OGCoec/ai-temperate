const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'description-highlight.js'),
		'utf8'
	)
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}#${Date.now()}`)
}

test('splits model-name matches case-insensitively without treating dots as regex syntax', async () => {
	const { buildTextHighlightSegments } = await loadModule()

	assert.deepEqual(
		buildTextHighlightSegments('gpt-5.4-mini', ['mini', '5.4']),
		[
			{ text: 'gpt-', matched: false },
			{ text: '5.4', matched: true },
			{ text: '-', matched: false },
			{ text: 'mini', matched: true }
		]
	)
})

test('prefers longer overlapping tokens and preserves untrusted text as plain text', async () => {
	const { buildTextHighlightSegments } = await loadModule()

	assert.deepEqual(
		buildTextHighlightSegments('<script>ChatGPT</script>', ['gpt', 'chatgpt']),
		[
			{ text: '<script>', matched: false },
			{ text: 'ChatGPT', matched: true },
			{ text: '</script>', matched: false }
		]
	)
})

test('returns one unmarked fallback segment for an empty description', async () => {
	const { buildTextHighlightSegments } = await loadModule()

	assert.deepEqual(buildTextHighlightSegments('', ['gpt'], '暂无模型说明。'), [
		{ text: '暂无模型说明。', matched: false }
	])
})

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadPresentation() {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-model-provider-presentation.js'),
		'utf8'
	)
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}#${Date.now()}-${Math.random()}`)
}

test('every model provider uses the icon URL already present in the model cache payload', async () => {
	const presentation = await loadPresentation()
	const iconUrl = 'https://assets.example.test/model-icons/grok.svg'

	for (const vendor of ['openai', 'xai', 'anthropic', 'google', 'moonshot']) {
		assert.deepEqual(
			presentation.modelProviderLogoSources({ vendor, icon: iconUrl }),
			[iconUrl]
		)
	}
})

test('provider presentation trims cached icon URLs and falls back directly to a compact text label', async () => {
	const presentation = await loadPresentation()

	assert.deepEqual(
		presentation.modelProviderLogoSources({ vendor: 'xai', icon: '  https://assets.example.test/grok.svg  ' }),
		['https://assets.example.test/grok.svg']
	)
	assert.deepEqual(presentation.modelProviderLogoSources({ vendor: 'moonshot' }), [])
	assert.deepEqual(presentation.modelProviderLogoSources({ vendor: 'xai', icon: '   ' }), [])
	assert.deepEqual(presentation.modelProviderLogoSources({ vendor: 'xai', icon: 42 }), [])
	assert.equal(presentation.modelProviderFallbackLabel({ vendor: 'openai' }), 'AI')
	assert.equal(presentation.modelProviderFallbackLabel({ vendor: 'xai' }), 'xAI')
	assert.equal(presentation.modelProviderFallbackLabel({ vendor: 'anthropic' }), 'A')
	assert.equal(presentation.modelProviderFallbackLabel({ vendor: 'google' }), 'G')
	assert.equal(presentation.modelProviderFallbackLabel({ vendor: 'moonshot' }), 'MO')
	assert.equal(presentation.modelProviderFallbackLabel(null), 'AI')
})

test('provider presentation contains no bundled provider logo mapping', () => {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-model-provider-presentation.js'),
		'utf8'
	)

	assert.doesNotMatch(source, /LOCAL_PROVIDER_LOGOS/)
	assert.doesNotMatch(source, /\/static\/model-providers\//)
})

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function loadModule() {
	const source = fs.readFileSync(path.join(__dirname,
		'ai-conversation-result-presentation.js'), 'utf8')
		.replaceAll('export function ', 'function ')
	return new Function(`${source}; return { shouldShowAiResultDisclaimer }`)()
}

test('disclaimer stays hidden for empty, active, stopped, saving, and failed results', () => {
	const { shouldShowAiResultDisclaimer } = loadModule()

	assert.equal(shouldShowAiResultDisclaimer({}), false)
	assert.equal(shouldShowAiResultDisclaimer({ responseText: 'partial', streaming: true }), false)
	assert.equal(shouldShowAiResultDisclaimer({ responseText: 'done', saving: true }), false)
	assert.equal(shouldShowAiResultDisclaimer({ responseText: 'done', stopped: true }), false)
	assert.equal(shouldShowAiResultDisclaimer({ responseText: 'done', error: 'failed' }), false)
})

test('disclaimer follows successful text, image, and video output only', () => {
	const { shouldShowAiResultDisclaimer } = loadModule()

	assert.equal(shouldShowAiResultDisclaimer({ responseText: 'done' }), true)
	assert.equal(shouldShowAiResultDisclaimer({
		responseAttachments: [{ contentType: 'image/png', url: 'https://example.test/image.png' }]
	}), true)
	assert.equal(shouldShowAiResultDisclaimer({
		responseAttachments: [{ contentType: 'video/mp4', url: 'https://example.test/video.mp4' }]
	}), true)
	assert.equal(shouldShowAiResultDisclaimer({
		responseAttachments: [{ contentType: 'image/png', url: '' }]
	}), false)
})

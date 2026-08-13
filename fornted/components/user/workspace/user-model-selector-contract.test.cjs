const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const selectorPath = path.resolve(__dirname, 'user-model-selector.vue')
const chatPanelPath = path.resolve(__dirname, 'user-chat-panel.vue')

function read(filePath) {
	return fs.readFileSync(filePath, 'utf8')
}

test('custom model selector preserves the native picker change payload contract', () => {
	const selector = read(selectorPath)
	const chatPanel = read(chatPanelPath)

	assert.match(selector, /this\.\$emit\('change',\s*\{\s*detail:\s*\{\s*value:\s*String\(index\)\s*\}\s*\}\)/)
	assert.match(chatPanel, /<user-model-selector[\s\S]*:options="models"[\s\S]*:selected-index="selectedModelIndex"[\s\S]*@change="selectModel"/)
	assert.match(chatPanel, /:disabled="generating \|\| !models\.length"/)
	assert.doesNotMatch(chatPanel, /<picker[\s\S]{0,240}:range="models"/)
	assert.match(chatPanel, /async selectModel\(event\)[\s\S]*normalizeReasoningEffortForModel/)
})

test('custom model selector keeps clear close paths and platform-specific presentation', () => {
	const selector = read(selectorPath)

	assert.match(selector, /@click="close"/)
	assert.match(selector, /@keydown\.esc\.stop\.prevent="close"/)
	assert.match(selector, /@media screen and \(max-width: 767px\)/)
	assert.match(selector, /position: fixed/)
	assert.match(selector, /bottom: calc\(100% \+ 10px\)/)
})

test('generation settings embeds the model list without relying on parent scoped styles', () => {
	const selector = read(selectorPath)
	const chatPanel = read(chatPanelPath)

	assert.match(chatPanel, /<user-model-selector[\s\S]*presentation="embedded"/)
	assert.match(selector, /presentation:\s*\{[\s\S]*default:\s*'overlay'/)
	assert.match(selector, /embedded\(\)\s*\{[\s\S]*this\.presentation === 'embedded'/)
	assert.match(selector, /v-if="open && !embedded"/)
	assert.match(selector, /user-model-selector\.is-embedded \.user-model-selector-panel/)
	assert.doesNotMatch(chatPanel, /generation-settings-fields \.user-model-selector-panel/)
})

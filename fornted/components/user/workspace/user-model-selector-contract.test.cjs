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

test('embedded model selector groups every model once by its primary generation capability', () => {
	const selector = read(selectorPath)

	assert.match(selector, /modelGroups\(\)/)
	assert.match(selector, /primaryModelGroup\(model\)/)
	assert.match(selector, /capabilities\.includes\('VIDEO_GENERATION'\)[\s\S]*return 'video'/)
	assert.match(selector, /capabilities\.includes\('IMAGE_GENERATION'\)[\s\S]*return 'image'/)
	assert.match(selector, /capabilities\.includes\('RESPONSES'\)[\s\S]*capabilities\.includes\('CHAT_COMPLETIONS'\)[\s\S]*return 'chat'/)
	assert.match(selector, /v-for="group in modelGroups"/)
	assert.match(selector, /v-for="entry in group\.models"/)
	assert.match(selector, /select\(entry\.originalIndex\)/)
})

test('grouped model rows expose provider, description and protocol badges', () => {
	const selector = read(selectorPath)
	const settings = read(path.resolve(__dirname, 'user-h5-generation-settings.vue'))

	assert.match(settings, /<user-model-selector[\s\S]*\bgrouped\b/)
	assert.match(selector, /'is-grouped': grouped/)
	assert.match(selector, /<user-model-provider-mark/)
	assert.match(selector, /optionDescription\(entry\.model\)/)
	assert.match(selector, /capabilityBadges\(entry\.model\)/)
	assert.match(selector, /if \(!this\.grouped \|\| this\.primaryModelGroup\(model\) !== 'chat'\) return \[\]/)
	assert.match(selector, /Responses/)
	assert.match(selector, /Chat Completions/)
	assert.match(selector, /@keydown\.down\.prevent="moveOptionFocus\(1\)"/)
	assert.match(selector, /@keydown\.up\.prevent="moveOptionFocus\(-1\)"/)
	assert.match(selector, /@keydown\.home\.prevent="focusBoundaryOption\(0\)"/)
	assert.match(selector, /@keydown\.end\.prevent="focusBoundaryOption\(flatModelEntries\.length - 1\)"/)
	assert.match(selector, /\.user-model-selector-option\s*\{[^}]*min-height:\s*58px/)
	assert.match(selector, /\.user-model-selector\.is-grouped \.user-model-selector-option\s*\{[^}]*min-height:\s*70px/)
})

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const settingsPath = path.resolve(__dirname, 'user-h5-generation-settings.vue')
const optionGroupPath = path.resolve(__dirname, 'user-generation-option-group.vue')
const chatPanelPath = path.resolve(__dirname, 'user-chat-panel.vue')
const layoutPath = path.resolve(__dirname, '../../../common/ui/h5-workspace-layout.js')

function read(filePath) {
	return fs.readFileSync(filePath, 'utf8')
}

test('H5 generation settings use only project-owned controls', () => {
	const settings = read(settingsPath)
	const optionGroup = read(optionGroupPath)

	assert.doesNotMatch(settings, /<picker\b|<select\b/)
	assert.doesNotMatch(optionGroup, /<picker\b|<select\b/)
	assert.match(settings, /<user-model-selector/)
	assert.match(settings, /<user-generation-option-group/)
	assert.match(settings, /v-for="section in visibleSections"/)
	assert.match(settings, /this\.\$emit\('change',\s*event\)/)
	assert.match(optionGroup,
		/this\.\$emit\('change',\s*\{\s*key:\s*this\.section\.key,\s*detail:\s*\{\s*value:\s*String\(index\)/)
})

test('custom option groups expose segmented, grid and row presentations with keyboard selection', () => {
	const optionGroup = read(optionGroupPath)

	assert.match(optionGroup, /is-segmented/)
	assert.match(optionGroup, /is-grid/)
	assert.match(optionGroup, /is-rows/)
	assert.match(optionGroup, /:role="isSegmented \? 'radiogroup' : 'listbox'"/)
	assert.match(optionGroup, /:role="optionRole"/)
	assert.match(optionGroup, /return this\.isSegmented \? 'radio' : 'option'/)
	assert.match(optionGroup, /@keydown\.left\.prevent="move\(-1\)"/)
	assert.match(optionGroup, /@keydown\.right\.prevent="move\(1\)"/)
	assert.match(optionGroup, /@keydown\.home\.prevent="moveToBoundary\(0\)"/)
	assert.match(optionGroup, /@keydown\.end\.prevent="moveToBoundary\(options\.length - 1\)"/)
	assert.match(optionGroup, /:aria-checked="isSegmented/)
	assert.match(optionGroup, /:aria-selected="!isSegmented/)
})

test('responsive H5 settings keep explicit close and focus-management paths', () => {
	const settings = read(settingsPath)
	const layout = read(layoutPath)

	assert.match(settings, /:class="`is-\$\{presentation\}`"/)
	assert.match(settings, /role="dialog"/)
	assert.match(settings, /aria-modal="true"/)
	assert.match(settings, /@keydown\.esc\.stop\.prevent="requestClose"/)
	assert.match(settings, /@keydown\.tab="trapFocus"/)
	assert.match(settings, /closeIfOpen\(\)/)
	assert.match(settings, /\.h5-generation-settings-panel\.is-sheet/)
	assert.match(settings, /max-height:\s*min\(86dvh,\s*680px\)/)
	assert.match(layout, /H5_SIDEBAR_PUSH_MIN_WIDTH\s*=\s*768/)
	assert.match(layout,
		/resolveH5GenerationSettingsPresentation\(width\)[\s\S]*< H5_SIDEBAR_PUSH_MIN_WIDTH \? 'sheet' : 'popover'/)
})

test('settings changes keep the outer drawer open while the model selector closes only its list', () => {
	const settings = read(settingsPath)
	const selector = read(path.resolve(__dirname, 'user-model-selector.vue'))
	const modelChangeHandler = settings.slice(
		settings.indexOf('handleModelChange(event)'),
		settings.indexOf('forwardChange(event)'))

	assert.match(settings,
		/handleModelChange\(event\)\s*\{\s*this\.\$emit\('change',\s*\{\s*key:\s*'model'/)
	assert.doesNotMatch(modelChangeHandler, /requestClose|\$emit\('close'/)
	assert.match(settings,
		/forwardChange\(event\)\s*\{\s*this\.\$emit\('change',\s*event\)/)
	assert.match(selector,
		/select\(index\)[\s\S]{0,700}this\.\$emit\('change',[\s\S]{0,180}this\.close\(\)/)
})

test('image quantity and video duration use H5 grids without mounting the native count dialog', () => {
	const chatPanel = read(chatPanelPath)

	assert.match(chatPanel,
		/generationSettingsSection\('imageCount',[\s\S]{0,180}\{ h5: 'grid', android: 'rows' \}/)
	assert.match(chatPanel,
		/generationSettingsSection\('videoDuration',[\s\S]{0,180}\{ h5: 'grid' \}/)
	assert.match(chatPanel,
		/<!-- #ifndef H5 -->\s*<user-image-output-count-dialog[\s\S]*?<!-- #endif -->/)
})

test('chat panel isolates custom H5 settings from native picker controls', () => {
	const chatPanel = read(chatPanelPath)
	const h5Start = chatPanel.indexOf('<!-- #ifdef H5 -->', chatPanel.indexOf('class="composer-controls"'))
	const h5End = chatPanel.indexOf('<!-- #endif -->', h5Start)
	const nativeStart = chatPanel.indexOf('<!-- #ifndef H5 -->', h5End)
	const nativeEnd = chatPanel.indexOf('<!-- #endif -->', nativeStart)
	const h5Controls = chatPanel.slice(h5Start, h5End)
	const nativeControls = chatPanel.slice(nativeStart, nativeEnd)

	assert.match(h5Controls, /<user-h5-generation-settings/)
	assert.doesNotMatch(h5Controls, /<picker\b/)
	assert.match(nativeControls, /<picker\b/)
	assert.match(chatPanel, /:sections="generationSettingsSections"/)
	assert.match(chatPanel, /@change="handleGenerationSettingsChange"/)
})

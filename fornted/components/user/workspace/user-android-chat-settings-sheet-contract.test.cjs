const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const sheetPath = path.resolve(__dirname, 'user-android-chat-settings-sheet.vue')
const chatPanelPath = path.resolve(__dirname, 'user-chat-panel.vue')
const providerMarkPath = path.resolve(__dirname, 'user-model-provider-mark.vue')

function read(filePath) {
	return fs.readFileSync(filePath, 'utf8')
}

test('Android settings sheet preserves native picker-compatible change events', () => {
	const sheet = read(sheetPath)

	assert.match(sheet, /this\.\$emit\('change',\s*\{\s*key,\s*detail:\s*\{\s*value:\s*String\(index\)\s*\}\s*\}\)/)
	assert.match(sheet, /open\(\)/)
	assert.match(sheet, /close\(\)/)
	assert.match(sheet, /closeIfOpen\(\)/)
	assert.match(sheet, /@keydown\.esc\.stop\.prevent="close"/)
	assert.match(sheet, /max-height:\s*min\(68dvh,\s*520px\)/)
	assert.doesNotMatch(sheet, /<picker\b/)
})

test('chat panel builds capability sections from existing model option arrays', () => {
	const chatPanel = read(chatPanelPath)

	assert.match(chatPanel, /androidSettingsMode\(\)[\s\S]*'VIDEO'[\s\S]*'IMAGE'[\s\S]*'TEXT'/)
	assert.match(chatPanel, /generationSettingsSections\(\)[\s\S]*generationSettingsSection\('reasoning'[\s\S]*generationSettingsSection\('webSearch'/)
	assert.match(chatPanel, /generationSettingsSections\(\)[\s\S]*generationSettingsSection\('imageQuality'[\s\S]*generationSettingsSection\('imageAspect'[\s\S]*generationSettingsSection\('imageCount'/)
	assert.match(chatPanel, /generationSettingsSections\(\)[\s\S]*generationSettingsSection\('videoMode'[\s\S]*generationSettingsSection\('videoResolution'[\s\S]*generationSettingsSection\('videoAspect'[\s\S]*generationSettingsSection\('videoDuration'/)
	assert.match(chatPanel, /androidSettingsSections\(\)[\s\S]*this\.generationSettingsSections\.map[\s\S]*section\.presentations\.android/)
	assert.doesNotMatch(chatPanel, /key:\s*'videoCount'/)
	assert.match(chatPanel, /handleGenerationSettingsChange\(event\)[\s\S]*selectModel[\s\S]*selectReasoningEffort[\s\S]*selectWebSearchMode[\s\S]*selectImageAspect[\s\S]*selectImageOutputCount[\s\S]*selectVideoMode[\s\S]*selectVideoDuration[\s\S]*selectVideoResolution[\s\S]*selectVideoAspect/)
})

test('Android model controls show provider marks without changing the picker event contract', () => {
	const sheet = read(sheetPath)
	const chatPanel = read(chatPanelPath)
	const providerMark = read(providerMarkPath)

	assert.match(chatPanel, /<user-model-provider-mark\s+v-if="selectedModel"\s+:model="selectedModel"\s+:size="16"/)
	assert.match(sheet, /<user-model-provider-mark\s+:model="model"\s+:size="18"/)
	assert.match(sheet, /class="android-chat-settings-check"[\s\S]*type="checkmarkempty"/)
	assert.match(sheet, /\.android-chat-settings-check\s*\{[^}]*flex:\s*0 0 auto/)
	assert.match(providerMark, /modelProviderLogoSources/)
	assert.match(providerMark, /modelProviderFallbackLabel/)
	assert.match(providerMark, /aria-hidden="true"/)
	assert.match(providerMark, /@error="handleImageError"/)
	assert.match(providerMark, /sourceSignature[\s\S]*sourceIndex = 0/)
	assert.doesNotMatch(providerMark, /\/static\/model-providers\//)
	assert.match(providerMark, /\.user-model-provider-mark\s*\{[^}]*border:\s*0[^}]*background:\s*transparent[^}]*box-shadow:\s*none/)
	assert.match(sheet, /this\.\$emit\('change',\s*\{\s*key,\s*detail:\s*\{\s*value:\s*String\(index\)/)
})

test('Android settings and context sheet close controls keep a 44px hit target around one 34px surface', () => {
	const sheet = read(sheetPath)
	const contextSheet = read(path.resolve(__dirname, 'user-context-usage-sheet.vue'))

	assert.match(sheet, /\.android-chat-settings-close\s*\{[^}]*@include user-android-compact-control\(34px/)
	assert.match(sheet, /\.android-chat-settings-close\s*\{[^}]*width:\s*44px[^}]*height:\s*44px/)
	assert.match(contextSheet, /\.context-usage-close\s*\{[^}]*@include user-android-compact-control\(34px/)
	assert.match(contextSheet, /\.context-usage-trigger\s*\{[^}]*@include user-android-compact-control\(36px,\s*34px/)
})

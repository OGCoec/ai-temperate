const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const sheetPath = path.resolve(__dirname, 'user-android-chat-settings-sheet.vue')
const chatPanelPath = path.resolve(__dirname, 'user-chat-panel.vue')

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
	assert.match(chatPanel, /androidSettingsSections\(\)[\s\S]*androidSettingsSection\('reasoning'[\s\S]*androidSettingsSection\('webSearch'/)
	assert.match(chatPanel, /androidSettingsSections\(\)[\s\S]*androidSettingsSection\('imageQuality'[\s\S]*androidSettingsSection\('imageAspect'[\s\S]*androidSettingsSection\('imageCount'/)
	assert.match(chatPanel, /androidSettingsSections\(\)[\s\S]*androidSettingsSection\('videoMode'[\s\S]*androidSettingsSection\('videoResolution'[\s\S]*androidSettingsSection\('videoAspect'[\s\S]*androidSettingsSection\('videoDuration'/)
	assert.doesNotMatch(chatPanel, /key:\s*'videoCount'/)
	assert.match(chatPanel, /handleAndroidSettingsChange\(event\)[\s\S]*selectModel[\s\S]*selectReasoningEffort[\s\S]*selectWebSearchMode[\s\S]*selectImageAspect[\s\S]*selectImageOutputCount[\s\S]*selectVideoMode[\s\S]*selectVideoDuration[\s\S]*selectVideoResolution[\s\S]*selectVideoAspect/)
})

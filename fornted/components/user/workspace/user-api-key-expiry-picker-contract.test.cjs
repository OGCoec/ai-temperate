const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const componentPath = path.resolve(__dirname, 'user-api-key-expiry-picker.vue')

function readComponent() {
	return fs.readFileSync(componentPath, 'utf8')
}

test('expiry picker exposes all presets and a custom local-date path', () => {
	const source = readComponent()

	for (const label of ['永久有效', '1 天', '3 天', '1 周', '1 个月', '3 个月', '1 年', '自定义日期']) {
		assert.match(source, new RegExp(label.replace(' ', '\\s*')))
	}
	assert.match(source, /role="radiogroup"/)
	assert.match(source, /type="text"/)
	assert.match(source, /inputmode="numeric"/)
	assert.doesNotMatch(source, /datetime-local/)
	assert.match(source, /23:59 到期/)
})

test('expiry picker owns an inline accessible calendar with keyboard navigation', () => {
	const source = readComponent()

	assert.match(source, /buildExpiryCalendarMonth/)
	assert.match(source, /v-for="cell in calendarCells"/)
	assert.match(source, /role="grid"/)
	assert.match(source, /role="gridcell"/)
	assert.match(source, /aria-label="上个月"/)
	assert.match(source, /aria-label="下个月"/)
	assert.match(source, /@keydown="handleCalendarKeydown/)
	assert.match(source, /PageUp/)
	assert.match(source, /PageDown/)
	assert.match(source, /ArrowLeft/)
	assert.match(source, /ArrowRight/)
	assert.match(source, /@keydown\.esc="handleEscape"/)
})

test('expiry picker connects validation messages and expandable state to the input', () => {
	const source = readComponent()

	assert.match(source, /:aria-describedby="inputDescribedBy"/)
	assert.match(source, /:aria-invalid="String\(Boolean\(inputError\)\)"/)
	assert.match(source, /:aria-expanded="String\(calendarOpen\)"/)
	assert.match(source, /aria-controls="api-key-expiry-calendar"/)
	assert.match(source, /role="alert"/)
	assert.match(source, /\$emit\('validity'/)
	assert.match(source, /\$emit\('change'/)
})

test('expiry picker uses a four-column desktop grid and a two-column narrow layout', () => {
	const source = readComponent()

	assert.match(source, /grid-template-columns:\s*repeat\(4,\s*minmax\(0,\s*1fr\)\)/)
	assert.match(source, /@media screen and \(max-width:\s*640px\)/)
	assert.match(source, /grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/)
	assert.match(source, /min-height:\s*40px/)
	assert.match(source, /prefers-reduced-motion:\s*reduce/)
})

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const componentPath = path.resolve(__dirname, 'user-media-upload-progress.vue')

test('media upload progress keeps completion visible before opacity-only fade', () => {
	const component = fs.readFileSync(componentPath, 'utf8')

	assert.match(component, /setTimeout\(\(\) => \{[\s\S]*?\}, 1000\)/)
	assert.match(component, /this\.fading = true/)
	assert.match(component, /\}, 200\)/)
	assert.match(component, /transition: opacity 200ms ease/)
	assert.match(component, /prefers-reduced-motion: reduce/)
})

test('media upload progress exposes determinate and indeterminate accessible states', () => {
	const component = fs.readFileSync(componentPath, 'utf8')

	assert.match(component, /:role="progress\.percent == null \? 'status' : 'progressbar'"/)
	assert.match(component, /:aria-valuenow="progress\.percent == null \? undefined : String\(progress\.percent\)"/)
	assert.match(component, /aria-live="polite"/)
	assert.match(component, /is-indeterminate/)
	assert.match(component, /is-failed/)
})

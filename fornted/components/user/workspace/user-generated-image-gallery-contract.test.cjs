const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const source = fs.readFileSync(
	path.resolve(__dirname, 'user-generated-image-gallery.vue'),
	'utf8'
)

test('uses explicit stable gallery layout classes', () => {
	assert.match(source, /HERO_TWO:\s*'is-hero-two'/)
	assert.match(source, /HERO_THREE:\s*'is-hero-three'/)
	assert.match(source, /DUAL_WITH_RAIL:\s*'is-dual-with-rail'/)
	assert.doesNotMatch(source, /layout\.toLowerCase\(\)/)
})

test('renders two-primary batches separately from the secondary rail', () => {
	assert.match(source, /presentation\?\.primaryItems/)
	assert.match(source, /presentation\?\.visibleSecondaryItems/)
	assert.match(source, /generated-image-secondary/)
	assert.match(source, /grid-template-columns:\s*repeat\(2,/)
})

test('places the secondary rail beside two primary images on desktop and below them on narrow screens', () => {
	assert.match(source, /has-secondary-rail/)
	assert.match(source, /class="generated-image-secondary"\s+scroll-x\s+scroll-y/)
	assert.match(source, /generated-image-layout\.has-secondary-rail[^}]*padding-right:/s)
	assert.match(source, /generated-image-layout\.has-secondary-rail[^}]*generated-image-secondary[^}]*position:\s*absolute/s)
	assert.match(source, /generated-image-secondary-track[^}]*flex-direction:\s*column/s)
	assert.match(source, /@media screen and \(max-width:\s*767px\)[\s\S]*generated-image-secondary-track[^}]*flex-direction:\s*row/)
})

test('makes every image and overflow count actionable', () => {
	assert.match(source, /type="button"/)
	assert.match(source, /\$emit\('open'/)
	assert.match(source, /查看其余/)
	assert.match(source, /openOverflow/)
	assert.doesNotMatch(source, /aria-hidden="true"[^>]*generated-image.*overflow/)
})

test('keeps Android generated images on the controlled local source path', () => {
	assert.match(source, /user-android-chat-image/)
	assert.match(source, /:local-src="androidSource\(attachment\)\.src"/)
	assert.match(source, /:managed-local-source="true"/)
})

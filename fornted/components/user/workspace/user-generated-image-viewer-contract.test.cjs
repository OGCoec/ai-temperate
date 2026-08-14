const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const source = fs.readFileSync(
	path.resolve(__dirname, 'user-generated-image-viewer.vue'),
	'utf8'
)

test('implements an accessible modal image viewer on H5', () => {
	assert.match(source, /role="dialog"/)
	assert.match(source, /aria-modal="true"/)
	assert.match(source, /@keydown\.esc\.prevent/)
	assert.match(source, /@keydown\.left\.prevent/)
	assert.match(source, /@keydown\.right\.prevent/)
	assert.match(source, /trapFocus/)
	assert.match(source, /previouslyFocusedElement/)
	assert.match(source, /document\.body\.style\.overflow/)
})

test('uses contain presentation and selectable conversation thumbnails', () => {
	assert.match(source, /mode="aspectFit"/)
	assert.match(source, /class="viewer-thumbnails"\s+scroll-x\s+scroll-y/)
	assert.match(source, /\$emit\('select'/)
	assert.match(source, /activeIndex \+ 1/)
	assert.match(source, /handleViewerImageError/)
	assert.match(source, /retryViewerImage/)
})

test('keeps the H5 image centered in a bounded frame at every browser zoom level', () => {
	assert.match(source, /class="viewer-media-frame"/)
	assert.match(source, /generated-image-viewer\.is-h5[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\)/s)
	assert.match(source, /viewer-media-frame[^}]*width:\s*min\([^}]*px\)/s)
	assert.match(source, /viewer-media-frame[^}]*height:\s*min\([^}]*px\)/s)
	assert.match(source, /viewer-thumbnails[^}]*position:\s*absolute/s)
	assert.doesNotMatch(source, /viewer-body\s*\{[^}]*grid-template-columns:\s*92px/s)
})

test('navigates sequentially with a vertical wheel gesture without blocking browser zoom', () => {
	assert.match(source, /@wheel="handleH5Wheel"/)
	assert.match(source, /H5_WHEEL_SWITCH_THRESHOLD/)
	assert.match(source, /H5_WHEEL_NAVIGATION_COOLDOWN_MS/)
	assert.match(source, /if\s*\(event\?\.ctrlKey\s*\|\|\s*event\?\.metaKey\)\s*return/)
	assert.match(source, /deltaY\s*>\s*0\s*\?\s*this\.selectNext\s*:\s*this\.selectPrevious/)
})

test('uses the project green material for all viewer controls', () => {
	assert.match(source, /--viewer-control-background:\s*rgba\(/)
	assert.match(source, /--viewer-control-border:\s*rgba\(/)
	assert.match(source, /viewer-button[^}]*var\(--viewer-control-background\)/s)
	assert.match(source, /viewer-navigation[^}]*var\(--viewer-control-background\)/s)
})

test('centers vector chevrons inside both navigation buttons', () => {
	assert.match(source, /class="viewer-navigation-icon"\s+viewBox="0 0 24 24"/)
	assert.match(source, /d="m15 18l-6-6l6-6"/)
	assert.match(source, /d="m9 18l6-6l-6-6"/)
	assert.match(source, /viewer-navigation[^}]*display:\s*flex[^}]*align-items:\s*center[^}]*justify-content:\s*center/s)
	assert.match(source, /viewer-navigation-icon[^}]*width:\s*24px[^}]*height:\s*24px/s)
	assert.doesNotMatch(source, /<text aria-hidden="true">[‹›]<\/text>/)
})

test('uses an Android swiper with a bounded rendered window', () => {
	assert.match(source, /<swiper/)
	assert.match(source, /androidWindowItems/)
	assert.match(source, /ANDROID_WINDOW_RADIUS/)
	assert.match(source, /handleAndroidSwiperChange/)
	assert.match(source, /viewer-quality-status/)
	assert.match(source, /高清图片加载中/)
	assert.doesNotMatch(source, /uni\.previewImage/)
})

test('exposes download, retry and older-page events without owning API state', () => {
	assert.match(source, /'request-older'/)
	assert.match(source, /'download'/)
	assert.match(source, /'retry'/)
	assert.match(source, /activeDownloadReady/)
	assert.match(source, /=== 'FINAL_READY'/)
	assert.doesNotMatch(source, /aiConversationApi/)
})

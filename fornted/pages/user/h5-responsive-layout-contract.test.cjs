const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const frontendRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

function h5StyleRules(source) {
	return (source.match(/\/\* #ifdef H5 \*\/[\s\S]*?\/\* #endif \*\//g) || []).join('\n')
}

test('H5 canvas uses the full viewport while preserving the existing sidebar breakpoints', () => {
	const pages = read('pages.json')
	const workspace = read('components/user/user-workspace.vue')
	const layout = read('common/ui/h5-workspace-layout.js')

	assert.doesNotMatch(pages, /"maxWidth"\s*:/)
	assert.match(workspace,
		/\.user-workspace\.is-h5-workspace\s*\{[^}]*--workspace-content-gutter:\s*clamp\(16px,\s*1\.5vw,\s*40px\)/)
	assert.match(workspace,
		/\.user-workspace\.is-h5-workspace\s*\{[^}]*--workspace-layout-gap:\s*clamp\(16px,\s*1\.25vw,\s*24px\)/)
	assert.match(layout, /H5_SIDEBAR_PUSH_MIN_WIDTH\s*=\s*768/)
	assert.match(layout, /H5_SIDEBAR_WIDE_MIN_WIDTH\s*=\s*1100/)
	assert.match(layout, /H5_SIDEBAR_MEDIUM_WIDTH\s*=\s*240/)
	assert.match(layout, /H5_SIDEBAR_WIDE_WIDTH\s*=\s*272/)
})

test('H5 chat content and composer fill the remaining workspace without changing Android limits', () => {
	const panel = read('components/user/workspace/user-chat-panel.vue')
	const h5Styles = h5StyleRules(panel)

	assert.match(h5Styles,
		/\.chat-main:not\(\.is-android-client\) \.message-shell\s*\{[^}]*width:\s*100%[^}]*max-width:\s*none[^}]*margin:\s*0/)
	assert.match(h5Styles,
		/\.chat-main:not\(\.is-android-client\) \.composer-wrap\s*\{[^}]*width:\s*100%[^}]*max-width:\s*none[^}]*margin:\s*0/)
	assert.match(h5Styles, /var\(--workspace-content-gutter,\s*16px\)/)
	assert.match(panel, /\.is-android-client \.composer-wrap\s*\{[^}]*padding:\s*6px 12px/)
})

test('H5 generated images stay bounded and the full-screen viewer adapts its thumbnail rail', () => {
	const gallery = read('components/user/workspace/user-generated-image-gallery.vue')
	const viewer = read('components/user/workspace/user-generated-image-viewer.vue')

	assert.match(gallery,
		/\.generated-image-gallery\s*\{[^}]*width:\s*min\(100%,\s*720px\)/)
	assert.match(gallery,
		/\.generated-image-stage\.is-dual-with-rail\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/)
	assert.match(gallery, /\.generated-image-secondary\s*\{[^}]*width:\s*100%/)
	assert.match(viewer,
		/\.generated-image-viewer\s*\{[^}]*position:\s*fixed[^}]*inset:\s*0/)
	assert.match(viewer,
		/\.viewer-thumbnails\s*\{[^}]*width:\s*92px[^}]*position:\s*absolute[^}]*left:\s*0/)
	assert.match(viewer,
		/@media screen and \(max-width:\s*767px\)[\s\S]*?\.viewer-thumbnails\s*\{[^}]*width:\s*auto[^}]*height:\s*86px[^}]*top:\s*auto[^}]*right:\s*0[^}]*bottom:\s*0/)
})

test('H5 model surfaces expand to the workspace and reflow at 1200 and 1920 pixels', () => {
	const catalog = read('components/user/workspace/user-model-catalog.vue')
	const detail = read('components/user/workspace/user-model-detail.vue')
	const catalogH5Styles = h5StyleRules(catalog)
	const detailH5Styles = h5StyleRules(detail)

	assert.match(catalogH5Styles,
		/\.catalog-shell\s*\{[^}]*width:\s*100%[^}]*max-width:\s*none[^}]*margin:\s*0/)
	assert.match(catalogH5Styles, /\.catalog-list\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)/)
	assert.match(catalogH5Styles,
		/@media screen and \(min-width:\s*1200px\)[\s\S]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/)
	assert.match(catalogH5Styles,
		/@media screen and \(min-width:\s*1920px\)[\s\S]*grid-template-columns:\s*repeat\(3,\s*minmax\(0,\s*1fr\)\)/)
	assert.match(detailH5Styles,
		/\.model-detail-shell\s*\{[^}]*width:\s*100%[^}]*max-width:\s*none[^}]*margin:\s*0/)
	assert.match(detail, /class="model-detail-content-grid"/)
	assert.match(detailH5Styles,
		/@media screen and \(min-width:\s*1200px\)[\s\S]*\.model-detail-content-grid\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/)
	assert.match(detail, /\.model-detail-page\.is-android-client \.model-detail-shell/)
})

test('H5 profile and API Key surfaces use full-width responsive grids', () => {
	const profile = read('components/user/workspace/user-profile-panel.vue')
	const apiKeys = read('components/user/workspace/user-api-key-panel.vue')
	const profileH5Styles = h5StyleRules(profile)
	const apiKeysH5Styles = h5StyleRules(apiKeys)

	assert.match(profile, /class="profile-content-grid"/)
	assert.match(profileH5Styles,
		/\.profile-shell\s*\{[^}]*width:\s*100%[^}]*max-width:\s*none[^}]*margin:\s*0/)
	assert.match(profileH5Styles,
		/@media screen and \(min-width:\s*1200px\)[\s\S]*\.profile-content-grid\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/)
	assert.match(profile, /\.profile-page\.is-android-client \.profile-shell/)
	assert.match(apiKeysH5Styles,
		/\.api-key-shell\s*\{[^}]*width:\s*100%[^}]*max-width:\s*none[^}]*margin:\s*0/)
	assert.match(apiKeysH5Styles, /\.api-key-list\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)/)
	assert.match(apiKeysH5Styles,
		/@media screen and \(min-width:\s*1200px\)[\s\S]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/)
	assert.match(apiKeysH5Styles,
		/@media screen and \(min-width:\s*1920px\)[\s\S]*grid-template-columns:\s*repeat\(3,\s*minmax\(0,\s*1fr\)\)/)
})

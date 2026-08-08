const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const frontendRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

test('all protected user entry pages render the same persistent workspace', () => {
	const entries = [
		['pages/ai-chat/index.vue', 'chat', false],
		['pages/ai-models/catalog.vue', 'models', false],
		['pages/ai-models/detail.vue', 'models', true],
		['pages/account/profile.vue', 'profile', false]
	]

	for (const [file, destination, expectsModelPublicId] of entries) {
		const page = read(file)
		assert.match(page, /<user-workspace/)
		assert.match(page, new RegExp(`initial-destination="${destination}"`))
		assert.match(page, /:authenticated="authReady"/)
		if (expectsModelPublicId) {
			assert.match(page, /:initial-model-public-id="modelPublicId"/)
		}
		assert.doesNotMatch(page, /<view[^>]+v-if="authReady"/)
		assert.match(page, /handlePageShow/)
		assert.match(page, /handlePageHide/)
		assert.match(page, /handlePageUnload/)
	}
})

test('workspace owns one sidebar and switches already-mounted content panels without routing', () => {
	const workspace = read('components/user/user-workspace.vue')

	assert.equal((workspace.match(/<user-workspace-sidebar\b/g) || []).length, 1)
	assert.match(workspace, /activeDestination/)
	assert.match(workspace, /visitedDestinations/)
	assert.match(workspace, /v-show="activeDestination === 'chat'"/)
	assert.match(workspace, /v-show="activeDestination === 'models'"/)
	assert.match(workspace, /v-show="activeDestination === 'profile'"/)
	assert.match(workspace, /selectDestination\(destination\)/)
	assert.match(workspace, /this\.visitedDestinations\[destination\] = true/)
	assert.doesNotMatch(workspace, /uni\.(?:navigateTo|navigateBack|redirectTo|reLaunch)\(/)
})

test('workspace reserves only the remaining flex width for protected page content', () => {
	const workspace = read('components/user/user-workspace.vue')

	assert.match(workspace, /DESKTOP_SIDEBAR_MIN_WIDTH\s*=\s*768/)
	assert.match(workspace, /windowWidth\s*<\s*DESKTOP_SIDEBAR_MIN_WIDTH/)
	assert.match(workspace,
		/\.user-workspace\s*\{[^}]*min-width:\s*0[^}]*max-width:\s*100%[^}]*flex-direction:\s*row/)
	assert.match(workspace,
		/\.user-workspace-content\s*\{[^}]*width:\s*0[^}]*max-width:\s*100%[^}]*min-width:\s*0[^}]*flex:\s*1 1 0%/)
})

test('workspace keeps navigation visible while authentication and panel data are pending', () => {
	const workspace = read('components/user/user-workspace.vue')
	const profilePanel = read('components/user/workspace/user-profile-panel.vue')
	const modelCatalog = read('components/user/workspace/user-model-catalog.vue')
	const modelDetail = read('components/user/workspace/user-model-detail.vue')

	assert.doesNotMatch(workspace, /v-if="authenticated"[^>]*class="user-workspace"/)
	assert.match(workspace, /<user-workspace-sidebar/)
	assert.match(profilePanel, /v-if="!authenticated"/)
	assert.match(modelCatalog, /v-if="!authenticated"/)
	assert.match(modelDetail, /v-if="!authenticated"/)
	assert.doesNotMatch(profilePanel, /<view[^>]+v-if="authReady"/)
})

test('workspace sidebar exposes new chat, primary destinations, and shared recent conversations', () => {
	const sidebar = read('components/user/user-workspace-sidebar.vue')
	const navigation = read('components/user/user-primary-navigation.vue')

	assert.match(sidebar, /class="workspace-new-chat"/)
	assert.match(sidebar, /<user-primary-navigation/)
	assert.match(sidebar, /<user-recent-conversations/)
	assert.match(sidebar, /<template #before-items>[\s\S]*workspace-new-chat/)
	assert.match(sidebar, /<template #after-items>[\s\S]*user-recent-conversations/)
	assert.match(sidebar, /ref="mobileDrawer"/)
	assert.match(sidebar, /tabindex="-1"/)
	assert.match(sidebar, /@keydown\.esc\.stop="\$emit\('close-drawer'\)"/)
	assert.ok(
		navigation.indexOf('<slot name="before-items"') <
			navigation.indexOf('class="user-primary-navigation-inner"')
	)
	assert.ok(
		navigation.indexOf('<slot name="after-items"') >
			navigation.indexOf('class="user-primary-navigation-inner"')
	)
	assert.doesNotMatch(navigation, /getCurrentPages\(\)/)
	assert.doesNotMatch(navigation, /uni\.(?:navigateTo|navigateBack|redirectTo|reLaunch)\(/)
	assert.match(navigation, /this\.\$emit\('destination-click', destination\)/)
})

test('workspace forwards explicit chat actions and page lifecycle without persisting conversation IDs', () => {
	const workspace = read('components/user/user-workspace.vue')

	assert.match(workspace, /startNewChat\(\)[\s\S]*onAuthenticatedPageReady\(\)[\s\S]*\.newChat\(\)/)
	assert.match(workspace, /openConversation\(conversationPublicId\)[\s\S]*onAuthenticatedPageReady\(\)[\s\S]*\.openConversation\(/)
	assert.match(workspace, /handlePageShow\(\)[\s\S]*this\.\$refs\.chatPanel\?\.handlePageShow/)
	assert.match(workspace, /handlePageHide\(\)[\s\S]*this\.\$refs\.chatPanel\?\.handlePageHide/)
	assert.match(workspace, /handlePageUnload\(\)[\s\S]*this\.\$refs\.chatPanel\?\.handlePageUnload/)
	assert.doesNotMatch(workspace, /setStorageSync\([^)]*conversation/i)
})

test('all ordinary user pages use a custom navigation bar and one viewport shell', () => {
	const pages = JSON.parse(read('pages.json'))
	const expected = new Set([
		'pages/ai-chat/index',
		'pages/ai-models/catalog',
		'pages/ai-models/detail',
		'pages/account/profile'
	])

	for (const page of pages.pages) {
		if (!expected.has(page.path)) continue
		assert.equal(page.style?.navigationStyle, 'custom')
		assert.equal(page.style?.backgroundColor, '#0b0d0c')
	}
})

test('chat video previews preserve their aspect ratio and stay within the available viewport', () => {
	const chatPanel = read('components/user/workspace/user-chat-panel.vue')
	const videoCardBindings = chatPanel.match(
		/:class="\{ 'is-video': previewVideo\(attachment\) \}"/g) || []
	const videoStyleBindings = chatPanel.match(
		/:style="previewVideo\(attachment\) \? generatedVideoCardStyle\(attachment(?:, message\.videoMetadata)?\) : null"/g) || []
	const metadataBindings = chatPanel.match(
		/@loadedmetadata="handleGeneratedVideoMetadata\(attachment, \$event\)"/g) || []

	assert.equal(videoCardBindings.length, 3)
	assert.equal(videoStyleBindings.length, 2)
	assert.equal(metadataBindings.length, 2)
	assert.match(chatPanel, /GENERATED_VIDEO_MAX_WIDTH_PX\s*=\s*720/)
	assert.match(chatPanel, /GENERATED_VIDEO_MAX_HEIGHT_PX\s*=\s*1080/)
	assert.match(chatPanel, /GENERATED_VIDEO_VIEWPORT_HEIGHT_RATIO\s*=\s*0\.68/)
	assert.match(chatPanel, /generatedVideoDisplaySize\(/)
	assert.match(chatPanel,
		/\.attachment-card\.is-video\s*\{[^}]*width:\s*min\(100%,\s*720px\)[^}]*justify-self:\s*center/)
	assert.match(chatPanel,
		/\.attachment-media-frame\.is-video\s*\{[^}]*max-width:\s*720px[^}]*max-height:\s*min\(68vh,\s*1080px\)/)
	assert.match(chatPanel,
		/\.attachment-video\s*\{[^}]*width:\s*100%[^}]*height:\s*100%[^}]*object-fit:\s*contain/)
	assert.doesNotMatch(chatPanel,
		/\.attachment-image,\s*\.attachment-video\s*\{[^}]*height:\s*180px/)
})

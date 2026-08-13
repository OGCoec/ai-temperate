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

test('Android moves primary navigation into the drawer while H5 keeps the existing navigation shell', () => {
	const sidebar = read('components/user/user-workspace-sidebar.vue')
	const navigation = read('components/user/user-primary-navigation.vue')

	assert.match(sidebar, /v-if="!androidClient"[\s\S]*<user-primary-navigation/)
	assert.match(sidebar, /v-if="androidClient"[\s\S]*variant="drawer"/)
	assert.match(sidebar, /getCurrentUserProfile\(\)/)
	assert.match(sidebar, /class="workspace-drawer-account"[\s\S]*@click="selectDrawerDestination\('profile'\)"/)
	assert.match(sidebar, /selectDrawerDestination\(destination\)[\s\S]*\$emit\('destination-click', destination\)[\s\S]*\$emit\('close-drawer'\)/)
	assert.match(navigation, /variant === 'drawer'/)
	assert.match(navigation, /\.user-primary-navigation\.is-drawer[\s\S]*position:\s*static/)
	assert.match(sidebar, /width:\s*min\(70vw,\s*288px\)/)
	assert.doesNotMatch(sidebar, />\s*(?:搜索|设置)\s*</)
})

test('Android workspace panels retain a drawer entry and delegate system back handling', () => {
	const workspace = read('components/user/user-workspace.vue')
	const chatPanel = read('components/user/workspace/user-chat-panel.vue')
	const modelCatalog = read('components/user/workspace/user-model-catalog.vue')
	const modelDetail = read('components/user/workspace/user-model-detail.vue')
	const profilePanel = read('components/user/workspace/user-profile-panel.vue')
	const entries = [
		read('pages/ai-chat/index.vue'),
		read('pages/ai-models/catalog.vue'),
		read('pages/ai-models/detail.vue'),
		read('pages/account/profile.vue')
	]

	assert.match(workspace, /@new-chat="startNewChat"/)
	assert.match(chatPanel, /class="chat-header-new-chat[^"]*"[\s\S]*@click="\$emit\('new-chat'\)"/)
	assert.match(modelCatalog, /@click="\$emit\('open-conversation-drawer'\)"/)
	assert.match(modelDetail, /@click="\$emit\('open-conversation-drawer'\)"/)
	assert.match(profilePanel, /@click="\$emit\('open-conversation-drawer'\)"/)
	assert.match(workspace, /handleBackPress\(\)[\s\S]*closeIfOpen\(\)[\s\S]*drawerOpen[\s\S]*return false/)
	for (const page of entries) {
		assert.match(page, /onBackPress\(\)[\s\S]*handleBackPress\(\)/)
	}
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

test('workspace exposes stable conversation errors without raw runtime messages', () => {
	const workspace = read('components/user/user-workspace.vue')
	const refresh = workspace.slice(
		workspace.indexOf('async refreshConversations()'),
		workspace.indexOf('copyConversationId(')
	)

	assert.match(refresh, /会话列表暂时无法加载，请重试。/)
	assert.match(refresh, /更多会话暂时无法加载，请重试。/)
	assert.doesNotMatch(refresh, /error\?\.message/)
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
		assert.equal(page.style?.appPlus?.softinputMode, 'adjustResize')
	}
})

test('chat video previews preserve their aspect ratio and stay within the available viewport', () => {
	const chatPanel = read('components/user/workspace/user-chat-panel.vue')
	const videoCardBindings = chatPanel.match(
		/:class="\{ 'is-video': previewVideo\(attachment\)(?:, 'is-android-media': androidClient)? \}"/g) || []
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

test('chat composer keeps the motion control for H5 and fixes Android to system preference', () => {
	const chatPanel = read('components/user/workspace/user-chat-panel.vue')

	assert.doesNotMatch(chatPanel,
		/<button\s+v-if="!manualMotionReduced"\s+class="motion-toggle"/)
	assert.match(chatPanel,
		/<button[\s\S]*?v-if="!androidClient"[\s\S]*?class="motion-toggle"[\s\S]*?@click="toggleMotionPreference"/)
	assert.match(chatPanel, /\{\{ motionPreferenceLabel \}\}/)
	assert.match(chatPanel, /if \(this\.androidClient\)[\s\S]*snapshot\.systemReduced[\s\S]*AI_MOTION_PREFERENCES\.SYSTEM/)
})

test('Android uses the compact two-row composer and keeps H5 picker controls', () => {
	const chatPanel = read('components/user/workspace/user-chat-panel.vue')
	const androidComposer = chatPanel.slice(
		chatPanel.indexOf('class="android-composer-tools"'),
		chatPanel.indexOf('<view v-if="!androidClient" class="composer-meta"')
	)
	const h5Controls = chatPanel.slice(
		chatPanel.indexOf('<view v-if="!androidClient" class="composer-meta"'),
		chatPanel.indexOf('<text v-if="pendingAttachments.length')
	)

	assert.match(androidComposer, /user-android-chat-settings-sheet/)
	assert.match(androidComposer, /user-context-usage-sheet/)
	assert.doesNotMatch(androidComposer, /<picker\b/)
	assert.match(h5Controls, /<picker\b/)
	assert.match(h5Controls, /class="composer-note"/)
	assert.match(chatPanel, /\.is-android-client\s*\{[\s\S]*padding-bottom:\s*0/)
})

test('desktop voice composer keeps live recognition compact until the final transcript is ready', () => {
	const chatPanel = read('components/user/workspace/user-chat-panel.vue')

	assert.match(chatPanel,
		/<view\s+class="composer"\s+:class="\{ 'is-voice-active': voiceInteractionActive \}"/)
	assert.match(chatPanel,
		/<button v-if="!voiceInteractionActive" class="composer-icon"/)
	assert.match(chatPanel, /<textarea\s+v-if="!voiceInteractionActive"\s+v-model="draft"/)
	assert.doesNotMatch(chatPanel, /voice-transcript-placeholder/)
	assert.match(chatPanel,
		/@media screen and \(min-width: 768px\)[\s\S]*?\.chat-main:not\(\.is-android-client\) \.composer\.is-voice-active \.voice-cancel-button[\s\S]*?width:\s*38px/)
	assert.match(chatPanel,
		/@media screen and \(min-width: 768px\)[\s\S]*?\.chat-main:not\(\.is-android-client\) \.composer-input\s*\{\s*font-size:\s*14px/)
	assert.match(chatPanel,
		/\.chat-main:not\(\.is-android-client\) \.composer\.is-voice-active \.voice-cancel-button\s*\{\s*order:\s*-1/)
	assert.match(chatPanel,
		/\.is-android-client \.voice-cancel-button,[\s\S]*?width:\s*48px/)
})

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const frontendRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

function sourceUrl(source) {
	return 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
}

async function loadH5WorkspaceLayout() {
	const source = read('common/ui/h5-workspace-layout.js')
	return import(sourceUrl(source) + '#' + Date.now() + '-' + Math.random())
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

	assert.equal((workspace.match(/<user-h5-workspace-sidebar\b/g) || []).length, 1)
	assert.equal((workspace.match(/<user-workspace-sidebar\b/g) || []).length, 1)
	assert.match(workspace, /#ifdef H5[\s\S]*<user-h5-workspace-sidebar/)
	assert.match(workspace, /#ifndef H5[\s\S]*<user-workspace-sidebar/)
	assert.match(workspace, /activeDestination/)
	assert.match(workspace, /visitedDestinations/)
	assert.match(workspace, /v-show="activeDestination === 'chat'"/)
	assert.match(workspace, /v-show="activeDestination === 'models'"/)
	assert.match(workspace, /v-show="activeDestination === 'profile'"/)
	assert.match(workspace, /selectDestination\(destination\)/)
	assert.match(workspace, /this\.visitedDestinations\[destination\] = true/)
	assert.doesNotMatch(workspace, /uni\.(?:navigateTo|navigateBack|redirectTo|reLaunch)\(/)
})

test('workspace reserves the remaining grid width and exposes one accessible sidebar toggle', () => {
	const workspace = read('components/user/user-workspace.vue')

	assert.match(workspace, /:class="workspaceClass"/)
	assert.match(workspace, /:open="sidebarOpen"/)
	assert.match(workspace, /:mode="sidebarMode"/)
	assert.match(workspace, /aria-controls="workspace-conversation-sidebar"/)
	assert.match(workspace, /:aria-expanded="String\(sidebarOpen\)"/)
	assert.match(workspace, /@click="toggleSidebar"/)
	assert.match(workspace, /this\.\$refs\.sidebarToggle/)
	assert.doesNotMatch(workspace, /(?:localStorage|setStorageSync)\([^)]*sidebar/i)
	assert.match(workspace,
		/\.user-workspace\.is-h5-workspace\s*\{[^}]*display:\s*grid[^}]*grid-template-columns:\s*var\(--workspace-sidebar-track\)/)
	assert.match(workspace,
		/\.user-workspace-content\s*\{[^}]*max-width:\s*100%[^}]*min-width:\s*0/)
	assert.match(workspace, /\.user-workspace\s*\{[^}]*overflow:\s*hidden/)
})

test('H5 sidebar breakpoints use overlay below 768 and push tracks at 240 or 272 pixels', async () => {
	const {
		defaultH5SidebarOpen,
		resolveH5FollowLatest,
		resolveH5GenerationSettingsPresentation,
		resolveH5SidebarOpen,
		resolveH5SidebarMode,
		resolveH5SidebarWidth
	} = await loadH5WorkspaceLayout()

	assert.equal(resolveH5SidebarMode(767), 'overlay')
	assert.equal(resolveH5SidebarMode(768), 'push')
	assert.equal(resolveH5SidebarWidth(1099), 240)
	assert.equal(resolveH5SidebarWidth(1100), 272)
	assert.equal(defaultH5SidebarOpen(767), false)
	assert.equal(defaultH5SidebarOpen(768), true)
	assert.equal(resolveH5SidebarOpen(false, true, 1440), false)
	assert.equal(resolveH5SidebarOpen(true, true, 375), true)
	assert.equal(resolveH5GenerationSettingsPresentation(767), 'sheet')
	assert.equal(resolveH5GenerationSettingsPresentation(768), 'popover')
	assert.equal(resolveH5FollowLatest({
		previousScrollTop: 120,
		nextScrollTop: 80,
		distanceToBottom: 20
	}), false)
	assert.equal(resolveH5FollowLatest({
		previousScrollTop: 80,
		nextScrollTop: 100,
		distanceToBottom: 96
	}), true)
	assert.equal(resolveH5FollowLatest({ distanceToBottom: 97 }), false)
	assert.equal(resolveH5FollowLatest({ distanceToBottom: 20, hasHiddenTurnsAfter: true }), false)
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

test('workspace sidebar is a focused conversation surface with close and account actions', () => {
	const sidebar = read('components/user/user-h5-workspace-sidebar.vue')

	assert.match(sidebar, /class="workspace-new-chat"/)
	assert.match(sidebar, /<user-recent-conversations/)
	assert.equal((sidebar.match(/<user-recent-conversations\b/g) || []).length, 1)
	assert.doesNotMatch(sidebar, /<user-primary-navigation/)
	assert.match(sidebar, /id="workspace-conversation-sidebar"/)
	assert.match(sidebar, /:aria-hidden="String\(!open\)"/)
	assert.match(sidebar, /aria-label="关闭会话边栏"/)
	assert.match(sidebar, /@click="requestClose"/)
	assert.match(sidebar, /@click="\$emit\('destination-click', 'profile'\)"/)
	assert.match(sidebar, /ref="sidebar"/)
	assert.match(sidebar, /tabindex="-1"/)
	assert.match(sidebar, /@keydown\.esc\.stop="requestClose"/)
	assert.match(sidebar, /@keydown\.tab="trapSidebarFocus"/)
	assert.match(sidebar, /document\.body\.style\.overflow = 'hidden'/)
	assert.match(sidebar, /if \(this\.mode !== 'overlay'\) return/)
	assert.match(sidebar, /min\(88vw, 360px\)/)
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

test('Android drawer uses compact density without changing H5 navigation or conversation behavior', () => {
	const sidebar = read('components/user/user-workspace-sidebar.vue')
	const navigation = read('components/user/user-primary-navigation.vue')
	const recent = read('components/user/user-recent-conversations.vue')

	assert.equal((sidebar.match(/:compact="androidClient"/g) || []).length, 1)
	assert.match(sidebar, /content-id="workspace-mobile-recent"\s+:compact="androidClient"/)
	assert.doesNotMatch(sidebar, /content-id="workspace-desktop-recent"\s+:compact=/)
	assert.match(recent, /class="recent-conversations"[\s\S]*'is-compact': compact/)
	assert.match(recent, /compact:\s*\{[\s\S]*type:\s*Boolean[\s\S]*default:\s*false/)
	assert.match(navigation, /\.is-drawer \.user-primary-navigation-inner\s*\{[^}]*gap:\s*0/)
	assert.match(navigation, /\.is-drawer \.user-primary-navigation-item\s*\{[^}]*min-height:\s*44px[^}]*font-size:\s*13px/)
	assert.equal((navigation.match(/@click="navigate\('(chat|models|profile)'\)"/g) || []).length, 3)
	assert.match(recent, /\.recent-conversations\.is-compact \.recent-toggle\s*\{[^}]*min-height:\s*40px/)
	assert.match(recent, /\.recent-conversations\.is-compact \.conversation-row\s*\{[^}]*height:\s*40px[^}]*margin:\s*0/)
	assert.match(recent, /\.recent-conversations\.is-compact \.conversation-open\s*\{[^}]*min-height:\s*40px[^}]*font-size:\s*12px/)
	assert.match(recent, /\.recent-conversations\.is-compact \.conversation-copy\s*\{[^}]*width:\s*40px[^}]*height:\s*40px/)
	assert.match(sidebar, /startDrawerNewChat\(\)[\s\S]*\$emit\('new-chat'\)[\s\S]*\$emit\('close-drawer'\)/)
	assert.match(sidebar, /openDrawerConversation\(conversationPublicId\)[\s\S]*\$emit\('open-conversation', conversationPublicId\)[\s\S]*\$emit\('close-drawer'\)/)
})

test('Android small controls use one compact frosted surface inside unchanged touch targets', () => {
	const material = read('common/ui/user-material.scss')
	const sidebar = read('components/user/user-workspace-sidebar.vue')
	const chatPanel = read('components/user/workspace/user-chat-panel.vue')
	const settingsSheet = read('components/user/workspace/user-android-chat-settings-sheet.vue')
	const contextSheet = read('components/user/workspace/user-context-usage-sheet.vue')
	const modelCatalog = read('components/user/workspace/user-model-catalog.vue')
	const modelDetail = read('components/user/workspace/user-model-detail.vue')
	const profilePanel = read('components/user/workspace/user-profile-panel.vue')

	assert.match(material, /@mixin user-android-compact-control\([\s\S]*border:\s*0[\s\S]*background:\s*transparent[\s\S]*box-shadow:\s*none/)
	assert.match(material, /@mixin user-android-compact-control\([\s\S]*&::before\s*\{[\s\S]*border:\s*1px solid \$user-glass-border[\s\S]*background:\s*\$user-frosted-control/)
	assert.match(sidebar, /\.is-android-drawer \.workspace-icon-button\s*\{[^}]*@include user-android-compact-control\(30px/)
	assert.match(chatPanel, /\.is-android-client \.icon-button\s*\{[^}]*@include user-android-compact-control\(32px/)
	assert.match(chatPanel, /\.is-android-client \.composer-icon,[\s\S]*?\.is-android-client \.send-button\s*\{[^}]*@include user-android-compact-control\(34px/)
	assert.match(chatPanel, /\.android-settings-trigger\s*\{[^}]*@include user-android-compact-control\(100%,\s*34px/)
	assert.match(settingsSheet, /\.android-chat-settings-close\s*\{[^}]*@include user-android-compact-control\(34px/)
	assert.match(contextSheet, /\.context-usage-trigger\s*\{[^}]*@include user-android-compact-control\(36px,\s*34px/)
	for (const panel of [modelCatalog, modelDetail, profilePanel]) {
		assert.match(panel, /\.workspace-panel-menu\s*\{[^}]*@include user-android-compact-control\(32px/)
		assert.match(panel, /@click="\$emit\('open-conversation-drawer'\)"/)
	}
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
		assert.equal(page.style?.['app-plus']?.softinputMode, 'adjustResize')
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
	assert.match(androidComposer, /user-model-provider-mark/)
	assert.doesNotMatch(androidComposer, /<picker\b/)
	assert.match(h5Controls, /<picker\b/)
	assert.match(h5Controls, /class="composer-note"/)
	assert.doesNotMatch(h5Controls, /user-model-provider-mark/)
	assert.match(chatPanel, /\.is-android-client\s*\{[\s\S]*padding-bottom:\s*0/)
})

test('Android context entry requires a real conversation usage snapshot', () => {
	const chatPanel = read('components/user/workspace/user-chat-panel.vue')
	const contextSheet = read('components/user/workspace/user-context-usage-sheet.vue')

	assert.match(chatPanel,
		/<user-context-usage-sheet[\s\S]*v-if="currentConversationPublicId && contextUsage"/)
	assert.match(contextSheet, /Math\.round\(Math\.max\(0, Number\(this\.usage\?\.usagePercent \|\| 0\)\)\)/)
	assert.match(contextSheet, /:size="20"/)
	assert.match(contextSheet, /closeIfOpen\(\)/)
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
		/\.is-android-client \.composer\.is-voice-active \.voice-cancel-button,[\s\S]*?width:\s*48px/)
})

test('Android voice actions keep large touch targets around compact visual controls', () => {
	const chatPanel = read('components/user/workspace/user-chat-panel.vue')

	assert.match(chatPanel,
		/\.is-android-client \.composer\.is-voice-active \.voice-cancel-button,[\s\S]*?width:\s*48px[\s\S]*?height:\s*48px/)
	assert.match(chatPanel,
		/\.is-android-client \.composer\.is-voice-active \.voice-cancel-button,[\s\S]*?@include user-android-compact-control\(34px/)
	assert.doesNotMatch(chatPanel,
		/\.is-android-client \.composer\.is-voice-active \.voice-commit-button::before\s*\{[^}]*background:\s*rgba\(55,\s*211,\s*154/)
	assert.match(chatPanel,
		/\.is-android-client \.composer\.is-voice-active \.voice-cancel-glyph\s*\{[^}]*font-size:\s*20px/)
	assert.match(chatPanel,
		/\.is-android-client \.composer\.is-voice-active \.voice-commit-square\s*\{[^}]*width:\s*11px[^}]*height:\s*11px/)
})

test('Android send and stop retain their events while sharing the neutral frosted button surface', () => {
	const chatPanel = read('components/user/workspace/user-chat-panel.vue')

	assert.match(chatPanel, /class="send-button stop-button"[\s\S]*@click="stop"/)
	assert.match(chatPanel, /class="send-button"[\s\S]*@click="send"/)
	assert.match(chatPanel, /:color="androidClient \? '#75dfb7' : '#07110d'"/)
	assert.doesNotMatch(chatPanel, /\.is-android-client[^}]*\.send-button::before\s*\{[^}]*background:\s*#37d39a/)
	assert.doesNotMatch(chatPanel, /\.is-android-client[^}]*\.stop-button::before\s*\{[^}]*background:\s*rgba\(55,\s*211,\s*154/)
})

test('Android keeps the voice canvas mounted without an overlay or changing H5 mounting', () => {
	const chatPanel = read('components/user/workspace/user-chat-panel.vue')
	const waveform = read('components/user/workspace/user-voice-waveform.vue')

	assert.match(chatPanel,
		/<view\s+v-if="androidClient \|\| voiceInteractionActive"\s+class="voice-inline-status"/)
	assert.match(chatPanel, /:class="\{ 'is-active': voiceInteractionActive \}"/)
	assert.match(chatPanel, /:aria-hidden="String\(!voiceInteractionActive\)"/)
	assert.doesNotMatch(chatPanel, /:stabilize-mount=/)
	assert.match(chatPanel,
		/\.is-android-client \.voice-inline-status:not\(\.is-active\)\s*\{[^}]*position:\s*absolute[^}]*visibility:\s*hidden[^}]*opacity:\s*0[^}]*pointer-events:\s*none/)
	assert.doesNotMatch(chatPanel,
		/\.is-android-client \.voice-inline-status:not\(\.is-active\)\s*\{[^}]*display:\s*none/)
	assert.doesNotMatch(waveform,
		/stabilizeMount|user-voice-waveform-placeholder|uses-stable-fallback|is-canvas-ready|opacity:\s*0/)
})

test('voice canvas overlay removal leaves recorder, PCM, cancel, commit, and transcript bindings intact', () => {
	const chatPanel = read('components/user/workspace/user-chat-panel.vue')

	assert.match(chatPanel, /@click="abortVoiceInput\('USER_DISCARD'\)"/)
	assert.match(chatPanel, /@click="finalizeVoiceInput\(false, 'USER_TAP'\)"/)
	assert.match(chatPanel, /await recorder\.start\(frame =>/)
	assert.match(chatPanel, /this\.publishVoiceWaveform\(frame, voiceEpoch\)/)
	assert.match(chatPanel, /session\.sendAudio\(frame\)/)
	assert.match(chatPanel, /this\.voicePartialText =/)
})

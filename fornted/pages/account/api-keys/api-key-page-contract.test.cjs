const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const frontendRoot = path.resolve(__dirname, '../../..')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

test('profile exposes an H5-only API Key entry without loading key data', () => {
	const profile = read('components/user/workspace/user-profile-panel.vue')

	assert.match(profile, /#ifdef H5[\s\S]*开发者工具[\s\S]*管理我的 API Key[\s\S]*openApiKeys[\s\S]*#endif/)
	assert.doesNotMatch(profile, /apiKeyApi\.list/)
	assert.match(profile, /openApiKeys\(\)[\s\S]{0,180}\$emit\('open-api-keys'\)/)
	assert.doesNotMatch(profile, /AUTH_ROUTES\.apiKeys/)
})

test('API Key page uses the shared workspace and keeps account navigation selected', () => {
	const entry = read('pages/account/api-keys.vue')
	const workspace = read('components/user/user-workspace.vue')
	const sidebar = read('components/user/user-h5-workspace-sidebar.vue')
	const pages = read('pages.json')

	assert.match(entry, /<user-workspace[\s\S]*initial-destination="apiKeys"/)
	assert.match(pages, /#ifdef H5[\s\S]*pages\/account\/api-keys[\s\S]*#endif/)
	assert.match(workspace, /'chat', 'models', 'profile', 'apiKeys'/)
	assert.match(workspace, /<user-profile-panel[\s\S]*@open-api-keys="selectDestination\('apiKeys'\)"/)
	assert.match(workspace, /<user-api-key-panel/)
	assert.match(sidebar, /\['profile', 'apiKeys'\]\.includes\(activeDestination\)/)
})

test('H5 background transitions preserve API Key state until unload or logout', () => {
	const workspace = read('components/user/user-workspace.vue')
	const panel = read('components/user/workspace/user-api-key-panel.vue')

	assert.match(workspace, /handlePageHide\(\)\s*\{\s*this\.\$refs\.chatPanel\?\.handlePageHide\(\)\s*\}/)
	assert.doesNotMatch(workspace, /handlePageHide\(\)[\s\S]{0,160}apiKeyPanel\?\.handlePageHide/)
	assert.doesNotMatch(panel, /handlePageHide\(\)/)
	assert.match(panel, /handlePageUnload\(\)[\s\S]{0,100}releasePageState\(\)/)
	assert.match(panel, /authenticated\(value\)[\s\S]{0,160}else this\.releasePageState\(\)/)
	assert.match(panel, /createdSecret\s*=\s*created\.value\.apiKey/)
	assert.doesNotMatch(panel, /visibilitychange|localStorage|sessionStorage|indexedDB|setStorage/)
})

test('management page keeps secrets out of list state and separates lifecycle from model saves', () => {
	const panel = read('components/user/workspace/user-api-key-panel.vue')
	const createDialog = read('components/user/workspace/user-api-key-create-dialog.vue')
	const secretDialog = read('components/user/workspace/user-api-key-secret-dialog.vue')
	const editor = read('components/user/workspace/user-api-key-editor-sheet.vue')

	assert.match(panel, /summaryFromCreatedKey/)
	assert.match(panel, /createdSecret\s*=\s*created\.value\.apiKey/)
	assert.match(panel, /createdSecret\s*=\s*''/)
	assert.doesNotMatch(panel, /localStorage|setStorage|sessionStorage/)
	assert.match(createDialog, /minimum-models="1"/)
	assert.match(secretDialog, /我已保存，关闭/)
	assert.match(secretDialog, /@click\.self\.stop/)
	assert.match(editor, /saveLifecycle/)
	assert.match(editor, /saveModels/)
	assert.match(editor, /If-Match|etag/)
})

test('list does not issue per-key detail requests or claim to show model counts', () => {
	const panel = read('components/user/workspace/user-api-key-panel.vue')

	assert.doesNotMatch(panel, /v-for="key[^"]*"[\s\S]{0,800}apiKeyApi\.detail/)
	assert.doesNotMatch(panel, /授权模型：.*个|models\.length/)
	assert.match(panel, /nextCursor/)
	assert.match(panel, /pageSize:\s*20/)
})

test('one-time secret overlay is explicit and never exposes the key through a URL', () => {
	const secretDialog = read('components/user/workspace/user-api-key-secret-dialog.vue')

	assert.match(secretDialog, /完整 API Key 只显示这一次/)
	assert.match(secretDialog, /copySecret/)
	assert.match(secretDialog, /copyBaseUrl/)
	assert.doesNotMatch(secretDialog, /navigateTo[\s\S]*apiKey|download|二维码/)
})

test('model selection is server-paged, searchable, cross-page and bounded at 500', () => {
	const picker = read('components/user/workspace/user-api-key-model-picker.vue')
	const createDialog = read('components/user/workspace/user-api-key-create-dialog.vue')

	assert.match(picker, /pageSize:\s*50/)
	assert.match(picker, /keyword:\s*this\.activeKeyword/)
	assert.match(picker, /selectedById:\s*new Map\(\)/)
	assert.match(picker, /selectionLimitReached/)
	assert.match(picker, /已达到 500 个模型的授权上限/)
	assert.doesNotMatch(createDialog, /displayName|hmac|prefix|status:/i)
})

test('create and management model rows reuse cached icons in a compact single-line grid', () => {
	const picker = read('components/user/workspace/user-api-key-model-picker.vue')

	assert.match(picker, /import UserModelProviderMark from '\.\/user-model-provider-mark\.vue'/)
	assert.match(picker, /components:\s*\{\s*UserModelProviderMark\s*\}/)
	assert.match(picker,
		/model-picker-check[\s\S]{0,180}<user-model-provider-mark[\s\S]{0,180}model-picker-vendor[\s\S]{0,180}model-picker-name/)
	assert.match(picker, /:model="model"/)
	assert.match(picker, /:size="20"/)
	assert.match(picker,
		/grid-template-columns:\s*22px\s+20px\s+64px\s+minmax\(0,\s*1fr\)/)
	assert.match(picker, /min-height:\s*48px/)
	assert.match(picker, /padding:\s*8px\s+10px/)
	assert.match(picker, /column-gap:\s*10px/)
	assert.match(picker, /model-picker-list\s*\{[^}]*gap:\s*6px/)
	assert.doesNotMatch(picker, /google\.com\/s2\/favicons|favicon\.ico/)
})

test('create and edit flows share the local-date expiry picker and never use sentinels', () => {
	const createDialog = read('components/user/workspace/user-api-key-create-dialog.vue')
	const editor = read('components/user/workspace/user-api-key-editor-sheet.vue')

	for (const source of [createDialog, editor]) {
		assert.match(source, /UserApiKeyExpiryPicker/)
		assert.match(source, /<user-api-key-expiry-picker/)
		assert.match(source, /expiresAtFromExpirySelection/)
		assert.doesNotMatch(source, /datetime-local|Date\.parse\(|expiresAt\s*[:=]\s*-1/)
	}
	assert.match(createDialog, /createPermanentExpirySelection/)
	assert.match(createDialog, /handleExpiryValidity/)
	assert.match(editor, /expirySelectionFromExpiresAt/)
	assert.match(editor, /handleExpiryValidity/)
})

test('conflicts and endpoint-specific saves never silently overwrite server state', () => {
	const editor = read('components/user/workspace/user-api-key-editor-sheet.vue')

	assert.match(editor, /VERSION_CONFLICT/)
	assert.match(editor, /重新加载/)
	assert.match(editor, /apiKeyApi\.update\([\s\S]*this\.etag/)
	assert.match(editor, /apiKeyApi\.replaceModels\([\s\S]*this\.etag/)
	assert.match(editor, /apiKeyApi\.remove\([\s\S]*this\.etag/)
	assert.match(editor, /MODEL_NOT_FOUND_OR_DISABLED[\s\S]*modelPicker\?\.refresh/)
	assert.match(editor, /selectedModelIds\.length === 0/)
	assert.match(editor, /已停用的原授权|disabledModels/)
})

test('dialogs trap focus, secret Escape is non-destructive and narrow screens become full-width', () => {
	const createDialog = read('components/user/workspace/user-api-key-create-dialog.vue')
	const secretDialog = read('components/user/workspace/user-api-key-secret-dialog.vue')
	const editor = read('components/user/workspace/user-api-key-editor-sheet.vue')

	for (const source of [createDialog, secretDialog, editor]) {
		assert.match(source, /aria-modal="true"/)
		assert.match(source, /trapFocus/)
		assert.match(source, /@media screen and \(max-width:/)
	}
	assert.match(secretDialog, /@keydown\.esc\.stop\.prevent="remind"/)
	assert.match(secretDialog, /我已保存，关闭/)
	assert.match(editor, /width:\s*100%/)
})

test('create and management dialogs keep controls fixed around independently scrolling content', () => {
	const createDialog = read('components/user/workspace/user-api-key-create-dialog.vue')
	const editor = read('components/user/workspace/user-api-key-editor-sheet.vue')

	assert.match(createDialog, /class="api-key-dialog-body"/)
	assert.match(createDialog, /class="api-key-dialog-footer"/)
	assert.match(createDialog,
		/\.api-key-dialog\s*\{[^}]*display:\s*grid[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\)\s+auto[^}]*overflow:\s*hidden/)
	assert.match(createDialog, /\.api-key-dialog-body\s*\{[^}]*overflow-y:\s*auto/)
	assert.match(createDialog, /type="closeempty"/)
	assert.doesNotMatch(createDialog, />\s*×\s*<\/button>/)

	assert.match(editor, /class="api-key-editor-body"/)
	assert.match(editor,
		/\.api-key-editor\s*\{[^}]*display:\s*grid[^}]*grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\)[^}]*overflow:\s*hidden/)
	assert.match(editor, /\.api-key-editor-body\s*\{[^}]*overflow-y:\s*auto/)
	assert.match(editor, /type="closeempty"/)
	assert.doesNotMatch(editor, />\s*×\s*<\/button>/)
})

test('H5 owns the only visible entry and production sources contain no literal complete key', () => {
	const profile = read('components/user/workspace/user-profile-panel.vue')
	const workspace = read('components/user/user-workspace.vue')
	const files = [
		'common/user/api-key-api.js',
		'common/user/api-key-state.js',
		'components/user/workspace/user-api-key-panel.vue',
		'components/user/workspace/user-api-key-create-dialog.vue',
		'components/user/workspace/user-api-key-secret-dialog.vue',
		'components/user/workspace/user-api-key-editor-sheet.vue',
		'../docs/api/openai-compatible-chat-completions.md'
	]

	assert.match(profile, /#ifdef H5[\s\S]*profile-api-key-card[\s\S]*#endif/)
	assert.match(workspace, /#ifdef H5[\s\S]*<user-api-key-panel[\s\S]*#endif/)
	for (const file of files) {
		const source = file.startsWith('../')
			? fs.readFileSync(path.resolve(frontendRoot, file), 'utf8')
			: read(file)
		assert.doesNotMatch(source, /sk-[A-Za-z0-9_-]{86}/)
	}
})

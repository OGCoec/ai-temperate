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

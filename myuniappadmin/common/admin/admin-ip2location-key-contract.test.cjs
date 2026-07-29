const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const source = file => fs.readFileSync(path.join(root, file), 'utf8')

test('administrator key console uses only fixed protected API paths and the shared request chain', () => {
	const api = source('common/admin/admin-ip2location-key-api.js')
	const http = source('common/admin/admin-http.js')

	assert.match(api, /import \{ adminRequest \} from ['"]\.\/admin-http\.js['"]/)
	assert.match(api, /\/api\/admin\/risk\/ip2location\/keys/)
	assert.doesNotMatch(api, /https?:\/\//)
	assert.match(http, /requiredAdminCsrfToken/)
	assert.match(http, /ensureAdminPreAuth/)
	assert.match(http, /Authorization/)
})

test('raw keys remain transient and never enter persistence headers urls or logs', () => {
	const page = source('components/admin/workspace/ip2location-keys-panel.vue')
	const sheet = source('components/admin/ip2location-key-import-sheet.vue')
	const api = source('common/admin/admin-ip2location-key-api.js')

	assert.match(sheet, /resetSensitiveInput/)
	assert.match(sheet, /this\.rawText = ''/)
	assert.match(page, /resetSensitiveInput/)
	for (const text of [page, sheet, api]) {
		assert.doesNotMatch(text, /localStorage|sessionStorage|setStorage|console\.(?:log|info|warn|error)/)
	}
	assert.doesNotMatch(api, /headers\s*:\s*\{[^}]*apiKeys/s)
})

test('the complete set is sorted before twenty-row client pagination', () => {
	const page = source('components/admin/workspace/ip2location-keys-panel.vue')
	const presenter = source('common/admin/ip2location-key-presenter.js')

	assert.match(page, /sortIp2LocationKeys\(this\.allKeys\)/)
	assert.match(page, /paginateIp2LocationKeys\(this\.presentedKeys, this\.page\)/)
	assert.match(presenter, /IP2LOCATION_KEY_PAGE_SIZE = 20/)
	assert.match(presenter, /Math\.min\(5,/)
})

test('android file picker capability fallback preserves multiline paste', () => {
	const picker = source('common/admin/ip2location-key-file-picker.js')
	const sheet = source('components/admin/ip2location-key-import-sheet.vue')

	assert.match(picker, /FILE_PICKER_UNAVAILABLE/)
	assert.match(sheet, /Android 当前版本请使用多行粘贴/)
	assert.match(sheet, /<textarea/)
})

test('route and authenticated dashboard expose the credential console', () => {
	const routes = source('pages.json')
	const dashboard = source('components/admin/workspace/dashboard-panel.vue')

	assert.match(routes, /pages\/risk\/ip2location-keys/)
	assert.match(dashboard, /IP 信誉凭据/)
	assert.match(dashboard, /view: 'ip2location-keys'/)
})

test('registered IP2Location components use Vue-compatible hyphenated tag names', () => {
	const page = source('components/admin/workspace/ip2location-keys-panel.vue')

	assert.match(page, /<ip2-location-key-list/)
	assert.match(page, /<ip2-location-key-import-sheet/)
	assert.doesNotMatch(page, /<ip2location-key-/)
})

test('credential import never accepts client-controlled expiration', () => {
	const sheet = source('components/admin/ip2location-key-import-sheet.vue')
	const api = source('common/admin/admin-ip2location-key-api.js')

	assert.doesNotMatch(sheet, /expiresDate|expiresTime|expiresAt/)
	assert.doesNotMatch(api, /expiresAt/)
	assert.match(sheet, /导入后有效 7 天/)
	assert.match(sheet, /导入后有效 1 个月/)
})

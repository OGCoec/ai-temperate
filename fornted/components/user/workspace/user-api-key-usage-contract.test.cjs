const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function read(name) {
	return fs.readFileSync(path.resolve(__dirname, name), 'utf8')
}

test('management and usage are independent workspace destinations', () => {
	const editor = read('user-api-key-editor-sheet.vue')
	const panel = read('user-api-key-panel.vue')
	const workspace = read('../user-workspace.vue')
	const usage = read('user-api-key-usage-panel.vue')

	assert.doesNotMatch(editor, /user-api-key-usage-panel|usagePanel/)
	assert.match(panel, />调用记录</)
	assert.match(panel, /@click="openUsage\(item\)"/)
	assert.match(workspace, /apiKeyUsage/)
	assert.match(workspace, /<user-api-key-usage-panel/)
	assert.match(workspace, /:android-client="androidClient"/)
	assert.doesNotMatch(usage, /#ifdef H5|#ifndef H5/)
})

test('usage requests are reachable only from manual query refresh and load-more methods', () => {
	const usage = read('user-api-key-usage-panel.vue')

	assert.doesNotMatch(usage, /mounted\s*\(|created\s*\(/)
	assert.match(usage, /handlePageShow\(\) \{\}/)
	assert.match(usage, /selectRange\(mode\)[\s\S]*filterDirty = this\.queried/)
	assert.match(usage, /applyCustomRange\(event\)[\s\S]*filterDirty = this\.queried/)
	assert.match(usage, /queryRecords\(\)[\s\S]*loadFirst|loadPreset/)
	assert.match(usage, /refreshRecords\(\) \{ return this\.queryRecords\(\) \}/)
	assert.match(usage, /async loadMore\(\)/)
	assert.match(usage, /点击“查询记录”后才会向服务器发送请求/)
})

test('A audit layout expands every request locally without rate or price recomputation', () => {
	const usage = read('user-api-key-usage-panel.vue')

	for (const text of [
		'输入 Token', '缓存输入', '未缓存输入 Token', '输出 Token',
		'本次额度', '查看详情', '模型公共 ID', '结束原因', '失败代码'
	]) assert.match(usage, new RegExp(text))
	assert.match(usage, /toggleDetails\(item, index\)/)
	assert.match(usage, /grid-template-columns:[^;]+/)
	assert.match(usage, /min-height:\s*48px/)
	assert.match(usage, /env\(safe-area-inset-bottom\)/)
	assert.match(usage, /requestGeneration/)
	assert.match(usage, /generation !== this\.requestGeneration/)
	assert.doesNotMatch(usage, /RPM|TPM|调用频率|趋势图|模型倍率|inputRatio|outputRatio/)
})

test('query button text stays centered without moving the desktop action area', () => {
	const usage = read('user-api-key-usage-panel.vue')

	assert.match(usage,
		/\.api-key-usage-range button\s*\{[^}]*display:\s*flex[^}]*align-items:\s*center[^}]*justify-content:\s*center[^}]*text-align:\s*center/)
	assert.match(usage,
		/\.api-key-usage-query-actions\s*\{[^}]*justify-content:\s*flex-end/)
	assert.match(usage,
		/\.api-key-usage-query\s*\{[^}]*display:\s*flex[^}]*align-items:\s*center[^}]*justify-content:\s*center[^}]*text-align:\s*center/)
	assert.match(usage,
		/\.api-key-usage-page\.is-android-client button\s*\{[^}]*min-height:\s*48px/)
})

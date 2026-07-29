const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const projectRoot = path.resolve(__dirname, '../..')

function source(relativePath) {
	return fs.readFileSync(path.join(projectRoot, relativePath), 'utf8')
}

test('workspace exposes one gateway model panel and protected navigation entry', () => {
	const workspace = source('pages/admin/workspace.vue')
	const navigation = source('components/admin/admin-side-navigation.vue')
	const pageShell = source('components/admin/admin-page-shell.vue')
	const route = source('common/admin/admin-workspace-route.js')

	assert.match(workspace, /AiModelDiscoveryPanel/)
	assert.match(workspace, /'ai-model-discovery': AiModelDiscoveryPanel/)
	assert.match(navigation, /view: 'ai-model-discovery'/)
	assert.match(navigation, /label: '网关模型'/)
	assert.match(pageShell, /'ai-model-discovery': '网关模型'/)
	assert.match(route, /'ai-model-discovery'/)
})

test('discovery panel keeps refresh failures in memory and never calls uni request directly', () => {
	const panel = source('components/admin/workspace/ai-model-discovery-panel.vue')

	assert.match(panel, /adminCliProxyModelApi\.discover\(\)/)
	assert.match(panel, /this\.models\.length/)
	assert.match(panel, /刷新失败/)
	assert.doesNotMatch(panel, /uni\.request/)
	assert.doesNotMatch(panel, /https?:\/\/|Authorization/)
})

test('discovery prefill leaves ratios capabilities and enabled state unconfigured', () => {
	const form = source('common/admin/admin-ai-model-form.js')
	const createPanel = source('components/admin/workspace/ai-model-create-panel.vue')

	assert.match(form, /createDiscoveredAiModelForm/)
	assert.match(form, /vendor: ''/)
	assert.match(form, /inputRatio: ''/)
	assert.match(form, /cachedInputRatio: ''/)
	assert.match(form, /outputRatio: ''/)
	assert.match(form, /capabilities: \[\]/)
	assert.match(createPanel, /enabled: false/)
	assert.match(createPanel, /discoveryPrefill/)
})

test('cancelled dirty navigation preserves the active discovery prefill', () => {
	const workspace = source('pages/admin/workspace.vue')

	assert.match(workspace, /const previousPrefill = this\.pendingModelPrefill/)
	assert.match(workspace, /const navigatingFromCreate = this\.location\.view === 'ai-model-create'/)
	assert.match(workspace, /if \(!changed\) \{\s*this\.pendingModelPrefill = previousPrefill/)
	assert.match(workspace, /catch \(error\) \{\s*this\.pendingModelPrefill = previousPrefill/)
})

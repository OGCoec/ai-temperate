const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const source = file => fs.readFileSync(path.join(root, file), 'utf8')

test('workspace owns one persistent shell and statically imports every business panel', () => {
	const workspace = source('pages/admin/workspace.vue')
	assert.equal((workspace.match(/<admin-page-shell\b/g) || []).length, 1)
	for (const panel of [
		'DashboardPanel', 'AiModelListPanel', 'AiModelCreatePanel', 'AiModelDetailPanel',
		'AiModelIconsPanel', 'Ip2locationKeysPanel', 'MailInspectionPanel'
	]) {
		assert.match(workspace, new RegExp(`import ${panel} from`))
	}
	assert.match(workspace, /components:\s*\{[^}]*AdminPageShell/)
	assert.doesNotMatch(workspace, /v-if="adminRouteReady"/)
	assert.doesNotMatch(workspace, /mode="out-in"/)
	assert.match(workspace, /errorCaptured\s*\(/)
	assert.match(workspace, /schedulePanelActivation\s*\(/)
	assert.match(workspace, /panel-render-error/)
	assert.doesNotMatch(workspace, /__AIT_ADMIN_WORKSPACE_INITIAL_PATH__/)
})

test('administrator workspace keeps the active panel mounted when browser visibility changes', () => {
	const app = source('App.vue')
	const workspace = source('pages/admin/workspace.vue')
	const lifecycleRefreshPattern = /\b(?:onShow|onHide)\s*\(/
	const hardReloadPattern = /\b(?:window\.)?location\.reload\s*\(/
	const visibilityListenerPattern = /addEventListener\(\s*['"](?:visibilitychange|focus|blur)['"]/

	for (const [name, frontendSource] of [
		['App.vue', app],
		['pages/admin/workspace.vue', workspace]
	]) {
		assert.doesNotMatch(frontendSource, lifecycleRefreshPattern, name)
		assert.doesNotMatch(frontendSource, hardReloadPattern, name)
		assert.doesNotMatch(frontendSource, visibilityListenerPattern, name)
	}
	assert.doesNotMatch(workspace, /shouldRevalidateAdminSession|workspaceHidden/)
})

test('business panels do not own page-stack navigation or page-level guards', () => {
	const panelDirectory = path.join(root, 'components', 'admin', 'workspace')
	for (const name of fs.readdirSync(panelDirectory).filter(name => name.endsWith('.vue'))) {
		const panel = fs.readFileSync(path.join(panelDirectory, name), 'utf8')
		assert.doesNotMatch(panel, /uni\.(?:navigateTo|redirectTo)\s*\(/, name)
		assert.doesNotMatch(panel, /uni\.request\s*\(/, name)
		assert.doesNotMatch(panel, /adminApi\.bootstrap\s*\(/, name)
		assert.doesNotMatch(panel, /adminRouteReady|runAfterAdminRouteGuard/, name)
	}
})

test('legacy pages only replace into the canonical workspace and contain no business API', () => {
	for (const name of [
		'pages/ai-models/index.vue',
		'pages/ai-models/create.vue',
		'pages/ai-models/detail.vue',
		'pages/ai-model-icons/index.vue',
		'pages/risk/ip2location-keys.vue',
		'pages/mail-inspection/openai/index.vue',
		'pages/mail-inspection/kiro/index.vue',
		'pages/mail-inspection/ip2location/index.vue'
	]) {
		const page = source(name)
		assert.match(page, /redirectLegacyAdminWorkspace/)
		assert.doesNotMatch(page, /adminApi|adminMailInspectionApi|uni\.request/)
	}
})

test('mail inspection removes duplicate first-level tabs but keeps centred IP2Location modes', () => {
	const panel = source('components/admin/workspace/mail-inspection-panel.vue')
	const workspace = source('pages/admin/workspace.vue')
	assert.doesNotMatch(panel, /MailInspectionBusinessTabs/)
	assert.match(workspace, /registration/)
	assert.match(workspace, /verify-link/)
	assert.match(panel, /IP2LOCATION_REGISTRATION/)
	assert.match(panel, /IP2LOCATION_VERIFY_LINK/)
	assert.match(panel, /mode-tabs/)
	assert.match(panel, /justify-content:\s*center/)
	assert.match(panel, /onWorkspaceActivated\(\)[\s\S]*resume\(\)/)
	assert.match(panel, /onWorkspaceDeactivated\(\)[\s\S]*pause\(\)/)
})

test('responsive shell provides desktop sidebar, tablet rail, mobile drawer and safe areas', () => {
	const shell = source('components/admin/admin-page-shell.vue')
	assert.match(shell, /grid-template-columns:\s*292px/)
	assert.match(shell, /@media \(min-width: 768px\) and \(max-width: 1023px\)/)
	assert.match(shell, /drawer-scrim/)
	assert.match(shell, /max-width:\s*320px/)
	assert.match(shell, /86vw/)
	assert.match(shell, /env\(safe-area-inset-top\)/)
	assert.match(shell, /prefers-reduced-motion/)
})

test('global roots remain dark while the workspace session is being verified', () => {
	const app = source('App.vue')
	const index = source('index.html')
	const initialShell = source('static/bootstrap/initial-shell.css')
	const workspace = source('pages/admin/workspace.vue')
	assert.match(app, /html[\s\S]*body[\s\S]*#app[\s\S]*uni-page[\s\S]*page/)
	assert.match(app, /#080b0d/)
	assert.match(index, /\/static\/bootstrap\/initial-shell\.css/)
	assert.match(initialShell, /html[\s\S]*body[\s\S]*#app[\s\S]*background:\s*#080b0d/)
	assert.match(workspace, /VERIFYING_SESSION/)
	assert.match(workspace, /TRANSIENT_FAILURE/)
	assert.match(workspace, /workspace-skeleton/)
})

test('administrator production hosting keeps SPA fallback and split cache policy', () => {
	const headers = source('public/_headers')
	const redirects = source('public/_redirects')
	const verifier = fs.readFileSync(
		path.resolve(root, '..', 'scripts', 'verify-admin-h5-production.ps1'),
		'utf8'
	)

	assert.match(redirects, /\/\* \/index\.html 200/)
	assert.match(headers, /\/index\.html[\s\S]*no-cache, no-store, must-revalidate/)
	assert.match(headers, /\/assets\/\*[\s\S]*max-age=31536000, immutable/)
	assert.match(headers, /X-Content-Type-Options: nosniff/)
	assert.match(headers, /X-Frame-Options: DENY/)
	for (const marker of ['.vue', '/@vite/', '/@fs/', '@vite/client', 'pages-json-js']) {
		assert.match(verifier, new RegExp(marker.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
	}
})

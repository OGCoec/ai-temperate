const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const projectRoot = path.resolve(__dirname, '..', '..')
const read = relativePath => fs.readFileSync(path.join(projectRoot, relativePath), 'utf8')

const pages = [
	'pages/mail-inspection/openai/index.vue',
	'pages/mail-inspection/kiro/index.vue',
	'pages/mail-inspection/ip2location/index.vue'
]
const components = [
	'components/admin/workspace/mail-inspection-panel.vue',
	'components/admin/mail-inspection-credential-input.vue',
	'components/admin/mail-inspection-job-progress.vue',
	'components/admin/mail-inspection-recovered-jobs.vue',
	'components/admin/mail-inspection-result-list.vue',
	'components/admin/mail-inspection-sensitive-credentials.vue'
]

test('dashboard exposes one entry and pages provide three custom-navigation routes', () => {
	const routes = JSON.parse(read('pages.json'))
	const dashboard = read('components/admin/workspace/dashboard-panel.vue')
	const byPath = new Map(routes.pages.map(page => [page.path, page.style]))

	for (const route of [
		'pages/mail-inspection/openai/index',
		'pages/mail-inspection/kiro/index',
		'pages/mail-inspection/ip2location/index'
	]) {
		assert.equal(byPath.get(route)?.navigationStyle, 'custom')
		assert.equal(byPath.get(route)?.backgroundColor, '#080b0d')
		assert.equal(byPath.get(route)?.['app-plus']?.softinputMode, 'adjustResize')
	}
	assert.match(dashboard, /邮箱证据检查/)
	assert.match(dashboard, /view: 'mail-openai'/)
})

test('business concurrency and restart recovery require explicit administrator actions', () => {
	const input = read('components/admin/mail-inspection-credential-input.vue')
	const workspace = read('components/admin/workspace/mail-inspection-panel.vue')
	const recovery = read('components/admin/mail-inspection-recovered-jobs.vue')
	const api = read('common/admin/admin-mail-inspection-api.js')

	for (const value of [1, 4, 8, 16, 32, 64]) {
		assert.match(input, new RegExp(`\\b${value}\\b`))
	}
	assert.match(input, /businessConcurrency/)
	assert.match(input, /class="concurrency-preset-button"/)
	assert.match(input, /@tap="selectBusinessConcurrency\(value\)"/)
	assert.match(input, /@keydown\.enter\.prevent="selectBusinessConcurrency\(value\)"/)
	assert.match(input, /:aria-pressed="Number\(businessConcurrency\) === value"/)
	assert.match(input, /\.concurrency-preset-button\s*\{[^}]*display:\s*flex;[^}]*align-items:\s*center;[^}]*justify-content:\s*center;/s)
	assert.match(input, /\.concurrency-preset-button:disabled/)
	assert.match(workspace, /const next = this\.controller\.setBusinessConcurrency\(Number\(value\)\)/)
	assert.match(workspace, /this\.viewState = next/)
	assert.match(recovery, /等待管理员批准/)
	assert.match(recovery, /批准继续处理全部剩余项/)
	assert.match(recovery, /maskedEmail/)
	assert.doesNotMatch(recovery, /refreshToken|clientId|password/)
	assert.match(api, /\/recovered-jobs/)
	assert.match(api, /\/resume/)
})

test('H5 development assets explicitly disable browser caching', () => {
	const vite = read('vite.config.js')

	assert.match(vite, /headers:\s*\{/)
	assert.match(vite, /'Cache-Control':\s*'no-store, max-age=0'/)
	assert.match(vite, /Pragma:\s*'no-cache'/)
})

test('workspace validates one shared API instance before restoring SSE', () => {
	const workspace = read('components/admin/workspace/mail-inspection-panel.vue')
	const contract = read('common/admin/mail-inspection-api-contract.js')

	assert.match(workspace, /requireAdminMailInspectionApi\(adminMailInspectionApi\)/)
	assert.match(workspace, /api:\s*this\.mailInspectionApi/)
	assert.match(workspace, /this\.mailInspectionApi\.getRecoveredJobs\(\)/)
	assert.match(contract, /MAIL_INSPECTION_FRONTEND_VERSION_MISMATCH/)
	assert.match(contract, /前端资源版本不一致，请清除本站缓存后重新加载。/)
})

test('three legacy mail pages redirect and IP2Location alone exposes two workspace modes', () => {
	const sources = pages.map(read)
	const workspace = read('pages/admin/workspace.vue')
	const panel = read('components/admin/workspace/mail-inspection-panel.vue')
	assert.ok(sources.every(source => /redirectLegacyAdminWorkspace/.test(source)))
	assert.doesNotMatch(panel, /MailInspectionBusinessTabs/)
	assert.match(workspace, /IP2LOCATION_REGISTRATION/)
	assert.match(workspace, /IP2LOCATION_VERIFY_LINK/)
	assert.match(panel, /showIp2Modes/)
})

test('IP2Location mode labels are centered inside both controls', () => {
	const workspace = read('components/admin/workspace/mail-inspection-panel.vue')
	const rule = workspace.match(/\.mode-tabs button\s*\{([^}]*)\}/s)?.[1] || ''

	assert.match(rule, /display:\s*flex;/)
	assert.match(rule, /align-items:\s*center;/)
	assert.match(rule, /justify-content:\s*center;/)
	assert.match(rule, /text-align:\s*center;/)
})

test('mail pages use the protected API and never call network primitives directly', () => {
	const api = read('common/admin/admin-mail-inspection-api.js')
	const ui = [...pages, ...components].map(read).join('\n')

	assert.match(api, /import \{ adminRequest \} from '\.\/admin-http\.js'/)
	assert.doesNotMatch(`${api}\n${ui}`, /uni\.request/)
	assert.match(ui, /role="alert"/)
	assert.match(ui, /aria-live="polite"/)
	assert.match(ui, /env\(safe-area-inset-bottom\)/)
	assert.match(ui, /@media \(max-width: 767px\)/)
	assert.match(ui, /@media \(prefers-reduced-motion: reduce\)/)
})

test('raw credential recovery is limited to retry exhaustion and explicit unregistered results', () => {
	const presenter = read('common/admin/mail-inspection-presenter.js')
	const results = read('components/admin/mail-inspection-result-list.vue')
	const workspace = read('components/admin/workspace/mail-inspection-panel.vue')

	assert.match(presenter, /retryable === true && result\.retryExhausted === true/)
	assert.match(presenter, /recoverUnregisteredCredentialLines/)
	assert.match(results, /复制原始重试凭证/)
	assert.doesNotMatch(results, /password|refreshToken|credentialLines/)
	assert.match(workspace, /recoverUnregisteredCredentialLines/)
	assert.match(workspace, /confirmRevealUnregistered/)
})

test('business result controls vary by inspection type without parsing labels', () => {
	const presenter = read('common/admin/mail-inspection-presenter.js')
	const results = read('components/admin/mail-inspection-result-list.vue')

	for (const category of [
		'UNREGISTERED',
		'REGISTERED',
		'RESTRICTED',
		'VERIFY_FOUND',
		'VERIFY_NOT_FOUND',
		'VERIFY_MALFORMED'
	]) {
		assert.match(presenter, new RegExp(category))
	}
	assert.match(results, /mailInspectionResultGroupOptions\(this\.inspectionType\)/)
	assert.match(results, /result\.businessCategory === this\.activeGroup/)
	assert.doesNotMatch(results, /includes\(.*未注册/)
})

test('workspace hides unregistered plaintext across lifecycle and destructive actions', () => {
	const workspace = read('components/admin/workspace/mail-inspection-panel.vue')
	const store = read('common/admin/admin-mail-inspection-session-store.js')

	assert.match(workspace, /visibleUnregisteredCredentialLines/)
	assert.match(workspace, /unregisteredCredentialsRevealed\s*\?\s*this\.unregisteredCredentialLines/)
	assert.match(workspace, /pause\(\)\s*\{[\s\S]*hideUnregisteredCredentials\(\)/)
	assert.match(workspace, /confirmClear\(\)[\s\S]*resetSensitiveCredentialView\(\)/)
	assert.match(workspace, /confirmClearAll\(\)[\s\S]*resetSensitiveCredentialView\(\)/)
	assert.match(store, /clearMailInspectionCredentialExports/)
})

test('missing active job expands credentials and shows one informational recovery banner', () => {
	const workspace = read('components/admin/workspace/mail-inspection-panel.vue')

	assert.match(workspace, /previousJobId\s*&&\s*!next\.jobId/)
	assert.match(
		workspace,
		/next\.message\s*===\s*MAIL_INSPECTION_MISSING_JOB_MESSAGE/)
	assert.match(workspace, /this\.inputCollapsed\s*=\s*false/)
	assert.match(workspace, /this\.bannerMessage\s*=\s*next\.message/)
	assert.match(workspace, /this\.bannerType\s*=\s*'info'/)
})

test('mail inspection exposes the ten-thousand-line limit and virtualizes large result sets', () => {
	const parser = read('common/admin/mail-inspection-credential-parser.js')
	const input = read('components/admin/mail-inspection-credential-input.vue')
	const progress = read('components/admin/mail-inspection-job-progress.vue')
	const results = read('components/admin/mail-inspection-result-list.vue')

	assert.match(parser, /CREDENTIAL_LINES_EMPTY/)
	assert.match(parser, /MAX_CREDENTIAL_LINES = 10000/)
	assert.doesNotMatch(`${input}\n${progress}`, /1～100|\/100 行/)
	assert.match(input, /总量不超过 1 MiB/)
	assert.match(input, /10,000 行/)
	assert.match(results, /const VIRTUAL_WINDOW_SIZE = 40/)
	assert.match(results, /visibleResults/)
	assert.match(results, /topSpacerStyle/)
	assert.match(results, /bottomSpacerStyle/)
	assert.match(results, /aria-posinset/)
	assert.match(results, /aria-setsize/)
})

test('Android mail credentials use a separate AES-GCM key and auth invalidation clears them', () => {
	const store = read('common/admin/admin-mail-inspection-session-store.js')
	const authApi = read('common/admin/admin-api.js')
	const http = read('common/admin/admin-http.js')
	const sessionExpiry = read('common/admin/admin-session-expiry-navigation.js')

	assert.match(store, /ait-admin-mail-inspection-v3/)
	assert.match(store, /AES\/GCM\/NoPadding/)
	assert.match(store, /AndroidKeyStore/)
	assert.doesNotMatch(store, /ait-admin-credentials-v1/)
	assert.match(authApi, /clearAdminMailInspectionSession/)
	assert.match(sessionExpiry, /clearAdminMailInspectionSession/)
	assert.match(http, /handleAdminSessionInvalid/)
})

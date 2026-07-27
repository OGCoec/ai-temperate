const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const projectRoot = path.resolve(__dirname, '../..')
const source = file => fs.readFileSync(path.join(projectRoot, file), 'utf8')

test('administrator production H5 uses same-origin API while Android keeps the API hostname', () => {
	const config = source('common/admin/admin-config.js')
	const http = source('common/admin/admin-http.js')
	const api = source('common/admin/admin-api.js')

	assert.match(config, /let adminApiBaseUrl = 'https:\/\/api\.niko000o\.site'/)
	assert.match(config, /adminApiBaseUrl = 'https:\/\/localhost:6655'/)
	assert.match(config, /h5Hostname === 'admin\.niko000o\.site'/)
	assert.match(config, /adminApiBaseUrl = ''/)
	assert.match(http, /url: `\$\{ADMIN_API_BASE_URL\}\$\{path\}`/)
	assert.doesNotMatch(api, /https?:\/\//)
})

test('administrator runs cookie-scope migration before API requests and retries 428 once', () => {
	const migration = source('common/admin/admin-cookie-scope-migration.js')
	const http = source('common/admin/admin-http.js')
	const app = source('App.vue')

	assert.match(migration, /\/api\/admin\/_edge\/cookie-scope/)
	assert.match(migration, /X-AIT-Cookie-Scope-Reset/)
	assert.match(migration, /clearAdminSecureState\(\)/)
	assert.match(http, /await ensureAdminCookieScopeMigration\(\)/)
	assert.match(http, /EDGE_COOKIE_SCOPE_RESET_REQUIRED/)
	assert.match(http, /!retryState\.migration/)
	assert.match(http, /\{ \.\.\.retryState, migration: true \}/)
	assert.match(app, /ensureAdminCookieScopeMigration/)
})

test('administrator Android hCaptcha page reuses the API hostname', () => {
	const hcaptcha = source('common/admin/admin-hcaptcha.js')

	assert.match(hcaptcha, /import \{ ADMIN_API_BASE_URL, adminClientPlatform \}/)
	assert.match(hcaptcha, /\$\{ADMIN_API_BASE_URL\}\/api\/admin\/auth\/hcaptcha\/page/)
	assert.doesNotMatch(hcaptcha, /admin\.niko000o\.site/)
})

test('administrator CSP uses self for production backend connections', () => {
	const index = source('index.html')
	const connectSource = index.match(/connect-src ([^;]+)/)?.[1]
	const scriptSource = index.match(/script-src ([^;]+)/)?.[1]

	assert.ok(connectSource, '管理员页面必须声明 connect-src CSP')
	assert.ok(scriptSource, '管理员页面必须声明 script-src CSP')
	assert.match(connectSource, /'self'/)
	assert.match(connectSource, /https:\/\/localhost:6655/)
	assert.doesNotMatch(connectSource, /https:\/\/api\.niko000o\.site/)
	assert.doesNotMatch(scriptSource, /'unsafe-inline'/)
})

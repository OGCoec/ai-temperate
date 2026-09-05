const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function readProjectFile(relativePath) {
	return fs.readFileSync(path.resolve(__dirname, '..', '..', relativePath), 'utf8')
}

function readRootProjectFile(relativePath) {
	return fs.readFileSync(path.resolve(__dirname, '..', '..', '..', relativePath), 'utf8')
}

async function loadAuthConfigModule() {
	const source = readProjectFile('common/auth/config.js')
	const encoded = Buffer.from(source, 'utf8').toString('base64')
	return import(`data:text/javascript;base64,${encoded}`)
}

test('uses the primary domain for Android and same-origin production H5 API', () => {
	const source = readProjectFile('common/auth/config.js')

	assert.match(source, /let authApiBaseUrl = 'https:\/\/niko000o\.site'/)
	assert.match(source, /authApiBaseUrl = 'https:\/\/localhost:6655'/)
	assert.match(source, /h5Hostname === 'niko000o\.site'/)
	assert.match(source, /authApiBaseUrl = ''/)
	assert.match(source, /window\.location\.hostname/)
	assert.doesNotMatch(source, /https:\/\/api\.niko000o\.site/)
	assert.doesNotMatch(source, /http:\/\/(?:127\.0\.0\.1|localhost):6655/)
})

test('maps only Android runtime information to the native edge transport', async () => {
	const { resolveClientPlatform } = await loadAuthConfigModule()

	assert.equal(resolveClientPlatform('android'), 'ANDROID')
	assert.equal(resolveClientPlatform('ANDROID'), 'ANDROID')
	assert.equal(resolveClientPlatform(' ios '), 'H5')
	assert.equal(resolveClientPlatform(''), 'H5')
	assert.equal(resolveClientPlatform(undefined), 'H5')
	assert.equal(resolveClientPlatform('harmony'), 'H5')
})

test('uses explicit compile-time branches for H5, Android and WeChat Mini Program', () => {
	const source = readProjectFile('common/auth/config.js')

	assert.match(source, /#ifdef MP-WEIXIN[\s\S]*return ClientPlatform\.WECHAT_MINI_PROGRAM[\s\S]*#endif/)
	assert.match(source, /#ifdef APP-PLUS[\s\S]*uni\.getSystemInfoSync\(\)\?\.platform[\s\S]*#endif/)
	assert.match(source, /#ifdef H5[\s\S]*return ClientPlatform\.H5[\s\S]*#endif/)
	assert.doesNotMatch(source, /#ifndef APP-PLUS[\s\S]*return ['"]H5['"]/)
	assert.doesNotMatch(source, /#ifdef APP-(?:ANDROID|IOS)/)
})

test('Android HBuilderX launcher uses the standard uni-app base', () => {
	const launch = JSON.parse(readProjectFile('.hbuilderx/launch.json'))
	const android = launch.configurations.find(
		configuration => configuration.type === 'uni-app:app-android')

	assert.ok(android, '必须存在 Android 运行配置')
	assert.equal(android.playground, 'standard')
	assert.equal(android.packageName, 'io.dcloud.HBuilder')
	assert.notEqual(android.packageName, 'com.android.chrome')
})

test('runs cookie-scope migration before ordinary API requests and retries 428 once', () => {
	const migration = readProjectFile('common/auth/cookie-scope-migration.js')
	const httpClient = readProjectFile('common/auth/http-client.js')
	const app = readProjectFile('App.vue')

	assert.match(migration, /\/api\/_edge\/cookie-scope/)
	assert.match(migration, /X-AIT-Cookie-Scope-Reset/)
	assert.match(migration, /clearSession\(\)/)
	assert.match(migration, /COOKIE_SCOPE_MIGRATION_GATE/)
	assert.match(migration, /COOKIE_SCOPE_MIGRATION_REQUEST_STARTED/)
	assert.match(migration, /COOKIE_SCOPE_MIGRATION_RESPONSE_RECEIVED/)
	assert.match(migration, /authDiagnosticRequestHeaders/)
	assert.match(httpClient, /await ensureCookieScopeMigration\(\{/)
	assert.match(httpClient, /EDGE_COOKIE_SCOPE_RESET_REQUIRED/)
	assert.match(httpClient, /migrationRetried/)
	assert.match(httpClient, /COOKIE_SCOPE_428_RECOVERY_STARTED/)
	assert.match(httpClient, /COOKIE_SCOPE_428_RECOVERY_COMPLETED/)
	assert.match(httpClient, /COOKIE_SCOPE_428_RECOVERY_FAILED/)
	assert.match(httpClient, /triggerClientRequestId/)
	assert.match(app, /ensureCookieScopeMigration/)
})

test('ordinary H5 CSP uses self for API connections', () => {
	const index = readProjectFile('index.html')
	const connectSource = index.match(/connect-src ([^;]+)/)?.[1]
	const scriptSource = index.match(/script-src ([^;]+)/)?.[1]

	assert.ok(connectSource, '普通用户页面必须声明 connect-src CSP')
	assert.ok(scriptSource, '普通用户页面必须声明 script-src CSP')
	assert.match(connectSource, /'self'/)
	assert.match(connectSource, /https:\/\/localhost:6655/)
	assert.match(connectSource, /wss:\/\/niko000o\.site/)
	assert.doesNotMatch(connectSource, /https:\/\/api\.niko000o\.site/)
	assert.doesNotMatch(connectSource, /wss:\/\/api\.niko000o\.site/)
	assert.doesNotMatch(scriptSource, /'unsafe-inline'/)
})

test('ordinary H5 CSP permits HTTPS model icons without broadening active content sources', () => {
	const index = readProjectFile('index.html')
	const imageSource = index.match(/img-src ([^;]+)/)?.[1]
	const scriptSource = index.match(/script-src ([^;]+)/)?.[1]

	assert.ok(imageSource, 'ordinary user pages must declare img-src CSP')
	assert.ok(scriptSource, 'ordinary user pages must declare script-src CSP')
	assert.match(imageSource, /(?:^|\s)https:(?:\s|$)/)
	assert.doesNotMatch(imageSource, /(?:^|\s)(?:http:|\*)(?:\s|$)/)
	assert.doesNotMatch(scriptSource, /(?:^|\s)(?:https:|\*)(?:\s|$)/)
})

test('ordinary H5 CSP permits blob-backed local attachment previews', () => {
	const index = readProjectFile('index.html')
	const imageSource = index.match(/img-src ([^;]+)/)?.[1]

	assert.ok(imageSource, 'ordinary user pages must declare img-src CSP')
	assert.match(imageSource, /(?:^|\s)blob:(?:\s|$)/)
	assert.doesNotMatch(imageSource, /(?:^|\s)\*(?:\s|$)/)
})

test('ordinary H5 CSP permits presigned uploads only to the conversation OSS origin', () => {
	const index = readProjectFile('index.html')
	const connectSource = index.match(/connect-src ([^;]+)/)?.[1]

	assert.ok(connectSource, 'ordinary user pages must declare connect-src CSP')
	assert.match(connectSource, /https:\/\/ihaveaplan\.oss-us-west-1\.aliyuncs\.com/)
	assert.doesNotMatch(connectSource, /(?:^|\s)(?:https:|\*)(?:\s|$)/)
	assert.doesNotMatch(connectSource, /\*\.oss-us-west-1\.aliyuncs\.com/)
})

test('ordinary H5 CSP permits generated video playback only from the conversation OSS origin', () => {
	const index = readProjectFile('index.html')
	const mediaSource = index.match(/media-src ([^;]+)/)?.[1]

	assert.ok(mediaSource, 'ordinary user pages must declare media-src CSP')
	assert.match(mediaSource, /(?:^|\s)'self'(?:\s|$)/)
	assert.match(mediaSource, /(?:^|\s)blob:(?:\s|$)/)
	assert.match(mediaSource, /https:\/\/ihaveaplan\.oss-us-west-1\.aliyuncs\.com/)
	assert.doesNotMatch(mediaSource, /(?:^|\s)(?:https:|\*)(?:\s|$)/)
	assert.doesNotMatch(mediaSource, /\*\.oss-us-west-1\.aliyuncs\.com/)
})

test('ordinary H5 CSP permits sandboxed blob HTML previews only as frame content', () => {
	const index = readProjectFile('index.html')
	const frameSource = index.match(/frame-src ([^;]+)/)?.[1]
	const scriptSource = index.match(/script-src ([^;]+)/)?.[1]

	assert.ok(frameSource, 'ordinary user pages must declare frame-src CSP')
	assert.match(frameSource, /(?:^|\s)blob:(?:\s|$)/)
	assert.match(frameSource, /https:\/\/challenges\.cloudflare\.com/)
	assert.match(frameSource, /https:\/\/ai-temperate-html-preview\.pages\.dev/)
	assert.doesNotMatch(frameSource, /https:\/\/(?:localhost|127\.0\.0\.1):4174/)
	assert.doesNotMatch(frameSource, /(?:^|\s)\*(?:\s|$)/)
	assert.doesNotMatch(scriptSource, /(?:^|\s)blob:(?:\s|$)/)
})

test('ordinary H5 CSP permits form submission only to self and the exact BAR origin', () => {
	const index = readProjectFile('index.html')
	const formSource = index.match(/form-action ([^;"']*(?:'[^']*'[^;"']*)*)/)?.[1]

	assert.ok(formSource, 'ordinary user pages must declare form-action CSP')
	const tokens = formSource.trim().split(/\s+/).sort()
	assert.deepEqual(tokens, ["'self'", 'https://ihaveagoddamnplan.com'].sort())
	assert.doesNotMatch(formSource, /(?:^|\s)(?:\*|https:|http:|\*\.ihaveagoddamnplan\.com)(?:\s|$)/)
})

test('login page eagerly initializes browser CSRF without removing the unsafe-request fallback', () => {
	const login = readProjectFile('pages/auth/login.vue')
	const httpClient = readProjectFile('common/auth/http-client.js')
	const loginOnLoad = login.slice(login.indexOf('\t\tonLoad()'), login.indexOf('\t\tonShow()'))

	assert.match(login, /import \{ initializeBrowserCsrf \} from '@\/common\/auth\/http-client\.js'/)
	assert.match(loginOnLoad, /this\.initializePageCsrf\(\)/)
	assert.match(login, /if \(clientPlatform\(\) !== 'H5'\) return/)
	assert.match(login, /const token = await initializeBrowserCsrf\(\)/)
	assert.match(login, /this\.error = authErrorMessage\(error\)/)
	assert.match(httpClient, /browserCsrfToken\(\) \|\| await initializeBrowserCsrf\(\)/)
})

test('cloudflare launchers split production rollback, frontend development, and api tunnel profiles', () => {
	const startFrontend = readRootProjectFile('scripts/cloudflare/windows-legacy-tunnel/start-cloudflare-frontend.bat')
	const stopFrontend = readRootProjectFile('scripts/cloudflare/windows-legacy-tunnel/stop-cloudflare-frontend.bat')
	const startFrontendDev = readRootProjectFile('scripts/cloudflare/windows-legacy-tunnel/start-cloudflare-frontend-dev.bat')
	const stopFrontendDev = readRootProjectFile('scripts/cloudflare/windows-legacy-tunnel/stop-cloudflare-frontend-dev.bat')
	const startApi = readRootProjectFile('scripts/cloudflare/windows-legacy-tunnel/start-cloudflare-api.bat')
	const stopApi = readRootProjectFile('scripts/cloudflare/windows-legacy-tunnel/stop-cloudflare-api.bat')
	const startScript = readRootProjectFile('scripts/cloudflare/windows-legacy-tunnel/start-cloudflare.ps1')
	const stopScript = readRootProjectFile('scripts/cloudflare/windows-legacy-tunnel/stop-cloudflare.ps1')

	assert.match(startFrontend, /-Profile frontend/)
	assert.match(stopFrontend, /-Profile frontend/)
	assert.match(startFrontendDev, /-Profile frontend-dev/)
	assert.match(startFrontendDev, /CF_FRONTEND_DEV_TUNNEL_ID=16698f57-7037-4252-adfe-4cc1319bf55c/)
	assert.match(stopFrontendDev, /-Profile frontend-dev/)
	assert.match(startApi, /-Profile api/)
	assert.match(stopApi, /-Profile api/)
	assert.match(startScript, /CF_FRONTEND_TUNNEL_ID/)
	assert.match(startScript, /CF_FRONTEND_DEV_TUNNEL_ID/)
	assert.match(startScript, /CF_API_TUNNEL_ID/)
	assert.match(startScript, /Hostname\s*=\s*"niko000o\.site"/)
	assert.match(startScript, /Hostname\s*=\s*"dev\.niko000o\.site"/)
	assert.match(startScript, /Hostname\s*=\s*"api\.niko000o\.site"/)
	assert.match(startScript, /DefaultPort\s*=\s*3000/)
	assert.match(startScript, /DefaultPort\s*=\s*6655/)
	assert.match(startScript, /'\^\\d\+\$'/)
	assert.match(startScript, /\$parsedPort\s+-ge\s+1\s+-and\s+\$parsedPort\s+-le\s+65535/)
	assert.match(stopScript, /cloudflared-frontend\.pid\.json/)
	assert.match(stopScript, /cloudflared-frontend-dev\.pid\.json/)
	assert.match(stopScript, /cloudflared-api\.pid\.json/)
})

test('configures the H5 Vite server with the external PKCS12 certificate', () => {
	const source = readProjectFile('vite.config.js')

	assert.match(source, /port:\s*3000/)
	assert.match(source, /strictPort:\s*true/)
	assert.match(source, /host:\s*'127\.0\.0\.1'/)
	assert.doesNotMatch(source, /host:\s*'localhost'/)
	assert.match(source, /pfx:/)
	assert.match(source, /SERVER_SSL_KEY_STORE_PASSWORD/)
	assert.match(source, /LOCAL_HTTPS_P12_PATH/)
	assert.doesNotMatch(source, /changeit/)
})

test('defaults HTML previews to the public Pages origin and rejects loopback origins', () => {
	const source = readProjectFile('vite.config.js')

	assert.match(source, /AI_HTML_PREVIEW_PRODUCTION_ORIGIN/)
	assert.doesNotMatch(source, /NODE_ENV === 'production'[\s\S]*https:\/\/localhost:4174/)
	assert.match(source, /normalizeAiHtmlPreviewOrigin/)
})

test('fails closed when the H5 certificate environment is incomplete', () => {
	const source = readProjectFile('vite.config.js')

	assert.match(source, /LOCAL_HTTPS_ENABLED/)
	assert.match(source, /throw new Error/)
})

test('local HTTPS launcher explicitly injects environment into IDE child processes', () => {
	const source = readRootProjectFile('scripts/https/start-local-https-dev.ps1')
	const originList = source.slice(
		source.indexOf('function Merge-OriginList'),
		source.indexOf('function Merge-HostnameList')
	)
	const localEnvironmentStart = source.indexOf('$localHttpsEnvironment = @{')
	const localEnvironmentEnd = source.indexOf('  if (-not $HBuilderXOnly)', localEnvironmentStart)
	const localEnvironment = source.slice(localEnvironmentStart, localEnvironmentEnd)
	const hostnameList = source.slice(
		source.indexOf('function Merge-HostnameList'),
		source.indexOf('$projectRoot =')
	)

	assert.match(source, /System\.Diagnostics\.ProcessStartInfo/)
	assert.match(source, /UseShellExecute\s*=\s*\$false/)
	assert.match(source, /Start-ProcessWithLocalHttpsEnvironment/)
	assert.match(source, /\$startInfo\.Environment\[\$name\]\s*=\s*\[string\]\$Environment\[\$name\]/)
	assert.match(source, /LOCAL_HTTPS_ENABLED/)
	assert.match(source, /LOCAL_HTTPS_P12_PATH/)
	assert.match(localEnvironment, /"AI_HTML_PREVIEW_ORIGIN"\s*=\s*"https:\/\/ai-temperate-html-preview\.pages\.dev"/)
	assert.match(source, /SERVER_SSL_KEY_STORE_PASSWORD/)
	assert.match(source, /"AUTH_COOKIE_DOMAIN"[\s\S]*"ADMIN_COOKIE_DOMAIN"[\s\S]*"ADMIN_CSRF_COOKIE_DOMAIN"[\s\S]*\$processEnvironment\.Remove\(\$cookieDomainVariable\)/)
	assert.doesNotMatch(localEnvironment, /(?:AUTH|ADMIN(?:_CSRF)?)_COOKIE_DOMAIN/)
	assert.match(source, /api\.niko000o\.site/)
	assert.match(originList, /"https:\/\/dev\.niko000o\.site"/)
	assert.match(hostnameList, /"dev\.niko000o\.site"/)
	assert.doesNotMatch(source, /Write-Host.*SERVER_SSL_KEY_STORE_PASSWORD/)
})

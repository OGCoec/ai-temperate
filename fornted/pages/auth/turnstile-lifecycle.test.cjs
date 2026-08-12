const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const frontendRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
	return fs.readFileSync(path.join(frontendRoot, relativePath), 'utf8')
}

test('Turnstile component delivers each token once and handles every terminal callback', () => {
	const source = read('components/auth/auth-turnstile.vue')

	assert.match(source, /tokenDelivered/)
	assert.match(source, /if \(this\.tokenDelivered\) return/)
	assert.match(source, /resetAfterServerRejection/)
	assert.match(source, /expired-callback/)
	assert.match(source, /timeout-callback/)
	assert.match(source, /retry:\s*'auto'/)
	assert.match(source, /'retry-interval':\s*8000/)
	assert.doesNotMatch(source, /console\.(?:log|info|warn|error)\([^\n]*token/)
})

test('Turnstile waits for the provider ready callback and can reload a failed SDK', () => {
	const source = read('components/auth/auth-turnstile.vue')

	assert.match(source, /TURNSTILE_SCRIPT_ID/)
	assert.match(source, /TURNSTILE_READY_CALLBACK/)
	assert.match(source, /render=explicit&onload=/)
	assert.match(source, /SDK_READY_TIMEOUT_MS\s*=\s*15_000/)
	assert.match(source, /TURNSTILE_SDK_PROMISE_KEY/)
	assert.match(source, /const prewarmedPromise = window\[TURNSTILE_SDK_PROMISE_KEY\]/)
	assert.match(source, /return this\.loadFresh\(\)/)
	assert.doesNotMatch(source, /script\.onload\s*=\s*resolve/)
	assert.match(source, /this\.scriptPromise\s*=\s*null/)
	assert.match(source, /script\.remove\(\)/)
})

test('Turnstile ignores stale renders and leaves automatic retries to the provider', () => {
	const source = read('components/auth/auth-turnstile.vue')

	assert.match(source, /renderGeneration/)
	assert.match(source, /generation\s*!==\s*this\.renderGeneration/)
	assert.match(source, /renderNonce:\s*options\.nonce/)
	assert.match(source, /MAX_SILENT_PROVIDER_FAILURES\s*=\s*2/)
	assert.match(source, /providerAutomaticallyRetries/)
	assert.match(source, /\^\(\?:300\|600\)\\d\{3\}\$/)
	assert.match(source, /providerRetryCount\s*<=\s*MAX_SILENT_PROVIDER_FAILURES/)
	assert.match(source, /retryStatus/)
	assert.doesNotMatch(source, /AUTO_RETRY_DELAY_MS/)
	assert.doesNotMatch(source, /MAX_AUTO_RETRIES/)
	assert.doesNotMatch(source, /retryTimer/)
})

test('H5 startup preconnects, loads the official SDK once, and prewarms public config', () => {
	const index = read('index.html')
	const sdkBootstrap = read('static/auth/turnstile-sdk-bootstrap.js')
	const app = read('App.vue')
	const prewarm = read('common/auth/turnstile-prewarm.js')
	const preconnectIndex = index.indexOf('rel="preconnect" href="https://challenges.cloudflare.com"')
	const sdkBootstrapIndex = index.indexOf('/static/auth/turnstile-sdk-bootstrap.js')

	assert.notEqual(preconnectIndex, -1)
	assert.notEqual(sdkBootstrapIndex, -1)
	assert.ok(preconnectIndex < sdkBootstrapIndex)
	assert.match(index, /rel="dns-prefetch" href="\/\/challenges\.cloudflare\.com"/)
	assert.match(sdkBootstrap, /https:\/\/challenges\.cloudflare\.com\/turnstile\/v0\/api\.js\?render=explicit&onload=/)
	assert.match(sdkBootstrap, /__AIT_TURNSTILE_SDK_PROMISE__/)
	assert.match(sdkBootstrap, /readyTimeoutMs\s*=\s*15000/)
	assert.match(app, /#ifdef H5[\s\S]*import \{ prewarmTurnstile \}[\s\S]*#endif/)
	assert.match(app, /#ifdef H5[\s\S]*void prewarmTurnstile\(\)[\s\S]*#endif/)
	assert.match(prewarm, /let turnstileConfigPromise = null/)
	assert.match(prewarm, /if \(turnstileConfigPromise\) return turnstileConfigPromise/)
	assert.match(prewarm, /turnstileConfigPromise = null/)
	assert.match(prewarm, /Promise\.allSettled/)
	assert.doesNotMatch(prewarm, /localStorage|sessionStorage/)
})

test('registration verifies the current server challenge before submitting a token', () => {
	const source = read('pages/auth/register.vue')
	const verifyMethod = source.slice(
		source.indexOf('async verifyHuman(token)'),
		source.indexOf('async sendCode(channel)')
	)

	assert.ok(verifyMethod.indexOf('registerStatus') < verifyMethod.indexOf('registerTurnstile'))
	assert.match(verifyMethod, /createTurnstileAttemptId/)
	assert.match(verifyMethod, /resetAfterServerRejection/)
	assert.match(source, /BroadcastChannel/)
})

test('login and password reset discard a token after backend rejection or unavailability', () => {
	for (const [relativePath, apiMethod, successAssignment, nextMethod] of [
		['pages/auth/login.vue', 'loginCodeTurnstile', 'this.humanVerified = true', 'async sendCode()'],
		['pages/auth/password-reset.vue', 'passwordResetTurnstile', "this.stage = 'CODE'", 'async send()']
	]) {
		const source = read(relativePath)
		const verifyMethod = source.slice(
			source.indexOf('async verifyHuman(token)'),
			source.indexOf(nextMethod)
		)

		assert.equal((verifyMethod.match(new RegExp(apiMethod, 'g')) || []).length, 1)
		assert.match(verifyMethod, /resetAfterServerRejection/)
		assert.ok(
			verifyMethod.indexOf(successAssignment) < verifyMethod.indexOf('resetAfterServerRejection'),
			relativePath
		)
		assert.doesNotMatch(verifyMethod, /retry|setTimeout/)
	}
})

test('Turnstile request carries only a correlation id in addition to existing credentials', () => {
	const source = read('common/auth/auth-api.js')

	assert.match(source, /X-Turnstile-Attempt-Id/)
	assert.match(source, /registerStatus\(flow, options = \{\}\)/)
	assert.doesNotMatch(source, /console\.(?:log|info|warn)\([^\n]*turnstileToken/)
})

test('Android Turnstile embeds a bounded child WebView and never opens the legacy full-screen gate', () => {
	const source = read('components/auth/auth-turnstile.vue')

	assert.match(source, /class="turnstile-widget turnstile-native-host"/)
	assert.match(source, /createAndroidTurnstileWebViewSession/)
	assert.match(source, /syncAndroidBounds\(context\s*=\s*\{\}\)/)
	assert.match(source, /reason\s*===\s*'scroll'/)
	assert.match(source, /androidBoundsRevision/)
	assert.match(source, /resolveAndroidTurnstileAnchor/)
	assert.match(source, /retryAndroidVerification\(\)/)
	assert.match(source, /parentWebview,/)
	assert.match(source, /\bchannel,/)
	assert.match(source, /timeoutMillis:\s*ANDROID_TURNSTILE_TIMEOUT_MS/)
	assert.doesNotMatch(source, /开始安全验证/)
	assert.doesNotMatch(source, /visibility-change/)
	assert.doesNotMatch(source, /closeVerification\(\)/)
	assert.doesNotMatch(source, /webview\.show\(/)
	assert.doesNotMatch(source, /top:\s*'0px'[\s\S]{0,80}bottom:\s*'0px'/)
	assert.doesNotMatch(source, /match:\s*'aiturnstile:\/\/\*'/)
})

test('Android Turnstile page receives public config through a cleared fragment and never refetches config', () => {
	const pageScript = fs.readFileSync(
		path.resolve(frontendRoot, '../ai-temperate-web/src/main/resources/verification-pages/turnstile-page.js'),
		'utf8')

	assert.match(pageScript, /location\.hash/)
	assert.match(pageScript, /history\.replaceState/)
	assert.match(pageScript, /siteKey/)
	assert.match(pageScript, /channel/)
	assert.match(pageScript, /dispatchResult\('verified'/)
	assert.match(pageScript, /terminalResult\('expired'/)
	assert.match(pageScript, /terminalResult\('timeout'/)
	assert.doesNotMatch(pageScript, /fetch\(['"]\/api\/auth\/turnstile\/config/)
})

test('Every Android Turnstile consumer supplies page scroll without moving the native child per frame', () => {
	for (const relativePath of [
		'pages/auth/login.vue',
		'pages/auth/register.vue',
		'pages/auth/password-reset.vue'
	]) {
		const source = read(relativePath)
		assert.match(source, /onPageScroll\(event\)/, relativePath)
		assert.match(source, /reason:\s*'scroll'/, relativePath)
		assert.match(source, /:page-scroll-top="turnstilePageScrollTop"/, relativePath)
		assert.match(source, /turnstilePageScrollTop:\s*0/, relativePath)
		assert.match(source, /onResize\(\)/, relativePath)
		assert.doesNotMatch(source, /@visibility-change="turnstileOpen/, relativePath)
		assert.doesNotMatch(source, /turnstileOpen/, relativePath)
	}

	const totp = read('pages/account/totp-security.vue')
	assert.doesNotMatch(totp, /<scroll-view[^>]*@scroll=/)
	assert.match(totp, /onPageScroll\(event\)/)
	assert.match(totp, /reason:\s*'scroll'/)
	assert.match(totp, /:page-scroll-top="turnstilePageScrollTop"/)
	assert.match(totp, /turnstilePageScrollTop:\s*0/)
	assert.match(totp, /onResize\(\)/)
})

test('H5 keeps the normal widget while Android scales only the Cloudflare module to 80 percent', () => {
	const component = read('components/auth/auth-turnstile.vue')
	const pageScript = fs.readFileSync(
		path.resolve(frontendRoot, '../ai-temperate-web/src/main/resources/verification-pages/turnstile-page.js'),
		'utf8')
	const pageCss = fs.readFileSync(
		path.resolve(frontendRoot, '../ai-temperate-web/src/main/resources/verification-pages/turnstile-page.css'),
		'utf8')

	assert.match(component, /size:\s*'normal'/)
	assert.match(component, /language:\s*'auto'/)
	assert.match(pageScript, /size:'normal'/)
	assert.match(pageScript, /language:'auto'/)
	assert.doesNotMatch(component, /v-if="loading" class="turnstile-status"/)
	assert.doesNotMatch(component, /\{\{ retryStatus \}\}/)
	assert.match(component, /class="turnstile-assistive"/)
	assert.match(component, /width:\s*300px/)
	assert.match(component, /height:\s*76px/)
	assert.match(component, /\.turnstile-native-host\s*\{[\s\S]*?width:\s*240px/)
	assert.match(pageCss, /transform:\s*scale\(0\.8\)/)
	assert.match(pageCss, /transform-origin:\s*top left/)
})

test('Android embedded page contains no independent full-screen security gate', () => {
	const pageHtml = fs.readFileSync(
		path.resolve(frontendRoot, '../ai-temperate-web/src/main/resources/verification-pages/turnstile-page.html'),
		'utf8')
	const pageCss = fs.readFileSync(
		path.resolve(frontendRoot, '../ai-temperate-web/src/main/resources/verification-pages/turnstile-page.css'),
		'utf8')

	assert.doesNotMatch(pageHtml, /完成安全验证|id="cancel"|class="actions"/)
	assert.match(pageHtml, /id="widget"/)
	assert.match(pageHtml, /rel="preconnect" href="https:\/\/challenges\.cloudflare\.com" crossorigin/)
	assert.match(pageCss, /background:\s*transparent/)
	assert.doesNotMatch(pageCss, /min-height:\s*100%|padding:\s*24px/)
})

test('H5 Turnstile renderer does not import or execute the Android navigation bridge', () => {
	const source = read('components/auth/auth-turnstile.vue')
	const renderJs = source.slice(source.indexOf('<script module="turnstile"'))

	assert.doesNotMatch(renderJs, /android-turnstile-navigation|loadAndroidTurnstilePage/)
	assert.doesNotMatch(renderJs, /ensurePreAuth|getDeviceInstallationId/)
})

const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

// 只替换平台编译指令及模块导入；会话策略、状态机、请求和传输实现均读取生产源码。
function loadSource(relative, bindings = {}, platform = 'H5') {
	let source = fs.readFileSync(path.resolve(__dirname, relative), 'utf8')
	if (relative.endsWith('.vue')) source = source.split('<script>')[1].split('</script>')[0]
	const enabled = [true]
	source = source.split(/\r?\n/).filter(line => {
		const directive = line.match(/^\s*\/\/\s*#(ifdef|ifndef|endif)\s*(.*)$/)
		if (!directive) return enabled.at(-1)
		if (directive[1] === 'endif') enabled.pop()
		else {
			const matches = directive[2].trim() === (platform === 'ANDROID' ? 'APP-PLUS' : 'H5')
			enabled.push(enabled.at(-1) && (directive[1] === 'ifdef' ? matches : !matches))
		}
		return false
	}).join('\n')
	const names = [...source.matchAll(/export\s+(?:async\s+)?(?:function|const|class)\s+(\w+)/g)].map(match => match[1])
	if (/export default/.test(source)) names.push('__default')
	source = source.replace(/^\s*import\s+[\s\S]*?\sfrom\s+['"][^'"]+['"]\s*;?\s*$/gm, '')
		.replace(/export default/g, 'const __default =')
		.replace(/\bexport (?=(?:async )?(?:function|const|class)\b)/g, '')
	return vm.runInNewContext(`${source}\n;({ ${names.join(', ')} })`, {
		console, setTimeout, clearTimeout, TextEncoder, TextDecoder, AbortController,
		...bindings
	}, { filename: relative })
}

function deferred() {
	let resolve, reject
	const promise = new Promise((yes, no) => { resolve = yes; reject = no })
	return { promise, resolve, reject }
}

async function flush() { await new Promise(resolve => setImmediate(resolve)) }

function createHarness(platform = 'H5', options = {}) {
	const state = loadSource('authenticated-session-state.js', {}, platform)
	const policy = loadSource('session-retry-policy.js', {}, platform)
	const requests = [], navigations = [], events = [], saved = []
	let clears = 0, preAuthCalls = 0, migrationCalls = 0
	let csrf = options.csrf === undefined ? 'test-csrf' : options.csrf
	let credentials = { accessToken: 'test-access', refreshToken: 'test-refresh', csrfToken: 'test-csrf' }
	const noop = () => {}
	const bindings = {
		...state, ...policy,
		AUTH_API_BASE_URL: 'https://example.test', AUTH_ROUTES: { login: '/pages/auth/login', home: '/pages/user/user-workspace' },
		clientPlatform: () => platform,
		usesExplicitTokenTransport: () => platform !== 'H5',
		browserCsrfToken: () => csrf,
		requiresCsrf: method => !['GET', 'HEAD'].includes(method),
		applyBrowserCsrfHeader: (headers, method, value) => { headers['X-CSRF-Token'] = value },
		androidEdgeRequestHeaders: headers => headers,
		ensureAndroidEdgeClearance: async () => {},
		runAndroidRequestWithEdgeRecovery: task => task(),
		ensureCookieScopeMigration: () => { migrationCalls++; return options.migration?.() || Promise.resolve({}) },
		invalidateCookieScopeMigration: noop,
		ensureDeviceInstallationId: async () => 'test-installation',
		getDeviceInstallationId: () => 'test-installation',
		currentAndroidOAuthPhase: () => '', isAndroidOAuthBlockingWebRtc: () => false,
		clearAndroidOAuthFlow: noop, clearH5OAuthWebRtcGate: noop, ownsH5WebRtcScheduling: () => false,
		captureEtagPayload: data => data,
		authDiagnosticRequestHeaders: () => ({}),
		createAuthRequestDiagnostic: (requestPath, source) => ({ path: requestPath, source, clientRequestId: 'test-request' }),
		recordAuthDiagnosticEvent: (name, fields) => events.push({ name, fields }),
		recordAuthDiagnosticFailure: noop, recordAuthDiagnosticResponse: noop,
		runAuthDiagnosticStage: (diagnostic, stage, task) => task(),
		acceptAndroidRiskChallenge: async () => {}, currentPreAuthToken: () => 'test-preauth',
		ensurePreAuth: () => { preAuthCalls++; return options.preAuth?.() || Promise.resolve() },
		invalidatePreAuth: noop, isPreAuthReady: () => true, recheckPreAuthAfterRiskChallenge: async () => {},
		repeatedAndroidRiskChallengeError: error => error,
		presentRiskBlock: () => false, beginRiskChallenge: noop,
		hasCompleteSessionCredentials: value => Boolean(value.refreshToken),
		clearSession: () => { clears++; credentials = {}; csrf = ''; state.clearRuntimeSessionAuthentication() },
		currentSession: () => credentials,
		saveSession: value => { saved.push(value); credentials = { ...credentials, ...value } },
		applyDiagnosticsToError: (error, diagnostic) => Object.assign(error, { responseClassification: diagnostic.classification }),
		inspectAuthResponse: response => ({ classification: response.header?.['cf-mitigated'] === 'challenge' ? 'EDGE_CHALLENGE' : 'ORIGIN' }),
		networkFailureDiagnostics: () => ({}),
		isWebRtcFailureCode: () => false, isWebRtcRetryCode: () => false,
		currentWebRtcVerificationEpoch: () => 0, ensureH5WebRtcVerified: async () => {},
		invalidateWebRtcVerification: noop, observeWebRtcVerificationHeaders: noop,
		presentWebRtcFailure: noop, scheduleH5WebRtcVerification: noop,
		startAndroidWebRtcVerificationInBackground: async () => {},
		uni: {
			request(request) { requests.push(request) },
			reLaunch(navigation) { navigations.push(navigation); if (options.autoNavigate !== false) navigation.success?.() }
		}
	}
	const http = loadSource('http-client.js', bindings, platform)
	return {
		state, policy, http, bindings, requests, navigations, events, saved,
		get clears() { return clears }, get preAuthCalls() { return preAuthCalls }, get migrationCalls() { return migrationCalls },
		setCsrf(value) { csrf = value },
		respond(index, statusCode, code, data = {}, header = {}) {
			requests[index].success({ statusCode, data: { ...data, ...(code ? { code } : {}) }, header })
		},
		login() { state.markRuntimeSessionAuthenticated({ newSession: true }); csrf = 'new-test-csrf'; credentials = { accessToken: 'new-test-access', refreshToken: 'new-test-refresh', csrfToken: csrf } },
		load(relative, extra = {}) { return loadSource(relative, { ...bindings, ...http, ...extra }, platform) }
	}
}

module.exports = { createHarness, deferred, flush, loadSource }

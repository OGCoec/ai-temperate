const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function memoryStorage() {
	const values = new Map()
	return {
		getItem: key => values.has(key) ? values.get(key) : null,
		setItem: (key, value) => values.set(key, String(value)),
		removeItem: key => values.delete(key)
	}
}

function restoreProperty(target, name, descriptor) {
	if (descriptor) {
		Object.defineProperty(target, name, descriptor)
		return
	}
	delete target[name]
}

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'auth-diagnostics.js'),
		'utf8')
	const uniqueSource = `${source}\n// test-instance=${Date.now()}-${Math.random()}`
	return import(`data:text/javascript;base64,${Buffer.from(uniqueSource).toString('base64')}`)
}

test('H5 authentication diagnostics remain enabled while only the console mirror can be disabled', async () => {
	globalThis.sessionStorage = memoryStorage()
	const diagnostics = await loadModule()

	assert.equal(diagnostics.isAuthDiagnosticsEnabled(), true)
	diagnostics.setAuthDiagnosticsEnabled(false)
	assert.equal(diagnostics.isAuthDiagnosticsEnabled(), true)
	diagnostics.recordAuthDiagnosticEvent('WEBRTC_ATTEMPT_CREATED', {
		probeRunId: '123e4567-e89b-42d3-a456-426614174002'
	})
	assert.equal(diagnostics.exportAuthDiagnostics().records.length, 1)
})

test('authentication diagnostics keep a bounded redacted timeline', async () => {
	globalThis.sessionStorage = memoryStorage()
	const diagnostics = await loadModule()
	diagnostics.clearAuthDiagnostics()

	for (let sequence = 0; sequence < 520; sequence += 1) {
		diagnostics.recordAuthDiagnosticEvent('REQUEST_REJECTED', {
			path: '/api/ai/conversations?pageSize=18',
			status: 401,
			errorCode: 'REFRESH_TOKEN_REQUIRED',
			token: 'must-not-be-recorded',
			cookie: 'must-not-be-recorded'
		})
	}

	const exported = diagnostics.exportAuthDiagnostics()
	assert.equal(exported.enabled, true)
	assert.equal(exported.records.length, 500)
	assert.equal(JSON.stringify(exported).includes('must-not-be-recorded'), false)
	assert.match(exported.records.at(-1).path, /^\/api\/ai\/conversations$/)
})

test('stored records are sanitized again before the diagnostic export bridge can read them', async () => {
	const storage = memoryStorage()
	storage.setItem('ait:auth:diagnostics:v1', JSON.stringify([{
		schemaVersion: 99,
		sequence: 7,
		event: 'WEBRTC_PROBE_FINISHED',
		occurredAt: '2026-08-30T18:27:57.316Z',
		monotonicMs: 10,
		pageInstanceId: '55555555-5555-4555-8555-555555555555',
		candidateCount: 0,
		cancelReason: 'DOCUMENT_UNLOADED',
		cookie: 'must-not-be-exported',
		token: 'must-not-be-exported',
		candidate: 'candidate:must-not-be-exported',
		webRtcIps: ['203.0.113.9']
	}]))
	globalThis.sessionStorage = storage
	const diagnostics = await loadModule()

	const exported = diagnostics.exportAuthDiagnostics()
	assert.equal(exported.records.length, 1)
	assert.equal(exported.records[0].schemaVersion, 1)
	assert.equal(exported.records[0].candidateCount, 0)
	assert.equal(exported.records[0].cancelReason, 'DOCUMENT_UNLOADED')
	assert.equal(JSON.stringify(exported).includes('must-not-be-exported'), false)
	assert.equal(JSON.stringify(exported).includes('203.0.113.9'), false)
})

test('request diagnostics expose only UUID correlation and bounded queue timing headers', async () => {
	globalThis.sessionStorage = memoryStorage()
	const diagnostics = await loadModule()
	const request = diagnostics.createAuthRequestDiagnostic(
		'/api/ai/conversations?pageSize=18',
		'user_workspace_mounted')
	const headers = diagnostics.authDiagnosticRequestHeaders(request)

	assert.match(headers['X-AIT-Client-Request-Id'], /^[0-9a-f-]{36}$/)
	assert.match(headers['X-AIT-Page-Instance-Id'], /^[0-9a-f-]{36}$/)
	assert.match(headers['X-AIT-Client-Queue-Ms'], /^\d+$/)
	assert.equal(Object.keys(headers).length, 3)
})

test('retry diagnostics propagate only the validated trigger request UUID', async () => {
	globalThis.sessionStorage = memoryStorage()
	const diagnostics = await loadModule()
	const triggerClientRequestId = '11111111-1111-4111-8111-111111111111'
	const request = diagnostics.createAuthRequestDiagnostic(
		'/api/_edge/cookie-scope',
		'cookie_scope_migration',
		{ triggerClientRequestId })
	const headers = diagnostics.authDiagnosticRequestHeaders(request)

	assert.equal(headers['X-AIT-Trigger-Request-Id'], triggerClientRequestId)
	assert.equal(
		diagnostics.authDiagnosticRequestHeaders(
			diagnostics.createAuthRequestDiagnostic(
				'/api/_edge/cookie-scope',
				'cookie_scope_migration',
				{ triggerClientRequestId: 'not-a-uuid' }))['X-AIT-Trigger-Request-Id'],
		undefined)
	assert.equal(
		diagnostics.exportAuthDiagnostics().records.some(record =>
			record.triggerClientRequestId === 'not-a-uuid'),
		false)
})

test('response diagnostics retain only bounded edge outcomes and deployment identities', async () => {
	const originalNavigator = Object.getOwnPropertyDescriptor(
		globalThis,
		'navigator')
	const originalSecureContext = Object.getOwnPropertyDescriptor(
		globalThis,
		'isSecureContext')
	const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')
	globalThis.sessionStorage = memoryStorage()
	globalThis.window = {
		location: {
			hostname: 'niko000o.site',
			origin: 'https://niko000o.site',
			search: ''
		}
	}
	Object.defineProperty(globalThis, 'navigator', {
		configurable: true,
		value: {
			cookieEnabled: true,
			platform: 'Win32',
			userAgent: 'Mozilla/5.0 Chrome/126.0.0.0 secret-device-detail'
		}
	})
	Object.defineProperty(globalThis, 'isSecureContext', {
		configurable: true,
		value: true
	})
	try {
		const diagnostics = await loadModule()
		const request = diagnostics.createAuthRequestDiagnostic(
			'/api/auth/csrf',
			'csrf')
		diagnostics.authDiagnosticRequestHeaders(request)
		diagnostics.recordAuthDiagnosticResponse(request, {
			statusCode: 428,
			data: { code: 'EDGE_COOKIE_SCOPE_RESET_REQUIRED' },
			header: {
				'CF-Ray': 'test-ray-ord',
				'X-AIT-Edge-Outcome': 'COOKIE_SCOPE_MARKER_MISSING',
				'X-AIT-Edge-Upstream-Attempted': '0',
				'X-AIT-Cookie-Scope-State': 'MISSING',
				'X-AIT-Cookie-Scope-Reset': '0',
				'X-AIT-Worker-Version': 'worker-version-test',
				'X-AIT-Pages-Deployment': 'e1444b89',
				'X-AIT-Backend-Release': 'backend-release-test',
				'Set-Cookie': 'must-not-be-exported'
			}
		})

		const exported = diagnostics.exportAuthDiagnostics()
		const response = exported.records.find(record =>
			record.event === 'NETWORK_RESPONSE_RECEIVED')
		assert.equal(response.cfRay, 'test-ray-ord')
		assert.equal(response.edgeOutcome, 'COOKIE_SCOPE_MARKER_MISSING')
		assert.equal(response.upstreamAttempted, false)
		assert.equal(response.cookieScopeState, 'MISSING')
		assert.equal(response.cookieScopeReset, false)
		assert.equal(response.workerVersion, 'worker-version-test')
		assert.equal(response.pagesDeployment, 'e1444b89')
		assert.equal(response.backendRelease, 'backend-release-test')
		assert.deepEqual(exported.runtime, {
			browserFamily: 'CHROME',
			browserMajor: 126,
			osFamily: 'WINDOWS',
			secureContext: true,
			cookieEnabled: true,
			originHost: 'niko000o.site'
		})
		assert.equal(JSON.stringify(exported).includes('secret-device-detail'), false)
		assert.equal(JSON.stringify(exported).includes('must-not-be-exported'), false)
	} finally {
		restoreProperty(globalThis, 'navigator', originalNavigator)
		restoreProperty(globalThis, 'isSecureContext', originalSecureContext)
		restoreProperty(globalThis, 'window', originalWindow)
	}
})

test('WebRTC request diagnostics add one validated probe correlation header', async () => {
	globalThis.sessionStorage = memoryStorage()
	const diagnostics = await loadModule()
	const request = diagnostics.createAuthRequestDiagnostic(
		'/api/_edge/webrtc/start',
		'webrtc_verification')
	const probeRunId = '22222222-2222-4222-8222-222222222222'
	const headers = diagnostics.authDiagnosticRequestHeaders(request, { probeRunId })

	assert.equal(headers['X-AIT-WebRTC-Probe-Run-Id'], probeRunId)
	assert.equal(
		diagnostics.authDiagnosticRequestHeaders(request, { probeRunId: 'not-a-uuid' })
			['X-AIT-WebRTC-Probe-Run-Id'],
		undefined)

	diagnostics.setCurrentAuthDiagnosticWebRtcProbeRunId(probeRunId)
	const relatedRequest = diagnostics.createAuthRequestDiagnostic(
		'/api/auth/oauth2/complete',
		'oauth_complete')
	assert.equal(
		diagnostics.authDiagnosticRequestHeaders(relatedRequest)
			['X-AIT-WebRTC-Probe-Run-Id'],
		probeRunId)
})

test('WebRTC causal diagnostics retain only bounded correlation fields', async () => {
	globalThis.sessionStorage = memoryStorage()
	const diagnostics = await loadModule()
	diagnostics.clearAuthDiagnostics()
	const parentId = '11111111-1111-4111-8111-111111111111'

	diagnostics.recordAuthDiagnosticEvent('WEBRTC_START_REQUEST_DISPATCHED', {
		triggerClientRequestId: parentId,
		probeRunId: '33333333-3333-4333-8333-333333333333',
		generation: '1',
		requestEpoch: 3,
		preAuthReady: true,
		activeTaskCount: 1,
		pendingRemainingMs: 990,
		reportGraceMs: 3000,
		probeBudgetMs: 1,
		candidateCount: 0,
		reportDispatched: true,
		cookie: 'must-not-be-recorded',
		preAuthToken: 'must-not-be-recorded',
		webRtcIps: ['203.0.113.9'],
		candidate: 'candidate:must-not-be-recorded'
	})

	const record = diagnostics.exportAuthDiagnostics().records.at(-1)
	assert.equal(record.triggerClientRequestId, parentId)
	assert.equal(record.probeRunId, '33333333-3333-4333-8333-333333333333')
	assert.equal(record.generation, '1')
	assert.equal(record.requestEpoch, 3)
	assert.equal(record.preAuthReady, true)
	assert.equal(record.activeTaskCount, 1)
	assert.equal(record.pendingRemainingMs, 990)
	assert.equal(record.reportGraceMs, 3000)
	assert.equal(record.probeBudgetMs, 1)
	assert.equal(record.candidateCount, 0)
	assert.equal(record.reportDispatched, true)
	assert.equal(JSON.stringify(record).includes('must-not-be-recorded'), false)
	assert.equal(JSON.stringify(record).includes('203.0.113.9'), false)
})

test('production-safe console mirroring is opt-in and exposes only the redacted bridge', async () => {
	globalThis.sessionStorage = memoryStorage()
	globalThis.window = {}
	const entries = []
	const originalConsole = globalThis.console
	globalThis.console = {
		...originalConsole,
		info: (...args) => entries.push(args)
	}
	try {
		const diagnostics = await loadModule()
		diagnostics.recordAuthDiagnosticEvent('WEBRTC_PROBE_FINISHED', {
			candidateCount: 0,
			candidate: 'candidate:must-not-be-recorded'
		})
		assert.equal(entries.length, 0)

		diagnostics.setAuthDiagnosticsConsoleEnabled(true)
		diagnostics.recordAuthDiagnosticEvent('WEBRTC_PROBE_FINISHED', {
			candidateCount: 0,
			candidate: 'candidate:must-not-be-recorded'
		})

		assert.equal(entries.length, 1)
		assert.match(String(entries[0][0]), /AIT_WEBRTC/)
		assert.equal(entries[0].join(' ').includes('must-not-be-recorded'), false)
		assert.equal(typeof globalThis.window.__AIT_AUTH_DIAGNOSTICS__.export, 'function')
		assert.equal(typeof globalThis.window.__AIT_AUTH_DIAGNOSTICS__.exportJson, 'function')
		assert.equal(
			globalThis.window.__AIT_AUTH_DIAGNOSTICS__.export().records.length > 0,
			true)
		assert.doesNotThrow(() => JSON.parse(
			globalThis.window.__AIT_AUTH_DIAGNOSTICS__.exportJson()))
	} finally {
		globalThis.console = originalConsole
		delete globalThis.window
	}
})

test('diagnostic URL switch and page identity survive one cross-document session', async () => {
	const storage = memoryStorage()
	globalThis.sessionStorage = storage
	globalThis.window = { location: { search: '?aitAuthDiagnostics=1' } }
	const originalConsole = globalThis.console
	globalThis.console = { ...originalConsole, info: () => {} }
	try {
		const beforeRedirect = await loadModule()
		const firstPageId = beforeRedirect.currentAuthDiagnosticPageId()
		beforeRedirect.recordAuthDiagnosticEvent('WEBRTC_ATTEMPT_CREATED', {
			probeRunId: '44444444-4444-4444-8444-444444444444'
		})
		beforeRedirect.flushAuthDiagnostics()
		assert.equal(beforeRedirect.isAuthDiagnosticsConsoleEnabled(), true)

		globalThis.window = { location: { search: '' } }
		const afterRedirect = await loadModule()
		assert.equal(afterRedirect.isAuthDiagnosticsConsoleEnabled(), true)
		assert.notEqual(afterRedirect.currentAuthDiagnosticPageId(), firstPageId)
		assert.equal(
			afterRedirect.exportAuthDiagnostics().records
				.some(record => record.event === 'WEBRTC_ATTEMPT_CREATED'),
			true)

		globalThis.window = { location: { search: '?aitAuthDiagnostics=0' } }
		const disabledMirror = await loadModule()
		assert.equal(disabledMirror.isAuthDiagnosticsConsoleEnabled(), false)
		assert.equal(globalThis.window.__AIT_AUTH_DIAGNOSTICS__, undefined)
	} finally {
		globalThis.console = originalConsole
		delete globalThis.window
	}
})

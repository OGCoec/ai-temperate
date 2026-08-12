import assert from 'node:assert/strict'
import { createHmac } from 'node:crypto'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
	COOKIE_SCOPE_MARKER_NAME,
	handleRequest
} from '../src/index.js'

const ENV = {
	API_UPSTREAM_ORIGIN: 'https://api.niko000o.site',
	SSE_ROUTE_LOG_SAMPLE_RATE: '0',
	EDGE_PROXY_HMAC_SECRET_BASE64: Buffer
		.from('worker-edge-test-secret-0123456789abcdef')
		.toString('base64')
}
const NOW = 1784916000000
const TURNSTILE_CHALLENGE = 'A'.repeat(38)
const ANDROID_PREAUTH_TOKEN = 'B'.repeat(43)
const ANDROID_DEVICE_ID = 'eb00070b-d902-4793-b5a9-c5d14e878264'
const VOICE_TICKET = 'C'.repeat(43)
const VOICE_PROTOCOL_HEADER = `ait-voice-v2, ait-ticket.${VOICE_TICKET}`

function turnstilePagePath(options = {}) {
	const challenge = options.challenge ?? TURNSTILE_CHALLENGE
	const action = options.action ?? 'login'
	return '/api/auth/turnstile/page'
		+ `?challenge=${encodeURIComponent(challenge)}`
		+ `&action=${encodeURIComponent(action)}`
}

function androidWebViewHeaders(overrides = {}) {
	const headers = {
		'X-Client-Platform': 'ANDROID',
		'X-AIT-PreAuth': ANDROID_PREAUTH_TOKEN,
		'X-Device-Installation-Id': ANDROID_DEVICE_ID,
		'Sec-Fetch-Site': 'none',
		'Sec-Fetch-Mode': 'navigate',
		'Sec-Fetch-Dest': 'document',
		'Sec-Fetch-User': '?1'
	}
	for (const [name, value] of Object.entries(overrides)) {
		if (value === undefined) delete headers[name]
		else headers[name] = value
	}
	return headers
}

function request(host, path, options = {}) {
	const headers = new Headers(options.headers)
	headers.set('CF-Ray', options.ray || 'test-ray-ord')
	headers.set('CF-Connecting-IP', options.clientIp || '203.0.113.10')
	if (options.migrated !== false) {
		headers.set('Cookie', `${COOKIE_SCOPE_MARKER_NAME}=1`)
	}
	const value = new Request(`https://${host}${path}`, {
		method: options.method || 'GET',
		headers,
		body: options.body,
		signal: options.signal
	})
	Object.defineProperty(value, 'cf', {
		value: options.cf || {
			country: 'US',
			asn: 64500,
			latitude: '41.8781',
			longitude: '-87.6298'
		}
	})
	return value
}

function runtime(fetchImpl, log = { info() {}, warn() {} }) {
	return {
		fetch: fetchImpl,
		now: () => NOW,
		log
	}
}

function diagnosticLogger() {
	const entries = []
	return {
		entries,
		logger: {
			info(value) {
				entries.push(JSON.parse(value))
			},
			warn(value) {
				entries.push(JSON.parse(value))
			}
		}
	}
}

function websocketUpgradeResponse(options = {}) {
	const headers = new Headers(options.headers)
	if ((options.status ?? 101) === 101
		&& !headers.has('Sec-WebSocket-Protocol')) {
		headers.set('Sec-WebSocket-Protocol', 'ait-voice-v2')
	}
	return {
		status: options.status ?? 101,
		headers,
		webSocket: Object.hasOwn(options, 'webSocket')
			? options.webSocket
			: Object.freeze({ kind: 'test-websocket' })
	}
}

test('wrangler exposes only the two exact Android clearance routes', () => {
	const config = readFileSync(new URL('../wrangler.jsonc', import.meta.url), 'utf8')

	assert.match(config, /"pattern":\s*"niko000o\.site\/__edge\/android-clearance"/)
	assert.match(config,
		/"pattern":\s*"niko000o\.site\/__edge\/android-clearance\/status"/)
	assert.doesNotMatch(config, /niko000o\.site\/__edge\/\*/)
})

test('Android clearance page and status never call the API upstream', async () => {
	let upstreamCalls = 0
	const fetchImpl = () => {
		upstreamCalls += 1
		throw new Error('clearance routes must terminate at the edge')
	}
	const missingPage = await handleRequest(
		request('niko000o.site', '/__edge/android-clearance', {
			migrated: false
		}),
		ENV,
		runtime(fetchImpl)
	)
	const verifiedPage = await handleRequest(
		request('niko000o.site', '/__edge/android-clearance', {
			migrated: false,
			headers: { Cookie: 'cf_clearance=test-clearance-value' }
		}),
		ENV,
		runtime(fetchImpl)
	)
	const missingStatus = await handleRequest(
		request('niko000o.site', '/__edge/android-clearance/status', {
			migrated: false
		}),
		ENV,
		runtime(fetchImpl)
	)
	const verifiedStatus = await handleRequest(
		request('niko000o.site', '/__edge/android-clearance/status', {
			migrated: false,
			headers: { Cookie: 'cf_clearance=test-clearance-value' }
		}),
		ENV,
		runtime(fetchImpl)
	)
	const pageBody = await verifiedPage.text()

	assert.equal(missingPage.status, 428)
	assert.equal((await missingPage.json()).code, 'EDGE_CLEARANCE_REQUIRED')
	assert.equal(verifiedPage.status, 200)
	assert.match(verifiedPage.headers.get('Content-Type') || '', /text\/html/)
	assert.match(verifiedPage.headers.get('Content-Security-Policy') || '',
		/default-src 'none'/)
	assert.equal(verifiedPage.headers.get('Cache-Control'), 'no-store')
	assert.match(pageBody, /ait-edge:\/\/verified/)
	assert.doesNotMatch(pageBody, /test-clearance-value/)
	assert.equal(missingStatus.status, 428)
	assert.equal((await missingStatus.json()).code, 'EDGE_CLEARANCE_REQUIRED')
	assert.equal(verifiedStatus.status, 204)
	assert.equal(verifiedStatus.headers.get('Cache-Control'), 'no-store')
	assert.equal(upstreamCalls, 0)
})

test('Android clearance routing accepts only exact GET paths', async () => {
	const noUpstream = runtime(() => {
		throw new Error('clearance route must not call upstream')
	})
	const notGet = await handleRequest(
		request('niko000o.site', '/__edge/android-clearance', {
			method: 'POST',
			migrated: false
		}),
		ENV,
		noUpstream
	)
	const forbidden = await Promise.all([
		'/__edge/android-clearance/',
		'/__edge/android-clearance/extra',
		'/__edge/android-clearance/status/extra',
		'/__edge/android-admin-clearance'
	].map(path => handleRequest(
		request('niko000o.site', path, { migrated: false }),
		ENV,
		noUpstream
	)))

	assert.equal(notGet.status, 405)
	assert.equal(notGet.headers.get('Allow'), 'GET')
	assert.deepEqual(forbidden.map(response => response.status), [403, 403, 403, 403])
})

test('root host forwards only ordinary API paths and preserves path plus query', async () => {
	let captured
	const response = await handleRequest(
		request(
			'niko000o.site',
			'/api/auth/csrf?attempt=1&upstream=https%3A%2F%2Fevil.example'
		),
		ENV,
		runtime(async upstream => {
			captured = upstream
			return new Response(null, { status: 204 })
		})
	)

	assert.equal(response.status, 204)
	assert.equal(
		captured.url,
		'https://api.niko000o.site/api/auth/csrf'
			+ '?attempt=1&upstream=https%3A%2F%2Fevil.example'
	)
	assert.equal(captured.headers.get('Origin'), 'https://niko000o.site')
	assert.equal(captured.headers.get('X-Client-Platform'), 'H5')
	assert.equal(captured.cache, 'no-store')
})

test('Android HTTP uses the primary-domain Worker without Cookie Scope and preserves explicit tokens', async () => {
	let captured
	const response = await handleRequest(
		request('niko000o.site', '/api/_edge/pre-auth?attempt=1', {
			method: 'POST',
			migrated: false,
			headers: {
				'X-Client-Platform': 'ANDROID',
				Authorization: 'Bearer android-access-token',
				'X-Refresh-Token': 'android-refresh-token',
				'X-CSRF-Token': 'android-csrf-token',
				'X-AIT-PreAuth': 'android-pre-auth-token',
				'X-Device-Installation-Id': 'test-installation-id',
				Cookie: 'captured_proxy_cookie=must-not-pass',
				Referer: 'https://untrusted.example/source',
				'X-AIT-Edge-Host': 'evil.example',
				'X-Forwarded-Host': 'evil.example'
			}
		}),
		ENV,
		runtime(upstream => {
			captured = upstream
			return new Response(null, { status: 204 })
		})
	)

	const canonical = [
		'v2',
		'POST',
		'/api/_edge/pre-auth?attempt=1',
		'niko000o.site',
		String(Math.floor(NOW / 1000)),
		'test-ray-ord',
		'203.0.113.10',
		'US',
		'64500',
		'41.8781',
		'-87.6298'
	].join('\n')
	const expectedSignature = createHmac(
		'sha256',
		Buffer.from(ENV.EDGE_PROXY_HMAC_SECRET_BASE64, 'base64')
	).update(canonical).digest('base64url')

	assert.equal(response.status, 204)
	assert.equal(captured.url,
		'https://api.niko000o.site/api/_edge/pre-auth?attempt=1')
	assert.equal(captured.headers.get('X-Client-Platform'), 'ANDROID')
	assert.equal(captured.headers.get('Origin'), null)
	assert.equal(captured.headers.get('Cookie'), null)
	assert.equal(captured.headers.get('Referer'), null)
	assert.equal(captured.headers.get('Authorization'), 'Bearer android-access-token')
	assert.equal(captured.headers.get('X-Refresh-Token'), 'android-refresh-token')
	assert.equal(captured.headers.get('X-CSRF-Token'), 'android-csrf-token')
	assert.equal(captured.headers.get('X-AIT-PreAuth'), 'android-pre-auth-token')
	assert.equal(captured.headers.get('X-Device-Installation-Id'), 'test-installation-id')
	assert.equal(captured.headers.get('X-Forwarded-Host'), null)
	assert.equal(captured.headers.get('X-AIT-Edge-Host'), 'niko000o.site')
	assert.equal(captured.headers.get('X-AIT-Edge-Signature'), expectedSignature)
})

test('Android Turnstile WebView document navigation is normalized to the Android upstream transport', async () => {
	let captured
	const response = await handleRequest(
		request('niko000o.site', turnstilePagePath(), {
			migrated: false,
			headers: androidWebViewHeaders({
				Cookie: 'cf_clearance=edge-only; captured_proxy_cookie=must-not-pass',
				Referer: 'https://untrusted.example/source'
			})
		}),
		ENV,
		runtime(upstream => {
			captured = upstream
			return new Response(null, { status: 204 })
		})
	)

	assert.equal(response.status, 204)
	assert.equal(
		captured.url,
		`https://api.niko000o.site${turnstilePagePath()}`
	)
	assert.equal(captured.headers.get('X-Client-Platform'), 'ANDROID')
	assert.equal(captured.headers.get('X-AIT-PreAuth'), ANDROID_PREAUTH_TOKEN)
	assert.equal(captured.headers.get('X-Device-Installation-Id'), ANDROID_DEVICE_ID)
	assert.equal(captured.headers.get('Cookie'), null)
	assert.equal(captured.headers.get('Origin'), null)
	assert.equal(captured.headers.get('Referer'), null)
	assert.equal(captured.headers.get('Sec-Fetch-Site'), null)
	assert.equal(captured.headers.get('Sec-Fetch-Mode'), null)
	assert.equal(captured.headers.get('Sec-Fetch-Dest'), null)
	assert.equal(captured.headers.get('Sec-Fetch-User'), null)
})

test('Android Turnstile WebView accepts same-origin document navigation metadata', async () => {
	let captured
	const response = await handleRequest(
		request('niko000o.site', turnstilePagePath({ action: 'register' }), {
			migrated: false,
			headers: androidWebViewHeaders({
				Origin: 'https://niko000o.site',
				'Sec-Fetch-Site': 'same-origin'
			})
		}),
		ENV,
		runtime(upstream => {
			captured = upstream
			return new Response(null, { status: 204 })
		})
	)

	assert.equal(response.status, 204)
	assert.equal(captured.headers.get('Origin'), null)
	assert.equal(captured.headers.get('X-Client-Platform'), 'ANDROID')
})

test('Android Turnstile WebView accepts document metadata when optional site and user fields are absent', async () => {
	let captured
	const response = await handleRequest(
		request('niko000o.site', turnstilePagePath(), {
			migrated: false,
			headers: androidWebViewHeaders({
				'Sec-Fetch-Site': undefined,
				'Sec-Fetch-User': undefined
			})
		}),
		ENV,
		runtime(upstream => {
			captured = upstream
			return new Response(null, { status: 204 })
		})
	)

	assert.equal(response.status, 204)
	assert.equal(captured.headers.get('X-Client-Platform'), 'ANDROID')
})

test('Android Turnstile WebView remains compatible when an older runtime omits Fetch Metadata', async () => {
	let captured
	const response = await handleRequest(
		request(
			'niko000o.site',
			`/api/auth/turnstile/page?action=password_reset&challenge=${TURNSTILE_CHALLENGE}`,
			{
				migrated: false,
				headers: androidWebViewHeaders({
					'Sec-Fetch-Site': undefined,
					'Sec-Fetch-Mode': undefined,
					'Sec-Fetch-Dest': undefined,
					'Sec-Fetch-User': undefined
				})
			}),
		ENV,
		runtime(upstream => {
			captured = upstream
			return new Response(null, { status: 204 })
		})
	)

	assert.equal(response.status, 204)
	assert.equal(captured.headers.get('X-Client-Platform'), 'ANDROID')
	assert.equal(captured.headers.get('X-AIT-PreAuth'), ANDROID_PREAUTH_TOKEN)
	assert.equal(captured.headers.get('X-Device-Installation-Id'), ANDROID_DEVICE_ID)
})

test('Android Turnstile WebView rejects every request outside the controlled document-navigation contract', async () => {
	let upstreamCalls = 0
	const fetchImpl = () => {
		upstreamCalls += 1
		return new Response(null, { status: 204 })
	}
	const invalidRequests = [
		{
			name: 'different API path',
			path: '/api/auth/turnstile/config',
			headers: androidWebViewHeaders()
		},
		{
			name: 'non-GET method',
			path: turnstilePagePath(),
			method: 'POST',
			headers: androidWebViewHeaders()
		},
		{
			name: 'fetch instead of navigation',
			path: turnstilePagePath(),
			headers: androidWebViewHeaders({
				'Sec-Fetch-Mode': 'cors',
				'Sec-Fetch-Dest': 'empty'
			})
		},
		{
			name: 'iframe destination',
			path: turnstilePagePath(),
			headers: androidWebViewHeaders({ 'Sec-Fetch-Dest': 'iframe' })
		},
		{
			name: 'cross-site navigation',
			path: turnstilePagePath(),
			headers: androidWebViewHeaders({ 'Sec-Fetch-Site': 'cross-site' })
		},
		{
			name: 'invalid user activation metadata',
			path: turnstilePagePath(),
			headers: androidWebViewHeaders({ 'Sec-Fetch-User': '?0' })
		},
		{
			name: 'foreign origin',
			path: turnstilePagePath(),
			headers: androidWebViewHeaders({ Origin: 'https://evil.example' })
		},
		{
			name: 'opaque origin',
			path: turnstilePagePath(),
			headers: androidWebViewHeaders({ Origin: 'null' })
		},
		{
			name: 'missing PreAuth',
			path: turnstilePagePath(),
			headers: androidWebViewHeaders({ 'X-AIT-PreAuth': undefined })
		},
		{
			name: 'older WebView cannot fall back to native without PreAuth',
			path: turnstilePagePath(),
			headers: androidWebViewHeaders({
				'X-AIT-PreAuth': undefined,
				'Sec-Fetch-Site': undefined,
				'Sec-Fetch-Mode': undefined,
				'Sec-Fetch-Dest': undefined,
				'Sec-Fetch-User': undefined
			})
		},
		{
			name: 'malformed PreAuth',
			path: turnstilePagePath(),
			headers: androidWebViewHeaders({ 'X-AIT-PreAuth': 'A'.repeat(42) })
		},
		{
			name: 'missing device id',
			path: turnstilePagePath(),
			headers: androidWebViewHeaders({ 'X-Device-Installation-Id': undefined })
		},
		{
			name: 'malformed device id',
			path: turnstilePagePath(),
			headers: androidWebViewHeaders({
				'X-Device-Installation-Id': 'not-a-uuid'
			})
		},
		{
			name: 'malformed challenge',
			path: turnstilePagePath({ challenge: 'A'.repeat(37) }),
			headers: androidWebViewHeaders()
		},
		{
			name: 'unknown action',
			path: turnstilePagePath({ action: 'verify' }),
			headers: androidWebViewHeaders()
		},
		{
			name: 'duplicate query parameter',
			path: `${turnstilePagePath()}&challenge=${TURNSTILE_CHALLENGE}`,
			headers: androidWebViewHeaders()
		},
		{
			name: 'unknown query parameter',
			path: `${turnstilePagePath()}&debug=1`,
			headers: androidWebViewHeaders()
		},
		{
			name: 'missing action',
			path: `/api/auth/turnstile/page?challenge=${TURNSTILE_CHALLENGE}`,
			headers: androidWebViewHeaders()
		}
	]

	for (const invalid of invalidRequests) {
		const response = await handleRequest(
			request('niko000o.site', invalid.path, {
				method: invalid.method,
				migrated: false,
				headers: invalid.headers
			}),
			ENV,
			runtime(fetchImpl)
		)

		assert.equal(response.status, 403, invalid.name)
		assert.equal(
			(await response.json()).code,
			'EDGE_CLIENT_TRANSPORT_INVALID',
			invalid.name
		)
	}
	assert.equal(upstreamCalls, 0)
})

test('Android transport rejects browser Origin or Fetch Metadata before proxying', async () => {
	let upstreamCalls = 0
	const fetchImpl = () => {
		upstreamCalls += 1
		return new Response(null, { status: 204 })
	}
	const withOrigin = await handleRequest(
		request('niko000o.site', '/api/_edge/pre-auth', {
			method: 'POST',
			migrated: false,
			headers: {
				'X-Client-Platform': 'ANDROID',
				Origin: 'https://niko000o.site'
			}
		}),
		ENV,
		runtime(fetchImpl)
	)
	const withFetchMetadata = await handleRequest(
		request('niko000o.site', '/api/_edge/pre-auth', {
			method: 'POST',
			migrated: false,
			headers: {
				'X-Client-Platform': 'ANDROID',
				'Sec-Fetch-Site': 'same-origin'
			}
		}),
		ENV,
		runtime(fetchImpl)
	)

	assert.equal(withOrigin.status, 403)
	assert.equal((await withOrigin.json()).code, 'EDGE_CLIENT_TRANSPORT_INVALID')
	assert.equal(withFetchMetadata.status, 403)
	assert.equal((await withFetchMetadata.json()).code,
		'EDGE_CLIENT_TRANSPORT_INVALID')
	assert.equal(upstreamCalls, 0)
})

test('unknown platforms follow the H5 Cookie Scope policy instead of Android fallback', async () => {
	const response = await handleRequest(
		request('niko000o.site', '/api/auth/csrf', {
			migrated: false,
			headers: { 'X-Client-Platform': 'UNKNOWN' }
		}),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)

	assert.equal(response.status, 428)
	assert.equal((await response.json()).code, 'EDGE_COOKIE_SCOPE_RESET_REQUIRED')
})

test('Android responses reject every upstream Set-Cookie header', async () => {
	const response = await handleRequest(
		request('niko000o.site', '/api/_edge/pre-auth', {
			method: 'POST',
			migrated: false,
			headers: { 'X-Client-Platform': 'ANDROID' }
		}),
		ENV,
		runtime(() => new Response(null, {
			status: 204,
			headers: {
				'Set-Cookie': '__Host-ait-preauth=unexpected; Path=/; Secure; HttpOnly'
			}
		}))
	)

	assert.equal(response.status, 502)
	assert.equal((await response.json()).code,
		'EDGE_ANDROID_COOKIE_POLICY_VIOLATION')
})

test('Android Turnstile WebView responses keep the Android upstream Cookie prohibition', async () => {
	const response = await handleRequest(
		request('niko000o.site', turnstilePagePath(), {
			migrated: false,
			headers: androidWebViewHeaders()
		}),
		ENV,
		runtime(() => new Response(null, {
			status: 204,
			headers: {
				'Set-Cookie': '__Host-ait-preauth=unexpected; Path=/; Secure; HttpOnly'
			}
		}))
	)

	assert.equal(response.status, 502)
	assert.equal((await response.json()).code,
		'EDGE_ANDROID_COOKIE_POLICY_VIOLATION')
})

test('root host forwards the ordinary AI model APIs without changing their paths', async () => {
	let captured
	const response = await handleRequest(
		request('niko000o.site', '/api/ai-models?pageNum=1&pageSize=20'),
		ENV,
		runtime(async upstream => {
			captured = upstream
			return Response.json({ models: [] })
		})
	)

	assert.equal(response.status, 200)
	assert.equal(
		captured.url,
		'https://api.niko000o.site/api/ai-models?pageNum=1&pageSize=20'
	)
	assert.equal(captured.headers.get('Origin'), 'https://niko000o.site')
})

test('root host forwards an ordinary AI model detail path', async () => {
	let captured
	const response = await handleRequest(
		request('niko000o.site', '/api/ai-models/AAABi0VWeJ8'),
		ENV,
		runtime(async upstream => {
			captured = upstream
			return Response.json({ publicId: 'AAABi0VWeJ8' })
		})
	)

	assert.equal(response.status, 200)
	assert.equal(captured.url, 'https://api.niko000o.site/api/ai-models/AAABi0VWeJ8')
})

test('root host forwards AI conversation POST SSE without buffering its body', async () => {
	let captured
	let capturedBody
	const response = await handleRequest(
		request('niko000o.site', '/api/ai/conversations/responses', {
			method: 'POST',
			body: JSON.stringify({
				modelPublicId: 'AAABi0VWeJ8',
				input: { text: 'hello', attachments: [] }
			}),
			headers: {
				'Content-Type': 'application/json',
				'Idempotency-Key': '4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6'
			}
		}),
		ENV,
		runtime(async upstream => {
			captured = upstream
			capturedBody = await upstream.text()
			return new Response('event: accepted\\ndata: {}\\n\\n', {
				status: 200,
				headers: { 'Content-Type': 'text/event-stream' }
			})
		})
	)

	assert.equal(response.status, 200)
	assert.equal(captured.method, 'POST')
	assert.equal(
		captured.url,
		'https://api.niko000o.site/api/ai/conversations/responses'
	)
	assert.equal(JSON.parse(capturedBody).input.text, 'hello')
	assert.equal(response.headers.get('X-Accel-Buffering'), 'no')
	assert.match(response.headers.get('Cache-Control'), /no-transform/)
})

test('Android SSE keeps streaming behavior while removing browser credentials', async () => {
	let captured
	const response = await handleRequest(
		request('niko000o.site', '/api/ai/conversations/responses', {
			method: 'POST',
			migrated: false,
			body: JSON.stringify({ input: { text: 'hello' } }),
			headers: {
				'Content-Type': 'application/json',
				Accept: 'text/event-stream',
				'X-Client-Platform': 'ANDROID',
				Authorization: 'Bearer android-access-token',
				Cookie: 'captured_proxy_cookie=must-not-pass'
			}
		}),
		ENV,
		runtime(upstream => {
			captured = upstream
			return new Response('event: accepted\ndata: {}\n\n', {
				headers: { 'Content-Type': 'text/event-stream' }
			})
		})
	)

	assert.equal(response.status, 200)
	assert.equal(captured.headers.get('X-Client-Platform'), 'ANDROID')
	assert.equal(captured.headers.get('Origin'), null)
	assert.equal(captured.headers.get('Cookie'), null)
	assert.equal(captured.headers.get('Authorization'), 'Bearer android-access-token')
	assert.equal(await response.text(), 'event: accepted\ndata: {}\n\n')
	assert.equal(response.headers.get('X-Accel-Buffering'), 'no')
	assert.match(response.headers.get('Cache-Control'), /no-transform/)
})

test('root host forwards the exact direct response cancellation endpoint', async () => {
	let captured
	const idempotencyKey = '4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6'
	const response = await handleRequest(
		request('niko000o.site', '/api/ai/conversations/responses/cancel', {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				'Idempotency-Key': idempotencyKey
			}
		}),
		ENV,
		runtime(async upstream => {
			captured = upstream
			return Response.json({ status: 'CANCEL_REQUESTED' }, { status: 202 })
		})
	)

	assert.equal(response.status, 202)
	assert.equal(captured.method, 'POST')
	assert.equal(
		captured.url,
		'https://api.niko000o.site/api/ai/conversations/responses/cancel'
	)
	assert.equal(captured.headers.get('Idempotency-Key'), idempotencyKey)
})

test('sampled AI generation SSE reports edge read and forward summaries without body text', async () => {
	const entries = []
	const generationId = 'AZ-vpV3kfag70-0EMMUETQ'
	const body = new ReadableStream({
		start(controller) {
			controller.enqueue(new TextEncoder().encode('event: delta\n'))
			controller.enqueue(new TextEncoder().encode(
				'data: {"revision":1,"text":"secret model output"}\n\n'))
			controller.close()
		}
	})
	const response = await handleRequest(
		request(
			'niko000o.site',
			`/api/ai/conversations/generations/${generationId}/events`,
			{ headers: { Accept: 'text/event-stream' } }),
		{ ...ENV, SSE_ROUTE_LOG_SAMPLE_RATE: '1' },
		{
			...runtime(() => new Response(body, {
				headers: {
					'Content-Type': 'text/event-stream',
					'X-Trace-Id': 'trace-edge-test',
					'X-AI-Usage-Id': 'AZ-usage-public-id'
				}
			})),
			random: () => 0,
			log: { info: value => entries.push(value) }
		}
	)

	assert.equal(await response.text(),
		'event: delta\ndata: {"revision":1,"text":"secret model output"}\n\n')
	await new Promise(resolve => setTimeout(resolve, 0))
	const summary = entries
		.map(value => JSON.parse(value))
		.find(value => value.event === 'sse_edge_transport_summary')
	assert.equal(summary.route,
		'/api/ai/conversations/generations/{generationId}/events')
	assert.equal(summary.generationPublicId, generationId)
	assert.equal(summary.traceId, 'trace-edge-test')
	assert.equal(summary.firstDeltaReadAt, NOW)
	assert.equal(summary.totalChunks, 2)
	assert.equal(summary.totalBytes > 0, true)
	assert.doesNotMatch(JSON.stringify(summary), /secret model output/)
})

test('root host forwards a bounded generation diagnostics summary path', async () => {
	const generationId = 'AZ-vpV3kfag70-0EMMUETQ'
	let captured
	const response = await handleRequest(
		request(
			'niko000o.site',
			`/api/ai/conversations/generations/${generationId}/stream-diagnostics`,
			{
				method: 'POST',
				body: JSON.stringify({ usagePublicId: 'AZ-50wCZAQGBuCvbSqIYsA' }),
				headers: { 'Content-Type': 'application/json' }
			}),
		ENV,
		runtime(upstream => {
			captured = upstream
			return new Response(null, { status: 204 })
		})
	)

	assert.equal(response.status, 204)
	assert.equal(captured.url,
		`https://api.niko000o.site/api/ai/conversations/generations/${generationId}/stream-diagnostics`)
	assert.equal(captured.method, 'POST')
})

test('root host forwards only exact asynchronous generation query and cancel endpoints', async () => {
	const generationId = 'AZ-vpV3kfag70-0EMMUETQ'
	const forwarded = []
	const fetchImpl = async upstream => {
		forwarded.push({ url: upstream.url, method: upstream.method })
		return Response.json({ ok: true })
	}
	const active = await handleRequest(
		request('niko000o.site', '/api/ai/conversations/generations'),
		ENV,
		runtime(fetchImpl)
	)
	const byIdempotency = await handleRequest(
		request('niko000o.site', '/api/ai/conversations/generations/by-idempotency', {
			headers: { 'Idempotency-Key': '4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6' }
		}),
		ENV,
		runtime(fetchImpl)
	)
	const detail = await handleRequest(
		request('niko000o.site', `/api/ai/conversations/generations/${generationId}`),
		ENV,
		runtime(fetchImpl)
	)
	const cancellation = await handleRequest(
		request('niko000o.site', `/api/ai/conversations/generations/${generationId}/cancel`, {
			method: 'POST'
		}),
		ENV,
		runtime(fetchImpl)
	)
	const unknownAction = await handleRequest(
		request('niko000o.site', `/api/ai/conversations/generations/${generationId}/delete`),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)

	assert.equal(active.status, 200)
	assert.equal(byIdempotency.status, 200)
	assert.equal(detail.status, 200)
	assert.equal(cancellation.status, 200)
	assert.equal(unknownAction.status, 403)
	assert.deepEqual(forwarded, [
		{
			url: 'https://api.niko000o.site/api/ai/conversations/generations',
			method: 'GET'
		},
		{
			url: 'https://api.niko000o.site/api/ai/conversations/generations/by-idempotency',
			method: 'GET'
		},
		{
			url: `https://api.niko000o.site/api/ai/conversations/generations/${generationId}`,
			method: 'GET'
		},
		{
			url: `https://api.niko000o.site/api/ai/conversations/generations/${generationId}/cancel`,
			method: 'POST'
		}
	])
})

test('root host forwards conversation history and attachment preupload without streaming headers', async () => {
	const forwarded = []
	const fetchImpl = async upstream => {
		forwarded.push({
			url: upstream.url,
			method: upstream.method
		})
		return Response.json({ ok: true })
	}
	const history = await handleRequest(
		request('niko000o.site', '/api/ai/conversations?cursor=&pageSize=20'),
		ENV,
		runtime(fetchImpl)
	)
	const messages = await handleRequest(
		request(
			'niko000o.site',
			'/api/ai/conversations/AZ-vpV3kfag70-0EMMUETQ/messages?pageSize=50'
		),
		ENV,
		runtime(fetchImpl)
	)
	const preupload = await handleRequest(
		request('niko000o.site', '/api/ai/conversation-attachments/preuploads', {
			method: 'POST',
			body: JSON.stringify({ files: [] }),
			headers: { 'Content-Type': 'application/json' }
		}),
		ENV,
		runtime(fetchImpl)
	)

	assert.equal(history.status, 200)
	assert.equal(messages.status, 200)
	assert.equal(preupload.status, 200)
	assert.equal(history.headers.get('X-Accel-Buffering'), null)
	assert.equal(preupload.headers.get('X-Accel-Buffering'), null)
	assert.deepEqual(forwarded, [
		{
			url: 'https://api.niko000o.site/api/ai/conversations?cursor=&pageSize=20',
			method: 'GET'
		},
		{
			url: 'https://api.niko000o.site/api/ai/conversations/AZ-vpV3kfag70-0EMMUETQ/messages?pageSize=50',
			method: 'GET'
		},
		{
			url: 'https://api.niko000o.site/api/ai/conversation-attachments/preuploads',
			method: 'POST'
		}
	])
})

test('root host forwards context usage snapshots and asynchronous compaction requests', async () => {
	const conversationId = 'AZ-vpV3kfag70-0EMMUETQ'
	const modelPublicId = 'AAABi0VWeJ8'
	const idempotencyKey = '4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6'
	const forwarded = []
	const fetchImpl = async upstream => {
		forwarded.push({
			url: upstream.url,
			method: upstream.method,
			idempotencyKey: upstream.headers.get('Idempotency-Key'),
			body: upstream.method === 'POST' ? await upstream.text() : null
		})
		return Response.json({ status: 'ok' }, {
			status: upstream.method === 'POST' ? 202 : 200
		})
	}
	const usage = await handleRequest(
		request(
			'niko000o.site',
			`/api/ai/conversations/${conversationId}/context-usage?modelPublicId=${modelPublicId}`),
		ENV,
		runtime(fetchImpl)
	)
	const compaction = await handleRequest(
		request(
			'niko000o.site',
			`/api/ai/conversations/${conversationId}/compactions`,
			{
				method: 'POST',
				headers: {
					'Content-Type': 'application/json',
					'Idempotency-Key': idempotencyKey
				},
				body: JSON.stringify({ modelPublicId })
			}),
		ENV,
		runtime(fetchImpl)
	)

	assert.equal(usage.status, 200)
	assert.equal(compaction.status, 202)
	assert.deepEqual(forwarded, [
		{
			url: `https://api.niko000o.site/api/ai/conversations/${conversationId}/context-usage?modelPublicId=${modelPublicId}`,
			method: 'GET',
			idempotencyKey: null,
			body: null
		},
		{
			url: `https://api.niko000o.site/api/ai/conversations/${conversationId}/compactions`,
			method: 'POST',
			idempotencyKey,
			body: JSON.stringify({ modelPublicId })
		}
	])
})

test('root host streams conversation context events without buffering', async () => {
	const conversationId = 'AZ-vpV3kfag70-0EMMUETQ'
	const modelPublicId = 'AAABi0VWeJ8'
	let captured
	const response = await handleRequest(
		request(
			'niko000o.site',
			`/api/ai/conversations/${conversationId}/context/events?modelPublicId=${modelPublicId}&afterRevision=12`,
			{ headers: { Accept: 'text/event-stream' } }),
		ENV,
		runtime(upstream => {
			captured = upstream
			return new Response('event: context_snapshot\ndata: {}\n\n', {
				headers: { 'Content-Type': 'text/event-stream' }
			})
		})
	)

	assert.equal(response.status, 200)
	assert.equal(
		captured.url,
		`https://api.niko000o.site/api/ai/conversations/${conversationId}/context/events?modelPublicId=${modelPublicId}&afterRevision=12`
	)
	assert.equal(response.headers.get('X-Accel-Buffering'), 'no')
	assert.match(response.headers.get('Cache-Control'), /no-transform/)
	assert.equal(
		await response.text(),
		'event: context_snapshot\ndata: {}\n\n'
	)
})

test('administrator host rejects ordinary conversation history and attachment APIs', async () => {
	const fetchImpl = () => {
		throw new Error('upstream must not be called')
	}
	const history = await handleRequest(
		request('admin.niko000o.site', '/api/ai/conversations'),
		ENV,
		runtime(fetchImpl)
	)
	const preupload = await handleRequest(
		request('admin.niko000o.site', '/api/ai/conversation-attachments/preuploads', {
			method: 'POST',
			body: '{}'
		}),
		ENV,
		runtime(fetchImpl)
	)

	assert.equal(history.status, 403)
	assert.equal(preupload.status, 403)
})

test('root host forwards only canonical AI conversation continuation IDs', async () => {
	const publicId = 'AZ-vpV3kfag70-0EMMUETQ'
	let captured
	const allowed = await handleRequest(
		request(
			'niko000o.site',
			`/api/ai/conversations/${publicId}/responses`,
			{
				method: 'POST',
				body: '{}',
				headers: { 'Content-Type': 'application/json' }
			}
		),
		ENV,
		runtime(async upstream => {
			captured = upstream
			return new Response('', {
				status: 200,
				headers: { 'Content-Type': 'text/event-stream' }
			})
		})
	)
	const malformed = await handleRequest(
		request(
			'niko000o.site',
			'/api/ai/conversations/not-a-public-id/responses',
			{ method: 'POST', body: '{}' }
		),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)
	const admin = await handleRequest(
		request(
			'admin.niko000o.site',
			`/api/ai/conversations/${publicId}/responses`,
			{ method: 'POST', body: '{}' }
		),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)

	assert.equal(allowed.status, 200)
	assert.equal(
		captured.url,
		`https://api.niko000o.site/api/ai/conversations/${publicId}/responses`
	)
	assert.equal(malformed.status, 403)
	assert.equal(admin.status, 403)
})

test('worker preserves admin request method and streams its body', async () => {
	let capturedBody
	let capturedMethod
	const response = await handleRequest(
		request('admin.niko000o.site', '/api/admin/me', {
			method: 'PATCH',
			body: JSON.stringify({ identifier: 'masked@example.test' }),
			headers: { 'Content-Type': 'application/json' }
		}),
		ENV,
		runtime(async upstream => {
			capturedMethod = upstream.method
			capturedBody = await upstream.text()
			return Response.json({ accepted: true })
		})
	)

	assert.equal(response.status, 200)
	assert.equal(capturedMethod, 'PATCH')
	assert.equal(
		capturedBody,
		JSON.stringify({ identifier: 'masked@example.test' })
	)
})

test('worker rejects non-API methods without narrowing the admin controller contract', async () => {
	const response = await handleRequest(
		request('admin.niko000o.site', '/api/admin/auth/state', {
			method: 'BREW'
		}),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)

	assert.equal(response.status, 405)
	assert.equal(
		response.headers.get('Allow'),
		'GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS'
	)
})

test('host and path policy rejects cross-surface and encoded-path bypasses', async () => {
	const forbiddenAdmin = await handleRequest(
		request('niko000o.site', '/api/admin/auth/state'),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)
	const forbiddenUser = await handleRequest(
		request('admin.niko000o.site', '/api/auth/csrf'),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)
	const forbiddenUserModelApi = await handleRequest(
		request('admin.niko000o.site', '/api/ai-models/AAABi0VWeJ8'),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)
	const malformedModelDetail = await handleRequest(
		request('niko000o.site', '/api/ai-models/not-a-public-id'),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)
	const malformedMessages = await handleRequest(
		request(
			'niko000o.site',
			'/api/ai/conversations/not-a-public-id/messages'
		),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)
	const unknownConversationAction = await handleRequest(
		request(
			'niko000o.site',
			'/api/ai/conversations/AZ-vpV3kfag70-0EMMUETQ/export'
		),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)
	const nestedConversationList = await handleRequest(
		request('niko000o.site', '/api/ai/conversations/archive'),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)
	const encoded = await handleRequest(
		request('niko000o.site', '/api/auth/%2e%2e/admin/auth/state'),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)

	assert.equal(forbiddenAdmin.status, 403)
	assert.equal(forbiddenUser.status, 403)
	assert.equal(forbiddenUserModelApi.status, 403)
	assert.equal(malformedModelDetail.status, 403)
	assert.equal(malformedMessages.status, 403)
	assert.equal(unknownConversationAction.status, 403)
	assert.equal(nestedConversationList.status, 403)
	assert.equal(encoded.status, 403)
})

test('worker overwrites spoofable proxy headers and signs the exact upstream request', async () => {
	let captured
	await handleRequest(
		request('admin.niko000o.site', '/api/admin/auth/state', {
			headers: {
				'X-AIT-Edge-Host': 'evil.example',
				'X-AIT-Edge-Timestamp': '1',
				'X-AIT-Edge-Signature': 'forged',
				'X-AIT-Edge-Future-Field': 'forged',
				'X-Forwarded-Host': 'evil.example',
				Forwarded: 'host=evil.example'
			}
		}),
		ENV,
		runtime(async upstream => {
			captured = upstream
			return new Response('{}', {
				status: 200,
				headers: { 'Content-Type': 'application/json' }
			})
		})
	)

	const timestamp = String(Math.floor(NOW / 1000))
	const canonical = [
		'v2',
		'GET',
		'/api/admin/auth/state',
		'admin.niko000o.site',
		timestamp,
		'test-ray-ord',
		'203.0.113.10',
		'US',
		'64500',
		'41.8781',
		'-87.6298'
	].join('\n')
	const expected = createHmac(
		'sha256',
		Buffer.from(ENV.EDGE_PROXY_HMAC_SECRET_BASE64, 'base64')
	).update(canonical).digest('base64url')

	assert.equal(captured.headers.get('X-AIT-Edge-Version'), 'v2')
	assert.equal(captured.headers.get('X-AIT-Edge-Host'), 'admin.niko000o.site')
	assert.equal(captured.headers.get('X-AIT-Edge-Timestamp'), timestamp)
	assert.equal(captured.headers.get('X-AIT-Edge-Ray'), 'test-ray-ord')
	assert.equal(captured.headers.get('X-AIT-Edge-IP'), '203.0.113.10')
	assert.equal(captured.headers.get('X-AIT-Edge-Country'), 'US')
	assert.equal(captured.headers.get('X-AIT-Edge-ASN'), '64500')
	assert.equal(captured.headers.get('X-AIT-Edge-Latitude'), '41.8781')
	assert.equal(captured.headers.get('X-AIT-Edge-Longitude'), '-87.6298')
	assert.equal(captured.headers.get('X-AIT-Edge-Signature'), expected)
	assert.equal(captured.headers.get('X-AIT-Edge-Future-Field'), null)
	assert.equal(captured.headers.get('X-Forwarded-Host'), null)
	assert.equal(captured.headers.get('Forwarded'), null)
	assert.equal(captured.headers.get('CF-Connecting-IP'), null)
})

test('root voice WebSocket is signed and transparently upgraded without credentials', async () => {
	let captured
	const upstreamResponse = websocketUpgradeResponse()
	const response = await handleRequest(
		request('niko000o.site', '/ws/voice', {
			migrated: false,
			headers: {
				Upgrade: 'websocket',
				'Sec-WebSocket-Protocol': VOICE_PROTOCOL_HEADER,
				Connection: 'Upgrade',
				Authorization: 'Bearer test-credential',
				Cookie: `${COOKIE_SCOPE_MARKER_NAME}=1; access_token=test-credential`,
				'X-AIT-Edge-Host': 'evil.example',
				'X-AIT-Edge-Signature': 'forged',
				'X-Forwarded-Host': 'evil.example'
			}
		}),
		ENV,
		runtime(upstream => {
			captured = upstream
			return upstreamResponse
		})
	)

	const timestamp = String(Math.floor(NOW / 1000))
	const canonical = [
		'v2',
		'GET',
		'/ws/voice',
		'niko000o.site',
		timestamp,
		'test-ray-ord',
		'203.0.113.10',
		'US',
		'64500',
		'41.8781',
		'-87.6298'
	].join('\n')
	const expected = createHmac(
		'sha256',
		Buffer.from(ENV.EDGE_PROXY_HMAC_SECRET_BASE64, 'base64')
	).update(canonical).digest('base64url')

	assert.equal(response, upstreamResponse)
	assert.equal(captured.url, 'https://api.niko000o.site/ws/voice')
	assert.equal(captured.method, 'GET')
	assert.equal(captured.headers.get('Upgrade'), 'websocket')
	assert.equal(captured.headers.get('Sec-WebSocket-Protocol'), VOICE_PROTOCOL_HEADER)
	assert.equal(captured.headers.get('Origin'), 'https://niko000o.site')
	assert.equal(captured.headers.get('X-Client-Platform'), 'H5')
	assert.equal(captured.headers.get('Cookie'), null)
	assert.equal(captured.headers.get('Authorization'), null)
	assert.equal(captured.headers.get('X-Forwarded-Host'), null)
	assert.equal(captured.headers.get('X-AIT-Edge-Host'), 'niko000o.site')
	assert.equal(captured.headers.get('X-AIT-Edge-Signature'), expected)
})

test('voice WebSocket emits one sanitized success summary without changing upgrade response', async () => {
	const diagnostic = diagnosticLogger()
	const upstreamResponse = websocketUpgradeResponse()
	const response = await handleRequest(
		request('niko000o.site', '/ws/voice', {
			migrated: false,
			headers: {
				Upgrade: 'websocket',
				'Sec-WebSocket-Protocol': VOICE_PROTOCOL_HEADER,
				Cookie: `${COOKIE_SCOPE_MARKER_NAME}=1; access_token=do-not-log`
			}
		}),
		ENV,
		runtime(() => upstreamResponse, diagnostic.logger)
	)

	assert.equal(response, upstreamResponse)
	assert.equal(diagnostic.entries.length, 1)
	assert.deepEqual(diagnostic.entries[0], {
		event: 'voice_ws_edge_summary',
		cfRay: 'test-ray-ord',
		transport: 'H5_BROWSER',
		clientCookiePresent: true,
		upstreamCookieForwarded: false,
		upstreamStatus: 101,
		responseWebSocketPresent: true,
		protocolMatched: true,
		setCookieReadable: true,
		setCookieCount: 0,
		edgeOutcome: 'EDGE_WEBSOCKET_UPGRADED',
		exceptionType: 'ABSENT',
		elapsedMs: 0
	})
	const serialized = JSON.stringify(diagnostic.entries[0])
	assert.doesNotMatch(serialized, /access_token|do-not-log|ait-ticket|C{43}/)
})

test('voice WebSocket logging failure never changes the upstream upgrade response', async () => {
	const upstreamResponse = websocketUpgradeResponse()
	const response = await handleRequest(
		request('niko000o.site', '/ws/voice', { headers: {
			Upgrade: 'websocket',
			'Sec-WebSocket-Protocol': VOICE_PROTOCOL_HEADER
		} }),
		ENV,
		runtime(
			() => upstreamResponse,
			{
				info() { throw new Error('diagnostic sink unavailable') },
				warn() { throw new Error('diagnostic sink unavailable') }
			})
	)

	assert.equal(response, upstreamResponse)
})

test('voice WebSocket summaries distinguish upstream, authorization, upgrade and cookie failures', async () => {
	const cases = [
		{
			response: () => { throw new TypeError('sensitive-upstream-message') },
			outcome: 'EDGE_UPSTREAM_UNAVAILABLE',
			status: -1,
			exceptionType: 'TypeError'
		},
		{
			response: () => websocketUpgradeResponse({ status: 403, webSocket: null }),
			outcome: 'EDGE_WEBSOCKET_AUTHORIZATION_FAILED',
			status: 403,
			exceptionType: 'ABSENT'
		},
		{
			response: () => websocketUpgradeResponse({ webSocket: null }),
			outcome: 'EDGE_WEBSOCKET_UPGRADE_FAILED',
			status: 101,
			exceptionType: 'ABSENT'
		},
		{
			response: () => websocketUpgradeResponse({
				headers: { 'Set-Cookie': 'secret=do-not-log; Secure; HttpOnly' }
			}),
			outcome: 'EDGE_WEBSOCKET_COOKIE_POLICY_VIOLATION',
			status: 101,
			exceptionType: 'ABSENT',
			setCookieCount: 1
		}
	]

	for (const item of cases) {
		const diagnostic = diagnosticLogger()
		await handleRequest(
			request('niko000o.site', '/ws/voice', { headers: {
				Upgrade: 'websocket',
				'Sec-WebSocket-Protocol': VOICE_PROTOCOL_HEADER
			} }),
			ENV,
			runtime(item.response, diagnostic.logger)
		)

		assert.equal(diagnostic.entries.length, 1)
		assert.equal(diagnostic.entries[0].edgeOutcome, item.outcome)
		assert.equal(diagnostic.entries[0].upstreamStatus, item.status)
		assert.equal(diagnostic.entries[0].exceptionType, item.exceptionType)
		if (item.setCookieCount !== undefined) {
			assert.equal(diagnostic.entries[0].setCookieCount, item.setCookieCount)
		}
		assert.doesNotMatch(
			JSON.stringify(diagnostic.entries[0]),
			/sensitive-upstream-message|secret=|do-not-log|ait-ticket/)
	}
})

test('Android voice WebSocket accepts App-Plus browser metadata and strips unsafe upstream headers', async () => {
	let captured
	const upstreamResponse = websocketUpgradeResponse()
	const response = await handleRequest(
		request('niko000o.site', '/ws/voice', {
			migrated: false,
			headers: {
				Upgrade: 'websocket',
				'Sec-WebSocket-Protocol': VOICE_PROTOCOL_HEADER,
				Connection: 'Upgrade',
				'X-Client-Platform': 'ANDROID',
				Origin: 'https://niko000o.site',
				Referer: 'https://niko000o.site/pages/ai-chat/index',
				'Sec-Fetch-Site': 'same-origin',
				'Sec-Fetch-Mode': 'websocket',
				'Sec-Fetch-Dest': 'empty',
				Authorization: 'Bearer must-not-pass-to-websocket',
				Cookie: 'captured_proxy_cookie=must-not-pass'
			}
		}),
		ENV,
		runtime(upstream => {
			captured = upstream
			return upstreamResponse
		})
	)

	const canonical = [
		'v2',
		'GET',
		'/ws/voice',
		'niko000o.site',
		String(Math.floor(NOW / 1000)),
		'test-ray-ord',
		'203.0.113.10',
		'US',
		'64500',
		'41.8781',
		'-87.6298'
	].join('\n')
	const expectedSignature = createHmac(
		'sha256',
		Buffer.from(ENV.EDGE_PROXY_HMAC_SECRET_BASE64, 'base64')
	).update(canonical).digest('base64url')

	assert.equal(response, upstreamResponse)
	assert.equal(captured.url, 'https://api.niko000o.site/ws/voice')
	assert.equal(captured.headers.get('X-Client-Platform'), 'ANDROID')
	assert.equal(captured.headers.get('Origin'), null)
	assert.equal(captured.headers.get('Referer'), null)
	assert.equal(captured.headers.get('Sec-Fetch-Site'), null)
	assert.equal(captured.headers.get('Sec-Fetch-Mode'), null)
	assert.equal(captured.headers.get('Sec-Fetch-Dest'), null)
	assert.equal(captured.headers.get('Sec-Fetch-User'), null)
	assert.equal(captured.headers.get('Cookie'), null)
	assert.equal(captured.headers.get('Authorization'), null)
	assert.equal(captured.headers.get('Sec-WebSocket-Protocol'), VOICE_PROTOCOL_HEADER)
	assert.equal(captured.headers.get('X-AIT-Edge-Signature'), expectedSignature)
})

test('voice WebSocket accepts only the exact path and a GET Upgrade request', async () => {
	const notGet = await handleRequest(
		request('niko000o.site', '/ws/voice', {
			method: 'POST',
			headers: { Upgrade: 'websocket' }
		}),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)
	const missingUpgrade = await handleRequest(
		request('niko000o.site', '/ws/voice'),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)
	const wrongUpgrade = await handleRequest(
		request('niko000o.site', '/ws/voice', {
			headers: { Upgrade: 'h2c' }
		}),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)
	const forbiddenPaths = await Promise.all([
		'/ws',
		'/ws/admin',
		'/ws/voice/extra'
	].map(path => handleRequest(
		request('niko000o.site', path, {
			headers: { Upgrade: 'websocket' }
		}),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		}))))

	assert.equal(notGet.status, 405)
	assert.equal(notGet.headers.get('Allow'), 'GET')
	assert.equal(missingUpgrade.status, 426)
	assert.equal(missingUpgrade.headers.get('Upgrade'), 'websocket')
	assert.equal(wrongUpgrade.status, 426)
	assert.deepEqual(forbiddenPaths.map(response => response.status), [403, 403, 403])
})

test('voice WebSocket fails closed when the upstream handshake is unsafe', async () => {
	const requestOptions = { headers: {
		Upgrade: 'websocket',
		'Sec-WebSocket-Protocol': VOICE_PROTOCOL_HEADER
	} }
	const unavailable = await handleRequest(
		request('niko000o.site', '/ws/voice', requestOptions),
		ENV,
		runtime(() => {
			throw new Error('test upstream failure')
		})
	)
	const rejected = await handleRequest(
		request('niko000o.site', '/ws/voice', requestOptions),
		ENV,
		runtime(() => websocketUpgradeResponse({
			status: 503,
			webSocket: null
		}))
	)
	const missingSocket = await handleRequest(
		request('niko000o.site', '/ws/voice', requestOptions),
		ENV,
		runtime(() => websocketUpgradeResponse({ webSocket: null }))
	)
	const cookie = await handleRequest(
		request('niko000o.site', '/ws/voice', requestOptions),
		ENV,
		runtime(() => websocketUpgradeResponse({
			headers: {
				'Set-Cookie': 'access_token=test; Path=/; Secure; HttpOnly'
			}
		}))
	)

	assert.equal(unavailable.status, 502)
	assert.deepEqual(await unavailable.json(), {
		code: 'EDGE_UPSTREAM_UNAVAILABLE',
		message: 'The edge request was rejected.'
	})
	assert.equal(rejected.status, 503)
	assert.deepEqual(await rejected.json(), {
		code: 'EDGE_WEBSOCKET_AUTHORIZATION_FAILED',
		message: 'The edge request was rejected.'
	})
	assert.equal(missingSocket.status, 502)
	assert.equal(cookie.status, 502)
	assert.deepEqual(await cookie.json(), {
		code: 'EDGE_WEBSOCKET_COOKIE_POLICY_VIOLATION',
		message: 'The edge request was rejected.'
	})
})

test('voice WebSocket preserves only the controlled Spring authorization statuses', async () => {
	const requestOptions = { headers: {
		Upgrade: 'websocket',
		'Sec-WebSocket-Protocol': VOICE_PROTOCOL_HEADER
	} }
	for (const status of [400, 401, 403, 428, 503]) {
		const response = await handleRequest(
			request('niko000o.site', '/ws/voice', requestOptions),
			ENV,
			runtime(() => websocketUpgradeResponse({ status, webSocket: null }))
		)
		assert.equal(response.status, status)
		assert.deepEqual(await response.json(), {
			code: 'EDGE_WEBSOCKET_AUTHORIZATION_FAILED',
			message: 'The edge request was rejected.'
		})
	}
	const unexpected = await handleRequest(
		request('niko000o.site', '/ws/voice', requestOptions),
		ENV,
		runtime(() => websocketUpgradeResponse({ status: 409, webSocket: null }))
	)
	assert.equal(unexpected.status, 502)
	assert.equal((await unexpected.json()).code, 'EDGE_WEBSOCKET_UPGRADE_FAILED')
})

test('Android voice WebSocket rejects upstream cookies with the native policy code', async () => {
	const diagnostic = diagnosticLogger()
	const response = await handleRequest(
		request('niko000o.site', '/ws/voice', {
			migrated: false,
			headers: {
				Upgrade: 'websocket',
				'Sec-WebSocket-Protocol': VOICE_PROTOCOL_HEADER,
				'X-Client-Platform': 'ANDROID'
			}
		}),
		ENV,
		runtime(() => websocketUpgradeResponse({
			headers: {
				'Set-Cookie': 'access_token=unexpected; Path=/; Secure; HttpOnly'
			}
		}), diagnostic.logger)
	)

	assert.equal(response.status, 502)
	assert.equal((await response.json()).code,
		'EDGE_ANDROID_COOKIE_POLICY_VIOLATION')
	assert.equal(diagnostic.entries.length, 1)
	assert.equal(diagnostic.entries[0].transport, 'ANDROID_NATIVE')
	assert.equal(diagnostic.entries[0].setCookieCount, 1)
	assert.equal(diagnostic.entries[0].edgeOutcome,
		'EDGE_ANDROID_COOKIE_POLICY_VIOLATION')
	assert.doesNotMatch(JSON.stringify(diagnostic.entries[0]), /access_token|unexpected/)
})

test('pre-auth and risk challenge paths are isolated to their matching host', async () => {
	const forwarded = []
	const fetchImpl = async upstream => {
		forwarded.push(new URL(upstream.url).pathname)
		return new Response(null, { status: 204 })
	}
	const rootPreAuth = await handleRequest(
		request('niko000o.site', '/api/_edge/pre-auth', { method: 'POST' }),
		ENV,
		runtime(fetchImpl)
	)
	const adminPreAuth = await handleRequest(
		request('admin.niko000o.site', '/api/admin/_edge/pre-auth', { method: 'POST' }),
		ENV,
		runtime(fetchImpl)
	)
	const wrongSurface = await handleRequest(
		request('niko000o.site', '/api/admin/_edge/pre-auth', { method: 'POST' }),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)

	assert.equal(rootPreAuth.status, 204)
	assert.equal(adminPreAuth.status, 204)
	assert.equal(wrongSurface.status, 403)
	assert.deepEqual(forwarded, [
		'/api/_edge/pre-auth',
		'/api/admin/_edge/pre-auth'
	])
})

test('voice WebSocket rejects invalid v2 subprotocols before calling upstream', async () => {
	let upstreamCalls = 0
	const fetchImpl = () => {
		upstreamCalls += 1
		return websocketUpgradeResponse()
	}
	const values = [
		undefined,
		'ait-voice-v1, ait-ticket.' + VOICE_TICKET,
		'ait-voice-v2',
		'ait-voice-v2, ait-ticket.short',
		VOICE_PROTOCOL_HEADER + ', unknown',
		'ait-voice-v2, ait-voice-v2',
		'a'.repeat(129)
	]
	for (const value of values) {
		const headers = { Upgrade: 'websocket' }
		if (value !== undefined) headers['Sec-WebSocket-Protocol'] = value
		const response = await handleRequest(
			request('niko000o.site', '/ws/voice', { headers }),
			ENV,
			runtime(fetchImpl)
		)
		assert.equal(response.status, 400)
		assert.equal((await response.json()).code, 'EDGE_WEBSOCKET_PROTOCOL_INVALID')
	}
	assert.equal(upstreamCalls, 0)
})

test('voice WebSocket rejects a reflected ticket subprotocol', async () => {
	const response = await handleRequest(
		request('niko000o.site', '/ws/voice', {
			headers: {
				Upgrade: 'websocket',
				'Sec-WebSocket-Protocol': VOICE_PROTOCOL_HEADER
			}
		}),
		ENV,
		runtime(() => websocketUpgradeResponse({
			headers: {
				'Sec-WebSocket-Protocol': `ait-ticket.${VOICE_TICKET}`
			}
		}))
	)
	assert.equal(response.status, 502)
	assert.equal((await response.json()).code, 'EDGE_WEBSOCKET_UPGRADE_FAILED')
})

test('Android WebView risk navigation preserves only the matching host bridge cookie', async () => {
	const forwarded = []
	const fetchImpl = async upstream => {
		forwarded.push({
			url: upstream.url,
			cookie: upstream.headers.get('Cookie'),
			platform: upstream.headers.get('X-Client-Platform')
		})
		return new Response(null, {
			status: 303,
			headers: { Location: '/pages/risk/challenge-complete' }
		})
	}
	const root = await handleRequest(
		request('niko000o.site', `/api/_edge/risk-challenge?ref=${'A'.repeat(43)}`, {
			migrated: false,
			headers: {
				Cookie: '__Host-ait-preauth=user-token; __Host-ait-admin-preauth=must-not-forward; admin_session=must-not-forward; cf_clearance=clearance',
				'Sec-Fetch-Mode': 'navigate',
				'Sec-Fetch-Dest': 'document'
			}
		}),
		ENV,
		runtime(fetchImpl))
	const admin = await handleRequest(
		request('admin.niko000o.site', `/api/admin/_edge/risk-challenge?ref=${'B'.repeat(43)}`, {
			migrated: false,
			headers: {
				Cookie: '__Host-ait-admin-preauth=admin-token; __Host-ait-preauth=must-not-forward; access_token=must-not-forward; cf_clearance=clearance',
				'Sec-Fetch-Mode': 'navigate',
				'Sec-Fetch-Dest': 'document'
			}
		}),
		ENV,
		runtime(fetchImpl))

	assert.equal(root.status, 303)
	assert.equal(admin.status, 303)
	assert.equal(forwarded.length, 2)
	assert.match(forwarded[0].cookie, /__Host-ait-preauth=user-token/)
	assert.doesNotMatch(forwarded[0].cookie, /ait-admin-preauth/)
	assert.doesNotMatch(forwarded[0].cookie, /admin_session/)
	assert.match(forwarded[1].cookie, /__Host-ait-admin-preauth=admin-token/)
	assert.doesNotMatch(forwarded[1].cookie, /(?:^|;)\s*__Host-ait-preauth=/)
	assert.doesNotMatch(forwarded[1].cookie, /access_token/)
	assert.deepEqual(forwarded.map(item => /cf_clearance=clearance/.test(item.cookie)), [true, true])
	assert.deepEqual(forwarded.map(item => item.platform), ['H5', 'H5'])
})

test('administrator Android native transport strips cookies while retaining explicit PreAuth', async () => {
	let captured
	const response = await handleRequest(
		request('admin.niko000o.site', '/api/admin/auth/state', {
			migrated: false,
			headers: {
				'X-Client-Platform': 'ANDROID',
				'X-AIT-PreAuth': 'native-preauth',
				Cookie: '__Host-ait-admin-preauth=must-not-forward'
			}
		}),
		ENV,
		runtime(upstream => {
			captured = upstream
			return new Response('{}', {
				status: 200,
				headers: { 'Content-Type': 'application/json' }
			})
		}))

	assert.equal(response.status, 200)
	assert.equal(captured.headers.get('Cookie'), null)
	assert.equal(captured.headers.get('X-Client-Platform'), 'ANDROID')
	assert.equal(captured.headers.get('X-AIT-PreAuth'), 'native-preauth')
})

test('ordinary WebRTC edge endpoints are forwarded only on the root host', async () => {
	const forwarded = []
	const fetchImpl = async upstream => {
		forwarded.push(new URL(upstream.url).pathname)
		return new Response(null, { status: 204 })
	}
	const start = await handleRequest(
		request('niko000o.site', '/api/_edge/webrtc/start'),
		ENV,
		runtime(fetchImpl)
	)
	const report = await handleRequest(
		request('niko000o.site', '/api/_edge/webrtc/report', { method: 'POST' }),
		ENV,
		runtime(fetchImpl)
	)
	const crossed = await handleRequest(
		request('admin.niko000o.site', '/api/_edge/webrtc/start'),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)

	assert.equal(start.status, 204)
	assert.equal(report.status, 204)
	assert.equal(crossed.status, 403)
	assert.deepEqual(forwarded, [
		'/api/_edge/webrtc/start',
		'/api/_edge/webrtc/report'
	])
})

test('business requests fail closed until the cookie-scope migration marker exists', async () => {
	const response = await handleRequest(
		request('niko000o.site', '/api/auth/csrf', { migrated: false }),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)

	assert.equal(response.status, 428)
	assert.equal((await response.json()).code, 'EDGE_COOKIE_SCOPE_RESET_REQUIRED')
})

test('migration endpoint expires legacy parent cookies before issuing the shared version marker', async () => {
	const response = await handleRequest(
		request('niko000o.site', '/api/_edge/cookie-scope', {
			method: 'POST',
			migrated: false
		}),
		ENV,
		runtime(() => {
			throw new Error('migration endpoint must not call upstream')
		})
	)
	const setCookies = response.headers.getSetCookie()

	assert.equal(response.status, 204)
	assert.equal(response.headers.get('X-AIT-Cookie-Scope-Reset'), '1')
	assert.ok(setCookies.some(value =>
		value.startsWith('XSRF-TOKEN=') &&
		value.includes('Domain=niko000o.site') &&
		value.includes('Max-Age=0')))
	assert.ok(setCookies.some(value =>
		value.startsWith('ADMIN-XSRF-TOKEN=') &&
		value.includes('Domain=.niko000o.site') &&
		value.includes('Max-Age=0')))
	assert.ok(setCookies.some(value =>
		value.startsWith(`${COOKIE_SCOPE_MARKER_NAME}=1`) &&
		value.includes('Max-Age=31536000') &&
		value.includes('HttpOnly')))
})

test('response policy rejects parent-domain and cross-surface business cookies', async () => {
	const parentDomain = await handleRequest(
		request('niko000o.site', '/api/auth/csrf'),
		ENV,
		runtime(() => new Response(null, {
			status: 204,
			headers: {
				'Set-Cookie': 'XSRF-TOKEN=value; Domain=niko000o.site; Path=/; Secure'
			}
		}))
	)
	const wrongSurface = await handleRequest(
		request('admin.niko000o.site', '/api/admin/auth/state'),
		ENV,
		runtime(() => new Response(null, {
			status: 200,
			headers: {
				'Set-Cookie': 'XSRF-TOKEN=value; Path=/; Secure'
			}
		}))
	)

	assert.equal(parentDomain.status, 502)
	assert.equal(wrongSurface.status, 502)
})

test('root login verification preserves the host-only TOTP flow cookie', async () => {
	const cookie = 'totp_login_flow=flow-token; Path=/api/auth/login/totp; '
		+ 'Secure; HttpOnly; SameSite=Strict'
	const response = await handleRequest(
		request('niko000o.site', '/api/auth/login/code/verify', {
			method: 'POST'
		}),
		ENV,
		runtime(() => new Response('{"status":"TOTP_REQUIRED"}', {
			status: 200,
			headers: {
				'Content-Type': 'application/json',
				'Set-Cookie': cookie
			}
		}))
	)

	assert.equal(response.status, 200)
	assert.deepEqual(response.headers.getSetCookie(), [cookie])
	assert.equal(response.headers.get('Cache-Control'), 'no-store')
})

test('administrator surface rejects the ordinary TOTP flow cookie', async () => {
	const response = await handleRequest(
		request('admin.niko000o.site', '/api/admin/auth/state'),
		ENV,
		runtime(() => new Response('{}', {
			status: 200,
			headers: {
				'Set-Cookie': 'totp_login_flow=flow-token; '
					+ 'Path=/api/auth/login/totp; Secure; HttpOnly; SameSite=Strict'
			}
		}))
	)

	assert.equal(response.status, 502)
	assert.deepEqual(await response.json(), {
		code: 'EDGE_COOKIE_POLICY_VIOLATION',
		message: 'The edge request was rejected.'
	})
})

test('root surface rejects a parent-domain TOTP flow cookie', async () => {
	const response = await handleRequest(
		request('niko000o.site', '/api/auth/login/code/verify', {
			method: 'POST'
		}),
		ENV,
		runtime(() => new Response('{"status":"TOTP_REQUIRED"}', {
			status: 200,
			headers: {
				'Set-Cookie': 'totp_login_flow=flow-token; Domain=niko000o.site; '
					+ 'Path=/api/auth/login/totp; Secure; HttpOnly; SameSite=Strict'
			}
		}))
	)

	assert.equal(response.status, 502)
	assert.deepEqual(await response.json(), {
		code: 'EDGE_COOKIE_POLICY_VIOLATION',
		message: 'The edge request was rejected.'
	})
})

test('allowed host-only cookies remain separate and responses are never cached', async () => {
	const headers = new Headers()
	headers.append('Set-Cookie', 'admin_session=session; Path=/api/admin; Secure; HttpOnly')
	headers.append('Set-Cookie', 'ADMIN-XSRF-TOKEN=csrf; Path=/; Secure')
	const response = await handleRequest(
		request('admin.niko000o.site', '/api/admin/auth/state'),
		ENV,
		runtime(() => new Response('{}', { status: 200, headers }))
	)

	assert.equal(response.status, 200)
	assert.equal(response.headers.getSetCookie().length, 2)
	assert.equal(response.headers.get('Cache-Control'), 'no-store')
	assert.equal(response.headers.get('CDN-Cache-Control'), 'no-store')
})

test('ordinary and administrator PreAuth cookies are allowed only on their own surface', async () => {
	const ordinary = await handleRequest(
		request(
			'niko000o.site',
			'/api/_edge/pre-auth',
			{ method: 'POST' }),
		ENV,
		runtime(() => new Response('{}', {
			status: 200,
			headers: {
				'Set-Cookie': '__Host-ait-preauth=value; Path=/; Secure; HttpOnly; SameSite=Strict'
			}
		}))
	)
	const administrator = await handleRequest(
		request(
			'admin.niko000o.site',
			'/api/admin/_edge/pre-auth',
			{ method: 'POST' }),
		ENV,
		runtime(() => new Response('{}', {
			status: 200,
			headers: {
				'Set-Cookie': '__Host-ait-admin-preauth=value; Path=/; Secure; HttpOnly; SameSite=Strict'
			}
		}))
	)
	const crossed = await handleRequest(
		request(
			'niko000o.site',
			'/api/_edge/pre-auth',
			{ method: 'POST' }),
		ENV,
		runtime(() => new Response('{}', {
			status: 200,
			headers: {
				'Set-Cookie': '__Host-ait-admin-preauth=value; Path=/; Secure; HttpOnly; SameSite=Strict'
			}
		}))
	)

	assert.equal(ordinary.status, 200)
	assert.equal(administrator.status, 200)
	assert.equal(crossed.status, 502)
})

test('cross-host upstream redirects fail closed', async () => {
	const response = await handleRequest(
		request('niko000o.site', '/api/auth/csrf'),
		ENV,
		runtime(upstream => {
			assert.equal(upstream.redirect, 'manual')
			return new Response(null, {
				status: 302,
				headers: { Location: 'https://evil.example/collect' }
			})
		})
	)
	const directApi = await handleRequest(
		request('admin.niko000o.site', '/api/admin/auth/state'),
		ENV,
		runtime(() => new Response(null, {
			status: 302,
			headers: { Location: 'https://api.niko000o.site/api/admin/auth/state' }
		}))
	)

	assert.equal(response.status, 502)
	assert.equal(directApi.status, 502)
})

test('mail inspection SSE stays streaming and preserves safe correlation headers', async () => {
	const jobId = 'AZ9nEjRWeJCrze8SNFZ4kA'
	const body = new ReadableStream({
		start(controller) {
			controller.enqueue(new TextEncoder().encode(
				'event: heartbeat\nid: 7\ndata: {"revision":7,"data":{}}\n\n'))
		}
	})
	let upstreamRequest
	const response = await handleRequest(
		request(
			'admin.niko000o.site',
			`/api/admin/mail-inspection/jobs/${jobId}/events`,
			{
				headers: {
					Accept: 'text/event-stream',
					'Last-Event-ID': '6',
					'X-Trace-Id': 'trace-test'
				}
			}),
		ENV,
		runtime(upstream => {
			upstreamRequest = upstream
			return new Response(body, {
				headers: {
					'Content-Type': 'text/event-stream',
					'X-Trace-Id': 'trace-origin'
				}
			})
		})
	)

	assert.equal(upstreamRequest.headers.get('Last-Event-ID'), '6')
	assert.equal(upstreamRequest.headers.get('X-Trace-Id'), 'trace-test')
	assert.equal(response.headers.get('Content-Type'), 'text/event-stream')
	assert.equal(response.headers.get('X-Trace-Id'), 'trace-origin')
	assert.equal(response.headers.get('X-Accel-Buffering'), 'no')
	assert.equal(
		response.headers.get('Cache-Control'),
		'no-store, private, no-transform')
	assert.equal(response.body, body)
})

test('mail inspection SSE propagates client cancellation to the Origin request', async () => {
	const controller = new AbortController()
	let upstreamSignal
	let releaseFetch
	let markCaptured
	const captured = new Promise(resolve => { markCaptured = resolve })
	const responsePromise = handleRequest(
		request(
			'admin.niko000o.site',
			'/api/admin/mail-inspection/jobs/AZ9nEjRWeJCrze8SNFZ4kA/events',
			{
				headers: { Accept: 'text/event-stream' },
				signal: controller.signal
			}),
		ENV,
		runtime(upstream => {
			upstreamSignal = upstream.signal
			markCaptured()
			return new Promise(resolve => { releaseFetch = resolve })
		})
	)
	await captured
	assert.equal(upstreamSignal.aborted, false)
	controller.abort()
	assert.equal(upstreamSignal.aborted, true)
	releaseFetch(new Response(null, {
		headers: { 'Content-Type': 'text/event-stream' }
	}))
	await responsePromise
})

test('sampled SSE logs use a route template and never contain the Job ID', async () => {
	const entries = []
	const jobId = 'AZ9nEjRWeJCrze8SNFZ4kA'
	await handleRequest(
		request(
			'admin.niko000o.site',
			`/api/admin/mail-inspection/jobs/${jobId}/events`),
		{ ...ENV, SSE_ROUTE_LOG_SAMPLE_RATE: '1' },
		{
			...runtime(() => new Response(null, {
				headers: { 'Content-Type': 'text/event-stream' }
			})),
			random: () => 0,
			log: { info: value => entries.push(value) }
		}
	)
	assert.equal(entries.length, 1)
	assert.match(entries[0], /\/jobs\/\{jobId\}\/events/)
	assert.doesNotMatch(entries[0], new RegExp(jobId))
})

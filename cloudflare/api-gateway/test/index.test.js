import assert from 'node:assert/strict'
import { createHmac } from 'node:crypto'
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

function runtime(fetchImpl) {
	return {
		fetch: fetchImpl,
		now: () => NOW
	}
}

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
	assert.equal(captured.cache, 'no-store')
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
	const encoded = await handleRequest(
		request('niko000o.site', '/api/auth/%2e%2e/admin/auth/state'),
		ENV,
		runtime(() => {
			throw new Error('upstream must not be called')
		})
	)

	assert.equal(forbiddenAdmin.status, 403)
	assert.equal(forbiddenUser.status, 403)
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

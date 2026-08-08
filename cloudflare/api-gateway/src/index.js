const ROOT_HOST = 'niko000o.site'
const ADMIN_HOST = 'admin.niko000o.site'
const UPSTREAM_ORIGIN = 'https://api.niko000o.site'
const SIGNATURE_VERSION = 'v2'
const AI_MODEL_DETAIL_PATH =
	/^\/api\/ai-models\/[A-Za-z0-9_-]{11}$/
const AI_CONVERSATION_MESSAGES_PATH =
	/^\/api\/ai\/conversations\/[A-Za-z0-9_-]{22}\/messages$/
const AI_CONVERSATION_RESPONSE_PATH =
	/^\/api\/ai\/conversations\/[A-Za-z0-9_-]{22}\/responses$/
const AI_CONVERSATION_CONTEXT_USAGE_PATH =
	/^\/api\/ai\/conversations\/[A-Za-z0-9_-]{22}\/context-usage$/
const AI_CONVERSATION_COMPACTION_PATH =
	/^\/api\/ai\/conversations\/[A-Za-z0-9_-]{22}\/compactions$/
const AI_CONVERSATION_CONTEXT_EVENTS_PATH =
	/^\/api\/ai\/conversations\/[A-Za-z0-9_-]{22}\/context\/events$/
const AI_CONVERSATION_GENERATION_EVENTS_PATH =
	/^\/api\/ai\/conversations\/generations\/([A-Za-z0-9_-]{22})\/events$/
const AI_CONVERSATION_GENERATION_DIAGNOSTICS_PATH =
	/^\/api\/ai\/conversations\/generations\/[A-Za-z0-9_-]{22}\/stream-diagnostics$/
const AI_CONVERSATION_GENERATION_PATH =
	/^\/api\/ai\/conversations\/generations\/[A-Za-z0-9_-]{22}$/
const AI_CONVERSATION_GENERATION_CANCELLATION_PATH =
	/^\/api\/ai\/conversations\/generations\/[A-Za-z0-9_-]{22}\/cancel$/

const EDGE_VERSION_HEADER = 'X-AIT-Edge-Version'
const EDGE_HOST_HEADER = 'X-AIT-Edge-Host'
const EDGE_TIMESTAMP_HEADER = 'X-AIT-Edge-Timestamp'
const EDGE_RAY_HEADER = 'X-AIT-Edge-Ray'
const EDGE_SIGNATURE_HEADER = 'X-AIT-Edge-Signature'
const EDGE_IP_HEADER = 'X-AIT-Edge-IP'
const EDGE_COUNTRY_HEADER = 'X-AIT-Edge-Country'
const EDGE_ASN_HEADER = 'X-AIT-Edge-ASN'
const EDGE_LATITUDE_HEADER = 'X-AIT-Edge-Latitude'
const EDGE_LONGITUDE_HEADER = 'X-AIT-Edge-Longitude'
const EDGE_RESET_HEADER = 'X-AIT-Cookie-Scope-Reset'
const API_METHODS = Object.freeze([
	'GET',
	'HEAD',
	'POST',
	'PUT',
	'PATCH',
	'DELETE',
	'OPTIONS'
])

export const COOKIE_SCOPE_MARKER_NAME = '__Secure-ait-cookie-scope-v2'

const SPOOFABLE_PROXY_HEADERS = Object.freeze([
	'Forwarded',
	'CF-Connecting-IP',
	'X-Forwarded-For',
	'X-Forwarded-Host',
	'X-Forwarded-Proto',
	'X-Real-IP'
])

const ROOT_COOKIE_NAMES = new Set([
	'access_token',
	'refresh_token',
	'XSRF-TOKEN',
	'rt',
	'register_flow_token',
	'register_flow_csrf',
	'register_challenge',
	'reset_flow_token',
	'forget_token',
	'totp_login_flow',
	'__Host-ait-preauth'
])

const ADMIN_COOKIE_NAMES = new Set([
	'admin_session',
	'ADMIN-XSRF-TOKEN',
	'admin_register_token',
	'admin_register_csrf',
	'admin_register_challenge',
	'admin_login_flow',
	'admin_login_csrf',
	'admin_login_challenge',
	'__Host-ait-admin-preauth'
])

const LEGACY_COOKIE_PATHS = Object.freeze([
	['access_token', '/api'],
	['refresh_token', '/api/auth/session'],
	['XSRF-TOKEN', '/'],
	['rt', '/'],
	['register_flow_token', '/api/auth/register'],
	['register_flow_csrf', '/api/auth/register'],
	['register_challenge', '/api/auth/register'],
	['reset_flow_token', '/api/auth/password-reset'],
	['forget_token', '/api/auth/password-reset/complete'],
	['admin_session', '/api/admin'],
	['ADMIN-XSRF-TOKEN', '/'],
	['admin_register_token', '/api/admin/auth/register'],
	['admin_register_csrf', '/'],
	['admin_register_challenge', '/api/admin/auth/register'],
	['admin_login_flow', '/api/admin/auth/login'],
	['admin_login_csrf', '/'],
	['admin_login_challenge', '/api/admin/auth/login']
])

export default {
	fetch(request, env, context) {
		return handleRequest(request, env, {
			waitUntil: context?.waitUntil?.bind(context)
		})
	}
}

/**
 * 中央 Worker 入口只接受两个固定前端 Host，并在转发前完成迁移、路径隔离和请求签名。
 */
export async function handleRequest(request, env, runtime = {}) {
	const fetchImpl = runtime.fetch || fetch
	const now = runtime.now || Date.now
	const url = new URL(request.url)
	const route = classifyRoute(url)
	if (!route.allowed) return jsonError(route.status, route.code)
	const sseDiagnostic = createSseDiagnostic(route, request, env, runtime)

	if (route.migration) {
		if (request.method !== 'POST') {
			return jsonError(405, 'METHOD_NOT_ALLOWED', { Allow: 'POST' })
		}
		return migrationResponse(request)
	}
	if (!API_METHODS.includes(request.method)) {
		return jsonError(405, 'METHOD_NOT_ALLOWED', {
			Allow: API_METHODS.join(', ')
		})
	}

	if (!hasCookie(request.headers.get('Cookie'), COOKIE_SCOPE_MARKER_NAME, '1')) {
		return jsonError(428, 'EDGE_COOKIE_SCOPE_RESET_REQUIRED')
	}
	if (env.API_UPSTREAM_ORIGIN !== UPSTREAM_ORIGIN) {
		return jsonError(503, 'EDGE_UPSTREAM_CONFIGURATION_INVALID')
	}
	if (!request.headers.get('CF-Ray')) {
		return jsonError(503, 'EDGE_RAY_UNAVAILABLE')
	}

	let upstreamResponse
	try {
		const upstreamRequest = await signedUpstreamRequest(request, env, route, now)
		upstreamResponse = await fetchImpl(upstreamRequest)
	} catch (_) {
		logSseRequest(sseDiagnostic, null)
		return jsonError(502, 'EDGE_UPSTREAM_UNAVAILABLE')
	}

	if (isCrossHostRedirect(upstreamResponse, route.surface)) {
		return jsonError(502, 'EDGE_UPSTREAM_REDIRECT_REJECTED')
	}
	logSseRequest(sseDiagnostic, upstreamResponse)
	const response = guardedResponse(
		upstreamResponse,
		route.surface,
		route.streaming === true)
	return instrumentSseResponse(response, sseDiagnostic, runtime)
}

function classifyRoute(url) {
	if (unsafePath(url.pathname)) {
		return denied()
	}
	if (url.hostname === ROOT_HOST) {
		if (url.pathname === '/api/_edge/cookie-scope') {
			return { allowed: true, migration: true, surface: 'root' }
		}
		const conversationResponse =
			url.pathname === '/api/ai/conversations/responses'
			|| AI_CONVERSATION_RESPONSE_PATH.test(url.pathname)
		if (conversationResponse) {
			return {
				allowed: true,
				migration: false,
				surface: 'root',
				streaming: true,
				routeTemplate: url.pathname === '/api/ai/conversations/responses'
					? '/api/ai/conversations/responses'
					: '/api/ai/conversations/{conversationId}/responses'
			}
		}
		const generationEvents = url.pathname.match(
			AI_CONVERSATION_GENERATION_EVENTS_PATH)
		if (generationEvents) {
			return {
				allowed: true,
				migration: false,
				surface: 'root',
				streaming: true,
				generationPublicId: generationEvents[1],
				routeTemplate:
					'/api/ai/conversations/generations/{generationId}/events'
			}
		}
		if (AI_CONVERSATION_CONTEXT_EVENTS_PATH.test(url.pathname)) {
			return {
				allowed: true,
				migration: false,
				surface: 'root',
				streaming: true,
				routeTemplate:
					'/api/ai/conversations/{conversationId}/context/events'
			}
		}
		if (AI_CONVERSATION_GENERATION_DIAGNOSTICS_PATH.test(url.pathname)) {
			return { allowed: true, migration: false, surface: 'root' }
		}
		const generationControlPath =
			url.pathname === '/api/ai/conversations/generations'
			|| url.pathname === '/api/ai/conversations/generations/by-idempotency'
			|| AI_CONVERSATION_GENERATION_PATH.test(url.pathname)
			|| AI_CONVERSATION_GENERATION_CANCELLATION_PATH.test(url.pathname)
		const ordinaryAiPath =
			url.pathname === '/api/ai-models'
			|| AI_MODEL_DETAIL_PATH.test(url.pathname)
			|| url.pathname === '/api/ai/conversations'
			|| url.pathname === '/api/ai/conversations/responses/cancel'
			|| AI_CONVERSATION_MESSAGES_PATH.test(url.pathname)
			|| AI_CONVERSATION_CONTEXT_USAGE_PATH.test(url.pathname)
			|| AI_CONVERSATION_COMPACTION_PATH.test(url.pathname)
			|| url.pathname === '/api/ai/conversation-attachments/preuploads'
			|| url.pathname === '/api/ai/conversations/stream-diagnostics'
		if (url.pathname === '/api/health'
			|| url.pathname === '/api/_edge/pre-auth'
			|| url.pathname === '/api/_edge/risk-challenge'
			|| url.pathname === '/api/_edge/webrtc/start'
			|| url.pathname === '/api/_edge/webrtc/report'
			|| pathWithin(url.pathname, '/api/auth')
			|| pathWithin(url.pathname, '/api/users')
			|| generationControlPath
			|| ordinaryAiPath) {
			return { allowed: true, migration: false, surface: 'root' }
		}
		return denied()
	}
	if (url.hostname === ADMIN_HOST) {
		if (url.pathname === '/api/admin/_edge/cookie-scope') {
			return { allowed: true, migration: true, surface: 'admin' }
		}
		const mailSse = url.pathname.match(
			/^\/api\/admin\/mail-inspection\/jobs\/([A-Za-z0-9_-]{22})\/events$/)
		if (mailSse) {
			return {
				allowed: true,
				migration: false,
				surface: 'admin',
				streaming: true,
				routeTemplate: '/api/admin/mail-inspection/jobs/{jobId}/events'
			}
		}
		if (pathWithin(url.pathname, '/api/admin')) {
			return {
				allowed: true,
				migration: false,
				surface: 'admin',
				streaming: false
			}
		}
		return denied()
	}
	return { allowed: false, status: 404, code: 'EDGE_ROUTE_NOT_FOUND' }
}

function pathWithin(pathname, prefix) {
	return pathname === prefix || pathname.startsWith(`${prefix}/`)
}

function unsafePath(pathname) {
	return pathname.includes('//')
		|| pathname.includes('\\')
		|| pathname.includes('%')
}

function denied() {
	return { allowed: false, status: 403, code: 'EDGE_ROUTE_FORBIDDEN' }
}

async function signedUpstreamRequest(request, env, route, now) {
	const inboundUrl = new URL(request.url)
	const upstreamUrl = new URL(
		`${inboundUrl.pathname}${inboundUrl.search}`,
		UPSTREAM_ORIGIN
	)
	const edgeNetwork = edgeNetworkContext(request)
	const headers = new Headers(request.headers)
	for (const name of SPOOFABLE_PROXY_HEADERS) headers.delete(name)
	for (const name of [...headers.keys()]) {
		const lowerName = name.toLowerCase()
		if (lowerName.startsWith('x-ait-edge-')
			|| lowerName.startsWith('x-forwarded-')) {
			headers.delete(name)
		}
	}

	const timestamp = String(Math.floor(now() / 1000))
	const ray = request.headers.get('CF-Ray')
	const externalHost = route.surface === 'admin' ? ADMIN_HOST : ROOT_HOST
	const canonical = [
		SIGNATURE_VERSION,
		request.method.toUpperCase(),
		`${upstreamUrl.pathname}${upstreamUrl.search}`,
		externalHost,
		timestamp,
		ray,
		edgeNetwork.clientIp,
		edgeNetwork.country,
		edgeNetwork.asn,
		edgeNetwork.latitude,
		edgeNetwork.longitude
	].join('\n')
	headers.set('Origin', `https://${externalHost}`)
	headers.set(EDGE_VERSION_HEADER, SIGNATURE_VERSION)
	headers.set(EDGE_HOST_HEADER, externalHost)
	headers.set(EDGE_TIMESTAMP_HEADER, timestamp)
	// CF-Ray 可能在子请求链路中变化，因此把入站值复制到受 HMAC 保护的专用头。
	headers.set(EDGE_RAY_HEADER, ray)
	headers.set(EDGE_IP_HEADER, edgeNetwork.clientIp)
	headers.set(EDGE_COUNTRY_HEADER, edgeNetwork.country)
	headers.set(EDGE_ASN_HEADER, edgeNetwork.asn)
	headers.set(EDGE_LATITUDE_HEADER, edgeNetwork.latitude)
	headers.set(EDGE_LONGITUDE_HEADER, edgeNetwork.longitude)
	headers.set(
		EDGE_SIGNATURE_HEADER,
		await hmacSha256Base64Url(env.EDGE_PROXY_HMAC_SECRET_BASE64, canonical)
	)

	const init = {
		method: request.method,
		headers,
		cache: 'no-store',
		redirect: 'manual',
		// 浏览器关闭页面或主动重连时立即取消 Origin 读取，避免遗留无消费者的 SSE。
		signal: request.signal
	}
	if (request.method !== 'GET' && request.method !== 'HEAD') {
		init.body = request.body
		// Node 的 Web Fetch 测试运行时要求显式声明半双工；Workers 运行时会忽略兼容字段。
		init.duplex = 'half'
	}
	return new Request(upstreamUrl, init)
}

function edgeNetworkContext(request) {
	const clientIp = String(request.headers.get('CF-Connecting-IP') || '').trim().toLowerCase()
	if (!clientIp || !/^[0-9a-f:.]+$/.test(clientIp)) {
		throw new Error('Missing Cloudflare client IP')
	}
	const cf = request.cf || {}
	const country = normalizeCountry(cf.country)
	const asn = normalizeAsn(cf.asn)
	const latitude = normalizeCoordinate(cf.latitude, -90, 90)
	const longitude = normalizeCoordinate(cf.longitude, -180, 180)
	if ((latitude === '') !== (longitude === '')) {
		return { clientIp, country, asn, latitude: '', longitude: '' }
	}
	return { clientIp, country, asn, latitude, longitude }
}

function normalizeCountry(value) {
	const normalized = typeof value === 'string' ? value.trim().toUpperCase() : ''
	return /^[A-Z]{2}$/.test(normalized) ? normalized : ''
}

function normalizeAsn(value) {
	const parsed = Number(value)
	return Number.isInteger(parsed) && parsed >= 0 && parsed <= 4294967295
		? String(parsed)
		: ''
}

function normalizeCoordinate(value, minimum, maximum) {
	const parsed = Number(value)
	return Number.isFinite(parsed) && parsed >= minimum && parsed <= maximum
		? String(parsed)
		: ''
}

async function hmacSha256Base64Url(secretBase64, canonical) {
	const secret = canonicalBase64Secret(secretBase64)
	const key = await crypto.subtle.importKey(
		'raw',
		secret,
		{ name: 'HMAC', hash: 'SHA-256' },
		false,
		['sign']
	)
	const signature = new Uint8Array(await crypto.subtle.sign(
		'HMAC',
		key,
		new TextEncoder().encode(canonical)
	))
	return bytesToBase64(signature)
		.replaceAll('+', '-')
		.replaceAll('/', '_')
		.replace(/=+$/, '')
}

function canonicalBase64Secret(value) {
	if (typeof value !== 'string' || !value) throw new Error('Missing edge secret')
	let decoded
	try {
		decoded = base64ToBytes(value)
	} catch (_) {
		throw new Error('Invalid edge secret')
	}
	if (decoded.length < 32 || bytesToBase64(decoded) !== value) {
		throw new Error('Invalid edge secret')
	}
	return decoded
}

function base64ToBytes(value) {
	const binary = atob(value)
	return Uint8Array.from(binary, character => character.charCodeAt(0))
}

function bytesToBase64(value) {
	let binary = ''
	for (const byte of value) binary += String.fromCharCode(byte)
	return btoa(binary)
}

function migrationResponse(request) {
	const headers = noStoreHeaders()
	if (hasCookie(request.headers.get('Cookie'), COOKIE_SCOPE_MARKER_NAME, '1')) {
		headers.set(EDGE_RESET_HEADER, '0')
		return new Response(null, { status: 204, headers })
	}

	for (const [name, path] of LEGACY_COOKIE_PATHS) {
		for (const domain of [ROOT_HOST, `.${ROOT_HOST}`]) {
			headers.append(
				'Set-Cookie',
				expiredCookie(name, path, domain)
			)
		}
	}
	headers.append(
		'Set-Cookie',
		`${COOKIE_SCOPE_MARKER_NAME}=1; Domain=${ROOT_HOST}; Path=/; `
			+ 'Max-Age=31536000; Secure; HttpOnly; SameSite=Strict'
	)
	headers.set(EDGE_RESET_HEADER, '1')
	return new Response(null, { status: 204, headers })
}

function expiredCookie(name, path, domain) {
	return `${name}=; Domain=${domain}; Path=${path}; Max-Age=0; `
		+ 'Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Strict'
}

function hasCookie(header, expectedName, expectedValue) {
	if (!header) return false
	return header.split(';').some(item => {
		const separator = item.indexOf('=')
		if (separator < 0) return false
		const name = item.slice(0, separator).trim()
		const value = item.slice(separator + 1).trim()
		return name === expectedName && value === expectedValue
	})
}

function guardedResponse(response, surface, streaming = false) {
	const setCookies = readSetCookies(response.headers)
	if (setCookies === null) {
		return jsonError(502, 'EDGE_SET_COOKIE_API_UNAVAILABLE')
	}
	const allowedNames = surface === 'admin' ? ADMIN_COOKIE_NAMES : ROOT_COOKIE_NAMES
	for (const cookie of setCookies) {
		const name = cookieName(cookie)
		if (!allowedNames.has(name) || /(?:^|;)\s*domain\s*=/i.test(cookie)) {
			return jsonError(502, 'EDGE_COOKIE_POLICY_VIOLATION')
		}
	}

	const headers = new Headers(response.headers)
	headers.delete('Set-Cookie')
	for (const cookie of setCookies) headers.append('Set-Cookie', cookie)
	applyNoStore(headers)
	if (streaming) {
		headers.set('Cache-Control', 'no-store, private, no-transform')
		headers.set('X-Accel-Buffering', 'no')
	}
	return new Response(response.body, {
		status: response.status,
		statusText: response.statusText,
		headers
	})
}

function createSseDiagnostic(route, request, env, runtime) {
	if (!route.streaming) return null
	const sampleRate = Number(env.SSE_ROUTE_LOG_SAMPLE_RATE)
	const random = runtime.random || Math.random
	if (!Number.isFinite(sampleRate)
		|| sampleRate <= 0
		|| random() >= Math.min(1, sampleRate)) {
		return null
	}
	return {
		logger: runtime.log || console,
		route: route.routeTemplate,
		method: request.method,
		cfRay: headerValue(request.headers, 'CF-Ray'),
		generationPublicId: route.generationPublicId || ''
	}
}

function logSseRequest(diagnostic, response) {
	if (!diagnostic) return
	diagnostic.logger.info(JSON.stringify({
		event: 'sse_edge_request',
		route: diagnostic.route,
		method: diagnostic.method,
		status: response?.status || 502,
		cfRay: diagnostic.cfRay
	}))
}

/**
 * 对采样到的 SSE 使用 TransformStream 逐块透传：不读取完整响应、不重组正文，
 * 只从 event 行识别 delta 的首个边缘读取时刻。这里的 forward 是写入 Worker
 * 下游 ReadableStream 的时刻，不把它误报为浏览器已经收到。
 */
function instrumentSseResponse(response, diagnostic, runtime) {
	if (!diagnostic || !response.body) return response
	const now = runtime.now || Date.now
	const decoder = new TextDecoder()
	const state = {
		startedAt: now(),
		firstReadAt: null,
		firstDeltaReadAt: null,
		firstForwardAt: null,
		lastForwardAt: null,
		disconnectAt: null,
		totalChunks: 0,
		totalBytes: 0,
		line: '',
		activeEventType: 'message',
		reported: false
	}
	const traceId = headerValue(response.headers, 'X-Trace-Id')
	const usagePublicId = headerValue(response.headers, 'X-AI-Usage-Id')
	const generationPublicId = diagnostic.generationPublicId
		|| headerValue(response.headers, 'X-AI-Generation-Id')
	const stream = new TransformStream({
		transform(chunk, controller) {
			const observedAt = now()
			if (state.firstReadAt === null) state.firstReadAt = observedAt
			const byteLength = chunk instanceof Uint8Array ? chunk.byteLength : 0
			state.totalChunks += 1
			state.totalBytes += byteLength
			if (byteLength > 0) {
				observeSseMetadata(
					state,
					decoder.decode(chunk, { stream: true }),
					observedAt)
			}
			controller.enqueue(chunk)
			const forwardedAt = now()
			if (state.firstForwardAt === null) state.firstForwardAt = forwardedAt
			state.lastForwardAt = forwardedAt
		},
		flush() {
			observeSseMetadata(state, decoder.decode(), now())
		}
	})
	const report = outcome => {
		if (state.reported) return
		state.reported = true
		if (outcome !== 'COMPLETED') state.disconnectAt = now()
		diagnostic.logger.info(JSON.stringify({
			event: 'sse_edge_transport_summary',
			occurredAt: new Date(now()).toISOString(),
			elapsedMs: Math.max(0, now() - state.startedAt),
			route: diagnostic.route,
			method: diagnostic.method,
			status: response.status,
			edgeRequestId: diagnostic.cfRay,
			traceId,
			usagePublicId,
			generationPublicId,
			firstReadAt: state.firstReadAt,
			firstReadElapsedMs: elapsedFrom(state, state.firstReadAt),
			firstDeltaReadAt: state.firstDeltaReadAt,
			firstDeltaReadElapsedMs: elapsedFrom(state, state.firstDeltaReadAt),
			firstForwardAt: state.firstForwardAt,
			firstForwardElapsedMs: elapsedFrom(state, state.firstForwardAt),
			lastForwardAt: state.lastForwardAt,
			lastForwardElapsedMs: elapsedFrom(state, state.lastForwardAt),
			disconnectAt: state.disconnectAt,
			totalChunks: state.totalChunks,
			totalBytes: state.totalBytes,
			outcome
		}))
	}
	const completion = response.body.pipeTo(stream.writable)
		.then(() => report('COMPLETED'))
		.catch(() => report('DISCONNECTED'))
	if (typeof runtime.waitUntil === 'function') {
		runtime.waitUntil(completion.catch(() => undefined))
	}
	return new Response(stream.readable, {
		status: response.status,
		statusText: response.statusText,
		headers: response.headers
	})
}

function elapsedFrom(state, occurredAt) {
	return occurredAt === null ? -1 : Math.max(0, occurredAt - state.startedAt)
}

function observeSseMetadata(state, text, observedAt) {
	for (const character of text) {
		if (character === '\n') {
			acceptSseMetadataLine(state, state.line, observedAt)
			state.line = ''
		} else if (character !== '\r' && state.line.length < 128) {
			state.line += character
		}
	}
}

function acceptSseMetadataLine(state, line, observedAt) {
	if (!line) {
		if (state.activeEventType === 'delta' && state.firstDeltaReadAt === null) {
			state.firstDeltaReadAt = observedAt
		}
		state.activeEventType = 'message'
		return
	}
	if (!line.startsWith('event:')) return
	const eventType = line.slice('event:'.length).trim()
	state.activeEventType = /^[A-Za-z_-]{1,32}$/.test(eventType)
		? eventType : 'message'
}

function headerValue(headers, name) {
	return String(headers.get(name) || '').slice(0, 128)
}

function readSetCookies(headers) {
	if (typeof headers.getSetCookie === 'function') {
		return headers.getSetCookie()
	}
	// 不允许退化为 get("Set-Cookie")，因为多个响应头可能被逗号合并并破坏 Cookie 边界。
	return null
}

function cookieName(value) {
	const separator = value.indexOf('=')
	return separator < 1 ? '' : value.slice(0, separator).trim()
}

function isCrossHostRedirect(response, surface) {
	if (response.status < 300 || response.status >= 400) return false
	const location = response.headers.get('Location')
	if (!location) return false
	const externalHost = surface === 'admin' ? ADMIN_HOST : ROOT_HOST
	const externalOrigin = `https://${externalHost}`
	try {
		// 相对地址会继续停留在同源 Worker；上游 API 绝对地址也必须拒绝，避免浏览器退回跨域直连。
		return new URL(location, externalOrigin).origin !== externalOrigin
	} catch (_) {
		return true
	}
}

function jsonError(status, code, additionalHeaders = {}) {
	const headers = noStoreHeaders()
	headers.set('Content-Type', 'application/json; charset=utf-8')
	for (const [name, value] of Object.entries(additionalHeaders)) {
		headers.set(name, value)
	}
	return new Response(JSON.stringify({
		code,
		message: 'The edge request was rejected.'
	}), { status, headers })
}

function noStoreHeaders() {
	const headers = new Headers()
	applyNoStore(headers)
	return headers
}

function applyNoStore(headers) {
	headers.set('Cache-Control', 'no-store')
	headers.set('CDN-Cache-Control', 'no-store')
	headers.set('Pragma', 'no-cache')
}

const ROOT_HOST = 'niko000o.site'
const ADMIN_HOST = 'admin.niko000o.site'
const UPSTREAM_ORIGIN = 'https://api.niko000o.site'
const API_CHAT_COMPLETIONS_PATH = '/v1/chat/completions'
const VOICE_WEBSOCKET_PATH = '/ws/voice'
const VOICE_WEBSOCKET_PROTOCOL = 'ait-voice-v2'
const VOICE_TICKET_PROTOCOL = /^ait-ticket\.[A-Za-z0-9_-]{43}$/
const VOICE_WEBSOCKET_REJECTION_STATUSES = new Set([400, 401, 403, 428, 503])
const ANDROID_CLEARANCE_PATH = '/__edge/android-clearance'
const ANDROID_CLEARANCE_STATUS_PATH = '/__edge/android-clearance/status'
const TURNSTILE_PAGE_PATH = '/api/auth/turnstile/page'
const CLOUDFLARE_CLEARANCE_COOKIE = 'cf_clearance'
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
const CLIENT_PLATFORM_HEADER = 'X-Client-Platform'
const ANDROID_TRANSPORT = 'ANDROID_NATIVE'
const ANDROID_WEBVIEW_TRANSPORT = 'ANDROID_WEBVIEW_DOCUMENT'
const H5_TRANSPORT = 'H5_BROWSER'
const API_KEY_SDK_TRANSPORT = 'API_KEY_SDK'
const TURNSTILE_CHALLENGE_PATTERN = /^[A-Za-z0-9_-]{38}$/
const PREAUTH_TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/
const DEVICE_INSTALLATION_ID_PATTERN =
	/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const TURNSTILE_ACTIONS = new Set([
	'register',
	'login',
	'password_reset'
])
const WEBVIEW_FETCH_SITES = new Set(['', 'none', 'same-origin'])
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
	if (!route.allowed) {
		return isApiKeySdkSurface(url)
			? openAiError(route.status, 'invalid_request',
				'The requested API route is not available.')
			: jsonError(route.status, route.code)
	}
	if (route.androidClearance) {
		if (request.method !== 'GET') {
			return jsonError(405, 'METHOD_NOT_ALLOWED', { Allow: 'GET' })
		}
		return androidClearanceResponse(request, route.androidClearance)
	}
	const sseDiagnostic = createSseDiagnostic(route, request, env, runtime)
	const voiceDiagnostic = createVoiceWebSocketDiagnostic(
		route, request, runtime, now)

	if (route.migration) {
		if (request.method !== 'POST') {
			return jsonError(405, 'METHOD_NOT_ALLOWED', { Allow: 'POST' })
		}
		return migrationResponse(request)
	}
	if (route.webSocket) {
		if (request.method !== 'GET') {
			finishVoiceWebSocketDiagnostic(voiceDiagnostic, {
				edgeOutcome: 'EDGE_CLIENT_TRANSPORT_REJECTED'
			})
			return jsonError(405, 'METHOD_NOT_ALLOWED', { Allow: 'GET' })
		}
		if (headerValue(request.headers, 'Upgrade').trim().toLowerCase()
			!== 'websocket') {
			finishVoiceWebSocketDiagnostic(voiceDiagnostic, {
				edgeOutcome: 'EDGE_CLIENT_TRANSPORT_REJECTED'
			})
			return jsonError(426, 'WEBSOCKET_UPGRADE_REQUIRED', {
				Upgrade: 'websocket'
			})
		}
		if (!validVoiceWebSocketProtocol(
			request.headers.get('Sec-WebSocket-Protocol'))) {
			finishVoiceWebSocketDiagnostic(voiceDiagnostic, {
				edgeOutcome: 'EDGE_WEBSOCKET_PROTOCOL_INVALID'
			})
			return jsonError(400, 'EDGE_WEBSOCKET_PROTOCOL_INVALID')
		}
	} else if (route.apiKeySdk && request.method !== 'POST') {
		return routeError(route, 405, 'method_not_allowed', {
			Allow: 'POST'
		})
	} else if (route.riskChallenge && request.method !== 'GET') {
		return jsonError(405, 'METHOD_NOT_ALLOWED', { Allow: 'GET' })
	} else if (!API_METHODS.includes(request.method)) {
		return jsonError(405, 'METHOD_NOT_ALLOWED', {
			Allow: API_METHODS.join(', ')
		})
	}

	const transport = classifyClientTransport(request, route, url)
	if (voiceDiagnostic) {
		voiceDiagnostic.transport = transport.allowed
			? transport.kind : 'INVALID'
	}
	if (!transport.allowed) {
		finishVoiceWebSocketDiagnostic(voiceDiagnostic, {
			edgeOutcome: 'EDGE_CLIENT_TRANSPORT_REJECTED'
		})
		return routeError(route, transport.status, transport.code)
	}
	if (transport.kind === H5_TRANSPORT
		&& !route.riskChallenge
		&& !hasCookie(request.headers.get('Cookie'), COOKIE_SCOPE_MARKER_NAME, '1')) {
		finishVoiceWebSocketDiagnostic(voiceDiagnostic, {
			edgeOutcome: 'EDGE_WEBSOCKET_AUTHORIZATION_FAILED'
		})
		return jsonError(428, 'EDGE_COOKIE_SCOPE_RESET_REQUIRED')
	}
	if (env.API_UPSTREAM_ORIGIN !== UPSTREAM_ORIGIN) {
		finishVoiceWebSocketDiagnostic(voiceDiagnostic, {
			edgeOutcome: 'EDGE_UPSTREAM_CONFIGURATION_INVALID'
		})
		return routeError(route, 503, 'EDGE_UPSTREAM_CONFIGURATION_INVALID')
	}
	if (!request.headers.get('CF-Ray')) {
		finishVoiceWebSocketDiagnostic(voiceDiagnostic, {
			edgeOutcome: 'EDGE_RAY_UNAVAILABLE'
		})
		return routeError(route, 503, 'EDGE_RAY_UNAVAILABLE')
	}

	let upstreamResponse
	try {
		const upstreamRequest = await signedUpstreamRequest(
			request,
			env,
			route,
			transport,
			now,
			voiceDiagnostic)
		upstreamResponse = await fetchImpl(upstreamRequest)
	} catch (error) {
		logSseRequest(sseDiagnostic, null)
		finishVoiceWebSocketDiagnostic(voiceDiagnostic, {
			edgeOutcome: 'EDGE_UPSTREAM_UNAVAILABLE',
			exceptionType: safeVoiceExceptionType(error)
		})
		return routeError(route, route.apiKeySdk ? 503 : 502,
			'EDGE_UPSTREAM_UNAVAILABLE')
	}

	if ((route.apiKeySdk && upstreamResponse.status >= 300
			&& upstreamResponse.status < 400)
		|| isCrossHostRedirect(upstreamResponse, route.surface)) {
		finishVoiceWebSocketDiagnostic(voiceDiagnostic, {
			...voiceUpstreamDiagnostic(upstreamResponse),
			edgeOutcome: 'EDGE_UPSTREAM_REDIRECT_REJECTED'
		})
		return routeError(route, 502, 'EDGE_UPSTREAM_REDIRECT_REJECTED')
	}
	if (route.webSocket) {
		return guardedWebSocketResponse(
			upstreamResponse, transport, voiceDiagnostic)
	}
	logSseRequest(sseDiagnostic, upstreamResponse)
	const response = guardedResponse(
		upstreamResponse,
		route.surface,
		route.streaming === true,
		transport)
	return instrumentSseResponse(response, sseDiagnostic, runtime)
}

function classifyRoute(url) {
	if (unsafePath(url.pathname)) {
		return denied()
	}
	if (url.hostname === ROOT_HOST) {
		if (url.pathname === API_CHAT_COMPLETIONS_PATH) {
			return {
				allowed: true,
				migration: false,
				surface: 'root',
				streaming: true,
				apiKeySdk: true,
				routeTemplate: API_CHAT_COMPLETIONS_PATH
			}
		}
		if (url.pathname === ANDROID_CLEARANCE_PATH) {
			return {
				allowed: true,
				androidClearance: 'page',
				surface: 'root'
			}
		}
		if (url.pathname === ANDROID_CLEARANCE_STATUS_PATH) {
			return {
				allowed: true,
				androidClearance: 'status',
				surface: 'root'
			}
		}
		if (url.pathname === '/api/_edge/cookie-scope') {
			return { allowed: true, migration: true, surface: 'root' }
		}
		if (url.pathname === '/api/_edge/risk-challenge') {
			return {
				allowed: true,
				migration: false,
				riskChallenge: true,
				surface: 'root'
			}
		}
		if (url.pathname === VOICE_WEBSOCKET_PATH) {
			return {
				allowed: true,
				migration: false,
				surface: 'root',
				webSocket: true,
				routeTemplate: VOICE_WEBSOCKET_PATH
			}
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
		if (url.pathname === '/api/admin/_edge/risk-challenge') {
			return {
				allowed: true,
				migration: false,
				riskChallenge: true,
				surface: 'admin'
			}
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

function classifyClientTransport(request, route, url) {
	if (route.apiKeySdk) {
		return classifyApiKeySdkTransport(request)
	}
	const platform = headerValue(request.headers, CLIENT_PLATFORM_HEADER)
		.trim()
		.toUpperCase()
	if (platform !== 'ANDROID') {
		// 缺少或未知平台不能降级成原生运输，只能继续接受 H5 的 Cookie Scope 约束。
		return { allowed: true, kind: H5_TRANSPORT }
	}
	if (validAndroidVoiceWebSocketTransport(request, route, url)) {
		// App-Plus 的 Socket 实现可能附带浏览器 Origin/Fetch Metadata；仅精确语音路由可忽略这些非授权元数据，且回源前仍会统一删除。
		return { allowed: true, kind: ANDROID_TRANSPORT }
	}
	if (route.surface === 'root'
		&& url.hostname === ROOT_HOST
		&& url.pathname === TURNSTILE_PAGE_PATH) {
		if (validAndroidWebViewDocumentRequest(request, url)) {
			return { allowed: true, kind: ANDROID_WEBVIEW_TRANSPORT }
		}
		return invalidAndroidTransport()
	}
	if (headerValue(request.headers, 'Origin').trim()
		|| hasFetchMetadata(request.headers)) {
		// 浏览器可以伪造普通平台头，但不能去除浏览器自动附加的 Origin/Fetch Metadata。
		return invalidAndroidTransport()
	}
	return { allowed: true, kind: ANDROID_TRANSPORT }
}

function classifyApiKeySdkTransport(request) {
	const authorization = headerValue(request.headers, 'Authorization')
	if (!/^Bearer [^\s,]+$/.test(authorization)) {
		return {
			allowed: false,
			status: 401,
			code: 'invalid_api_key'
		}
	}
	if (request.headers.has('Cookie')
		|| request.headers.has('Origin')
		|| request.headers.has('Referer')
		|| hasFetchMetadata(request.headers)) {
		return {
			allowed: false,
			status: 403,
			code: 'browser_request_not_allowed'
		}
	}
	if (!validApiKeySdkAccept(request.headers.get('Accept'))) {
		return {
			allowed: false,
			status: 400,
			code: 'invalid_accept'
		}
	}
	return { allowed: true, kind: API_KEY_SDK_TRANSPORT }
}

function validApiKeySdkAccept(value) {
	if (value === null || value.trim() === '') return true
	const allowed = new Set(['text/event-stream', 'application/json', '*/*'])
	return value.split(',').every(part => {
		const mediaType = part.split(';', 1)[0].trim().toLowerCase()
		return allowed.has(mediaType)
	})
}

function validAndroidVoiceWebSocketTransport(request, route, url) {
	return route.webSocket === true
		&& route.surface === 'root'
		&& url.hostname === ROOT_HOST
		&& url.pathname === VOICE_WEBSOCKET_PATH
		&& request.method === 'GET'
		&& headerValue(request.headers, 'Upgrade').trim().toLowerCase()
			=== 'websocket'
		&& validVoiceWebSocketProtocol(
			request.headers.get('Sec-WebSocket-Protocol'))
}

function validAndroidWebViewDocumentRequest(request, url) {
	if (request.method !== 'GET') return false
	const origin = headerValue(request.headers, 'Origin').trim()
	if (origin && origin !== `https://${ROOT_HOST}`) return false
	if (!validAndroidWebViewFetchMetadata(request.headers)) return false
	if (!PREAUTH_TOKEN_PATTERN.test(
		headerValue(request.headers, 'X-AIT-PreAuth').trim())) return false
	if (!DEVICE_INSTALLATION_ID_PATTERN.test(
		headerValue(request.headers, 'X-Device-Installation-Id').trim())) return false
	return validTurnstilePageQuery(url)
}

function validAndroidWebViewFetchMetadata(headers) {
	if (!hasFetchMetadata(headers)) return true
	const mode = headerValue(headers, 'Sec-Fetch-Mode').trim().toLowerCase()
	const destination = headerValue(headers, 'Sec-Fetch-Dest').trim().toLowerCase()
	const site = headerValue(headers, 'Sec-Fetch-Site').trim().toLowerCase()
	const user = headerValue(headers, 'Sec-Fetch-User').trim().toLowerCase()
	return mode === 'navigate'
		&& destination === 'document'
		&& WEBVIEW_FETCH_SITES.has(site)
		&& (user === '' || user === '?1')
}

function validTurnstilePageQuery(url) {
	const values = new Map()
	for (const [name, value] of url.searchParams.entries()) {
		if ((name !== 'challenge' && name !== 'action') || values.has(name)) {
			return false
		}
		values.set(name, value)
	}
	return values.size === 2
		&& TURNSTILE_CHALLENGE_PATTERN.test(values.get('challenge') || '')
		&& TURNSTILE_ACTIONS.has(values.get('action') || '')
}

function invalidAndroidTransport() {
	return {
		allowed: false,
		status: 403,
		code: 'EDGE_CLIENT_TRANSPORT_INVALID'
	}
}

function isAndroidUpstreamTransport(transport) {
	return transport?.kind === ANDROID_TRANSPORT
		|| transport?.kind === ANDROID_WEBVIEW_TRANSPORT
}

function hasFetchMetadata(headers) {
	for (const name of headers.keys()) {
		if (name.toLowerCase().startsWith('sec-fetch-')) return true
	}
	return false
}

async function signedUpstreamRequest(
	request,
	env,
	route,
	transport,
	now,
	voiceDiagnostic = null) {
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
	if (isAndroidUpstreamTransport(transport)) {
		// Android原生与受控WebView请求只保留显式Token、PreAuth和设备头，浏览器运输信息不得进入源站。
		headers.delete('Cookie')
		headers.delete('Origin')
		headers.delete('Referer')
		for (const name of [...headers.keys()]) {
			if (name.toLowerCase().startsWith('sec-fetch-')) headers.delete(name)
		}
		headers.set(CLIENT_PLATFORM_HEADER, 'ANDROID')
	} else if (transport.kind === API_KEY_SDK_TRANSPORT) {
		// 长期 API Key 只允许服务端 SDK 运输；保留 Authorization，移除所有浏览器、Cookie 与平台伪装元数据。
		headers.delete('Cookie')
		headers.delete('Origin')
		headers.delete('Referer')
		headers.delete(CLIENT_PLATFORM_HEADER)
		// 客户端可以使用 OpenAI SDK 常见 Accept；回源统一声明 SSE，避免 Spring 内容协商把合法流式请求判为 406。
		headers.set('Accept', 'text/event-stream')
		for (const name of [...headers.keys()]) {
			if (name.toLowerCase().startsWith('sec-fetch-')) headers.delete(name)
		}
	} else {
		if (route.riskChallenge) {
			// 风险挑战只把当前 Host 所属凭据送往共享源站，防止手工构造请求跨越普通端与管理端 Cookie 边界。
			const forbiddenNames = route.surface === 'admin'
				? ROOT_COOKIE_NAMES
				: ADMIN_COOKIE_NAMES
			const scopedCookies = withoutCookieNames(
				headers.get('Cookie'),
				forbiddenNames)
			if (scopedCookies) headers.set('Cookie', scopedCookies)
			else headers.delete('Cookie')
		}
		headers.set(CLIENT_PLATFORM_HEADER, 'H5')
	}
	if (route.webSocket) {
		// Ticket 通过子协议进入握手，Spring 在返回 101 前原子消费；主域名 Cookie 与 Authorization 仍不回源。
		headers.delete('Cookie')
		headers.delete('Authorization')
	}
	if (voiceDiagnostic) {
		voiceDiagnostic.upstreamCookieForwarded = headers.has('Cookie')
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
	if (transport.kind === H5_TRANSPORT) {
		headers.set('Origin', `https://${externalHost}`)
	}
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

function validVoiceWebSocketProtocol(value) {
	if (typeof value !== 'string' || !value || value.length > 128
		|| value.includes('\r') || value.includes('\n')) return false
	const tokens = value.split(',').map(token => token.trim())
	if (tokens.length !== 2 || tokens.some(token => !token)
		|| new Set(tokens).size !== 2) return false
	return tokens.includes(VOICE_WEBSOCKET_PROTOCOL)
		&& tokens.filter(token => VOICE_TICKET_PROTOCOL.test(token)).length === 1
}

function withoutCookieNames(header, forbiddenNames) {
	return String(header || '')
		.split(';')
		.map(value => value.trim())
		.filter(value => {
			const separator = value.indexOf('=')
			if (separator <= 0) return false
			return !forbiddenNames.has(value.slice(0, separator).trim())
		})
		.join('; ')
}

function androidClearanceResponse(request, responseKind) {
	const clearance = cookieValue(
		request.headers.get('Cookie'),
		CLOUDFLARE_CLEARANCE_COOKIE)
	if (!clearance) return jsonError(428, 'EDGE_CLEARANCE_REQUIRED')
	if (responseKind === 'status') {
		return new Response(null, { status: 204, headers: noStoreHeaders() })
	}

	const nonce = randomNonce()
	const headers = noStoreHeaders()
	headers.set('Content-Type', 'text/html; charset=UTF-8')
	headers.set('Content-Security-Policy',
		`default-src 'none'; script-src 'nonce-${nonce}'; `
		+ "base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
	headers.set('Referrer-Policy', 'no-referrer')
	headers.set('X-Content-Type-Options', 'nosniff')
	headers.set('X-Frame-Options', 'DENY')
	return new Response(androidClearancePage(nonce), { status: 200, headers })
}

function cookieValue(header, expectedName) {
	if (!header) return ''
	for (const item of header.split(';')) {
		const separator = item.indexOf('=')
		if (separator < 0) continue
		const name = item.slice(0, separator).trim()
		const value = item.slice(separator + 1).trim()
		if (name !== expectedName) continue
		if (!value || value.length > 4096 || /[\u0000-\u0020\u007f;]/.test(value)) {
			return ''
		}
		return value
	}
	return ''
}

function randomNonce() {
	const bytes = new Uint8Array(18)
	crypto.getRandomValues(bytes)
	return bytesToBase64(bytes)
		.replaceAll('+', '-')
		.replaceAll('/', '_')
		.replace(/=+$/, '')
}

function androidClearancePage(nonce) {
	return '<!doctype html><html lang="zh-CN"><head>'
		+ '<meta charset="utf-8">'
		+ '<meta name="viewport" content="width=device-width,initial-scale=1">'
		+ '<title>安全验证已完成</title>'
		+ '</head><body>'
		+ '<p>安全验证已完成，正在返回应用。</p>'
		+ `<script nonce="${nonce}">location.replace('ait-edge://verified')</script>`
		+ '</body></html>'
}

function guardedResponse(response, surface, streaming = false, transport = null) {
	const setCookies = readSetCookies(response.headers)
	if (setCookies === null) {
		return transport?.kind === API_KEY_SDK_TRANSPORT
			? openAiError(502, 'upstream_protocol_error',
				'The upstream response could not be validated.')
			: jsonError(502, 'EDGE_SET_COOKIE_API_UNAVAILABLE')
	}
	if (transport?.kind === API_KEY_SDK_TRANSPORT && setCookies.length > 0) {
		return openAiError(502, 'upstream_protocol_error',
			'The upstream response violated the API cookie policy.')
	}
	if (transport?.kind === API_KEY_SDK_TRANSPORT) {
		const contentType = headerValue(response.headers, 'Content-Type')
			.split(';', 1)[0].trim().toLowerCase()
		const expected = response.status >= 200 && response.status < 300
			? 'text/event-stream' : 'application/json'
		if (contentType !== expected) {
			return openAiError(502, 'upstream_protocol_error',
				'The upstream response used an invalid content type.')
		}
	}
	if (isAndroidUpstreamTransport(transport) && setCookies.length > 0) {
		// Android 使用显式 Token 协议；拒绝源站 Cookie，避免与 H5 会话模型发生隐式混用。
		return jsonError(502, 'EDGE_ANDROID_COOKIE_POLICY_VIOLATION')
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

function guardedWebSocketResponse(response, transport, diagnostic) {
	const baseDiagnostic = {
		upstreamStatus: response.status,
		responseWebSocketPresent: Boolean(response.webSocket),
		protocolMatched: response.headers.get('Sec-WebSocket-Protocol')
			=== VOICE_WEBSOCKET_PROTOCOL
	}
	if (response.status !== 101) {
		const outcome = VOICE_WEBSOCKET_REJECTION_STATUSES.has(response.status)
			? 'EDGE_WEBSOCKET_AUTHORIZATION_FAILED'
			: 'EDGE_WEBSOCKET_UPGRADE_FAILED'
		finishVoiceWebSocketDiagnostic(diagnostic, {
			...baseDiagnostic,
			...voiceSetCookieDiagnostic(response.headers),
			edgeOutcome: outcome
		})
		if (VOICE_WEBSOCKET_REJECTION_STATUSES.has(response.status)) {
			return jsonError(response.status, 'EDGE_WEBSOCKET_AUTHORIZATION_FAILED')
		}
		return jsonError(502, 'EDGE_WEBSOCKET_UPGRADE_FAILED')
	}
	if (!response.webSocket
		|| response.headers.get('Sec-WebSocket-Protocol') !== VOICE_WEBSOCKET_PROTOCOL) {
		finishVoiceWebSocketDiagnostic(diagnostic, {
			...baseDiagnostic,
			...voiceSetCookieDiagnostic(response.headers),
			edgeOutcome: 'EDGE_WEBSOCKET_UPGRADE_FAILED'
		})
		return jsonError(502, 'EDGE_WEBSOCKET_UPGRADE_FAILED')
	}
	const setCookies = readSetCookies(response.headers)
	if (setCookies === null || setCookies.length > 0) {
		if (isAndroidUpstreamTransport(transport) && setCookies?.length > 0) {
			finishVoiceWebSocketDiagnostic(diagnostic, {
				...baseDiagnostic,
				setCookieReadable: true,
				setCookieCount: setCookies.length,
				edgeOutcome: 'EDGE_ANDROID_COOKIE_POLICY_VIOLATION'
			})
			return jsonError(502, 'EDGE_ANDROID_COOKIE_POLICY_VIOLATION')
		}
		finishVoiceWebSocketDiagnostic(diagnostic, {
			...baseDiagnostic,
			setCookieReadable: setCookies !== null,
			setCookieCount: setCookies === null ? -1 : setCookies.length,
			edgeOutcome: 'EDGE_WEBSOCKET_COOKIE_POLICY_VIOLATION'
		})
		return jsonError(502, 'EDGE_WEBSOCKET_COOKIE_POLICY_VIOLATION')
	}
	finishVoiceWebSocketDiagnostic(diagnostic, {
		...baseDiagnostic,
		setCookieReadable: true,
		setCookieCount: 0,
		edgeOutcome: 'EDGE_WEBSOCKET_UPGRADED'
	})
	// 透明代理必须保留运行时挂载的 WebSocket 对象；重建普通 Response 会丢失升级后的双向通道。
	return response
}

function createVoiceWebSocketDiagnostic(route, request, runtime, now) {
	if (!route.webSocket) return null
	const platform = headerValue(request.headers, CLIENT_PLATFORM_HEADER)
		.trim()
		.toUpperCase()
	return {
		logger: runtime.log || console,
		now,
		startedAt: safeVoiceNow(now),
		cfRay: safeVoiceIdentifier(request.headers.get('CF-Ray')),
		transport: platform === 'ANDROID'
			? ANDROID_TRANSPORT
			: platform === 'H5' || platform === ''
				? H5_TRANSPORT
				: 'INVALID',
		clientCookiePresent: Boolean(request.headers.get('Cookie')),
		upstreamCookieForwarded: false,
		logged: false
	}
}

function finishVoiceWebSocketDiagnostic(diagnostic, values) {
	if (!diagnostic || diagnostic.logged) return
	diagnostic.logged = true
	const entry = {
		event: 'voice_ws_edge_summary',
		cfRay: diagnostic.cfRay,
		transport: diagnostic.transport,
		clientCookiePresent: diagnostic.clientCookiePresent,
		upstreamCookieForwarded: diagnostic.upstreamCookieForwarded,
		upstreamStatus: values.upstreamStatus ?? -1,
		responseWebSocketPresent: values.responseWebSocketPresent ?? false,
		protocolMatched: values.protocolMatched ?? false,
		setCookieReadable: values.setCookieReadable ?? false,
		setCookieCount: values.setCookieCount ?? -1,
		edgeOutcome: values.edgeOutcome,
		exceptionType: values.exceptionType || 'ABSENT',
		elapsedMs: Math.max(0, safeVoiceNow(diagnostic.now) - diagnostic.startedAt)
	}
	try {
		const log = entry.edgeOutcome === 'EDGE_WEBSOCKET_UPGRADED'
			? diagnostic.logger.info
			: diagnostic.logger.warn
		if (typeof log === 'function') {
			log.call(diagnostic.logger, JSON.stringify(entry))
		}
	} catch (_) {
		// 诊断输出失败不得改变握手响应、状态码或 WebSocket 对象。
	}
}

function voiceUpstreamDiagnostic(response) {
	return {
		upstreamStatus: response?.status ?? -1,
		responseWebSocketPresent: Boolean(response?.webSocket),
		protocolMatched: response?.headers?.get('Sec-WebSocket-Protocol')
			=== VOICE_WEBSOCKET_PROTOCOL,
		...voiceSetCookieDiagnostic(response?.headers)
	}
}

function voiceSetCookieDiagnostic(headers) {
	if (!headers) {
		return { setCookieReadable: false, setCookieCount: -1 }
	}
	try {
		const setCookies = readSetCookies(headers)
		return {
			setCookieReadable: setCookies !== null,
			setCookieCount: setCookies === null ? -1 : setCookies.length
		}
	} catch (_) {
		// 诊断读取失败不能改变现有 Worker 响应分支，只标记运行时接口不可用。
		return { setCookieReadable: false, setCookieCount: -1 }
	}
}

function safeVoiceIdentifier(value) {
	const normalized = String(value || '').trim()
	if (!normalized) return 'ABSENT'
	return /^[A-Za-z0-9-]{1,128}$/.test(normalized)
		? normalized : 'INVALID'
}

function safeVoiceExceptionType(error) {
	const name = typeof error?.name === 'string' ? error.name : 'UnknownError'
	return /^[A-Za-z0-9_$]{1,128}$/.test(name) ? name : 'INVALID'
}

function safeVoiceNow(now) {
	try {
		const value = Number(now())
		return Number.isFinite(value) ? value : 0
	} catch (_) {
		return 0
	}
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

function routeError(route, status, code, additionalHeaders = {}) {
	if (!route?.apiKeySdk) return jsonError(status, code, additionalHeaders)
	const messages = {
		invalid_api_key: 'Invalid API Key.',
		browser_request_not_allowed:
			'Browser requests are not allowed for long-lived API Keys.',
		invalid_accept: 'Accept must allow text/event-stream or application/json.',
		method_not_allowed: 'Only POST is allowed for this endpoint.',
		EDGE_UPSTREAM_CONFIGURATION_INVALID:
			'The API gateway is temporarily unavailable.',
		EDGE_RAY_UNAVAILABLE: 'The API gateway is temporarily unavailable.',
		EDGE_UPSTREAM_UNAVAILABLE: 'The model service is temporarily unavailable.',
		EDGE_UPSTREAM_REDIRECT_REJECTED:
			'The model service returned an invalid redirect.'
	}
	return openAiError(status, code, messages[code]
		|| 'The API gateway rejected the request.', additionalHeaders)
}

function openAiError(status, code, message, additionalHeaders = {}) {
	const headers = noStoreHeaders()
	headers.set('Content-Type', 'application/json; charset=utf-8')
	for (const [name, value] of Object.entries(additionalHeaders)) {
		headers.set(name, value)
	}
	const type = status === 401
		? 'authentication_error'
		: status === 403
			? 'permission_error'
			: status === 429
				? 'rate_limit_error'
				: status >= 500
					? 'server_error'
					: 'invalid_request_error'
	return new Response(JSON.stringify({
		error: {
			message,
			type,
			param: null,
			code
		}
	}), { status, headers })
}

function isApiKeySdkSurface(url) {
	return url.hostname === ROOT_HOST && pathWithin(url.pathname, '/v1')
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

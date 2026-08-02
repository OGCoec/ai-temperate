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
2026-08-01T16:58:33.898-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.bindContextCursor            : ==> Parameters: 83dd8bd1-634b-488c-ad5b-a158e19543bb(String), 3(Long), 2026-08-01T21:58:33.897382100Z(OffsetDateTime), [B@9c6e404(byte[])
2026-08-01T16:58:33.899-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.bindContextCursor            : <==    Updates: 1
2026-08-01T16:58:33.902-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findById                     : ==>  Preparing: SELECT id, login_identity_id, conversation_id, usage_id, idempotency_key_digest, model_id, generation_status, observer_status, observer_epoch, owner_instance_id, cancel_source, terminal_type, terminal_reason, terminal_version, created_at, started_at, detached_at, cancel_requested_at, terminal_at, settled_at, updated_at FROM ai_conversation_generation WHERE id = ?
2026-08-01T16:58:33.903-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findById                     : ==> Parameters: [B@9c6e404(byte[])
2026-08-01T16:58:33.905-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findById                     : <==      Total: 1
2026-08-01T16:58:33.907-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findByGenerationId           : ==>  Preparing: SELECT generation_id, input_text, input_attachments, reasoning_effort, assistant_text, assistant_attachments, conversation_message_id, context_generation, ephemeral_ordinal, prompt_tokens, completion_tokens, cached_prompt_tokens, reasoning_tokens, model_finish_reason, upstream_request_id, updated_at FROM ai_conversation_generation_payload WHERE generation_id = ?
2026-08-01T16:58:33.907-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findByGenerationId           : ==> Parameters: [B@2bdc0ed7(byte[])
2026-08-01T16:58:33.910-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findByGenerationId           : <==      Total: 1
2026-08-01T16:58:34.071-05:00  INFO 48252 --- [ai-temperate] [ntContainer#5-1] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_subscribed traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER
2026-08-01T16:58:34.759-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=1035 silenceBeforeMs=980 windowMs=1035 chunkCount=2 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:36.752-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=3027 silenceBeforeMs=996 windowMs=1992 chunkCount=2 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:37.756-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=4031 silenceBeforeMs=1003 windowMs=1003 chunkCount=1 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:39.763-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=6038 silenceBeforeMs=1010 windowMs=2007 chunkCount=2 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:41.755-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=8030 silenceBeforeMs=997 windowMs=1991 chunkCount=2 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:42.761-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=9036 silenceBeforeMs=1006 windowMs=1006 chunkCount=1 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:44.757-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=11032 silenceBeforeMs=997 windowMs=1995 chunkCount=2 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:45.766-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=12041 silenceBeforeMs=1009 windowMs=1009 chunkCount=1 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:47.756-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=14032 silenceBeforeMs=997 windowMs=1990 chunkCount=2 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:48.761-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=15036 silenceBeforeMs=1004 windowMs=1004 chunkCount=1 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:48.945-05:00  INFO 48252 --- [ai-temperate] [     parallel-7] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=15034 silenceBeforeMs=15034 windowMs=15034 chunkCount=1 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-7 burst=false terminal=false
2026-08-01T16:58:49.182-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-4] tionStreamTransportDiagnosticServiceImpl : event=ai_stream_worker_flush occurredAt=2026-08-01T21:58:49.182272Z elapsedMs=15271 traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER details={deltaChars=0, deltaBytes=0, flushReason=TIME_THRESHOLD, generationPublicId=AZ-_VgWXAQE1RnE7rMLXVQ, chunkCount=1}
2026-08-01T16:58:50.760-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=17035 silenceBeforeMs=1004 windowMs=1998 chunkCount=2 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:52.752-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=19027 silenceBeforeMs=999 windowMs=1991 chunkCount=2 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:53.761-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=20036 silenceBeforeMs=1009 windowMs=1009 chunkCount=1 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:54.978-05:00  INFO 48252 --- [ai-temperate] [resence-bloom-1] t.s.a.m.d.MailInspectionDiagnosticAspect : event=admin_mail_inspection_operation operation=lease-refresh outcome=success durationMs=1
2026-08-01T16:58:55.756-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=22032 silenceBeforeMs=999 windowMs=1995 chunkCount=2 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:56.745-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] markExpiredReservationsForReconciliation : ==>  Preparing: WITH expired AS ( SELECT id FROM ai_model_usage WHERE billing_status = ? AND created_at <= ? ORDER BY created_at ASC, id ASC LIMIT ? FOR UPDATE SKIP LOCKED ) UPDATE ai_model_usage usage SET billing_status = ?, failure_code = ?, settled_at = ? FROM expired WHERE usage.id = expired.id AND usage.billing_status = ?
2026-08-01T16:58:56.747-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] markExpiredReservationsForReconciliation : ==> Parameters: 0(Integer), 2026-08-01T21:41:56.745383200Z(OffsetDateTime), 500(Integer), 3(Integer), AI_RESERVED_EXPIRED(String), 2026-08-01T21:58:56.745383200Z(OffsetDateTime), 0(Integer)
2026-08-01T16:58:56.748-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] markExpiredReservationsForReconciliation : <==    Updates: 0
2026-08-01T16:58:56.751-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] c.e.t.m.a.A.findRecoveryCandidates       : ==>  Preparing: SELECT id, login_identity_id, conversation_id, usage_id, idempotency_key_digest, model_id, generation_status, observer_status, observer_epoch, owner_instance_id, cancel_source, terminal_type, terminal_reason, terminal_version, created_at, started_at, detached_at, cancel_requested_at, terminal_at, settled_at, updated_at FROM ai_conversation_generation WHERE ( generation_status IN ( ?, ?, ? ) AND updated_at <= ? ) OR ( generation_status = ? AND updated_at <= ? ) OR ( generation_status IN ( ?, ? ) AND observer_status = ? AND detached_at <= ? ) ORDER BY updated_at ASC, id ASC LIMIT ?
2026-08-01T16:58:56.757-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] c.e.t.m.a.A.findRecoveryCandidates       : ==> Parameters: 0(Integer), 2(Integer), 3(Integer), 2026-08-01T21:57:56.751132400Z(OffsetDateTime), 1(Integer), 2026-08-01T21:42:56.751132400Z(OffsetDateTime), 0(Integer), 1(Integer), 1(Integer), 2026-08-01T21:58:26.751132400Z(OffsetDateTime), 500(Integer)
2026-08-01T16:58:56.760-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] c.e.t.m.a.A.findRecoveryCandidates       : <==      Total: 0
2026-08-01T16:58:56.761-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] .e.t.m.a.A.findTerminalCleanupCandidates : ==>  Preparing: SELECT id, login_identity_id, conversation_id, usage_id, idempotency_key_digest, model_id, generation_status, observer_status, observer_epoch, owner_instance_id, cancel_source, terminal_type, terminal_reason, terminal_version, created_at, started_at, detached_at, cancel_requested_at, terminal_at, settled_at, updated_at FROM ai_conversation_generation WHERE generation_status IN ( ? , ? ) AND updated_at <= ? ORDER BY updated_at ASC, id ASC LIMIT ? FOR UPDATE SKIP LOCKED
2026-08-01T16:58:56.762-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] .e.t.m.a.A.findTerminalCleanupCandidates : ==> Parameters: 4(Integer), 5(Integer), 2026-07-31T21:58:56.760307200Z(OffsetDateTime), 500(Integer)
2026-08-01T16:58:56.764-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] .e.t.m.a.A.findTerminalCleanupCandidates : <==      Total: 0
2026-08-01T16:58:56.764-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=23039 silenceBeforeMs=1007 windowMs=1007 chunkCount=1 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:57.766-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=24041 silenceBeforeMs=1001 windowMs=1001 chunkCount=1 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:58:59.752-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=26027 silenceBeforeMs=999 windowMs=1986 chunkCount=2 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:59:00.756-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=27031 silenceBeforeMs=1004 windowMs=1004 chunkCount=1 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:59:02.752-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=29027 silenceBeforeMs=998 windowMs=1995 chunkCount=2 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:59:03.767-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#4-1] c.e.t.m.a.A.findByIdForUpdate            : ==>  Preparing: SELECT id, login_identity_id, conversation_id, usage_id, idempotency_key_digest, model_id, generation_status, observer_status, observer_epoch, owner_instance_id, cancel_source, terminal_type, terminal_reason, terminal_version, created_at, started_at, detached_at, cancel_requested_at, terminal_at, settled_at, updated_at FROM ai_conversation_generation WHERE id = ? FOR UPDATE
2026-08-01T16:59:03.777-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=30052 silenceBeforeMs=1024 windowMs=1024 chunkCount=1 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:59:03.782-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#4-1] c.e.t.m.a.A.findByIdForUpdate            : ==> Parameters: [B@3c9ec39c(byte[])
2026-08-01T16:59:03.802-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#4-1] c.e.t.m.a.A.findByIdForUpdate            : <==      Total: 1
2026-08-01T16:59:03.962-05:00  INFO 48252 --- [ai-temperate] [     parallel-7] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=30051 silenceBeforeMs=15017 windowMs=15017 chunkCount=1 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-7 burst=false terminal=false
2026-08-01T16:59:04.207-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-4] tionStreamTransportDiagnosticServiceImpl : event=ai_stream_worker_flush occurredAt=2026-08-01T21:59:04.207449900Z elapsedMs=30296 traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER details={deltaChars=0, deltaBytes=0, flushReason=TIME_THRESHOLD, generationPublicId=AZ-_VgWXAQE1RnE7rMLXVQ, chunkCount=1}
2026-08-01T16:59:05.767-05:00  INFO 48252 --- [ai-temperate] [     parallel-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=32042 silenceBeforeMs=1011 windowMs=1990 chunkCount=2 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=parallel-6 burst=false terminal=false
2026-08-01T16:59:06.377-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_first_raw traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32466 thread=boundedElastic-6
2026-08-01T16:59:06.378-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32466 silenceBeforeMs=32466 windowMs=32466 chunkCount=1 textChars=0 schedulerDelayMaxMs=0 pendingChunkMax=1 thread=boundedElastic-6 burst=false terminal=false
2026-08-01T16:59:06.381-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32470 silenceBeforeMs=32470 windowMs=32470 chunkCount=1 textChars=0 schedulerDelayMaxMs=4 pendingChunkMax=4 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.382-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_first_text traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32471 thread=boundedElastic-6
2026-08-01T16:59:06.383-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32472 silenceBeforeMs=2421 windowMs=2421 chunkCount=1 textChars=0 schedulerDelayMaxMs=4 pendingChunkMax=7 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.391-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32481 silenceBeforeMs=4 windowMs=15 chunkCount=100 textChars=171 schedulerDelayMaxMs=4 pendingChunkMax=14 thread=boundedElastic-6 burst=false terminal=false
2026-08-01T16:59:06.393-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32483 silenceBeforeMs=2 windowMs=12 chunkCount=100 textChars=171 schedulerDelayMaxMs=4 pendingChunkMax=14 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.393-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32483 silenceBeforeMs=1 windowMs=11 chunkCount=100 textChars=171 schedulerDelayMaxMs=4 pendingChunkMax=14 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.404-05:00  WARN 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_burst traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32494 silenceBeforeMs=32466 windowMs=27 chunkCount=200 textChars=162 schedulerDelayMaxMs=4 pendingChunkMax=59 beforeThread=boundedElastic-6 afterThread=boundedElastic-5 burst=true
2026-08-01T16:59:06.405-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32494 silenceBeforeMs=2 windowMs=13 chunkCount=100 textChars=163 schedulerDelayMaxMs=4 pendingChunkMax=59 thread=boundedElastic-6 burst=true terminal=false
2026-08-01T16:59:06.405-05:00  WARN 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_burst traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32495 silenceBeforeMs=32470 windowMs=24 chunkCount=200 textChars=162 schedulerDelayMaxMs=4 pendingChunkMax=59 beforeThread=boundedElastic-6 afterThread=boundedElastic-5 burst=true
2026-08-01T16:59:06.406-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32495 silenceBeforeMs=2 windowMs=12 chunkCount=100 textChars=163 schedulerDelayMaxMs=4 pendingChunkMax=59 thread=boundedElastic-5 burst=true terminal=false
2026-08-01T16:59:06.406-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32496 silenceBeforeMs=3 windowMs=12 chunkCount=100 textChars=163 schedulerDelayMaxMs=4 pendingChunkMax=59 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.410-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32499 silenceBeforeMs=0 windowMs=4 chunkCount=100 textChars=170 schedulerDelayMaxMs=4 pendingChunkMax=68 thread=boundedElastic-6 burst=true terminal=false
2026-08-01T16:59:06.414-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32503 silenceBeforeMs=1 windowMs=4 chunkCount=100 textChars=167 schedulerDelayMaxMs=5 pendingChunkMax=143 thread=boundedElastic-6 burst=true terminal=false
2026-08-01T16:59:06.416-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32505 silenceBeforeMs=1 windowMs=9 chunkCount=100 textChars=170 schedulerDelayMaxMs=6 pendingChunkMax=152 thread=boundedElastic-5 burst=true terminal=false
2026-08-01T16:59:06.416-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32506 silenceBeforeMs=1 windowMs=10 chunkCount=100 textChars=170 schedulerDelayMaxMs=6 pendingChunkMax=152 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.425-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32514 silenceBeforeMs=2 windowMs=9 chunkCount=100 textChars=167 schedulerDelayMaxMs=11 pendingChunkMax=152 thread=boundedElastic-5 burst=true terminal=false
2026-08-01T16:59:06.426-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32515 silenceBeforeMs=1 windowMs=8 chunkCount=100 textChars=167 schedulerDelayMaxMs=11 pendingChunkMax=152 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.426-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32516 silenceBeforeMs=10 windowMs=13 chunkCount=100 textChars=173 schedulerDelayMaxMs=11 pendingChunkMax=152 thread=boundedElastic-6 burst=true terminal=false
2026-08-01T16:59:06.427-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32517 silenceBeforeMs=1 windowMs=2 chunkCount=100 textChars=173 schedulerDelayMaxMs=11 pendingChunkMax=152 thread=boundedElastic-5 burst=true terminal=false
2026-08-01T16:59:06.427-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32517 silenceBeforeMs=0 windowMs=2 chunkCount=100 textChars=173 schedulerDelayMaxMs=11 pendingChunkMax=152 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.429-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32519 silenceBeforeMs=0 windowMs=2 chunkCount=100 textChars=193 schedulerDelayMaxMs=11 pendingChunkMax=152 thread=boundedElastic-6 burst=true terminal=false
2026-08-01T16:59:06.432-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32522 silenceBeforeMs=2 windowMs=4 chunkCount=100 textChars=193 schedulerDelayMaxMs=11 pendingChunkMax=152 thread=boundedElastic-5 burst=true terminal=false
2026-08-01T16:59:06.434-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-4] tionStreamTransportDiagnosticServiceImpl : event=ai_stream_worker_flush occurredAt=2026-08-01T21:59:06.434826Z elapsedMs=32524 traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER details={deltaChars=1036, deltaBytes=2026, flushReason=TIME_THRESHOLD, generationPublicId=AZ-_VgWXAQE1RnE7rMLXVQ, chunkCount=600}
2026-08-01T16:59:06.436-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32525 silenceBeforeMs=3 windowMs=7 chunkCount=100 textChars=193 schedulerDelayMaxMs=11 pendingChunkMax=152 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.439-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32528 silenceBeforeMs=5 windowMs=9 chunkCount=100 textChars=178 schedulerDelayMaxMs=11 pendingChunkMax=152 thread=boundedElastic-6 burst=true terminal=false
2026-08-01T16:59:06.441-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32531 silenceBeforeMs=0 windowMs=2 chunkCount=100 textChars=241 schedulerDelayMaxMs=11 pendingChunkMax=200 thread=boundedElastic-6 burst=true terminal=false
2026-08-01T16:59:06.482-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-4] tionStreamTransportDiagnosticServiceImpl : event=ai_stream_redis_delta_published occurredAt=2026-08-01T21:59:06.482966600Z elapsedMs=32572 traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER details={deltaChars=1036, deltaBytes=2026, flushReason=TIME_THRESHOLD, generationPublicId=AZ-_VgWXAQE1RnE7rMLXVQ, chunkCount=600, revision=1, redisAppendElapsedMs=2, redisPublishElapsedMs=21, redisAppendStartElapsedMs=32548, redisAppendEndElapsedMs=32551, redisPublishStartElapsedMs=32551, redisPublishEndElapsedMs=32572}
2026-08-01T16:59:06.484-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32574 silenceBeforeMs=51 windowMs=52 chunkCount=100 textChars=178 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=true terminal=false
2026-08-01T16:59:06.485-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32575 silenceBeforeMs=47 windowMs=49 chunkCount=100 textChars=178 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.486-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32576 silenceBeforeMs=0 windowMs=1 chunkCount=100 textChars=241 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=true terminal=false
2026-08-01T16:59:06.487-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32577 silenceBeforeMs=0 windowMs=1 chunkCount=100 textChars=241 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.490-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32579 silenceBeforeMs=42 windowMs=48 chunkCount=100 textChars=211 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-6 burst=true terminal=false
2026-08-01T16:59:06.490-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32580 silenceBeforeMs=1 windowMs=3 chunkCount=100 textChars=211 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=true terminal=false
2026-08-01T16:59:06.491-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32580 silenceBeforeMs=1 windowMs=3 chunkCount=100 textChars=211 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.492-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32582 silenceBeforeMs=0 windowMs=2 chunkCount=100 textChars=178 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-6 burst=true terminal=false
2026-08-01T16:59:06.494-05:00  INFO 48252 --- [ai-temperate] [enerContainer-1] tionStreamTransportDiagnosticServiceImpl : event=ai_stream_redis_observer_received occurredAt=2026-08-01T21:59:06.494910100Z elapsedMs=32770 traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER details={revision=1, generationPublicId=AZ-_VgWXAQE1RnE7rMLXVQ, eventType=delta}
2026-08-01T16:59:06.496-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32586 silenceBeforeMs=3 windowMs=5 chunkCount=100 textChars=178 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=true terminal=false
2026-08-01T16:59:06.496-05:00  INFO 48252 --- [ai-temperate] [enerContainer-1] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_first_text traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=32772 thread=serviceRedisMessageListenerContainer-1
2026-08-01T16:59:06.497-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32587 silenceBeforeMs=3 windowMs=6 chunkCount=100 textChars=178 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.501-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32591 silenceBeforeMs=4 windowMs=9 chunkCount=100 textChars=188 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-6 burst=true terminal=false
2026-08-01T16:59:06.508-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32598 silenceBeforeMs=7 windowMs=12 chunkCount=100 textChars=188 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=true terminal=false
2026-08-01T16:59:06.509-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32599 silenceBeforeMs=7 windowMs=11 chunkCount=100 textChars=188 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.511-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32601 silenceBeforeMs=6 windowMs=10 chunkCount=100 textChars=201 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-6 burst=true terminal=false
2026-08-01T16:59:06.530-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32619 silenceBeforeMs=17 windowMs=18 chunkCount=100 textChars=157 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-6 burst=true terminal=false
2026-08-01T16:59:06.535-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-6] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32625 silenceBeforeMs=3 windowMs=5 chunkCount=100 textChars=179 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-6 burst=true terminal=false
2026-08-01T16:59:06.538-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32627 silenceBeforeMs=19 windowMs=29 chunkCount=100 textChars=201 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=true terminal=false
2026-08-01T16:59:06.539-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32628 silenceBeforeMs=25 windowMs=29 chunkCount=100 textChars=201 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.541-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32630 silenceBeforeMs=1 windowMs=2 chunkCount=100 textChars=157 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=true terminal=false
2026-08-01T16:59:06.542-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32631 silenceBeforeMs=1 windowMs=3 chunkCount=100 textChars=157 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.553-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32642 silenceBeforeMs=9 windowMs=12 chunkCount=100 textChars=179 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=true terminal=false
2026-08-01T16:59:06.554-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32643 silenceBeforeMs=10 windowMs=12 chunkCount=100 textChars=179 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=false terminal=false
2026-08-01T16:59:06.556-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_model_terminal traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A path=ASYNC_GENERATION_WORKER outcome=COMPLETE failureType=none elapsedMs=32646
2026-08-01T16:59:06.558-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] tionStreamTransportDiagnosticServiceImpl : event=ai_stream_worker_flush occurredAt=2026-08-01T21:59:06.558182700Z elapsedMs=32647 traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER details={deltaChars=1603, deltaBytes=2915, flushReason=TERMINAL, generationPublicId=AZ-_VgWXAQE1RnE7rMLXVQ, chunkCount=845}
2026-08-01T16:59:06.573-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] tionStreamTransportDiagnosticServiceImpl : event=ai_stream_redis_delta_published occurredAt=2026-08-01T21:59:06.573677500Z elapsedMs=32663 traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER details={deltaChars=1603, deltaBytes=2915, flushReason=TERMINAL, generationPublicId=AZ-_VgWXAQE1RnE7rMLXVQ, chunkCount=845, revision=2, redisAppendElapsedMs=2, redisPublishElapsedMs=2, redisAppendStartElapsedMs=32658, redisAppendEndElapsedMs=32660, redisPublishStartElapsedMs=32660, redisPublishEndElapsedMs=32663}
2026-08-01T16:59:06.575-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=SPRING_AI_RAW elapsedMs=32664 silenceBeforeMs=9 windowMs=38 chunkCount=44 textChars=69 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=true terminal=true
2026-08-01T16:59:06.575-05:00  INFO 48252 --- [ai-temperate] [enerContainer-2] tionStreamTransportDiagnosticServiceImpl : event=ai_stream_redis_observer_received occurredAt=2026-08-01T21:59:06.575708200Z elapsedMs=32850 traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER details={revision=2, generationPublicId=AZ-_VgWXAQE1RnE7rMLXVQ, eventType=delta}
2026-08-01T16:59:06.575-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_BOUNDED_ELASTIC elapsedMs=32664 silenceBeforeMs=1 windowMs=21 chunkCount=44 textChars=69 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=true terminal=true
2026-08-01T16:59:06.575-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER boundary=AFTER_STREAM_BATCHER elapsedMs=32664 silenceBeforeMs=0 windowMs=20 chunkCount=44 textChars=69 schedulerDelayMaxMs=53 pendingChunkMax=231 thread=boundedElastic-5 burst=false terminal=true
2026-08-01T16:59:06.576-05:00  INFO 48252 --- [ai-temperate] [oundedElastic-5] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_summary traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_WORKER outcome=COMPLETE failureType=none elapsedMs=32664 firstRawMs=32466 firstVisibleTextMs=32471 rawChunks=1445 schedulerChunks=1445 batcherChunks=1447 sseEvents=0 textChars=0 schedulerDelayMaxMs=53 pendingChunkMax=231 schedulerPairingEnabled=true
2026-08-01T16:59:06.578-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findById                     : ==>  Preparing: SELECT id, login_identity_id, conversation_id, usage_id, idempotency_key_digest, model_id, generation_status, observer_status, observer_epoch, owner_instance_id, cancel_source, terminal_type, terminal_reason, terminal_version, created_at, started_at, detached_at, cancel_requested_at, terminal_at, settled_at, updated_at FROM ai_conversation_generation WHERE id = ?
2026-08-01T16:59:06.578-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findById                     : ==> Parameters: [B@9c6e404(byte[])
2026-08-01T16:59:06.579-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findById                     : <==      Total: 1
2026-08-01T16:59:06.580-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findByGenerationId           : ==>  Preparing: SELECT generation_id, input_text, input_attachments, reasoning_effort, assistant_text, assistant_attachments, conversation_message_id, context_generation, ephemeral_ordinal, prompt_tokens, completion_tokens, cached_prompt_tokens, reasoning_tokens, model_finish_reason, upstream_request_id, updated_at FROM ai_conversation_generation_payload WHERE generation_id = ?
2026-08-01T16:59:06.580-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findByGenerationId           : ==> Parameters: [B@44b91f9c(byte[])
2026-08-01T16:59:06.581-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findByGenerationId           : <==      Total: 1
2026-08-01T16:59:06.583-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findByIdForUpdate            : ==>  Preparing: SELECT id, login_identity_id, conversation_id, usage_id, idempotency_key_digest, model_id, generation_status, observer_status, observer_epoch, owner_instance_id, cancel_source, terminal_type, terminal_reason, terminal_version, created_at, started_at, detached_at, cancel_requested_at, terminal_at, settled_at, updated_at FROM ai_conversation_generation WHERE id = ? FOR UPDATE
2026-08-01T16:59:06.583-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findByIdForUpdate            : ==> Parameters: [B@7dabcac1(byte[])
2026-08-01T16:59:06.585-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.findByIdForUpdate            : <==      Total: 1
2026-08-01T16:59:06.586-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.freezeTerminalEvidence       : ==>  Preparing: UPDATE ai_conversation_generation_payload SET assistant_text = ?, assistant_attachments = CAST(? AS JSONB), prompt_tokens = ?, completion_tokens = ?, cached_prompt_tokens = ?, reasoning_tokens = ?, model_finish_reason = ?, upstream_request_id = ?, updated_at = ? WHERE generation_id = ? AND assistant_text IS NULL AND model_finish_reason IS NULL
2026-08-01T16:59:06.587-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.freezeTerminalEvidence       : ==> Parameters: 可以，我把这几个概念一次给你讲清楚。      

---

## 1）什么是散列（哈希）？

**散列 / 哈希**，本质上是同一个意思。

它指的是一种函数：

> 把任意长度的数据，变成一个固定长度的结果。

这个结果通常叫：

- **hash**
- **digest**
- **摘要**

比如：

- 输入：`hello`
- 输出：一串固定长度的字符

---

### 哈希的特点

一个好的哈希函数通常有这些特性：

1. **单向性**
   - 只能从输入算出结果
   - 很难从结果反推原文

2. **固定长度**
   - 不管输入多长，输出长度通常固定

3. **雪崩效应**
   - 输入只改一个字母，输出会变化很大

4. **抗碰撞性**
   - 很难找到两个不同输入，得到同一个哈希值

---

## 2）MD5 是不是哈希方式？

**是，MD5 是一种哈希算法。**

更准确地说：

- **MD5 = 哈希算法**
- **不是加密算法**

---

### 但要注意
MD5 现在已经 **不安全** 了，原因是：

- 太快，容易被暴力破解
- 容易被构造碰撞
- 不适合用于密码存储、数字签名等安全场景

所以现在一般：

- **不建议用 MD5 存密码**
- 也不建议用 MD5 做安全敏感用途

---

## 3）SHA-256 也是哈希方式吗？

**是，SHA-256 也是哈希算法。**

它属于 SHA 系列里比较常用的一种。

### 它和 MD5 的关系
- 都是哈希算法
- 但 **SHA-256 比 MD5 安全得多**
- 输出长度也更长

---

### 但也要注意
虽然 SHA-256 比 MD5 更安全，
**它仍然不适合直接拿来存密码**。

原因是：

- 它也很快
- 很容易被 GPU/彩虹表/字典攻击

所以密码存储一般不会直接用 `SHA-256(password)`，而是用专门的密码哈希算法。

---

## 4）你说的 Spring Security 里“以 $ 开头”的是什么？

你这个描述，**大概率是 BCrypt**。

### 典型格式长这样：

```text
$2a$10$eImiTXuWVxfM37uY4JANjQ==...
```

或者类似：

```text
$2b$12$...
```

---

## 5）这到底是什么“加密方式”？

严格说，**这不是加密**，而是：

> **密码哈希算法**

更具体地说，通常是：

> **BCrypt**

---

### BCrypt 的特点
它是专门为密码设计的哈希算法，特点是：

1. **自带盐（salt）**
   - 同一个密码，每次生成的结果可以不同

2. **计算慢**
   - 故意设计得比较慢，防暴力破解

3. **有成本因子（cost factor）**
   - 可以调节计算强度

4. **不可逆**
   - 不能像解密那样还原原密码

---

## 6）Spring Security 里为什么经常看到 BCrypt？

因为 Spring Security 里密码存储最常见的方式就是 BCrypt。

比如你用 `BCryptPasswordEncoder`：

```java
new BCryptPasswordEncoder()
```

它生成的结果通常就会以 `$2a$`、`$2b$` 之类开头。

---

## 7）这个 `$` 开头字符串里面有什么？

以 BCrypt 为例，它通常包含：

- **版本号**
- **成本因子**
- **salt**
- **最终 hash**

例如：

```text
$2a$10$abcdefghijklmnopqrstuv1234567890abcdefgHIJKLMNO
```

大致可理解为：

- `$2a$`：BCrypt 版本
- `10`：成本因子
- 后面一段：salt 和 hash 结果

---

## 8）哈希、加密、密码哈希，三者区别是什么？

### 1. 加密（Encryption）
- **可逆**
- 有密钥就能解密

比如：
- AES
- RSA
- TLS 里的加密

---

### 2. 哈希（Hash）
- **不可逆**
- 用于完整性校验、摘要、指纹

比如：
- MD5
- SHA-256

---

### 3. 密码哈希（Password Hashing）
- 也是不可逆
- 但专门为密码设计
- 通常比较慢，带 salt

比如：
- BCrypt
- PBKDF2
- scrypt
- Argon2

---

## 9）为什么密码不能直接用 MD5 或 SHA-256？

因为它们太快了。

密码哈希的目标不是“快”，而是：

> **让攻击者破解成本很高**

如果你直接用：

- `MD5(password)`
- `SHA-256(password)`

攻击者可以非常快地尝试海量密码。

而 BCrypt / Argon2 / PBKDF2 这些算法是故意“慢”的，能显著增加破解成本。

---

## 10）你可以这样理解 Spring Security 里的那个 `$` 哈希

你可以把它理解成：

> **一种带盐、不可逆、专门用于密码存储的哈希方式**
>
> 最常见就是 **BCrypt**

它不是“解密后还原密码”，而是：

- 用户登录时输入密码
- 系统再算一遍哈希
- 和数据库里存的哈希结果比对
- 一样就说明密码正确

---

## 11）一句话总结

- **散列 / 哈希**：把数据映射成固定长度摘要
- **MD5**：哈希算法，但不安全
- **SHA-256**：哈希算法，比 MD5 安全，但不适合直接存密码
- **Spring Security 里 `$` 开头的密码串**：通常是 **BCrypt 哈希**
- **它不是加密，而是密码哈希**

---

如果你愿意，我下一条可以继续给你讲：

**“BCrypt、PBKDF2、Argon2 这几个密码哈希算法到底有什么区别？”**

我可以给你做一个非常直观的对比表。(String), [](String), 2553(Long), 1967(Long), 0(Long), 516(Long), STOP(String), resp_05d350a2f7f84fe4016a6e6bfad960819489ca79576301caa4(String), 2026-08-01T21:59:06.586005300Z(OffsetDateTime), [B@7dabcac1(byte[])
2026-08-01T16:59:06.595-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.freezeTerminalEvidence       : <==    Updates: 1
2026-08-01T16:59:06.595-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.freezeTerminal               : ==>  Preparing: UPDATE ai_conversation_generation SET generation_status = ?, terminal_type = ?, terminal_reason = ?, terminal_version = terminal_version + 1, terminal_at = ?, updated_at = ? WHERE id = ? AND terminal_version = ? AND terminal_type IS NULL AND generation_status IN ( ? , ? , ? )
2026-08-01T16:59:06.597-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.freezeTerminal               : ==> Parameters: 3(Integer), COMPLETED(String), STOP(String), 2026-08-01T21:59:06.586005300Z(OffsetDateTime), 2026-08-01T21:59:06.586005300Z(OffsetDateTime), [B@7dabcac1(byte[]), 0(Integer), 0(Integer), 1(Integer), 2(Integer)
2026-08-01T16:59:06.599-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#5-1] c.e.t.m.a.A.freezeTerminal               : <==    Updates: 1
2026-08-01T16:59:06.622-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findById                     : ==>  Preparing: SELECT id, login_identity_id, conversation_id, usage_id, idempotency_key_digest, model_id, generation_status, observer_status, observer_epoch, owner_instance_id, cancel_source, terminal_type, terminal_reason, terminal_version, created_at, started_at, detached_at, cancel_requested_at, terminal_at, settled_at, updated_at FROM ai_conversation_generation WHERE id = ?
2026-08-01T16:59:06.623-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findById                     : ==> Parameters: [B@3c1a86f0(byte[])
2026-08-01T16:59:06.625-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findById                     : <==      Total: 1
2026-08-01T16:59:06.626-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByGenerationId           : ==>  Preparing: SELECT generation_id, input_text, input_attachments, reasoning_effort, assistant_text, assistant_attachments, conversation_message_id, context_generation, ephemeral_ordinal, prompt_tokens, completion_tokens, cached_prompt_tokens, reasoning_tokens, model_finish_reason, upstream_request_id, updated_at FROM ai_conversation_generation_payload WHERE generation_id = ?
2026-08-01T16:59:06.626-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByGenerationId           : ==> Parameters: [B@3c1a86f0(byte[])
2026-08-01T16:59:06.635-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByGenerationId           : <==      Total: 1
2026-08-01T16:59:06.636-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByUsageId                : ==>  Preparing: SELECT id, usage_id, conversation_id, conversation_message_id, idempotency_key_digest, upstream_request_id, vendor_snapshot, is_stream, estimated_prompt_tokens, max_output_tokens, input_ratio_snapshot, cached_input_ratio_snapshot, output_ratio_snapshot, reserved_quota_minor, settlement_delta_minor FROM ai_model_usage_detail WHERE usage_id = ?
2026-08-01T16:59:06.637-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByUsageId                : ==> Parameters: [B@192b697d(byte[])
2026-08-01T16:59:06.640-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByUsageId                : <==      Total: 1
2026-08-01T16:59:06.642-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByGenerationIdForUpdate  : ==>  Preparing: SELECT generation_id, input_text, input_attachments, reasoning_effort, assistant_text, assistant_attachments, conversation_message_id, context_generation, ephemeral_ordinal, prompt_tokens, completion_tokens, cached_prompt_tokens, reasoning_tokens, model_finish_reason, upstream_request_id, updated_at FROM ai_conversation_generation_payload WHERE generation_id = ? FOR UPDATE
2026-08-01T16:59:06.642-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByGenerationIdForUpdate  : ==> Parameters: [B@3c1a86f0(byte[])
2026-08-01T16:59:06.645-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByGenerationIdForUpdate  : <==      Total: 1
2026-08-01T16:59:06.645-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.reserveMessageId             : ==>  Preparing: SELECT nextval( pg_get_serial_sequence('ai_conversation_message', 'id') )
2026-08-01T16:59:06.646-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.reserveMessageId             : ==> Parameters:
2026-08-01T16:59:06.648-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.reserveMessageId             : <==      Total: 1
2026-08-01T16:59:06.648-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.assignConversationMessageId  : ==>  Preparing: UPDATE ai_conversation_generation_payload SET conversation_message_id = ?, updated_at = ? WHERE generation_id = ? AND conversation_message_id IS NULL
2026-08-01T16:59:06.649-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.assignConversationMessageId  : ==> Parameters: 15(Long), 2026-08-01T21:59:06.648923700Z(OffsetDateTime), [B@3c1a86f0(byte[])
2026-08-01T16:59:06.649-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.assignConversationMessageId  : <==    Updates: 1
2026-08-01T16:59:06.926-05:00  INFO 48252 --- [ai-temperate] [ntContainer#3-1] org.wltea.analyzer.dic.Dictionary        : 加载扩展词典:ik/ai-model.dic
2026-08-01T16:59:06.933-05:00  INFO 48252 --- [ai-temperate] [ntContainer#3-1] org.wltea.analyzer.dic.Dictionary        : 加载扩展停止词典:org/wltea/analyzer/dic/stopword.dic      
2026-08-01T16:59:06.935-05:00  INFO 48252 --- [ai-temperate] [ntContainer#3-1] org.wltea.analyzer.dic.Dictionary        : 加载扩展停止词典:ik/ai-stopwords.dic
2026-08-01T16:59:06.956-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByIdForUpdate            : ==>  Preparing: SELECT id, login_identity_id, conversation_id, usage_id, idempotency_key_digest, model_id, generation_status, observer_status, observer_epoch, owner_instance_id, cancel_source, terminal_type, terminal_reason, terminal_version, created_at, started_at, detached_at, cancel_requested_at, terminal_at, settled_at, updated_at FROM ai_conversation_generation WHERE id = ? FOR UPDATE
2026-08-01T16:59:06.956-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByIdForUpdate            : ==> Parameters: [B@6d7a5c38(byte[])
2026-08-01T16:59:06.958-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByIdForUpdate            : <==      Total: 1
2026-08-01T16:59:06.962-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByIdForUpdate            : ==>  Preparing: SELECT id, login_identity_id, ai_model_id, billing_status, prompt_tokens, completion_tokens, cached_prompt_tokens, reasoning_tokens, charged_quota_minor, finish_reason, failure_code, created_at, settled_at FROM ai_model_usage WHERE id = ? FOR UPDATE
2026-08-01T16:59:06.963-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByIdForUpdate            : ==> Parameters: [B@45e3d867(byte[])
2026-08-01T16:59:06.964-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByIdForUpdate            : <==      Total: 1
2026-08-01T16:59:06.964-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByUsageId                : ==>  Preparing: SELECT id, usage_id, conversation_id, conversation_message_id, idempotency_key_digest, upstream_request_id, vendor_snapshot, is_stream, estimated_prompt_tokens, max_output_tokens, input_ratio_snapshot, cached_input_ratio_snapshot, output_ratio_snapshot, reserved_quota_minor, settlement_delta_minor FROM ai_model_usage_detail WHERE usage_id = ?
2026-08-01T16:59:06.965-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByUsageId                : ==> Parameters: [B@45e3d867(byte[])
2026-08-01T16:59:06.965-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.findByUsageId                : <==      Total: 1
2026-08-01T16:59:06.965-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] t.m.u.m.U.findByLoginIdentityIdForUpdate : ==>  Preparing: SELECT id, login_identity_id, membership_tier, quota_balance_minor, quota_period_started_at, quota_period_ends_at FROM user_membership_quota WHERE login_identity_id = ? FOR UPDATE
2026-08-01T16:59:06.965-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] t.m.u.m.U.findByLoginIdentityIdForUpdate : ==> Parameters: 76721355290185728(Long)
2026-08-01T16:59:06.967-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] t.m.u.m.U.findByLoginIdentityIdForUpdate : <==      Total: 1
2026-08-01T16:59:06.967-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.u.m.U.updateBalanceAndPeriod     : ==>  Preparing: UPDATE user_membership_quota SET quota_balance_minor = ?, quota_period_started_at = ?, quota_period_ends_at = ? WHERE id = ? AND login_identity_id = ?
2026-08-01T16:59:06.967-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.u.m.U.updateBalanceAndPeriod     : ==> Parameters: 4734(Long), 2026-07-31T19:39:23.627854Z(OffsetDateTime), 2026-08-07T19:39:23.627854Z(OffsetDateTime), 4(Long), 76721355290185728(Long)
2026-08-01T16:59:06.968-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.u.m.U.updateBalanceAndPeriod     : <==    Updates: 1
2026-08-01T16:59:06.969-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.insert                       : ==>  Preparing: INSERT INTO ai_conversation_message ( id, conversation_id, content_text, content_attachments, content_parts, question_tokens, response_attachments ) OVERRIDING SYSTEM VALUE VALUES ( ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), ?, CAST(? AS JSONB) )
2026-08-01T16:59:06.969-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.insert                       : ==> Parameters: 15(Long), [B@51f5cf37(byte[]), 什么是这个 散列哈希呢?什么是散列哈希呢?MD5是不是一种加密哈希?不对,是一种哈希方式。SHA256是不是也是一种哈希方式?然后还有一种就是说,对,比如说我输入一颗密码,这个密码,对,比如说用Spring Security进行杂点哈希之后,它是一个$开头的。那这种加密方式又是什么呢?回答我这个问题好吗?(String), [](String), ["散","列","哈希","散","列","哈希","md5","是不是","一种","加密","哈希","一种","哈 希","方式","sha256","是不是","也是","一种","哈希","方式","一种","输入","一颗","密码","密码","spring","security","进行","杂点","哈希","之后","它是","一个","开头","这种","加密","方式","又是","回答","问题","好吗"](String), 可以，我把这几个概念一次给你讲清楚。

---

## 1）什么是散列（哈希）？

**散列 / 哈希**，本质上是同一个意思。

它指的是一种函数：

> 把任意长度的数据，变成一个固定长度的结果。

这个结果通常叫：

- **hash**
- **digest**
- **摘要**

比如：

- 输入：`hello`
- 输出：一串固定长度的字符

---

### 哈希的特点

一个好的哈希函数通常有这些特性：

1. **单向性**
   - 只能从输入算出结果
   - 很难从结果反推原文

2. **固定长度**
   - 不管输入多长，输出长度通常固定

3. **雪崩效应**
   - 输入只改一个字母，输出会变化很大

4. **抗碰撞性**
   - 很难找到两个不同输入，得到同一个哈希值

---

## 2）MD5 是不是哈希方式？

**是，MD5 是一种哈希算法。**

更准确地说：

- **MD5 = 哈希算法**
- **不是加密算法**

---

### 但要注意
MD5 现在已经 **不安全** 了，原因是：

- 太快，容易被暴力破解
- 容易被构造碰撞
- 不适合用于密码存储、数字签名等安全场景

所以现在一般：

- **不建议用 MD5 存密码**
- 也不建议用 MD5 做安全敏感用途

---

## 3）SHA-256 也是哈希方式吗？

**是，SHA-256 也是哈希算法。**

它属于 SHA 系列里比较常用的一种。

### 它和 MD5 的关系
- 都是哈希算法
- 但 **SHA-256 比 MD5 安全得多**
- 输出长度也更长

---

### 但也要注意
虽然 SHA-256 比 MD5 更安全，
**它仍然不适合直接拿来存密码**。

原因是：

- 它也很快
- 很容易被 GPU/彩虹表/字典攻击

所以密码存储一般不会直接用 `SHA-256(password)`，而是用专门的密码哈希算法。

---

## 4）你说的 Spring Security 里“以 $ 开头”的是什么？

你这个描述，**大概率是 BCrypt**。

### 典型格式长这样：

```text
$2a$10$eImiTXuWVxfM37uY4JANjQ==...
```

或者类似：

```text
$2b$12$...
```

---

## 5）这到底是什么“加密方式”？

严格说，**这不是加密**，而是：

> **密码哈希算法**

更具体地说，通常是：

> **BCrypt**

---

### BCrypt 的特点
它是专门为密码设计的哈希算法，特点是：

1. **自带盐（salt）**
   - 同一个密码，每次生成的结果可以不同

2. **计算慢**
   - 故意设计得比较慢，防暴力破解

3. **有成本因子（cost factor）**
   - 可以调节计算强度

4. **不可逆**
   - 不能像解密那样还原原密码

---

## 6）Spring Security 里为什么经常看到 BCrypt？

因为 Spring Security 里密码存储最常见的方式就是 BCrypt。

比如你用 `BCryptPasswordEncoder`：

```java
new BCryptPasswordEncoder()
```

它生成的结果通常就会以 `$2a$`、`$2b$` 之类开头。

---

## 7）这个 `$` 开头字符串里面有什么？

以 BCrypt 为例，它通常包含：

- **版本号**
- **成本因子**
- **salt**
- **最终 hash**

例如：

```text
$2a$10$abcdefghijklmnopqrstuv1234567890abcdefgHIJKLMNO
```

大致可理解为：

- `$2a$`：BCrypt 版本
- `10`：成本因子
- 后面一段：salt 和 hash 结果

---

## 8）哈希、加密、密码哈希，三者区别是什么？

### 1. 加密（Encryption）
- **可逆**
- 有密钥就能解密

比如：
- AES
- RSA
- TLS 里的加密

---

### 2. 哈希（Hash）
- **不可逆**
- 用于完整性校验、摘要、指纹

比如：
- MD5
- SHA-256

---

### 3. 密码哈希（Password Hashing）
- 也是不可逆
- 但专门为密码设计
- 通常比较慢，带 salt

比如：
- BCrypt
- PBKDF2
- scrypt
- Argon2

---

## 9）为什么密码不能直接用 MD5 或 SHA-256？

因为它们太快了。

密码哈希的目标不是“快”，而是：

> **让攻击者破解成本很高**

如果你直接用：

- `MD5(password)`
- `SHA-256(password)`

攻击者可以非常快地尝试海量密码。

而 BCrypt / Argon2 / PBKDF2 这些算法是故意“慢”的，能显著增加破解成本。

---

## 10）你可以这样理解 Spring Security 里的那个 `$` 哈希

你可以把它理解成：

> **一种带盐、不可逆、专门用于密码存储的哈希方式**
>
> 最常见就是 **BCrypt**

它不是“解密后还原密码”，而是：

- 用户登录时输入密码
- 系统再算一遍哈希
- 和数据库里存的哈希结果比对
- 一样就说明密码正确

---

## 11）一句话总结

- **散列 / 哈希**：把数据映射成固定长度摘要
- **MD5**：哈希算法，但不安全
- **SHA-256**：哈希算法，比 MD5 安全，但不适合直接存密码
- **Spring Security 里 `$` 开头的密码串**：通常是 **BCrypt 哈希**
- **它不是加密，而是密码哈希**

---

如果你愿意，我下一条可以继续给你讲：

**“BCrypt、PBKDF2、Argon2 这几个密码哈希算法到底有什么区别？”**

我可以给你做一个非常直观的对比表。(String), [](String)
2026-08-01T16:59:06.997-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.insert                       : <==    Updates: 1
2026-08-01T16:59:06.998-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.updateAfterPersistedMessage  : ==>  Preparing: UPDATE ai_conversation SET title = CASE WHEN last_message_id IS NULL THEN ? ELSE title END, last_message_id = ? WHERE id = ? AND ( last_message_id IS NULL OR last_message_id < ? )
2026-08-01T16:59:06.998-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.updateAfterPersistedMessage  : ==> Parameters: 什么是这个散列哈希呢?什么是散列哈希呢?MD5 是不是一种加密哈希?不对,是一种哈希方式。SHA256是不是也是一种哈希方式?然后还有一种就是说,对,比如说我输入(String), 15(Long), [B@51f5cf37(byte[]), 15(Long)
2026-08-01T16:59:06.999-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.updateAfterPersistedMessage  : <==    Updates: 1
2026-08-01T16:59:07.000-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.ai.AiModelUsageMapper.settle     : ==>  Preparing: UPDATE ai_model_usage SET billing_status = ?, prompt_tokens = ?, completion_tokens = ?, cached_prompt_tokens = ?, reasoning_tokens = ?, charged_quota_minor = ?, finish_reason = ?, failure_code = ?, settled_at = ? WHERE id = ? AND billing_status = ?
2026-08-01T16:59:07.001-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.ai.AiModelUsageMapper.settle     : ==> Parameters: 1(Integer), 2553(Long), 1967(Long), 0(Long), 516(Long), 14(Long), STOP(String), null, 2026-08-01T21:59:07.000994800Z(OffsetDateTime), [B@3e929cfe(byte[]), 0(Integer)
2026-08-01T16:59:07.002-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.ai.AiModelUsageMapper.settle     : <==    Updates: 1
2026-08-01T16:59:07.002-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.finalizeDetail               : ==>  Preparing: UPDATE ai_model_usage_detail SET conversation_message_id = ?, upstream_request_id = ?, settlement_delta_minor = ? WHERE usage_id = ?
2026-08-01T16:59:07.003-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.finalizeDetail               : ==> Parameters: 15(Long), resp_05d350a2f7f84fe4016a6e6bfad960819489ca79576301caa4(String), -229(Long), [B@7a15c02f(byte[])
2026-08-01T16:59:07.004-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.finalizeDetail               : <==    Updates: 1
2026-08-01T16:59:07.005-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.completeBilling              : ==>  Preparing: UPDATE ai_conversation_generation SET generation_status = ?, settled_at = ?, updated_at = ? WHERE id = ? AND generation_status = ? AND terminal_version = ?
2026-08-01T16:59:07.006-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.completeBilling              : ==> Parameters: 4(Integer), 2026-08-01T21:59:07.005386300Z(OffsetDateTime), 2026-08-01T21:59:07.005386300Z(OffsetDateTime), [B@6d7a5c38(byte[]), 3(Integer), 1(Integer)
2026-08-01T16:59:07.007-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#3-1] c.e.t.m.a.A.completeBilling              : <==    Updates: 1
2026-08-01T16:59:07.112-05:00  INFO 48252 --- [ai-temperate] [enerContainer-3] tionStreamTransportDiagnosticServiceImpl : event=ai_stream_redis_observer_received occurredAt=2026-08-01T21:59:07.112006100Z elapsedMs=33387 traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER details={revision=2, generationPublicId=AZ-_VgWXAQE1RnE7rMLXVQ, eventType=completed}
2026-08-01T16:59:07.113-05:00  INFO 48252 --- [ai-temperate] [enerContainer-3] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_window traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER boundary=SSE_EVENT_READY elapsedMs=33388 silenceBeforeMs=730 windowMs=1345 chunkCount=4 textChars=2639 schedulerDelayMaxMs=0 pendingChunkMax=0 thread=serviceRedisMessageListenerContainer-3 burst=false terminal=false
2026-08-01T16:59:07.113-05:00  INFO 48252 --- [ai-temperate] [enerContainer-3] rsationStreamTimingDiagnosticServiceImpl : event=ai_stream_timing_summary traceId=unavailable usagePublicId=AZ-_VgVbAQGNl6C9NWfy-A conversationPublicId=AZ--xAApAQEVvSwSVb464A modelPublicId=ARAYbDiCEAA path=ASYNC_GENERATION_OBSERVER outcome=COMPLETE failureType=none elapsedMs=33388 firstRawMs=-1 firstVisibleTextMs=32772 rawChunks=0 schedulerChunks=0 batcherChunks=0 sseEvents=37 textChars=2639 schedulerDelayMaxMs=0 pendingChunkMax=0 schedulerPairingEnabled=true
2026-08-01T16:59:07.119-05:00 DEBUG 48252 --- [ai-temperate] [enerContainer-3] c.e.t.m.a.A.detachObserver               : ==>  Preparing: UPDATE ai_conversation_generation SET observer_status = ?, detached_at = COALESCE(detached_at, ?), updated_at = ? WHERE id = ? AND login_identity_id = ? AND observer_epoch = ? AND observer_status = ?
2026-08-01T16:59:07.120-05:00 DEBUG 48252 --- [ai-temperate] [0.1-6655-exec-9] c.e.t.m.u.i.U.findAuthenticationById     : ==>  Preparing: SELECT uli.id AS identity_id, uli.password_hash, uli.password_version, CASE up.account_status WHEN 0 THEN 'ACTIVE' WHEN 1 THEN 'FROZEN' WHEN 2 THEN 'DISABLED' ELSE 'DISABLED' END AS account_status, up.display_name , uli.email , uli.phone FROM userloginidentity uli LEFT JOIN user_profile up ON up.login_identity_id = uli.id WHERE uli.id = ?
2026-08-01T16:59:07.121-05:00 DEBUG 48252 --- [ai-temperate] [enerContainer-3] c.e.t.m.a.A.detachObserver               : ==> Parameters: 1(Integer), 2026-08-01T21:59:07.116485Z(OffsetDateTime), 2026-08-01T21:59:07.116485Z(OffsetDateTime), [B@63c316f7(byte[]), 76721355290185728(Long), 1(Long), 0(Integer)
2026-08-01T16:59:07.122-05:00 DEBUG 48252 --- [ai-temperate] [0.1-6655-exec-9] c.e.t.m.u.i.U.findAuthenticationById     : ==> Parameters: 76721355290185728(Long)
2026-08-01T16:59:07.124-05:00 DEBUG 48252 --- [ai-temperate] [enerContainer-3] c.e.t.m.a.A.detachObserver               : <==    Updates: 1
2026-08-01T16:59:07.129-05:00 DEBUG 48252 --- [ai-temperate] [0.1-6655-exec-9] c.e.t.m.u.i.U.findAuthenticationById     : <==      Total: 1
2026-08-01T16:59:07.858-05:00 DEBUG 48252 --- [ai-temperate] [.1-6655-exec-10] c.e.t.m.u.i.U.findAuthenticationById     : ==>  Preparing: SELECT uli.id AS identity_id, uli.password_hash, uli.password_version, CASE up.account_status WHEN 0 THEN 'ACTIVE' WHEN 1 THEN 'FROZEN' WHEN 2 THEN 'DISABLED' ELSE 'DISABLED' END AS account_status, up.display_name , uli.email , uli.phone FROM userloginidentity uli LEFT JOIN user_profile up ON up.login_identity_id = uli.id WHERE uli.id = ?
2026-08-01T16:59:07.859-05:00 DEBUG 48252 --- [ai-temperate] [.1-6655-exec-10] c.e.t.m.u.i.U.findAuthenticationById     : ==> Parameters: 76721355290185728(Long)
2026-08-01T16:59:07.860-05:00 DEBUG 48252 --- [ai-temperate] [.1-6655-exec-10] c.e.t.m.u.i.U.findAuthenticationById     : <==      Total: 1
2026-08-01T16:59:07.862-05:00 DEBUG 48252 --- [ai-temperate] [.1-6655-exec-10] c.e.t.m.a.A.findActivePage               : ==>  Preparing: SELECT id, login_identity_id, is_active, title, last_message_id, last_compacted_message_id, compacted_context::TEXT AS compacted_context_json, created_at FROM ai_conversation WHERE login_identity_id = ? AND is_active = TRUE AND last_message_id IS NOT NULL ORDER BY last_message_id DESC, id DESC LIMIT ?
2026-08-01T16:59:07.862-05:00 DEBUG 48252 --- [ai-temperate] [.1-6655-exec-10] c.e.t.m.a.A.findActivePage               : ==> Parameters: 76721355290185728(Long), 21(Integer)      
2026-08-01T16:59:07.863-05:00 DEBUG 48252 --- [ai-temperate] [.1-6655-exec-10] c.e.t.m.a.A.findActivePage               : <==      Total: 7
2026-08-01T16:59:08.339-05:00 DEBUG 48252 --- [ai-temperate] [0.1-6655-exec-4] c.e.t.m.u.i.U.findAuthenticationById     : ==>  Preparing: SELECT uli.id AS identity_id, uli.password_hash, uli.password_version, CASE up.account_status WHEN 0 THEN 'ACTIVE' WHEN 1 THEN 'FROZEN' WHEN 2 THEN 'DISABLED' ELSE 'DISABLED' END AS account_status, up.display_name , uli.email , uli.phone FROM userloginidentity uli LEFT JOIN user_profile up ON up.login_identity_id = uli.id WHERE uli.id = ?
2026-08-01T16:59:08.340-05:00 DEBUG 48252 --- [ai-temperate] [0.1-6655-exec-4] c.e.t.m.u.i.U.findAuthenticationById     : ==> Parameters: 76721355290185728(Long)
2026-08-01T16:59:08.340-05:00 DEBUG 48252 --- [ai-temperate] [0.1-6655-exec-4] c.e.t.m.u.i.U.findAuthenticationById     : <==      Total: 1
2026-08-01T16:59:08.342-05:00 DEBUG 48252 --- [ai-temperate] [0.1-6655-exec-4] c.e.t.m.a.A.findActiveOwned              : ==>  Preparing: SELECT id, login_identity_id, is_active, title, last_message_id, last_compacted_message_id, compacted_context::TEXT AS compacted_context_json, created_at FROM ai_conversation WHERE id = ? AND login_identity_id = ? AND is_active = TRUE
2026-08-01T16:59:08.342-05:00 DEBUG 48252 --- [ai-temperate] [0.1-6655-exec-4] c.e.t.m.a.A.findActiveOwned              : ==> Parameters: [B@6e74da34(byte[]), 76721355290185728(Long)
2026-08-01T16:59:08.343-05:00 DEBUG 48252 --- [ai-temperate] [0.1-6655-exec-4] c.e.t.m.a.A.findActiveOwned              : <==      Total: 1
2026-08-01T16:59:08.344-05:00 DEBUG 48252 --- [ai-temperate] [0.1-6655-exec-4] c.e.t.m.a.A.findOwnedHistoryPage         : ==>  Preparing: SELECT message.id AS message_id, message.conversation_id, message.content_text, message.content_attachments::TEXT AS content_attachments_json, message.question_tokens, message.response_attachments::TEXT AS response_attachments_json, message.created_at, usage.id AS usage_id, usage.ai_model_id, model.model_name, usage.prompt_tokens, usage.cached_prompt_tokens, usage.completion_tokens, usage.reasoning_tokens, usage.charged_quota_minor, usage.finish_reason FROM ai_conversation_message message INNER JOIN ai_conversation conversation ON conversation.id = message.conversation_id INNER JOIN ai_model_usage_detail detail ON detail.conversation_message_id = message.id AND detail.conversation_id = message.conversation_id INNER JOIN ai_model_usage usage ON usage.id = detail.usage_id AND usage.login_identity_id = conversation.login_identity_id INNER JOIN ai_model model ON model.id = usage.ai_model_id WHERE message.conversation_id = ? AND conversation.login_identity_id = ? AND conversation.is_active = TRUE ORDER BY message.id DESC LIMIT ?
2026-08-01T16:59:08.345-05:00 DEBUG 48252 --- [ai-temperate] [0.1-6655-exec-4] c.e.t.m.a.A.findOwnedHistoryPage         : ==> Parameters: [B@6e74da34(byte[]), 76721355290185728(Long), 51(Integer)
2026-08-01T16:59:08.348-05:00 DEBUG 48252 --- [ai-temperate] [0.1-6655-exec-4] c.e.t.m.a.A.findOwnedHistoryPage         : <==      Total: 3
2026-08-01T16:59:24.988-05:00  INFO 48252 --- [ai-temperate] [resence-bloom-1] t.s.a.m.d.MailInspectionDiagnosticAspect : event=admin_mail_inspection_operation operation=lease-refresh outcome=success durationMs=1
2026-08-01T16:59:37.132-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#4-1] c.e.t.m.a.A.findByIdForUpdate            : ==>  Preparing: SELECT id, login_identity_id, conversation_id, usage_id, idempotency_key_digest, model_id, generation_status, observer_status, observer_epoch, owner_instance_id, cancel_source, terminal_type, terminal_reason, terminal_version, created_at, started_at, detached_at, cancel_requested_at, terminal_at, settled_at, updated_at FROM ai_conversation_generation WHERE id = ? FOR UPDATE
2026-08-01T16:59:37.132-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#4-1] c.e.t.m.a.A.findByIdForUpdate            : ==> Parameters: [B@9f6ffeb(byte[])
2026-08-01T16:59:37.134-05:00 DEBUG 48252 --- [ai-temperate] [ntContainer#4-1] c.e.t.m.a.A.findByIdForUpdate            : <==      Total: 1
2026-08-01T16:59:54.992-05:00  INFO 48252 --- [ai-temperate] [resence-bloom-1] t.s.a.m.d.MailInspectionDiagnosticAspect : event=admin_mail_inspection_operation operation=lease-refresh outcome=success durationMs=3
2026-08-01T16:59:56.831-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] markExpiredReservationsForReconciliation : ==>  Preparing: WITH expired AS ( SELECT id FROM ai_model_usage WHERE billing_status = ? AND created_at <= ? ORDER BY created_at ASC, id ASC LIMIT ? FOR UPDATE SKIP LOCKED ) UPDATE ai_model_usage usage SET billing_status = ?, failure_code = ?, settled_at = ? FROM expired WHERE usage.id = expired.id AND usage.billing_status = ?
2026-08-01T16:59:56.831-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] markExpiredReservationsForReconciliation : ==> Parameters: 0(Integer), 2026-08-01T21:42:56.831028500Z(OffsetDateTime), 500(Integer), 3(Integer), AI_RESERVED_EXPIRED(String), 2026-08-01T21:59:56.831028500Z(OffsetDateTime), 0(Integer)
2026-08-01T16:59:56.832-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] markExpiredReservationsForReconciliation : <==    Updates: 0
2026-08-01T16:59:56.833-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] c.e.t.m.a.A.findRecoveryCandidates       : ==>  Preparing: SELECT id, login_identity_id, conversation_id, usage_id, idempotency_key_digest, model_id, generation_status, observer_status, observer_epoch, owner_instance_id, cancel_source, terminal_type, terminal_reason, terminal_version, created_at, started_at, detached_at, cancel_requested_at, terminal_at, settled_at, updated_at FROM ai_conversation_generation WHERE ( generation_status IN ( ?, ?, ? ) AND updated_at <= ? ) OR ( generation_status = ? AND updated_at <= ? ) OR ( generation_status IN ( ?, ? ) AND observer_status = ? AND detached_at <= ? ) ORDER BY updated_at ASC, id ASC LIMIT ?
2026-08-01T16:59:56.833-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] c.e.t.m.a.A.findRecoveryCandidates       : ==> Parameters: 0(Integer), 2(Integer), 3(Integer), 2026-08-01T21:58:56.832640Z(OffsetDateTime), 1(Integer), 2026-08-01T21:43:56.832640Z(OffsetDateTime), 0(Integer), 1(Integer), 1(Integer), 2026-08-01T21:59:26.832640Z(OffsetDateTime), 500(Integer)
2026-08-01T16:59:56.833-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] c.e.t.m.a.A.findRecoveryCandidates       : <==      Total: 0
2026-08-01T16:59:56.834-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] .e.t.m.a.A.findTerminalCleanupCandidates : ==>  Preparing: SELECT id, login_identity_id, conversation_id, usage_id, idempotency_key_digest, model_id, generation_status, observer_status, observer_epoch, owner_instance_id, cancel_source, terminal_type, terminal_reason, terminal_version, created_at, started_at, detached_at, cancel_requested_at, terminal_at, settled_at, updated_at FROM ai_conversation_generation WHERE generation_status IN ( ? , ? ) AND updated_at <= ? ORDER BY updated_at ASC, id ASC LIMIT ? FOR UPDATE SKIP LOCKED
2026-08-01T16:59:56.834-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] .e.t.m.a.A.findTerminalCleanupCandidates : ==> Parameters: 4(Integer), 5(Integer), 2026-07-31T21:59:56.833750200Z(OffsetDateTime), 500(Integer)
2026-08-01T16:59:56.835-05:00 DEBUG 48252 --- [ai-temperate] [resence-bloom-1] .e.t.m.a.A.findTerminalCleanupCandidates : <==      Total: 0
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
			|| AI_CONVERSATION_MESSAGES_PATH.test(url.pathname)
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

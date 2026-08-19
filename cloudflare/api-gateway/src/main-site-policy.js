import { H5_ASSET_PATHS } from './generated/h5-assets.js'

export const H5_PAGE_PATHS = Object.freeze([
	'/',
	'/pages/launch/session-gate',
	'/pages/auth/login',
	'/pages/auth/totp-login',
	'/pages/auth/register',
	'/pages/auth/password-reset',
	'/pages/ai-chat/index',
	'/pages/account/profile',
	'/pages/account/api-keys',
	'/pages/account/totp-security',
	'/pages/ai-models/catalog',
	'/pages/ai-models/detail',
	'/pages/risk/challenge-complete',
	'/pages/risk/challenge-failed',
	'/pages/risk/blocked',
	'/pages/risk/webrtc-failed'
])

const H5_PAGE_SET = new Set(H5_PAGE_PATHS)
const H5_ASSET_SET = new Set(H5_ASSET_PATHS)
const PUBLIC_ID_11 = '[A-Za-z0-9_-]{11}'
const PUBLIC_ID_22 = '[A-Za-z0-9_-]{22}'
const PREUPLOAD_ID_24 = '[A-Za-z0-9_-]{24}'
const PUBLIC_ID_11_PATTERN = /^[A-Za-z0-9_-]{11}$/

function isCanonicalPositiveLongPublicId(value) {
	// 正 Long 的首位不能带符号位；8 字节 Base64URL 的末位只允许四个有效数据位，且零值必须拒绝。
	return PUBLIC_ID_11_PATTERN.test(value)
		&& /^[A-Za-f]/.test(value)
		&& /[AEIMQUYcgkosw048]$/.test(value)
		&& value !== 'AAAAAAAAAAA'
}

function route(path, allowedMethods, options = {}) {
	const {
		clientPlatforms = ['H5', 'ANDROID'],
		errorResponseType = 'API_JSON',
		...features
	} = options
	return Object.freeze({
		path,
		allowedMethods: Object.freeze([...allowedMethods]),
		clientPlatforms: Object.freeze([...clientPlatforms]),
		errorResponseType,
		surface: 'root',
		migration: false,
		...features
	})
}

const EXACT_ROOT_ROUTES = new Map([
	route('/v1/chat/completions', ['POST'], {
		clientPlatforms: ['API_KEY_CLIENT'],
		errorResponseType: 'OPENAI_JSON',
		streaming: true,
		responseMode: 'adaptive',
		protocol: 'chat_completions',
		apiKeySdk: true,
		routeTemplate: '/v1/chat/completions'
	}),
	route('/v1/responses', ['POST'], {
		clientPlatforms: ['API_KEY_CLIENT'],
		errorResponseType: 'OPENAI_JSON',
		streaming: true,
		responseMode: 'adaptive',
		protocol: 'responses',
		apiKeySdk: true,
		routeTemplate: '/v1/responses'
	}),
	route('/v1/models', ['GET'], {
		clientPlatforms: ['API_KEY_CLIENT'],
		errorResponseType: 'OPENAI_JSON',
		streaming: false,
		apiKeySdk: true,
		routeTemplate: '/v1/models'
	}),
	route('/__edge/android-clearance', ['GET'], {
		clientPlatforms: ['ANDROID'],
		androidClearance: 'page'
	}),
	route('/__edge/android-clearance/status', ['GET'], {
		clientPlatforms: ['ANDROID'],
		androidClearance: 'status'
	}),
	route('/api/_edge/cookie-scope', ['POST'], {
		clientPlatforms: ['H5'],
		migration: true
	}),
	route('/api/_edge/risk-challenge', ['GET'], { riskChallenge: true }),
	route('/api/_edge/pre-auth', ['POST']),
	route('/api/_edge/webrtc/start', ['GET']),
	route('/api/_edge/webrtc/report', ['POST']),
	route('/ws/voice', ['GET'], {
		webSocket: true,
		routeTemplate: '/ws/voice'
	}),
	route('/api/health', ['GET']),
	route('/api/auth/csrf', ['GET']),
	route('/api/auth/phone-country', ['GET']),
	route('/api/auth/turnstile/config', ['GET']),
	route('/api/auth/turnstile/page', ['GET']),
	route('/api/auth/turnstile/page.css', ['GET'], {
		credentiallessVerificationAsset: true
	}),
	route('/api/auth/turnstile/page.js', ['GET'], {
		credentiallessVerificationAsset: true
	}),
	route('/api/auth/login/password', ['POST']),
	route('/api/auth/login/code/start', ['POST']),
	route('/api/auth/login/code/turnstile', ['POST']),
	route('/api/auth/login/code/send', ['POST']),
	route('/api/auth/login/code/verify', ['POST']),
	route('/api/auth/login/totp/verify', ['POST']),
	route('/api/auth/register/start', ['POST']),
	route('/api/auth/register/status', ['GET']),
	route('/api/auth/register/turnstile', ['POST']),
	route('/api/auth/register/codes/email/send', ['POST']),
	route('/api/auth/register/codes/sms/send', ['POST']),
	route('/api/auth/register/codes/phone/send', ['POST']),
	route('/api/auth/register/codes/verify', ['POST']),
	route('/api/auth/register/complete', ['POST']),
	route('/api/auth/password-reset/start', ['POST']),
	route('/api/auth/password-reset/turnstile', ['POST']),
	route('/api/auth/password-reset/send', ['POST']),
	route('/api/auth/password-reset/verify', ['POST']),
	route('/api/auth/password-reset/complete', ['POST']),
	route('/api/auth/session/bootstrap', ['POST']),
	route('/api/auth/session/logout', ['POST']),
	route('/api/auth/session/logout-all', ['POST']),
	route('/api/users/me', ['GET']),
	route('/api/users/me/voice/session-tickets', ['POST']),
	route('/api/users/me/avatar/preuploads', ['POST']),
	route('/api/users/me/security/totp', ['GET']),
	route('/api/users/me/security/totp/reverification/password', ['POST']),
	route('/api/users/me/security/totp/reverification/code/start', ['POST']),
	route('/api/users/me/security/totp/reverification/code/turnstile', ['POST']),
	route('/api/users/me/security/totp/reverification/code/send', ['POST']),
	route('/api/users/me/security/totp/reverification/code/verify', ['POST']),
	route('/api/users/me/security/totp/setup/start', ['POST']),
	route('/api/users/me/security/totp/setup/confirm', ['POST']),
	route('/api/users/me/security/totp/disable', ['POST']),
	route('/api/users/me/api-keys', ['GET', 'POST'], {
		apiKeyManagement: true
	}),
	route('/api/ai-models', ['GET']),
	route('/api/ai/conversations', ['GET']),
	route('/api/ai/conversations/responses', ['POST'], {
		streaming: true,
		routeTemplate: '/api/ai/conversations/responses'
	}),
	route('/api/ai/conversations/responses/cancel', ['POST']),
	route('/api/ai/conversation-attachments/preuploads', ['POST']),
	route('/api/ai/conversations/generations', ['GET']),
	route('/api/ai/conversations/generations/by-idempotency', ['GET'])
].map(item => [item.path, item]))

function templateRoute(pattern, invalidPattern, allowedMethods, options = {}) {
	const {
		clientPlatforms = ['H5', 'ANDROID'],
		errorResponseType = 'API_JSON',
		parameterType,
		...features
	} = options
	return Object.freeze({
		pattern,
		invalidPattern,
		allowedMethods: Object.freeze([...allowedMethods]),
		clientPlatforms: Object.freeze([...clientPlatforms]),
		errorResponseType,
		parameterType,
		surface: 'root',
		migration: false,
		...features
	})
}

const TEMPLATE_ROOT_ROUTES = Object.freeze([
	templateRoute(
		new RegExp(`^/api/users/me/api-keys/(${PUBLIC_ID_11})$`),
		/^\/api\/users\/me\/api-keys\/[^/]+$/,
		['GET', 'PUT', 'DELETE'],
		{
			apiKeyManagement: true,
			parameterType: 'PUBLIC_LONG_BASE64URL_11',
			validateMatch: match => isCanonicalPositiveLongPublicId(match[1])
		}),
	templateRoute(
		new RegExp(`^/api/users/me/api-keys/(${PUBLIC_ID_11})/models$`),
		/^\/api\/users\/me\/api-keys\/[^/]+\/models$/,
		['PUT'],
		{
			apiKeyManagement: true,
			parameterType: 'PUBLIC_LONG_BASE64URL_11',
			validateMatch: match => isCanonicalPositiveLongPublicId(match[1])
		}),
	templateRoute(
		new RegExp(`^/api/users/me/avatar/preuploads/${PREUPLOAD_ID_24}$`),
		/^\/api\/users\/me\/avatar\/preuploads\/[^/]+$/,
		['DELETE'],
		{ parameterType: 'NANOID_24' }),
	templateRoute(
		new RegExp(`^/api/users/me/avatar/preuploads/${PREUPLOAD_ID_24}/confirm$`),
		/^\/api\/users\/me\/avatar\/preuploads\/[^/]+\/confirm$/,
		['POST'],
		{ parameterType: 'NANOID_24' }),
	templateRoute(
		new RegExp(`^/api/ai-models/(${PUBLIC_ID_11})$`),
		/^\/api\/ai-models\/[^/]+$/,
		['GET'],
		{
			parameterType: 'PUBLIC_LONG_BASE64URL_11',
			validateMatch: match => isCanonicalPositiveLongPublicId(match[1])
		}),
	templateRoute(
		new RegExp(`^/api/ai/conversations/${PUBLIC_ID_22}/messages$`),
		/^\/api\/ai\/conversations\/[^/]+\/messages$/,
		['GET'],
		{ parameterType: 'PUBLIC_ID_22' }),
	templateRoute(
		new RegExp(`^/api/ai/conversations/${PUBLIC_ID_22}/responses$`),
		/^\/api\/ai\/conversations\/[^/]+\/responses$/,
		['POST'],
		{
			parameterType: 'PUBLIC_ID_22',
			streaming: true,
			routeTemplate: '/api/ai/conversations/{conversationId}/responses'
		}),
	templateRoute(
		new RegExp(`^/api/ai/conversations/${PUBLIC_ID_22}/context-usage$`),
		/^\/api\/ai\/conversations\/[^/]+\/context-usage$/,
		['GET'],
		{ parameterType: 'PUBLIC_ID_22' }),
	templateRoute(
		new RegExp(`^/api/ai/conversations/${PUBLIC_ID_22}/compactions$`),
		/^\/api\/ai\/conversations\/[^/]+\/compactions$/,
		['POST'],
		{ parameterType: 'PUBLIC_ID_22' }),
	templateRoute(
		new RegExp(`^/api/ai/conversations/${PUBLIC_ID_22}/context/events$`),
		/^\/api\/ai\/conversations\/[^/]+\/context\/events$/,
		['GET'],
		{
			parameterType: 'PUBLIC_ID_22',
			streaming: true,
			routeTemplate: '/api/ai/conversations/{conversationId}/context/events'
		}),
	templateRoute(
		new RegExp(`^/api/ai/conversations/generations/(${PUBLIC_ID_22})/events$`),
		/^\/api\/ai\/conversations\/generations\/[^/]+\/events$/,
		['GET'],
		{
			streaming: true,
			parameterType: 'PUBLIC_ID_22',
			captureGenerationPublicId: true,
			routeTemplate: '/api/ai/conversations/generations/{generationId}/events'
		}),
	templateRoute(
		new RegExp(`^/api/ai/conversations/generations/${PUBLIC_ID_22}/stream-diagnostics$`),
		/^\/api\/ai\/conversations\/generations\/[^/]+\/stream-diagnostics$/,
		['POST'],
		{ parameterType: 'PUBLIC_ID_22' }),
	templateRoute(
		new RegExp(`^/api/ai/conversations/generations/${PUBLIC_ID_22}$`),
		/^\/api\/ai\/conversations\/generations\/[^/]+$/,
		['GET'],
		{ parameterType: 'PUBLIC_ID_22' }),
	templateRoute(
		new RegExp(`^/api/ai/conversations/generations/${PUBLIC_ID_22}/cancel$`),
		/^\/api\/ai\/conversations\/generations\/[^/]+\/cancel$/,
		['POST'],
		{ parameterType: 'PUBLIC_ID_22' })
])

export function isH5PagePath(pathname) {
	return H5_PAGE_SET.has(pathname)
}

export function isH5AssetPath(pathname) {
	return H5_ASSET_SET.has(pathname)
}

export function matchRootApiRoute(pathname) {
	const exact = EXACT_ROOT_ROUTES.get(pathname)
	if (exact) return exact

	for (const candidate of TEMPLATE_ROOT_ROUTES) {
		const match = pathname.match(candidate.pattern)
		if (match) {
			if (candidate.validateMatch && !candidate.validateMatch(match)) {
				return invalidApiParameter()
			}
			return candidate.captureGenerationPublicId
				? { ...candidate, generationPublicId: match[1] }
				: candidate
		}
	}
	for (const candidate of TEMPLATE_ROOT_ROUTES) {
		if (candidate.invalidPattern.test(pathname)) {
			return invalidApiParameter()
		}
	}
	return null
}

function invalidApiParameter() {
	return Object.freeze({
		allowed: false,
		status: 400,
		code: 'INVALID_INPUT',
		category: 'API_PARAMETER_INVALID',
		surface: 'root'
	})
}

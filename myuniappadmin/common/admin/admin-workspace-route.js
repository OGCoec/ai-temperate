export const ADMIN_WORKSPACE_PATH = '/pages/admin/workspace'
export const ADMIN_WORKSPACE_DEFAULT_VIEW = 'dashboard'
export const ADMIN_WORKSPACE_VIEWS = Object.freeze([
	'dashboard',
	'ai-models',
	'ai-model-discovery',
	'ai-model-create',
	'ai-model-detail',
	'ai-model-icons',
	'ip2location-keys',
	'mail-openai',
	'mail-kiro',
	'mail-ip2location'
])

const VIEW_SET = new Set(ADMIN_WORKSPACE_VIEWS)
const IP2LOCATION_MODES = new Set(['registration', 'verify-link'])
const MODEL_PUBLIC_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/

const LEGACY_VIEW_BY_PATH = Object.freeze({
	'/pages/ai-models/index': 'ai-models',
	'/pages/ai-models/create': 'ai-model-create',
	'/pages/ai-models/detail': 'ai-model-detail',
	'/pages/ai-model-icons/index': 'ai-model-icons',
	'/pages/risk/ip2location-keys': 'ip2location-keys',
	'/pages/mail-inspection/openai/index': 'mail-openai',
	'/pages/mail-inspection/kiro/index': 'mail-kiro',
	'/pages/mail-inspection/ip2location/index': 'mail-ip2location'
})

function decodeQueryPart(value) {
	try {
		return decodeURIComponent(String(value || '').replace(/\+/g, ' '))
	} catch (_error) {
		return ''
	}
}

function parseQuery(rawQuery) {
	const result = Object.create(null)
	for (const part of String(rawQuery || '').replace(/^\?/, '').split('&')) {
		if (!part) continue
		const separator = part.indexOf('=')
		const key = decodeQueryPart(separator >= 0 ? part.slice(0, separator) : part)
		if (!key || Object.prototype.hasOwnProperty.call(result, key)) continue
		result[key] = decodeQueryPart(separator >= 0 ? part.slice(separator + 1) : '')
	}
	return result
}

function splitRoute(value) {
	const raw = String(value || '').split('#', 1)[0]
	const separator = raw.indexOf('?')
	const path = (separator >= 0 ? raw.slice(0, separator) : raw).replace(/\/+$/, '') || '/'
	return {
		path: path.startsWith('/') ? path : `/${path}`,
		query: parseQuery(separator >= 0 ? raw.slice(separator + 1) : '')
	}
}

export function isAdminModelPublicId(value) {
	return MODEL_PUBLIC_ID_PATTERN.test(String(value || ''))
}

/**
 * 将任何外部路由参数收敛到工作台公开契约，避免未知视图或敏感查询参数进入业务面板。
 */
export function normalizeAdminWorkspaceLocation(value = {}) {
	const requestedView = String(value.view || '')
	let view = VIEW_SET.has(requestedView) ? requestedView : ADMIN_WORKSPACE_DEFAULT_VIEW
	let mode = ''
	let publicId = ''
	let corrected = view !== requestedView
	let notice = corrected && requestedView
		? '无法打开指定页面，已返回管理员控制台。'
		: ''

	if (view === 'mail-ip2location') {
		const requestedMode = String(value.mode || '')
		mode = IP2LOCATION_MODES.has(requestedMode) ? requestedMode : 'registration'
		corrected = corrected || (requestedMode !== '' && requestedMode !== mode)
	} else if (value.mode) {
		corrected = true
	}

	if (view === 'ai-model-detail') {
		if (isAdminModelPublicId(value.publicId)) {
			publicId = String(value.publicId)
		} else {
			view = 'ai-models'
			corrected = true
			notice = '模型标识无效，已返回模型目录。'
		}
	} else if (value.publicId) {
		corrected = true
	}

	return { view, mode, publicId, corrected, notice }
}

export function buildAdminWorkspaceUrl(value = {}) {
	const location = normalizeAdminWorkspaceLocation(value)
	const query = [`view=${encodeURIComponent(location.view)}`]
	if (location.view === 'mail-ip2location') {
		query.push(`mode=${encodeURIComponent(location.mode)}`)
	}
	if (location.view === 'ai-model-detail') {
		query.push(`publicId=${encodeURIComponent(location.publicId)}`)
	}
	return `${ADMIN_WORKSPACE_PATH}?${query.join('&')}`
}

export function parseAdminWorkspaceUrl(value) {
	const { query } = splitRoute(value)
	return normalizeAdminWorkspaceLocation(query)
}

export function legacyAdminRouteToWorkspaceUrl(value) {
	const { path, query } = splitRoute(value)
	const view = LEGACY_VIEW_BY_PATH[path]
	if (!view) return buildAdminWorkspaceUrl({ view: ADMIN_WORKSPACE_DEFAULT_VIEW })
	return buildAdminWorkspaceUrl({ view, mode: query.mode, publicId: query.publicId })
}

export function isAdminWorkspaceUrl(value) {
	return splitRoute(value).path === ADMIN_WORKSPACE_PATH
}

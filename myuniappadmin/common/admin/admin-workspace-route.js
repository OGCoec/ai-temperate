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
const MODEL_DETAIL_FRAGMENT = '/ai-models/'
const INVALID_WORKSPACE_NOTICE = '无法打开指定页面，已返回管理员控制台。'
const INVALID_MODEL_NOTICE = '模型标识无效，已返回模型目录。'

const STATIC_LOCATION_BY_FRAGMENT = new Map([
	['', { view: 'dashboard', mode: '', publicId: '' }],
	['/ai-models', { view: 'ai-models', mode: '', publicId: '' }],
	[
		'/ai-models/discovery',
		{ view: 'ai-model-discovery', mode: '', publicId: '' }
	],
	[
		'/ai-models/new',
		{ view: 'ai-model-create', mode: '', publicId: '' }
	],
	[
		'/ai-model-icons',
		{ view: 'ai-model-icons', mode: '', publicId: '' }
	],
	[
		'/ip2location/keys',
		{ view: 'ip2location-keys', mode: '', publicId: '' }
	],
	[
		'/mail-inspection/openai',
		{ view: 'mail-openai', mode: '', publicId: '' }
	],
	[
		'/mail-inspection/kiro',
		{ view: 'mail-kiro', mode: '', publicId: '' }
	],
	[
		'/mail-inspection/ip2location/registration',
		{ view: 'mail-ip2location', mode: 'registration', publicId: '' }
	],
	[
		'/mail-inspection/ip2location/verify-link',
		{ view: 'mail-ip2location', mode: 'verify-link', publicId: '' }
	]
])

function analyzeRoute(value) {
	const raw = String(value || '')
	const hashIndex = raw.indexOf('#')
	const beforeHash = hashIndex >= 0 ? raw.slice(0, hashIndex) : raw
	const queryIndex = beforeHash.indexOf('?')
	const rawPath = queryIndex >= 0 ? beforeHash.slice(0, queryIndex) : beforeHash
	const path = rawPath.startsWith('/') ? rawPath : `/${rawPath}`
	const rawFragment = hashIndex >= 0 ? raw.slice(hashIndex + 1) : ''
	const trailingSlash = rawFragment.length > 1 && rawFragment.endsWith('/')
	const fragment = trailingSlash ? rawFragment.slice(0, -1) : rawFragment
	return {
		path: path || '/',
		fragment,
		corrected: queryIndex >= 0 || trailingSlash || rawFragment === '/',
		unsafe: path !== ADMIN_WORKSPACE_PATH
			|| /\/{2,}/.test(rawFragment)
			|| rawFragment.includes('\\')
			|| rawFragment.includes('%')
			|| rawFragment.includes('?')
			|| rawFragment.includes('#')
	}
}

function parsedLocation(view, {
	mode = '',
	publicId = '',
	corrected = false,
	notice = ''
} = {}) {
	return { view, mode, publicId, corrected, notice }
}

function invalidWorkspaceLocation() {
	return parsedLocation('dashboard', {
		corrected: true,
		notice: INVALID_WORKSPACE_NOTICE
	})
}

function invalidModelLocation() {
	return parsedLocation('ai-models', {
		corrected: true,
		notice: INVALID_MODEL_NOTICE
	})
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
			notice = INVALID_MODEL_NOTICE
		}
	} else if (value.publicId) {
		corrected = true
	}

	return { view, mode, publicId, corrected, notice }
}

export function buildAdminWorkspaceUrl(value = {}) {
	const location = normalizeAdminWorkspaceLocation(value)
	switch (location.view) {
		case 'ai-models':
			return `${ADMIN_WORKSPACE_PATH}#/ai-models`
		case 'ai-model-discovery':
			return `${ADMIN_WORKSPACE_PATH}#/ai-models/discovery`
		case 'ai-model-create':
			return `${ADMIN_WORKSPACE_PATH}#/ai-models/new`
		case 'ai-model-detail':
			return `${ADMIN_WORKSPACE_PATH}#${MODEL_DETAIL_FRAGMENT}${location.publicId}`
		case 'ai-model-icons':
			return `${ADMIN_WORKSPACE_PATH}#/ai-model-icons`
		case 'ip2location-keys':
			return `${ADMIN_WORKSPACE_PATH}#/ip2location/keys`
		case 'mail-openai':
			return `${ADMIN_WORKSPACE_PATH}#/mail-inspection/openai`
		case 'mail-kiro':
			return `${ADMIN_WORKSPACE_PATH}#/mail-inspection/kiro`
		case 'mail-ip2location':
			return `${ADMIN_WORKSPACE_PATH}#/mail-inspection/ip2location/${location.mode}`
		default:
			return ADMIN_WORKSPACE_PATH
	}
}

export function parseAdminWorkspaceUrl(value) {
	const route = analyzeRoute(value)
	const staticLocation = STATIC_LOCATION_BY_FRAGMENT.get(route.fragment)
	if (!route.unsafe && staticLocation) {
		return parsedLocation(staticLocation.view, {
			mode: staticLocation.mode,
			publicId: staticLocation.publicId,
			corrected: route.corrected
		})
	}

	if (!route.unsafe && route.fragment.startsWith(MODEL_DETAIL_FRAGMENT)) {
		const publicId = route.fragment.slice(MODEL_DETAIL_FRAGMENT.length)
		if (!route.unsafe && isAdminModelPublicId(publicId)) {
			return parsedLocation('ai-model-detail', {
				publicId,
				corrected: route.corrected
			})
		}
		return invalidModelLocation()
	}
	return invalidWorkspaceLocation()
}

export function legacyAdminRouteToWorkspaceUrl(_value) {
	return ADMIN_WORKSPACE_PATH
}

export function isAdminWorkspaceUrl(value) {
	const route = analyzeRoute(value)
	if (route.unsafe || route.corrected) return false
	if (STATIC_LOCATION_BY_FRAGMENT.has(route.fragment)) return true
	if (!route.fragment.startsWith(MODEL_DETAIL_FRAGMENT)) return false
	return isAdminModelPublicId(route.fragment.slice(MODEL_DETAIL_FRAGMENT.length))
}

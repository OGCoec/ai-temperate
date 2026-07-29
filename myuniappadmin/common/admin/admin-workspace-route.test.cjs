const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'admin-workspace-route.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('workspace accepts only the published view and parameter contract', async () => {
	const { normalizeAdminWorkspaceLocation } = await loadModule()

	assert.deepEqual(normalizeAdminWorkspaceLocation({ view: 'mail-ip2location', mode: 'verify-link' }), {
		view: 'mail-ip2location',
		mode: 'verify-link',
		publicId: '',
		corrected: false,
		notice: ''
	})
	assert.equal(normalizeAdminWorkspaceLocation({ view: 'unknown' }).view, 'dashboard')
	assert.equal(normalizeAdminWorkspaceLocation({ view: 'unknown' }).corrected, true)
	assert.deepEqual(normalizeAdminWorkspaceLocation({ view: 'mail-openai', mode: 'verify-link' }), {
		view: 'mail-openai',
		mode: '',
		publicId: '',
		corrected: true,
		notice: ''
	})
	assert.equal(normalizeAdminWorkspaceLocation({ view: 'mail-ip2location', mode: 'unexpected' }).mode, 'registration')
})

test('invalid model identifiers fall back before a detail request can be sent', async () => {
	const { normalizeAdminWorkspaceLocation } = await loadModule()

	assert.deepEqual(normalizeAdminWorkspaceLocation({ view: 'ai-model-detail', publicId: 'AAAAAAAAAAA' }), {
		view: 'ai-model-detail',
		mode: '',
		publicId: 'AAAAAAAAAAA',
		corrected: false,
		notice: ''
	})
	const invalid = normalizeAdminWorkspaceLocation({ view: 'ai-model-detail', publicId: 'bad=' })
	assert.equal(invalid.view, 'ai-models')
	assert.equal(invalid.publicId, '')
	assert.equal(invalid.corrected, true)
})

test('workspace URLs contain only canonical public parameters in a stable order', async () => {
	const { buildAdminWorkspaceUrl, parseAdminWorkspaceUrl } = await loadModule()

	assert.equal(
		buildAdminWorkspaceUrl({ view: 'ai-model-detail', publicId: 'AAAAAAAAAAA', token: 'secret' }),
		'/pages/admin/workspace?view=ai-model-detail&publicId=AAAAAAAAAAA'
	)
	assert.deepEqual(
		parseAdminWorkspaceUrl('/pages/admin/workspace?mode=verify-link&view=mail-ip2location&token=secret'),
		{
			view: 'mail-ip2location',
			mode: 'verify-link',
			publicId: '',
			corrected: false,
			notice: ''
		}
	)
})

test('every legacy administrator business URL maps to the canonical workspace', async () => {
	const { legacyAdminRouteToWorkspaceUrl } = await loadModule()

	const cases = new Map([
		['/pages/ai-models/index', '/pages/admin/workspace?view=ai-models'],
		['/pages/ai-models/create', '/pages/admin/workspace?view=ai-model-create'],
		['/pages/ai-models/detail?publicId=AAAAAAAAAAA', '/pages/admin/workspace?view=ai-model-detail&publicId=AAAAAAAAAAA'],
		['/pages/ai-model-icons/index', '/pages/admin/workspace?view=ai-model-icons'],
		['/pages/risk/ip2location-keys', '/pages/admin/workspace?view=ip2location-keys'],
		['/pages/mail-inspection/openai/index', '/pages/admin/workspace?view=mail-openai'],
		['/pages/mail-inspection/kiro/index', '/pages/admin/workspace?view=mail-kiro'],
		['/pages/mail-inspection/ip2location/index?mode=verify-link', '/pages/admin/workspace?view=mail-ip2location&mode=verify-link']
	])

	for (const [legacy, expected] of cases) {
		assert.equal(legacyAdminRouteToWorkspaceUrl(legacy), expected, legacy)
	}
})

test('accepts the gateway model discovery view without adding sensitive query parameters', async () => {
	const { buildAdminWorkspaceUrl, normalizeAdminWorkspaceLocation } = await loadModule()

	const location = normalizeAdminWorkspaceLocation({ view: 'ai-model-discovery' })

	assert.equal(location.view, 'ai-model-discovery')
	assert.equal(
		buildAdminWorkspaceUrl({
			view: 'ai-model-discovery',
			apiKey: 'must-not-survive',
			baseUrl: 'https://gateway.invalid'
		}),
		'/pages/admin/workspace?view=ai-model-discovery')
})

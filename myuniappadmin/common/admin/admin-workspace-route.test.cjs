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

test('workspace URLs keep the registered pathname and encode panels in the fragment', async () => {
	const { buildAdminWorkspaceUrl, parseAdminWorkspaceUrl } = await loadModule()

	const cases = [
		[{ view: 'dashboard' }, '/pages/admin/workspace'],
		[{ view: 'ai-models' }, '/pages/admin/workspace#/ai-models'],
		[{ view: 'ai-model-discovery' }, '/pages/admin/workspace#/ai-models/discovery'],
		[{ view: 'ai-model-create' }, '/pages/admin/workspace#/ai-models/new'],
		[
			{ view: 'ai-model-detail', publicId: 'AAAAAAAAAAA' },
			'/pages/admin/workspace#/ai-models/AAAAAAAAAAA'
		],
		[{ view: 'ai-model-icons' }, '/pages/admin/workspace#/ai-model-icons'],
		[{ view: 'ip2location-keys' }, '/pages/admin/workspace#/ip2location/keys'],
		[{ view: 'mail-openai' }, '/pages/admin/workspace#/mail-inspection/openai'],
		[{ view: 'mail-kiro' }, '/pages/admin/workspace#/mail-inspection/kiro'],
		[
			{ view: 'mail-ip2location', mode: 'registration' },
			'/pages/admin/workspace#/mail-inspection/ip2location/registration'
		],
		[
			{ view: 'mail-ip2location', mode: 'verify-link' },
			'/pages/admin/workspace#/mail-inspection/ip2location/verify-link'
		]
	]

	for (const [location, expectedUrl] of cases) {
		assert.equal(buildAdminWorkspaceUrl({ ...location, token: 'must-not-survive' }), expectedUrl)
		const parsed = parseAdminWorkspaceUrl(expectedUrl)
		assert.equal(parsed.view, location.view)
		assert.equal(parsed.mode, location.mode || '')
		assert.equal(parsed.publicId, location.publicId || '')
		assert.equal(parsed.corrected, false)
		assert.equal(parsed.notice, '')
		assert.equal(buildAdminWorkspaceUrl(parsed), expectedUrl)
		assert.equal(new URL(`https://admin.invalid${expectedUrl}`).pathname, '/pages/admin/workspace')
	}
})

test('static model routes win before the dynamic public identifier route', async () => {
	const { parseAdminWorkspaceUrl } = await loadModule()

	assert.equal(
		parseAdminWorkspaceUrl('/pages/admin/workspace#/ai-models/new').view,
		'ai-model-create'
	)
	assert.equal(
		parseAdminWorkspaceUrl('/pages/admin/workspace#/ai-models/discovery').view,
		'ai-model-discovery'
	)
	assert.deepEqual(
		parseAdminWorkspaceUrl('/pages/admin/workspace#/ai-models/AAAAAAAAAAA'),
		{
			view: 'ai-model-detail',
			mode: '',
			publicId: 'AAAAAAAAAAA',
			corrected: false,
			notice: ''
		}
	)
})

test('workspace path parsing canonicalizes harmless syntax and rejects unsafe aliases', async () => {
	const { parseAdminWorkspaceUrl } = await loadModule()

	assert.deepEqual(
		parseAdminWorkspaceUrl('/pages/admin/workspace?token=secret#/ai-models/'),
		{
			view: 'ai-models',
			mode: '',
			publicId: '',
			corrected: true,
			notice: ''
		}
	)
	for (const invalidIdPath of [
		'/pages/admin/workspace#/ai-models/bad=',
		'/pages/admin/workspace#/ai-models/AAAAAAAAAA!',
		'/pages/admin/workspace#/ai-models/AAAAAAAAAA',
		'/pages/admin/workspace#/ai-models/AAAAAAAAAAA/extra',
		'/pages/admin/workspace#/ai-models/AAAAAAAAAAA%2Fextra'
	]) {
		const invalid = parseAdminWorkspaceUrl(invalidIdPath)
		assert.equal(invalid.view, 'ai-models', invalidIdPath)
		assert.equal(invalid.publicId, '', invalidIdPath)
		assert.equal(invalid.corrected, true, invalidIdPath)
		assert.equal(invalid.notice, '模型标识无效，已返回模型目录。', invalidIdPath)
	}
	for (const invalidWorkspacePath of [
		'/pages/admin/workspace#//ai-models',
		'/pages/admin/workspace#/AI-MODELS',
		'/pages/admin/workspace#/unknown'
	]) {
		const invalid = parseAdminWorkspaceUrl(invalidWorkspacePath)
		assert.equal(invalid.view, 'dashboard', invalidWorkspacePath)
		assert.equal(invalid.corrected, true, invalidWorkspacePath)
		assert.equal(invalid.notice, '无法打开指定页面，已返回管理员控制台。', invalidWorkspacePath)
	}
})

test('legacy query state no longer restores a workspace business panel', async () => {
	const { buildAdminWorkspaceUrl, parseAdminWorkspaceUrl } = await loadModule()

	const parsed = parseAdminWorkspaceUrl(
		'/pages/admin/workspace?view=ai-model-detail&publicId=AAAAAAAAAAA&token=secret')
	assert.deepEqual(parsed, {
		view: 'dashboard',
		mode: '',
		publicId: '',
		corrected: true,
		notice: ''
	})
	assert.equal(buildAdminWorkspaceUrl(parsed), '/pages/admin/workspace')
})

test('every legacy administrator business URL stops at the workspace home', async () => {
	const { legacyAdminRouteToWorkspaceUrl } = await loadModule()

	for (const legacy of [
		'/pages/ai-models/index',
		'/pages/ai-models/create',
		'/pages/ai-models/detail?publicId=AAAAAAAAAAA',
		'/pages/ai-model-icons/index',
		'/pages/risk/ip2location-keys',
		'/pages/mail-inspection/openai/index',
		'/pages/mail-inspection/kiro/index',
		'/pages/mail-inspection/ip2location/index?mode=verify-link'
	]) {
		assert.equal(legacyAdminRouteToWorkspaceUrl(legacy), '/pages/admin/workspace', legacy)
	}
})

test('workspace URL recognition accepts only the registered pathname and published fragments', async () => {
	const { isAdminWorkspaceUrl } = await loadModule()

	for (const valid of [
		'/pages/admin/workspace',
		'/pages/admin/workspace#/ai-models',
		'/pages/admin/workspace#/ai-models/new',
		'/pages/admin/workspace#/ai-models/discovery',
		'/pages/admin/workspace#/ai-models/AAAAAAAAAAA',
		'/pages/admin/workspace#/mail-inspection/ip2location/verify-link'
	]) {
		assert.equal(isAdminWorkspaceUrl(valid), true, valid)
	}
	for (const invalid of [
		'/pages/admin/workspace/',
		'/pages/admin/workspace-invalid',
		'/pages/admin/workspace/ai-models',
		'/pages/admin/workspace#/unknown',
		'/pages/admin/workspace#/ai-models/bad=',
		'/pages/admin/workspace#//ai-models'
	]) {
		assert.equal(isAdminWorkspaceUrl(invalid), false, invalid)
	}
})

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	let source = fs.readFileSync(
		path.join(__dirname, 'admin-workspace-entry-state.js'),
		'utf8'
	)
	source = source.replace(
		/import \{ ADMIN_WORKSPACE_PATH, normalizeAdminWorkspaceLocation \} from '.\/admin-workspace-route\.js'/,
		"const ADMIN_WORKSPACE_PATH = '/pages/admin/workspace'; const normalizeAdminWorkspaceLocation = value => ({ view: value?.view || 'dashboard', mode: value?.mode || '', publicId: value?.publicId || '', corrected: false, notice: '' })"
	)
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('workspace entry state transfers one normalized location without query parameters', async () => {
	const {
		consumeAdminWorkspaceEntryLocation,
		stageAdminWorkspaceEntryLocation
	} = await loadModule()

	const route = stageAdminWorkspaceEntryLocation({
		view: 'ai-model-detail',
		publicId: 'AAAAAAAAAAA',
		token: 'must-not-survive'
	})
	assert.equal(route, '/pages/admin/workspace')
	assert.deepEqual(consumeAdminWorkspaceEntryLocation(), {
		view: 'ai-model-detail',
		mode: '',
		publicId: 'AAAAAAAAAAA',
		corrected: false,
		notice: ''
	})
	assert.equal(consumeAdminWorkspaceEntryLocation(), null)
})

test('failed navigation can clear a staged workspace location', async () => {
	const {
		clearAdminWorkspaceEntryLocation,
		consumeAdminWorkspaceEntryLocation,
		stageAdminWorkspaceEntryLocation
	} = await loadModule()

	stageAdminWorkspaceEntryLocation({ view: 'mail-openai' })
	clearAdminWorkspaceEntryLocation()
	assert.equal(consumeAdminWorkspaceEntryLocation(), null)
})

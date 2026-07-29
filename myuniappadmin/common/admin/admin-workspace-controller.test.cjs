const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	let source = fs.readFileSync(path.join(__dirname, 'admin-workspace-controller.js'), 'utf8')
	source = source.replace(
		/import \{ normalizeAdminWorkspaceLocation \} from '.\/admin-workspace-route\.js'/,
		"const normalizeAdminWorkspaceLocation = value => ({ view: value?.view || 'dashboard', mode: value?.mode || '', publicId: value?.publicId || '', corrected: false, notice: '' })"
	)
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

function createHistoryProbe() {
	const calls = []
	return {
		calls,
		push: location => calls.push(['push', location.view]),
		replace: location => calls.push(['replace', location.view]),
		releaseToSystem: () => calls.push(['system'])
	}
}

test('peer navigation deactivates and activates panels without rebuilding the shell', async () => {
	const { createAdminWorkspaceController } = await loadModule()
	const history = createHistoryProbe()
	const events = []
	const panels = {
		dashboard: { onWorkspaceDeactivated: () => events.push('dashboard:off') },
		'ai-models': { onWorkspaceActivated: () => events.push('models:on') }
	}
	const controller = createAdminWorkspaceController({
		initialLocation: { view: 'dashboard' },
		historyAdapter: history,
		resolvePanel: location => panels[location.view]
	})

	assert.equal(await controller.navigate({ view: 'ai-models' }), true)
	assert.deepEqual(events, ['dashboard:off', 'models:on'])
	assert.deepEqual(history.calls, [['push', 'ai-models']])
})

test('drawer closes before workspace history or system back is touched', async () => {
	const { createAdminWorkspaceController } = await loadModule()
	const history = createHistoryProbe()
	const controller = createAdminWorkspaceController({
		initialLocation: { view: 'dashboard' },
		historyAdapter: history
	})

	controller.openDrawer()
	assert.equal(await controller.back(), true)
	assert.equal(controller.snapshot().drawerOpen, false)
	assert.deepEqual(history.calls, [])
})

test('dirty panels can stop navigation and model editors return to the catalogue first', async () => {
	const { createAdminWorkspaceController } = await loadModule()
	const history = createHistoryProbe()
	let allowLeave = false
	const controller = createAdminWorkspaceController({
		initialLocation: { view: 'ai-model-create' },
		historyAdapter: history,
		resolvePanel: () => ({ beforeWorkspaceLeave: () => allowLeave })
	})

	assert.equal(await controller.navigate({ view: 'mail-openai' }), false)
	assert.equal(controller.snapshot().location.view, 'ai-model-create')
	allowLeave = true
	assert.equal(await controller.back(), true)
	assert.equal(controller.snapshot().location.view, 'ai-models')
	assert.deepEqual(history.calls, [['replace', 'ai-models']])
})

test('an empty internal history delegates the final back action to the platform', async () => {
	const { createAdminWorkspaceController } = await loadModule()
	const history = createHistoryProbe()
	const controller = createAdminWorkspaceController({
		initialLocation: { view: 'dashboard' },
		historyAdapter: history
	})

	assert.equal(await controller.back(), false)
	assert.deepEqual(history.calls, [['system']])
})

test('platform history changes reconcile the internal stack without rewriting browser history', async () => {
	const { createAdminWorkspaceController } = await loadModule()
	const history = createHistoryProbe()
	const controller = createAdminWorkspaceController({
		initialLocation: { view: 'dashboard' },
		historyAdapter: history
	})

	await controller.navigate({ view: 'ai-models' })
	await controller.navigate({ view: 'mail-openai' })
	assert.equal(controller.snapshot().historyDepth, 2)
	assert.equal(await controller.acceptPlatformLocation({ view: 'ai-models' }), true)
	assert.equal(controller.snapshot().historyDepth, 1)
	assert.equal(await controller.acceptPlatformLocation({ view: 'dashboard' }), true)
	assert.equal(controller.snapshot().historyDepth, 0)
	assert.deepEqual(history.calls, [
		['push', 'ai-models'],
		['push', 'mail-openai']
	])

	assert.equal(await controller.acceptPlatformLocation({ view: 'ai-models' }), true)
	assert.equal(controller.snapshot().historyDepth, 1)
})

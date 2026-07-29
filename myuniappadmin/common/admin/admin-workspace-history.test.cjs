const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function load(name) {
	const source = fs.readFileSync(path.join(__dirname, name), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('H5 history uses push and replace state and reports popstate without writing another entry', async () => {
	const { createAdminWorkspaceHistoryH5 } = await load('admin-workspace-history-h5.js')
	const calls = []
	let popstate
	const fakeWindow = {
		location: { pathname: '/pages/admin/workspace', search: '?view=dashboard' },
		history: {
			pushState: (state, title, url) => calls.push(['push', state, url]),
			replaceState: (state, title, url) => calls.push(['replace', state, url])
		},
		addEventListener: (name, listener) => { if (name === 'popstate') popstate = listener },
		removeEventListener: () => undefined
	}
	const seen = []
	const adapter = createAdminWorkspaceHistoryH5({ windowObject: fakeWindow, onPop: value => seen.push(value) })

	adapter.start()
	adapter.push({ view: 'ai-models' }, '/pages/admin/workspace?view=ai-models')
	adapter.replace({ view: 'mail-openai' }, '/pages/admin/workspace?view=mail-openai')
	popstate({ state: { __adminWorkspace: true, adminWorkspaceLocation: { view: 'dashboard' } } })
	assert.equal(calls.filter(item => item[0] === 'push').length, 1)
	assert.equal(calls.filter(item => item[0] === 'replace').length, 2)
	assert.deepEqual(seen, [{ view: 'dashboard' }])
})

test('Android workspace history pops internal views before releasing system back', async () => {
	const { createAdminWorkspaceHistoryApp } = await load('admin-workspace-history-app.js')
	let released = 0
	const adapter = createAdminWorkspaceHistoryApp({ onSystemBack: () => { released += 1 } })

	adapter.replace({ view: 'dashboard' })
	adapter.push({ view: 'ai-models' })
	adapter.push({ view: 'ai-model-detail', publicId: 'AAAAAAAAAAA' })
	assert.equal(adapter.pop().view, 'ai-models')
	assert.equal(adapter.pop().view, 'dashboard')
	assert.equal(adapter.pop(), null)
	adapter.releaseToSystem()
	assert.equal(released, 1)
})

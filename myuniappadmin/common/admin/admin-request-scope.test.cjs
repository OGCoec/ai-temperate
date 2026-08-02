const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'admin-request-scope.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('request scope aborts every tracked task once and becomes inactive', async () => {
	const { createAdminRequestScope } = await loadModule()
	const aborted = []
	const scope = createAdminRequestScope()
	const first = { abort: () => aborted.push('first') }
	const second = { abort: () => aborted.push('second') }
	scope.track(first)
	scope.track(second)

	scope.abortAll()
	scope.abortAll()

	assert.deepEqual(aborted, ['first', 'second'])
	assert.equal(scope.isActive(), false)
})

test('request scope releases completed tasks and immediately aborts late tasks', async () => {
	const { createAdminRequestScope } = await loadModule()
	const aborted = []
	const scope = createAdminRequestScope()
	const completed = { abort: () => aborted.push('completed') }
	scope.track(completed)
	scope.release(completed)
	scope.abortAll()
	assert.deepEqual(aborted, [])

	const late = { abort: () => aborted.push('late') }
	scope.track(late)
	assert.deepEqual(aborted, ['late'])
})

test('administrator read panels bind request scopes to deactivation and stale-response guards', () => {
	const root = path.resolve(__dirname, '..', '..')
	for (const file of [
		'components/admin/workspace/ai-model-list-panel.vue',
		'components/admin/workspace/ai-model-detail-panel.vue'
	]) {
		const panel = fs.readFileSync(path.join(root, file), 'utf8')
		assert.match(panel, /createAdminRequestScope/)
		assert.match(panel, /beforeUnmount\(\)[\s\S]*cancelReadRequests/)
		assert.match(panel, /onWorkspaceDeactivated\(\)[\s\S]*cancelReadRequests/)
		assert.match(panel, /requestGeneration/)
		assert.match(panel, /isAdminRequestAborted/)
	}
})

test('administrator HTTP transport scopes only GET requests and suppresses abort retries', () => {
	const source = fs.readFileSync(path.join(__dirname, 'admin-http.js'), 'utf8')
	assert.match(source, /options\.scope\s*&&\s*method\s*!==\s*'GET'/)
	assert.match(source, /requireActiveAdminRequestScope\(options\)/)
	assert.match(source, /isAdminRequestAborted\(error\)\) throw error/)
	assert.match(source, /success\(response\)[\s\S]*options\.scope\s*&&\s*!options\.scope\.isActive\(\)/)
	assert.match(source, /options\.scope\?\.track\(task\)/)
	assert.match(source, /options\.scope\?\.release\(task\)/)
})

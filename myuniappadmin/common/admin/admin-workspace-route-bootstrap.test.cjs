const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const root = path.resolve(__dirname, '..', '..')
const bootstrapPath = path.join(
	root,
	'static/bootstrap/admin-workspace-route-bootstrap.js')
const bootstrapSource = fs.readFileSync(bootstrapPath, 'utf8')

function executeBootstrap({ pathname, search = '', hash = '' }) {
	const replacements = []
	const windowObject = {
		location: { pathname, search, hash },
		history: {
			state: { framework: 'preserved' },
			replaceState(state, _title, url) {
				replacements.push({ state, url })
			}
		}
	}
	Object.defineProperties(windowObject, {
		localStorage: { get() { throw new Error('localStorage must not be read') } },
		sessionStorage: { get() { throw new Error('sessionStorage must not be read') } }
	})

	vm.runInNewContext(bootstrapSource, { window: windowObject })
	return { replacements, windowObject }
}

test('workspace deep links are migrated to fragments before UniApp starts', () => {
	const { replacements, windowObject } = executeBootstrap({
		pathname: '/pages/admin/workspace/ai-models/AAAAAAAAAAA',
		search: '?token=must-not-survive',
		hash: '#secret'
	})

	assert.deepEqual(replacements, [{
		state: { framework: 'preserved' },
		url: '/pages/admin/workspace#/ai-models/AAAAAAAAAAA'
	}])
	assert.equal('__AIT_ADMIN_WORKSPACE_INITIAL_PATH__' in windowObject, false)
})

test('legacy workspace query state is left for the workspace canonicalizer without being read', () => {
	const { replacements, windowObject } = executeBootstrap({
		pathname: '/pages/admin/workspace',
		search: '?view=ai-model-detail&publicId=AAAAAAAAAAA'
	})

	assert.equal(replacements.length, 0)
	assert.equal('__AIT_ADMIN_WORKSPACE_INITIAL_PATH__' in windowObject, false)
})

test('canonical workspace fragments are left untouched', () => {
	const { replacements, windowObject } = executeBootstrap({
		pathname: '/pages/admin/workspace',
		hash: '#/ai-models/AAAAAAAAAAA'
	})
	assert.equal(replacements.length, 0)
	assert.equal('__AIT_ADMIN_WORKSPACE_INITIAL_PATH__' in windowObject, false)
})

test('login, risk and workspace-like prefixes are outside the bootstrap boundary', () => {
	for (const pathname of [
		'/pages/index/index',
		'/pages/risk/blocked',
		'/pages/admin/workspace-invalid'
	]) {
		const { replacements, windowObject } = executeBootstrap({ pathname })
		assert.equal(replacements.length, 0, pathname)
		assert.equal('__AIT_ADMIN_WORKSPACE_INITIAL_PATH__' in windowObject, false, pathname)
	}
})

test('bootstrap source does not inspect persistent browser or authentication state', () => {
	assert.doesNotMatch(bootstrapSource, /cookie/i)
	assert.doesNotMatch(bootstrapSource, /localStorage/)
	assert.doesNotMatch(bootstrapSource, /sessionStorage/)
	assert.doesNotMatch(bootstrapSource, /location\.search/)
	assert.doesNotMatch(bootstrapSource, /location\.hash/)
	assert.doesNotMatch(bootstrapSource, /__AIT_ADMIN_WORKSPACE_INITIAL_PATH__/)
})

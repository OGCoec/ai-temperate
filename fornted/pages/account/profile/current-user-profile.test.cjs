const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadProfileModule(requestProfile) {
	const nonce = `${Date.now()}-${Math.random()}`
	const userDirectory = path.resolve(__dirname, '../../../common/user')
	const vaultSource = fs.readFileSync(path.join(userDirectory, 'profile-vault.js'), 'utf8')
	const vaultUrl = `${sourceUrl(vaultSource)}#vault-${nonce}`
	globalThis.__requestCurrentUserProfile = requestProfile
	const apiUrl = `${sourceUrl(`export const currentUserApi = {
		me: (...args) => globalThis.__requestCurrentUserProfile(...args)
	}`)}#api-${nonce}`
	const profileSource = fs.readFileSync(path.join(userDirectory, 'current-user-profile.js'), 'utf8')
		.replace("from './current-user-api.js'", `from '${apiUrl}'`)
		.replace("from './profile-vault.js'", `from '${vaultUrl}'`)
	return import(`${sourceUrl(profileSource)}#profile-${nonce}`)
}

test('merges concurrent profile requests and reuses the in-memory cache', async () => {
	let calls = 0
	let resolveRequest
	const request = new Promise(resolve => { resolveRequest = resolve })
	const module = await loadProfileModule(() => {
		calls += 1
		return request
	})

	const first = module.loadCurrentUserProfile()
	const second = module.loadCurrentUserProfile()
	resolveRequest({ displayName: 'Alice', email: 'alice@example.test', phone: null })

	assert.deepEqual(await first, await second)
	assert.equal(calls, 1)
	assert.equal((await module.loadCurrentUserProfile()).email, 'alice@example.test')
	assert.equal(calls, 1)
	delete globalThis.__requestCurrentUserProfile
})

test('force reload replaces the cached profile and clear removes it', async () => {
	let version = 0
	const module = await loadProfileModule(async () => {
		version += 1
		return {
			displayName: `User ${version}`,
			email: `user${version}@example.test`,
			phone: '+8613800138000'
		}
	})

	assert.equal((await module.loadCurrentUserProfile()).displayName, 'User 1')
	assert.equal((await module.loadCurrentUserProfile({ force: true })).displayName, 'User 2')
	module.clearCurrentUserProfile()
	assert.equal(module.getCurrentUserProfile(), null)
	delete globalThis.__requestCurrentUserProfile
})

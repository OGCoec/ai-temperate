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
	globalThis.__profileLifecycleEntries = []
	const lifecycleUrl = `${sourceUrl(`export function recordAiConversationProfileRefresh(...args) {
			globalThis.__profileLifecycleEntries.push(args)
			return true
		}`)}#lifecycle-${nonce}`
	const profileSource = fs.readFileSync(path.join(userDirectory, 'current-user-profile.js'), 'utf8')
		.replace("from './current-user-api.js'", `from '${apiUrl}'`)
		.replace("from './profile-vault.js'", `from '${vaultUrl}'`)
		.replace(
			"from '../aichat/ai-conversation-lifecycle-diagnostics.js'",
			`from '${lifecycleUrl}'`
		)
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

test('retains membership and quota presentation fields in the in-memory vault', async () => {
	const quotaBalanceMinor = '900719925474099312345'
	const quotaBalance = '9007199254740993.12345'
	const quotaTotalMinor = '5000000'
	const quotaTotal = '50000.0'
	const quotaUsedMinor = '123456'
	const quotaUsed = '1234.56'
	const module = await loadProfileModule(async () => ({
		displayName: 'Alice',
		email: 'alice@example.test',
		phone: '+14155550123',
		avatarUrl: 'https://cdn.example.test/avatar.webp',
		membershipTier: 'FREE',
		quotaBalanceMinor,
		quotaBalance,
		quotaTotalMinor,
		quotaTotal,
		quotaUsedMinor,
		quotaUsed,
		quotaUsagePercent: '2.5',
		quotaPeriodStartedAt: null,
		quotaResetAt: '2026-08-06T12:00:00Z'
	}))

	const profile = await module.loadCurrentUserProfile()

	assert.deepEqual(profile, {
		displayName: 'Alice',
		email: 'alice@example.test',
		phone: '+14155550123',
		avatarUrl: 'https://cdn.example.test/avatar.webp',
		membershipTier: 'FREE',
		quotaBalanceMinor,
		quotaBalance,
		quotaTotalMinor,
		quotaTotal,
		quotaUsedMinor,
		quotaUsed,
		quotaUsagePercent: '2.5',
		quotaPeriodStartedAt: null,
		quotaResetAt: '2026-08-06T12:00:00Z'
	})
	assert.equal(typeof profile.quotaBalanceMinor, 'string')
	assert.equal(typeof profile.quotaBalance, 'string')
	assert.equal(typeof profile.quotaUsagePercent, 'string')
	delete globalThis.__requestCurrentUserProfile
})

test('profile refresh diagnostics record only whether quota changed', async () => {
	let balance = '1000'
	const module = await loadProfileModule(async () => ({
		displayName: 'Alice',
		email: 'alice@example.test',
		quotaBalanceMinor: balance,
		quotaUsedMinor: '0'
	}))

	await module.loadCurrentUserProfile()
	balance = '1200'
	await module.loadCurrentUserProfile({ force: true })

	const serialized = JSON.stringify(globalThis.__profileLifecycleEntries)
	assert.equal(serialized.includes('1200'), false)
	assert.equal(globalThis.__profileLifecycleEntries.some(entry =>
		entry[0] === 'PROFILE_QUOTA_CHANGED'
			&& entry[1]?.quotaChanged === true), true)
	delete globalThis.__requestCurrentUserProfile
	delete globalThis.__profileLifecycleEntries
})

test('confirmed avatar only updates the in-memory current profile', async () => {
	const module = await loadProfileModule(async () => ({
		displayName: 'Alice',
		email: 'alice@example.test',
		phone: null,
		avatarUrl: null
	}))
	await module.loadCurrentUserProfile()

	const updated = module.updateCurrentUserAvatar('https://cdn.example.test/avatar.webp')

	assert.equal(updated.avatarUrl, 'https://cdn.example.test/avatar.webp')
	assert.equal(
		module.getCurrentUserProfile().avatarUrl,
		'https://cdn.example.test/avatar.webp'
	)
	delete globalThis.__requestCurrentUserProfile
})

test('profile quota card exposes accessible progress and Ultra display mapping', () => {
	const panel = fs.readFileSync(
		path.resolve(__dirname, '../../../components/user/workspace/user-profile-panel.vue'),
		'utf8'
	)

	assert.match(panel, /role="progressbar"/)
	assert.match(panel, /aria-valuemin="0"/)
	assert.match(panel, /:aria-valuenow="quotaUsagePercent"/)
	assert.match(panel, /额度进度暂不可用/)
	assert.match(panel, /MAX: 'Ultra'/)
	assert.match(panel, /transform: scaleX/)
	assert.match(panel, /prefers-reduced-motion/)
})

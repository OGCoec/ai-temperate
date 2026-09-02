const assert = require('node:assert/strict')
const test = require('node:test')

async function loadCoordinator() {
	const source = require('node:fs').readFileSync(
		require('node:path').resolve(__dirname, 'android-oauth-coordinator.js'),
		'utf8')
	const sourceUrl = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(sourceUrl)
}

test('same operation joins the original promise', async () => {
	const coordinator = await loadCoordinator()
	let calls = 0
	const first = coordinator.run('google', async ({ setPhase }) => {
		calls += 1
		setPhase(coordinator.AndroidOAuthPhase.NATIVE_PICKER)
		await Promise.resolve()
		return 'done'
	})
	const joined = coordinator.join('google')
	assert.strictEqual(joined, first)
	assert.equal(await joined, 'done')
	assert.equal(calls, 1)
})

test('different operation waits for the current terminal state', async () => {
	const coordinator = await loadCoordinator()
	let release
	const gate = new Promise(resolve => { release = resolve })
	const first = coordinator.run('google', async () => gate)
	const second = coordinator.run('github', async () => 'second')
	assert.notStrictEqual(second, first)
	release('first')
	assert.equal(await first, 'first')
	assert.equal(await second, 'second')
})

test('active OAuth blocks Android WebRTC until finished', async () => {
	const coordinator = await loadCoordinator()
	const pending = coordinator.run('google', async ({ setPhase }) => {
		setPhase(coordinator.AndroidOAuthPhase.NATIVE_COMPLETE)
		await Promise.resolve()
		return true
	})
	assert.equal(coordinator.isBlockingWebRtc(), true)
	await pending
	assert.equal(coordinator.isBlockingWebRtc(), false)
})

test('join with another key never adopts a different flow', async () => {
	const coordinator = await loadCoordinator()
	const pending = coordinator.run('google', async () => new Promise(resolve => {
		setTimeout(() => resolve(true), 5)
	}))
	assert.equal(coordinator.join('github'), null)
	await pending
})

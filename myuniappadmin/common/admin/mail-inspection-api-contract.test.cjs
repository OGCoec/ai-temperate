const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'mail-inspection-api-contract.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

function completeApi() {
	return {
		contractVersion: 3,
		createJob() {},
		getJob() {},
		getRecoveredJobs() {},
		resumeJob() {}
	}
}

test('complete API instances pass the frontend contract unchanged', async () => {
	const { requireAdminMailInspectionApi } = await loadModule()
	const api = completeApi()

	assert.equal(requireAdminMailInspectionApi(api), api)
})

test('stale API instances fail with one controlled frontend version error', async () => {
	const { requireAdminMailInspectionApi } = await loadModule()
	const staleClients = [
		{ ...completeApi(), contractVersion: 1 },
		{ ...completeApi(), contractVersion: 2 },
		{ ...completeApi(), getRecoveredJobs: undefined },
		null
	]

	for (const api of staleClients) {
		assert.throws(
			() => requireAdminMailInspectionApi(api),
			error => error.code === 'MAIL_INSPECTION_FRONTEND_VERSION_MISMATCH'
				&& error.message === '前端资源版本不一致，请清除本站缓存后重新加载。')
	}
})

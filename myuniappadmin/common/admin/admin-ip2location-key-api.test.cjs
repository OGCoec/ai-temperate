const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	let source = fs.readFileSync(path.join(__dirname, 'admin-ip2location-key-api.js'), 'utf8')
	source = source.replace("import { adminRequest } from './admin-http.js'", 'const adminRequest = async () => { throw new Error("not configured") }')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('listAll consumes hscan cursors to zero and de-duplicates key ids', async () => {
	const { createAdminIp2LocationKeyApi } = await loadModule()
	const calls = []
	const pages = [
		{ nextCursor: 9, items: [{ keyId: 'a' }, { keyId: 'b' }] },
		{ nextCursor: 0, items: [{ keyId: 'b' }, { keyId: 'c' }] }
	]
	const api = createAdminIp2LocationKeyApi(async path => {
		calls.push(path)
		return pages.shift()
	})

	assert.deepEqual((await api.listAll()).map(item => item.keyId), ['a', 'b', 'c'])
	assert.deepEqual(calls, [
		'/api/admin/risk/ip2location/keys?cursor=0&size=100',
		'/api/admin/risk/ip2location/keys?cursor=9&size=100'
	])
})

test('listAll rejects cursor loops and responses exceeding one hundred keys', async () => {
	const { createAdminIp2LocationKeyApi } = await loadModule()
	const loopApi = createAdminIp2LocationKeyApi(async () => ({ nextCursor: 2, items: [] }))
	await assert.rejects(() => loopApi.listAll(), error => error.code === 'IP2LOCATION_CURSOR_LOOP')

	let page = 0
	const overflowApi = createAdminIp2LocationKeyApi(async () => ({
		nextCursor: page++ === 0 ? 1 : 0,
		items: Array.from({ length: 51 }, (_, index) => ({ keyId: `${page}-${index}` }))
	}))
	await assert.rejects(() => overflowApi.listAll(), error => error.code === 'IP2LOCATION_KEY_LIMIT_EXCEEDED')
})

test('imports and deletes use fixed administrator routes without leaking keys into the url', async () => {
	const { createAdminIp2LocationKeyApi } = await loadModule()
	const calls = []
	const api = createAdminIp2LocationKeyApi(async (requestPath, options) => {
		calls.push({ requestPath, options })
		return {}
	})
	await api.importBatch({
		planType: 'FREE', initialQuota: 50000,
		mode: 'CREATE_ONLY', apiKeys: ['sensitive-key']
	})
	await api.deleteBatch(['safe-key-id'])

	assert.equal(calls[0].requestPath, '/api/admin/risk/ip2location/keys/batch')
	assert.equal(calls[0].options.method, 'POST')
	assert.equal(Object.hasOwn(calls[0].options.data, 'expiresAt'), false)
	assert.doesNotMatch(calls[0].requestPath, /sensitive-key/)
	assert.equal(calls[1].requestPath, '/api/admin/risk/ip2location/keys/delete')
	assert.deepEqual(calls[1].options.data, { keyIds: ['safe-key-id'] })
})

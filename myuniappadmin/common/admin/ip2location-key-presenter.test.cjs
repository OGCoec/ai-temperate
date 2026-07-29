const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'ip2location-key-presenter.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('multiline input trims empty rows and removes duplicates without changing order', async () => {
	const { parseIp2LocationKeyText } = await loadModule()
	const result = parseIp2LocationKeyText('  key-one  \n\nkey-two\nkey-one\n')

	assert.deepEqual(result.apiKeys, ['key-one', 'key-two'])
	assert.equal(result.duplicateCount, 1)
	assert.equal(result.nonEmptyCount, 3)
})

test('capacity validation covers zero one one-hundred and one-hundred-one boundaries', async () => {
	const { validateIp2LocationImportCapacity } = await loadModule()

	assert.equal(validateIp2LocationImportCapacity(0, 0).allowed, false)
	assert.equal(validateIp2LocationImportCapacity(99, 1).allowed, true)
	assert.equal(validateIp2LocationImportCapacity(0, 100).allowed, true)
	assert.equal(validateIp2LocationImportCapacity(0, 101).allowed, false)
	assert.equal(validateIp2LocationImportCapacity(100, 1).remainingCapacity, 0)
})

test('full collection is sorted by expiry then key id before page slicing', async () => {
	const { sortIp2LocationKeys, paginateIp2LocationKeys } = await loadModule()
	const entries = Array.from({ length: 21 }, (_, index) => ({
		keyId: String(index).padStart(2, '0'),
		expiresAt: index === 20 ? '2026-01-01T00:00:00Z' : '2027-01-01T00:00:00Z',
		remainingQuota: 1
	}))
	const sorted = sortIp2LocationKeys(entries)

	assert.equal(paginateIp2LocationKeys(sorted, 1).items[0].keyId, '20')
	assert.equal(paginateIp2LocationKeys(sorted, 1).items.length, 20)
	assert.equal(paginateIp2LocationKeys(sorted, 2).items.length, 1)
})

test('equal expiry uses deterministic javascript code-unit key order', async () => {
	const { sortIp2LocationKeys } = await loadModule()
	const expiresAt = '2026-08-01T00:00:00Z'
	const sorted = sortIp2LocationKeys([
		{ keyId: '_key', expiresAt },
		{ keyId: 'akey', expiresAt },
		{ keyId: '-key', expiresAt }
	])

	assert.deepEqual(sorted.map(item => item.keyId), ['-key', '_key', 'akey'])
})

test('status handles invalid dates expired keys zero quota and expiring keys', async () => {
	const { ip2LocationKeyStatus } = await loadModule()
	const now = Date.parse('2026-07-25T00:00:00Z')

	assert.equal(ip2LocationKeyStatus({ expiresAt: 'invalid', remainingQuota: 1 }, now).code, 'INVALID')
	assert.equal(ip2LocationKeyStatus({ expiresAt: '2026-07-24T00:00:00Z', remainingQuota: 0 }, now).code, 'EXPIRED')
	assert.equal(ip2LocationKeyStatus({
		expiresAt: new Date(now + (60 * 60 * 1000)).toISOString(),
		remainingQuota: 0
	}, now).code, 'EXHAUSTED')
	assert.equal(ip2LocationKeyStatus({
		expiresAt: new Date(now + (24 * 60 * 60 * 1000)).toISOString(),
		remainingQuota: 1
	}, now).code, 'EXPIRING')
	assert.equal(ip2LocationKeyStatus({
		expiresAt: new Date(now + (24 * 60 * 60 * 1000) - 1).toISOString(),
		remainingQuota: 1
	}, now).code, 'EXPIRING')
	assert.equal(ip2LocationKeyStatus({
		expiresAt: new Date(now + (24 * 60 * 60 * 1000) + 1).toISOString(),
		remainingQuota: 1
	}, now).code, 'ACTIVE')
	assert.equal(ip2LocationKeyStatus({ expiresAt: '2026-08-25T00:00:00Z', remainingQuota: 1 }, now).code, 'ACTIVE')
})

test('pagination is fixed to twenty rows and no more than five pages', async () => {
	const { paginateIp2LocationKeys } = await loadModule()
	const hundred = Array.from({ length: 100 }, (_, index) => ({ keyId: String(index) }))

	assert.equal(paginateIp2LocationKeys([], 1).pageCount, 1)
	assert.equal(paginateIp2LocationKeys(hundred.slice(0, 1), 1).pageCount, 1)
	assert.equal(paginateIp2LocationKeys(hundred.slice(0, 20), 1).pageCount, 1)
	assert.equal(paginateIp2LocationKeys(hundred.slice(0, 21), 2).items.length, 1)
	assert.equal(paginateIp2LocationKeys(hundred, 8).page, 5)
	assert.equal(paginateIp2LocationKeys(hundred, 8).pageCount, 5)
})

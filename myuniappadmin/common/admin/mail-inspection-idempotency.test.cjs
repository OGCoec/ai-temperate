const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(
		path.join(__dirname, 'mail-inspection-idempotency.js'),
		'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('mail inspection idempotency IDs are canonical UUIDv4 values', async () => {
	const {
		createMailInspectionClientRequestId,
		requireMailInspectionClientRequestId
	} = await loadModule()
	const value = createMailInspectionClientRequestId()
	assert.match(
		value,
		/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
	assert.equal(requireMailInspectionClientRequestId(value), value)
	assert.throws(() => requireMailInspectionClientRequestId(value.toUpperCase()))
})

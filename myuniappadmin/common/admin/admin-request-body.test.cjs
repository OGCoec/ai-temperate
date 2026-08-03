const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(
		path.join(__dirname, 'admin-request-body.js'),
		'utf8'
	)
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('structured JSON content types serialize object payloads as JSON text', async () => {
	const { serializeStructuredJsonRequestBody } = await loadModule()

	assert.equal(
		serializeStructuredJsonRequestBody(
			{ description: null },
			{ 'Content-Type': 'application/merge-patch+json' }
		),
		'{"description":null}'
	)
	assert.equal(
		serializeStructuredJsonRequestBody(
			['IMAGE_INPUT', 'AUDIO_INPUT'],
			{
				'Content-Type': 'application/json',
				'content-type': 'Application/Vnd.Ait+Json; charset=UTF-8'
			}
		),
		'["IMAGE_INPUT","AUDIO_INPUT"]'
	)
})

test('ordinary JSON and already serialized payloads preserve their existing transport contract', async () => {
	const { serializeStructuredJsonRequestBody } = await loadModule()
	const ordinaryJson = { modelName: 'gpt-5.6' }
	const serializedPatch = '{"description":null}'

	assert.equal(
		serializeStructuredJsonRequestBody(
			ordinaryJson,
			{ 'Content-Type': 'application/json' }
		),
		ordinaryJson
	)
	assert.equal(
		serializeStructuredJsonRequestBody(
			serializedPatch,
			{ 'Content-Type': 'application/merge-patch+json' }
		),
		serializedPatch
	)
	assert.equal(
		serializeStructuredJsonRequestBody(
			null,
			{ 'Content-Type': 'application/merge-patch+json' }
		),
		null
	)
	assert.equal(
		serializeStructuredJsonRequestBody(
			undefined,
			{ 'Content-Type': 'application/merge-patch+json' }
		),
		undefined
	)
})

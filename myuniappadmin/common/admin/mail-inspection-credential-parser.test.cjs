const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'mail-inspection-credential-parser.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

const uuid = '00000000-0000-0000-0000-000000000000'
const valid = email => `${email}----password----${uuid}----refresh-token`

test('blank lines are ignored before stable submitted line numbers are assigned', async () => {
	const { analyzeMailboxCredentialText } = await loadModule()
	const result = analyzeMailboxCredentialText(`\r\n${valid('first@example.com')}\r\n \r\n${valid('second@example.com')}\r\n`)

	assert.equal(result.valid, true)
	assert.deepEqual(result.credentialLines, [
		valid('first@example.com'),
		valid('second@example.com')
	])
	assert.equal(result.lineCount, 2)
	assert.equal(result.errors.length, 0)
})

test('invalid segment counts and duplicate normalized emails block submission', async () => {
	const { analyzeMailboxCredentialText } = await loadModule()
	const result = analyzeMailboxCredentialText([
		valid('Person@Example.com'),
		'not-four-segments',
		valid('person@example.com')
	].join('\n'))

	assert.equal(result.valid, false)
	assert.deepEqual(result.errors.map(error => error.code), [
		'INVALID_CREDENTIAL_FORMAT',
		'DUPLICATE_EMAIL'
	])
	assert.deepEqual(result.errors.map(error => error.lineNumber), [2, 3])
})

test('client validation enforces field boundaries without interpreting token expiry', async () => {
	const { analyzeMailboxCredentialText } = await loadModule()
	const invalid = analyzeMailboxCredentialText([
		`bad-address----password----${uuid}----refresh-token`,
		`mail@example.com--------${uuid}----refresh-token`,
		`other@example.com----password----not-a-uuid----refresh-token`,
		`third@example.com----password----${uuid}----bad\u0001token`
	].join('\n'))

	assert.deepEqual(invalid.errors.map(error => error.code), [
		'INVALID_EMAIL',
		'INVALID_PASSWORD_FIELD',
		'INVALID_CLIENT_ID',
		'INVALID_REFRESH_TOKEN'
	])
	assert.equal(invalid.errors.some(error => /expired|过期/i.test(error.message)), false)

	const many = analyzeMailboxCredentialText(
		Array.from({ length: 1000 }, (_, index) => valid(`person${index}@example.com`)).join('\n'))
	assert.equal(many.valid, true)
	assert.equal(many.lineCount, 1000)

	const empty = analyzeMailboxCredentialText(' \n\r\n\t')
	assert.equal(empty.errors[0].code, 'CREDENTIAL_LINES_EMPTY')
})

test('request byte size and ten-thousand-line limit are batch-wide boundaries', async () => {
	const { analyzeMailboxCredentialText } = await loadModule()
	const oversized = Array.from(
		{ length: 180 },
		(_, index) => `${valid(`large${index}@example.com`)}${'x'.repeat(6000)}`
	).join('\n')

	const result = analyzeMailboxCredentialText(oversized)

	assert.equal(result.byteCount > 1024 * 1024, true)
	assert.equal(result.errors.some(error => error.code === 'REQUEST_TOO_LARGE'), true)

	const tooMany = analyzeMailboxCredentialText(
		Array.from(
			{ length: 10001 },
			(_, index) => valid(`p${index}@x.io`)
		).join('\n'))
	assert.equal(
		tooMany.errors.some(error =>
			error.code === 'CREDENTIAL_LINES_LIMIT_EXCEEDED'),
		true)
})

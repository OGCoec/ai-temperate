const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'ip2location-key-file-picker.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('text file metadata accepts utf8 txt up to 256kb', async () => {
	const { validateIp2LocationKeyFile } = await loadModule()
	assert.doesNotThrow(() => validateIp2LocationKeyFile({ name: 'keys.txt', size: 256 * 1024 }))
	assert.throws(
		() => validateIp2LocationKeyFile({ name: 'keys.csv', size: 10 }),
		error => error.code === 'FILE_TYPE_INVALID'
	)
	assert.throws(
		() => validateIp2LocationKeyFile({ name: 'keys.txt', size: 256 * 1024 + 1 }),
		error => error.code === 'FILE_TOO_LARGE'
	)
})

test('unavailable runtime fails with a controlled fallback code', async () => {
	const { chooseIp2LocationKeyTextFile } = await loadModule()
	await assert.rejects(
		() => chooseIp2LocationKeyTextFile({}),
		error => error.code === 'FILE_PICKER_UNAVAILABLE'
	)
})

test('selected file is read as text and returns only transient metadata', async () => {
	const { chooseIp2LocationKeyTextFile } = await loadModule()
	const runtime = {
		chooseFile(options) {
			options.success({ tempFiles: [{ name: 'keys.txt', size: 15, text: async () => 'one\ntwo' }] })
		}
	}
	const selected = await chooseIp2LocationKeyTextFile(runtime)

	assert.deepEqual(selected, { name: 'keys.txt', size: 15, text: 'one\ntwo' })
})

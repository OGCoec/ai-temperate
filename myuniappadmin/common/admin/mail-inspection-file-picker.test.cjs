const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(path.join(__dirname, 'mail-inspection-file-picker.js'), 'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('mail credential picker accepts UTF-8 txt files up to one MiB', async () => {
	const { validateMailInspectionTextFile } = await loadModule()
	assert.doesNotThrow(() => validateMailInspectionTextFile({ name: 'credentials.txt', size: 1024 * 1024 }))
	assert.throws(
		() => validateMailInspectionTextFile({ name: 'credentials.csv', size: 10 }),
		error => error.code === 'FILE_TYPE_INVALID')
	assert.throws(
		() => validateMailInspectionTextFile({ name: 'credentials.txt', size: 1024 * 1024 + 1 }),
		error => error.code === 'FILE_TOO_LARGE')
})

test('runtime capability detection provides a controlled paste fallback', async () => {
	const { isMailInspectionFilePickerAvailable, chooseMailInspectionTextFile } = await loadModule()
	assert.equal(isMailInspectionFilePickerAvailable({}), false)
	await assert.rejects(
		() => chooseMailInspectionTextFile({}),
		error => error.code === 'FILE_PICKER_UNAVAILABLE')
})

test('selected text is returned without persisting its credential content', async () => {
	const { chooseMailInspectionTextFile } = await loadModule()
	const runtime = {
		chooseFile(options) {
			options.success({
				tempFiles: [{
					name: 'credentials.txt',
					size: 16,
					text: async () => 'credential-line'
				}]
			})
		}
	}
	const selected = await chooseMailInspectionTextFile(runtime)
	assert.deepEqual(selected, { name: 'credentials.txt', size: 16, text: 'credential-line' })
})

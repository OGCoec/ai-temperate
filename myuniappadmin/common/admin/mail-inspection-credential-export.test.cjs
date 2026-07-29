const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

async function loadModule() {
	const source = fs.readFileSync(
		path.join(__dirname, 'mail-inspection-credential-export.js'),
		'utf8')
	return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}

test('credential export uses UTF-8 LF content and a safe deterministic filename', async () => {
	const { createMailInspectionCredentialExport } = await loadModule()
	const exported = createMailInspectionCredentialExport({
		inspectionType: 'OPENAI_STATUS',
		jobId: 'AQzUqWaCEAA',
		credentialLines: ['line-one', 'line-two'],
		now: new Date('2026-07-28T20:15:26Z')
	})

	assert.equal(exported.content, 'line-one\nline-two')
	assert.equal(
		exported.filename,
		'mail-inspection-openai-status-unregistered-AQzUqWaCEAA-20260728-201526.txt')
	assert.equal(exported.mimeType, 'text/plain;charset=utf-8')
})

test('H5 download revokes the temporary object URL after dispatching the file', async () => {
	const { exportMailInspectionCredentialFile } = await loadModule()
	const events = []
	const anchor = {
		style: {},
		click: () => events.push('click'),
		remove: () => events.push('remove')
	}
	const result = await exportMailInspectionCredentialFile(
		{ filename: 'credentials.txt', content: 'secret', mimeType: 'text/plain;charset=utf-8' },
		{
			platform: 'H5',
			BlobCtor: class BlobStub {},
			urlApi: {
				createObjectURL: () => 'blob:test',
				revokeObjectURL: value => events.push(`revoke:${value}`)
			},
			documentRef: {
				createElement: () => anchor,
				body: {
					appendChild: () => events.push('append')
				}
			}
		})

	assert.deepEqual(events, ['append', 'click', 'remove', 'revoke:blob:test'])
	assert.equal(result.platform, 'H5')
	assert.equal(result.filename, 'credentials.txt')
})

test('Android export writes only to the private document export directory', async () => {
	const { exportMailInspectionCredentialFile } = await loadModule()
	const calls = []
	const fileEntry = {
		toLocalURL: () => '_doc/mail-inspection-exports/credentials.txt',
		createWriter(success) {
			success({
				write(value) {
					calls.push(['write', value])
					this.onwrite?.()
				}
			})
		}
	}
	const directory = {
		getFile(name, options, success) {
			calls.push(['getFile', name, options])
			success(fileEntry)
		}
	}
	const plusRef = {
		io: {
			PRIVATE_DOC: 2,
			requestFileSystem(type, success) {
				calls.push(['requestFileSystem', type])
				success({
					root: {
						getDirectory(name, options, resolved) {
							calls.push(['getDirectory', name, options])
							resolved(directory)
						}
					}
				})
			}
		}
	}

	const result = await exportMailInspectionCredentialFile(
		{ filename: 'credentials.txt', content: 'secret', mimeType: 'text/plain;charset=utf-8' },
		{ platform: 'ANDROID', plusRef })

	assert.equal(result.path, '_doc/mail-inspection-exports/credentials.txt')
	assert.deepEqual(calls, [
		['requestFileSystem', 2],
		['getDirectory', 'mail-inspection-exports', { create: true }],
		['getFile', 'credentials.txt', { create: true, exclusive: false }],
		['write', 'secret']
	])
})

test('Android session cleanup removes the complete private export directory', async () => {
	const { clearMailInspectionCredentialExports } = await loadModule()
	const calls = []
	const plusRef = {
		io: {
			PRIVATE_DOC: 2,
			requestFileSystem(type, success) {
				success({
					root: {
						getDirectory(name, options, resolved) {
							calls.push(['getDirectory', name, options])
							resolved({
								removeRecursively(done) {
									calls.push(['removeRecursively'])
									done()
								}
							})
						}
					}
				})
			}
		}
	}

	const removed = await clearMailInspectionCredentialExports({
		platform: 'ANDROID',
		plusRef
	})

	assert.equal(removed, true)
	assert.deepEqual(calls, [
		['getDirectory', 'mail-inspection-exports', { create: false }],
		['removeRecursively']
	])
})

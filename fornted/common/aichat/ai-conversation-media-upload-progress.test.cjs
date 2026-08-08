const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')
const { loadEsmModule } = require('./ai-code-test-loader.cjs')

async function loadModule() {
	return loadEsmModule(path.resolve(__dirname, 'ai-conversation-media-upload-progress.js'))
}

test('keeps the newest event for one attempt and accepts a real retry reset', async () => {
	const module = await loadModule()
	const initial = module.mergeMediaUploadProgress({}, {
		mediaType: 'IMAGE', outputIndex: 1, attempt: 1, maxAttempts: 3,
		state: 'UPLOADING', transferredBytes: 50, totalBytes: 100, percent: 50, sequence: 2
	})
	const stale = module.mergeMediaUploadProgress(initial, {
		mediaType: 'IMAGE', outputIndex: 1, attempt: 1, maxAttempts: 3,
		state: 'UPLOADING', transferredBytes: 20, totalBytes: 100, percent: 20, sequence: 1
	})
	const retried = module.mergeMediaUploadProgress(stale, {
		mediaType: 'IMAGE', outputIndex: 1, attempt: 2, maxAttempts: 3,
		state: 'UPLOADING', transferredBytes: 0, totalBytes: 100, percent: 0, sequence: 1
	})

	assert.equal(stale, initial)
	assert.equal(retried['image:1'].attempt, 2)
	assert.equal(retried['image:1'].percent, 0)
})

test('keeps unknown-length upload progress without inventing a percentage', async () => {
	const module = await loadModule()
	const value = module.mergeMediaUploadProgress({}, {
		mediaType: 'VIDEO', outputIndex: 0, attempt: 1, maxAttempts: 1,
		state: 'UPLOADING', transferredBytes: 4_194_304, totalBytes: null,
		percent: null, sequence: 1
	})

	assert.equal(value['video:0'].percent, null)
	assert.equal(value['video:0'].transferredBytes, 4_194_304)
})

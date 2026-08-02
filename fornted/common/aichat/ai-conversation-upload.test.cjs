const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadUpload() {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-conversation-upload.js'),
		'utf8'
	)
		.replace(
			"import { aiConversationApi } from './ai-conversation-api.js'",
			'const aiConversationApi = globalThis.__uploadApi'
		)
		.replace(
			"import { putFile } from '@/uni_modules/ait-oss-put'",
			'const putFile = globalThis.__putFile'
		)
	globalThis.__uploadApi = { createPreuploads: async () => ({}) }
	globalThis.__putFile = () => ({ close() {} })
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function files(count) {
	return Array.from({ length: count }, (_, index) => ({
		fileName: `${index}.jpg`,
		contentType: 'image/jpeg',
		sizeBytes: 1024,
		raw: { size: 1024 }
	}))
}

function preuploads(declared, session = 'session-1') {
	return {
		uploadSessionId: session,
		files: declared.map((file, index) => ({
			...file,
			attachmentId: `attachment-${session}-${index}`,
			uploadUrl: `https://oss.example.test/${session}/${index}`,
			uploadHeaders: {}
		}))
	}
}

test('one selection batch requests one preupload and never exceeds three uploads', async () => {
	const upload = await loadUpload()
	let preuploadCalls = 0
	let active = 0
	let maximumActive = 0
	const manager = upload.createConversationUploadManager({
		async createPreuploads(declared) {
			preuploadCalls += 1
			return preuploads(declared)
		},
		startUpload(file, signed, onProgress) {
			active += 1
			maximumActive = Math.max(maximumActive, active)
			let resolve
			const completed = new Promise(done => { resolve = done })
			setTimeout(() => {
				onProgress(100)
				active -= 1
				resolve()
			}, 1)
			return { completed, cancel() {} }
		}
	})

	const batch = manager.enqueueBatch(files(5))
	const result = await batch.completed

	assert.equal(preuploadCalls, 1)
	assert.equal(maximumActive, 3)
	assert.equal(result.filter(item => item.status === 'fulfilled').length, 5)
})

test('retryable upload failure obtains one fresh signed URL and retries once', async () => {
	const upload = await loadUpload()
	let preuploadCalls = 0
	let uploadCalls = 0
	const manager = upload.createConversationUploadManager({
		async createPreuploads(declared) {
			preuploadCalls += 1
			return preuploads(declared, `session-${preuploadCalls}`)
		},
		startUpload() {
			uploadCalls += 1
			return {
				completed: uploadCalls === 1
					? Promise.reject(Object.assign(new Error('server'), { code: 'OSS_500' }))
					: Promise.resolve(),
				cancel() {}
			}
		}
	})

	const batch = manager.enqueueBatch(files(1))
	const [result] = await batch.completed

	assert.equal(result.status, 'fulfilled')
	assert.equal(result.value.uploadSessionId, 'session-2')
	assert.equal(preuploadCalls, 2)
	assert.equal(uploadCalls, 2)
})

test('cancelling a task ignores late progress and settles the queue slot', async () => {
	const upload = await loadUpload()
	let lateProgress
	let cancelled = false
	const progress = []
	const manager = upload.createConversationUploadManager({
		createPreuploads: async declared => preuploads(declared),
		startUpload(file, signed, onProgress) {
			lateProgress = onProgress
			let reject
			const completed = new Promise((resolve, onReject) => { reject = onReject })
			return {
				completed,
				cancel() {
					cancelled = true
					reject(Object.assign(new Error('cancelled'), { code: 'OSS_UPLOAD_CANCELLED' }))
				}
			}
		}
	})
	const batch = manager.enqueueBatch(files(1), {
		onProgress(index, value) { progress.push([index, value]) }
	})
	await new Promise(resolve => setTimeout(resolve, 0))
	batch.tasks[0].cancel()
	lateProgress?.(80)
	const [result] = await batch.completed

	assert.equal(cancelled, true)
	assert.equal(result.status, 'rejected')
	assert.deepEqual(progress, [])
})

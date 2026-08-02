const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadState() {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-conversation-upload-state.js'),
		'utf8'
	)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function attachment(state, contentType = 'image/jpeg') {
	return {
		fileName: 'sample.jpg',
		contentType,
		sizeBytes: 1024,
		state
	}
}

test('validates attachment count, per-file size and combined size against existing files', async () => {
	const state = await loadState()
	const existing = Array.from({ length: 7 }, (_, index) => ({
		fileName: `${index}.txt`,
		contentType: 'text/plain',
		sizeBytes: 1024
	}))

	assert.equal(state.validateAttachmentSelection(existing, [{ sizeBytes: 1024 }]).count, 8)
	assert.throws(
		() => state.validateAttachmentSelection(existing, [{ sizeBytes: 1 }, { sizeBytes: 1 }]),
		error => error.code === 'AI_ATTACHMENT_COUNT_EXCEEDED'
	)
	assert.throws(
		() => state.validateAttachmentSelection([], [{ sizeBytes: 100 * 1024 * 1024 + 1 }]),
		error => error.code === 'AI_ATTACHMENT_TOO_LARGE'
	)
	assert.throws(
		() => state.validateAttachmentSelection(
			[{ sizeBytes: 100 * 1024 * 1024 }],
			[{ sizeBytes: 100 * 1024 * 1024 }, { sizeBytes: 1 }]
		),
		error => error.code === 'AI_ATTACHMENT_TOTAL_SIZE_EXCEEDED'
	)
})

test('only media attachments require matching model capability', async () => {
	const state = await loadState()
	const image = attachment(state.ATTACHMENT_UPLOAD_STATES.UPLOADED)
	const video = attachment(state.ATTACHMENT_UPLOAD_STATES.UPLOADED, 'video/mp4')
	const document = attachment(state.ATTACHMENT_UPLOAD_STATES.UPLOADED, 'application/pdf')

	assert.equal(state.isAttachmentCompatible(image, { capabilities: ['IMAGE'] }), true)
	assert.equal(state.isAttachmentCompatible(video, { capabilities: ['IMAGE'] }), false)
	assert.equal(state.isAttachmentCompatible(document, { capabilities: [] }), true)
	assert.equal(state.isAttachmentCompatible(video, null), true)
})

test('send gate explains uploading, failed and incompatible blockers', async () => {
	const state = await loadState()
	const model = { capabilities: ['IMAGE'] }

	assert.match(state.deriveSendGate({
		model,
		text: 'hello',
		attachments: [attachment(state.ATTACHMENT_UPLOAD_STATES.UPLOADING)],
		generating: false
	}).reason, /正在上传/)
	assert.match(state.deriveSendGate({
		model,
		text: 'hello',
		attachments: [attachment(state.ATTACHMENT_UPLOAD_STATES.FAILED)],
		generating: false
	}).reason, /上传失败/)
	assert.match(state.deriveSendGate({
		model,
		text: 'hello',
		attachments: [attachment(state.ATTACHMENT_UPLOAD_STATES.UPLOADED, 'video/mp4')],
		generating: false
	}).reason, /不受当前模型支持/)
	assert.equal(state.deriveSendGate({
		model,
		text: '',
		attachments: [attachment(state.ATTACHMENT_UPLOAD_STATES.UPLOADED)],
		generating: false
	}).allowed, true)
})

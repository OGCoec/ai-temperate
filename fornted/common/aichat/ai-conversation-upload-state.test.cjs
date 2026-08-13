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
	const audio = attachment(state.ATTACHMENT_UPLOAD_STATES.UPLOADED, 'audio/mpeg')
	const video = attachment(state.ATTACHMENT_UPLOAD_STATES.UPLOADED, 'video/mp4')
	const document = attachment(state.ATTACHMENT_UPLOAD_STATES.UPLOADED, 'application/pdf')

	assert.equal(state.requiredMediaCapability(image), 'IMAGE_INPUT')
	assert.equal(state.requiredMediaCapability(audio), 'AUDIO_INPUT')
	assert.equal(state.requiredMediaCapability(video), 'VIDEO_INPUT')
	assert.equal(state.isAttachmentCompatible(image, { capabilities: ['IMAGE_INPUT'] }), true)
	assert.equal(state.isAttachmentCompatible(image, { capabilities: ['IMAGE_GENERATION'] }), false)
	assert.equal(state.isAttachmentCompatible(audio, { capabilities: ['AUDIO_INPUT'] }), true)
	assert.equal(state.isAttachmentCompatible(video, { capabilities: ['IMAGE_INPUT'] }), false)
	assert.equal(state.isAttachmentCompatible(video, { capabilities: ['VIDEO_INPUT'] }), true)
	assert.equal(state.isAttachmentCompatible(document, { capabilities: [] }), true)
	assert.equal(state.isAttachmentCompatible(video, null), true)
})

test('send gate explains uploading, failed and incompatible blockers', async () => {
	const state = await loadState()
	const model = { capabilities: ['IMAGE_INPUT'] }

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

test('image edit mode accepts PNG JPEG and WebP without IMAGE_INPUT capability', async () => {
	const state = await loadState()
	const model = { capabilities: ['IMAGE_GENERATION', 'IMAGE_EDIT'] }
	for (const contentType of ['image/png', 'image/jpeg', 'image/webp']) {
		assert.equal(state.deriveSendGate({
			model,
			text: 'edit this',
			attachments: [attachment(state.ATTACHMENT_UPLOAD_STATES.UPLOADED, contentType)],
			generating: false,
			imageEditing: true
		}).allowed, true)
	}
	assert.match(state.deriveSendGate({
		model,
		text: 'edit this',
		attachments: [attachment(state.ATTACHMENT_UPLOAD_STATES.UPLOADED, 'image/svg+xml')],
		generating: false,
		imageEditing: true
	}).reason, /PNG、JPEG 和 WebP/)
})

test('media operation delegates media compatibility to its dedicated mode gate', async () => {
	const state = await loadState()
	const model = { capabilities: ['VIDEO_GENERATION'] }

	assert.equal(state.deriveSendGate({
		model,
		text: 'animate this image',
		attachments: [attachment(state.ATTACHMENT_UPLOAD_STATES.UPLOADED)],
		generating: false,
		mediaOperation: true
	}).allowed, true)
	assert.match(state.deriveSendGate({
		model,
		text: 'animate this image',
		attachments: [attachment(state.ATTACHMENT_UPLOAD_STATES.UPLOADING)],
		generating: false,
		mediaOperation: true
	}).reason, /正在上传/)
})

test('classifies Java archives as binary archives without treating Java source as an archive', async () => {
	const state = await loadState()
	for (const fileName of ['library.jar', 'application.war', 'module.ear']) {
		assert.equal(state.attachmentCategory({
			fileName,
			contentType: 'application/octet-stream'
		}), 'ARCHIVE', fileName)
	}
	assert.equal(state.attachmentCategory({
		fileName: 'Main.java',
		contentType: 'text/x-java-source'
	}), 'DOCUMENT')
})

test('separates optimistic image metadata from local preview paths', async () => {
	const state = await loadState()
	const result = state.createOptimisticInputPresentation([{
		fileName: 'shoe.png',
		contentType: 'image/png',
		sizeBytes: 305152,
		path: '/data/user/0/com.example/cache/ait-conversation-picks/local',
		uploaded: { attachmentId: 'attachment-1' }
	}])

	assert.equal(result.attachments[0].url, '')
	assert.equal(result.attachments[0].attachmentId, 'attachment-1')
	assert.equal(
		result.previewSources['attachment-1'],
		'/data/user/0/com.example/cache/ait-conversation-picks/local'
	)
	assert.equal(
		JSON.stringify(result.attachments).includes('/data/user/0'),
		false
	)
	assert.equal(Object.isFrozen(result), true)
	assert.equal(Object.isFrozen(result.attachments), true)
	assert.equal(Object.isFrozen(result.previewSources), true)
})

test('keeps non-image optimistic behavior outside the image preview fix', async () => {
	const state = await loadState()
	const videoPath = 'blob:https://app.example.test/video-preview'
	const documentPath = 'blob:https://app.example.test/document-preview'
	const svgPath = 'blob:https://app.example.test/svg-file'
	const audioPath = 'blob:https://app.example.test/audio-file'
	const result = state.createOptimisticInputPresentation([
		{
			fileName: 'clip.mp4',
			contentType: 'video/mp4',
			sizeBytes: 1024,
			path: videoPath,
			uploaded: { attachmentId: 'video-1' }
		},
		{
			fileName: 'notes.pdf',
			contentType: 'application/pdf',
			sizeBytes: 2048,
			path: documentPath,
			uploaded: { attachmentId: 'document-1' }
		},
		{
			fileName: 'diagram.svg',
			contentType: 'image/svg+xml',
			sizeBytes: 4096,
			path: svgPath,
			uploaded: { attachmentId: 'svg-1' }
		},
		{
			fileName: 'voice.mp3',
			contentType: 'audio/mpeg',
			sizeBytes: 8192,
			path: audioPath,
			uploaded: { attachmentId: 'audio-1' }
		}
	])

	assert.equal(result.attachments[0].url, videoPath)
	assert.equal(result.attachments[1].url, documentPath)
	assert.equal(result.attachments[2].url, svgPath)
	assert.equal(result.attachments[3].url, audioPath)
	assert.equal(result.attachments[3].category, 'AUDIO')
	assert.deepEqual(result.previewSources, {
		'video-1': videoPath,
		'document-1': documentPath,
		'svg-1': svgPath,
		'audio-1': audioPath
	})
	assert.equal(
		state.createOptimisticInputPresentation([
			{
				fileName: 'clip.mp4',
				contentType: 'video/mp4',
				sizeBytes: 1024,
				path: videoPath,
				uploaded: { attachmentId: 'video-1' }
			}
		], { suppressVideoPreview: true }).attachments[0].url,
		''
	)
})

test('rejects optimistic attachments without a server upload reference', async () => {
	const state = await loadState()
	assert.throws(
		() => state.createOptimisticInputPresentation([{
			fileName: 'shoe.png',
			contentType: 'image/png',
			sizeBytes: 305152,
			path: '/data/user/0/com.example/cache/local'
		}]),
		error => error.code === 'AI_ATTACHMENT_UPLOAD_REFERENCE_INVALID'
	)
})

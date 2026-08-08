const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

async function loadModule() {
	const source = fs.readFileSync(
		path.resolve(__dirname, 'ai-conversation-video-generation.js'), 'utf8')
	const url = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
	return import(`${url}#${Date.now()}-${Math.random()}`)
}

const model = Object.freeze({
	supportedVideoModes: [
		'TEXT_TO_VIDEO', 'IMAGE_TO_VIDEO', 'REFERENCE_TO_VIDEO',
		'VIDEO_EDIT', 'VIDEO_EXTEND'
	],
	supportedVideoResolutions: ['P480', 'P720', 'P1080'],
	supportedVideoAspectRatios: [
		'RATIO_1_1', 'RATIO_16_9', 'RATIO_9_16', 'RATIO_4_3',
		'RATIO_3_4', 'RATIO_3_2', 'RATIO_2_3'
	],
	videoDuration: { minimumSeconds: 1, maximumSeconds: 15 }
})

test('builds ordinary generation without external URLs or media bytes', async () => {
	const module = await loadModule()
	const request = module.videoGenerationRequest({
		model,
		mode: 'IMAGE_TO_VIDEO',
		durationSeconds: 10,
		resolution: 'P1080',
		aspectRatio: 'RATIO_16_9',
		attachments: [{ attachmentId: 'A'.repeat(38) }]
	})

	assert.deepEqual(request, {
		mode: 'IMAGE_TO_VIDEO',
		inputAttachmentPublicIds: ['A'.repeat(38)],
		durationSeconds: 10,
		resolution: 'P1080',
		aspectRatio: 'RATIO_16_9'
	})
	assert.equal(JSON.stringify(request).includes('url'), false)
	assert.equal(JSON.stringify(request).includes('base64'), false)
})

test('removes inherited controls from edit and extension requests', async () => {
	const module = await loadModule()
	const attachment = { attachmentId: 'B'.repeat(38) }
	const edit = module.videoGenerationRequest({
		model, mode: 'VIDEO_EDIT', durationSeconds: 15,
		resolution: 'P1080', aspectRatio: 'RATIO_16_9', attachments: [attachment]
	})
	const extension = module.videoGenerationRequest({
		model, mode: 'VIDEO_EXTEND', durationSeconds: 10,
		resolution: 'P1080', aspectRatio: 'RATIO_16_9', attachments: [attachment]
	})

	assert.deepEqual(edit, {
		mode: 'VIDEO_EDIT', inputAttachmentPublicIds: ['B'.repeat(38)]
	})
	assert.deepEqual(extension, {
		mode: 'VIDEO_EXTEND', inputAttachmentPublicIds: ['B'.repeat(38)],
		durationSeconds: 10
	})
})

test('caps reference generation at 720p', async () => {
	const module = await loadModule()
	assert.deepEqual(
		module.supportedVideoResolutionOptions(model, 'REFERENCE_TO_VIDEO')
			.map(option => option.value),
		['P480', 'P720'])
})

test('enforces attachment shapes without creating video previews', async () => {
	const module = await loadModule()
	assert.equal(module.videoSendGate({
		model, mode: 'TEXT_TO_VIDEO', text: 'prompt', attachments: []
	}).allowed, true)
	assert.match(module.videoSendGate({
		model,
		mode: 'VIDEO_EDIT',
		text: 'prompt',
		attachments: [{ contentType: 'video/webm' }]
	}).reason, /MP4/)
	assert.equal(module.videoSendGate({
		model,
		mode: 'REFERENCE_TO_VIDEO',
		text: 'prompt',
		attachments: Array.from({ length: 7 }, () => ({ contentType: 'image/png' }))
	}).allowed, true)
	assert.equal(module.isVideoAttachmentCompatible(
		'IMAGE_TO_VIDEO', { contentType: 'image/jpeg' }), true)
	assert.equal(module.isVideoAttachmentCompatible(
		'VIDEO_EXTEND', { contentType: 'video/mp4' }), true)
	assert.equal(module.isVideoAttachmentCompatible(
		'VIDEO_EXTEND', { contentType: 'video/webm' }), false)
})

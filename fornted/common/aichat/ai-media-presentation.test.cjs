const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadPresentation() {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-media-presentation.js'),
		'utf8'
	)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

test('creates an immutable HTTPS media descriptor without mutating the attachment', async () => {
	const presentation = await loadPresentation()
	const attachment = {
		attachmentId: 'attachment-public-id',
		url: 'https://media.example.test/video.mp4?signature=private',
		fileName: 'video.mp4',
		contentType: 'video/mp4',
		width: 640,
		height: 360,
		durationMillis: 5000
	}
	const snapshot = { ...attachment }
	const descriptor = presentation.createMediaDescriptor(attachment, {
		width: 1280,
		height: 720
	})

	assert.deepEqual(attachment, snapshot)
	assert.equal(Object.isFrozen(descriptor), true)
	assert.equal(descriptor.key, 'attachment-public-id')
	assert.equal(descriptor.src, attachment.url)
	assert.equal(descriptor.sourceKind, 'REMOTE_HTTPS')
	assert.equal(descriptor.width, 1280)
	assert.equal(descriptor.height, 720)
	assert.equal(descriptor.durationMillis, 5000)
})

test('accepts an explicit App local image source without weakening remote URLs', async () => {
	const presentation = await loadPresentation()
	const attachment = {
		attachmentId: 'input-image-1',
		url: '',
		fileName: 'shoe.png',
		contentType: 'image/png'
	}

	const descriptor = presentation.createMediaDescriptor(
		attachment,
		null,
		{ localSrc: '/data/user/0/com.example/cache/ait-conversation-picks/preview' }
	)

	assert.equal(
		descriptor.src,
		'/data/user/0/com.example/cache/ait-conversation-picks/preview'
	)
	assert.equal(descriptor.sourceKind, 'APP_LOCAL')
	assert.equal(
		presentation.createMediaDescriptor(
			attachment,
			null,
			{ localSrc: '_doc/ait-conversation-picks/preview.png' }
		).sourceKind,
		'APP_LOCAL'
	)
})

test('rejects unmarked or unsafe local media sources', async () => {
	const presentation = await loadPresentation()
	assert.throws(
		() => presentation.createMediaDescriptor({
			attachmentId: 'a',
			url: '/data/user/0/com.example/cache/private.png'
		}),
		/HTTPS URL/
	)
	for (const localSrc of [
		'http://media.example.test/private.png',
		'javascript:alert(1)',
		'data:image/png;base64,AAAA',
		'content://media/external/images/1',
		'/data/user/0/com.example/cache/../shared/secret.png',
		'_doc/../secret.png',
		'/data/user/0/com.example/cache/\u0000private.png',
		'/data/user/0/com.example/cache/private.png\n'
	]) {
		assert.throws(
			() => presentation.createMediaDescriptor(
				{ attachmentId: 'a', url: '', contentType: 'image/png' },
				null,
				{ localSrc }
			),
			/App local media source/
		)
	}
})

test('rejects missing keys and non-HTTPS media sources', async () => {
	const presentation = await loadPresentation()
	assert.throws(
		() => presentation.createMediaDescriptor({ url: 'https://media.example.test/a.mp4' }),
		/ public key /i
	)
	assert.throws(
		() => presentation.createMediaDescriptor({ attachmentId: 'a', url: 'http://media.example.test/a.mp4' }),
		/HTTPS URL/
	)
	assert.throws(
		() => presentation.createMediaDescriptor({ attachmentId: 'a', url: 'javascript:alert(1)' }),
		/HTTPS URL/
	)
})

test('normalizes invalid dimensions and resolves a stable aspect ratio', async () => {
	const presentation = await loadPresentation()
	const descriptor = presentation.createMediaDescriptor({
		attachmentId: 'a',
		url: 'https://media.example.test/a.mp4',
		width: -1,
		height: Number.POSITIVE_INFINITY
	})

	assert.equal(descriptor.width, null)
	assert.equal(descriptor.height, null)
	assert.equal(presentation.resolveMediaAspectRatio(descriptor), 16 / 9)
	assert.equal(presentation.resolveMediaAspectRatio({ width: 900, height: 1600 }), 900 / 1600)
	assert.equal(presentation.resolveMediaAspectRatio(null, 4 / 3), 4 / 3)
})

test('maps HTML media error codes to safe categories', async () => {
	const presentation = await loadPresentation()
	assert.equal(presentation.MEDIA_PHASES.WAITING_REMOTE, 'WAITING_REMOTE')
	assert.equal(presentation.classifyHtmlMediaError(1), 'ABORTED')
	assert.equal(presentation.classifyHtmlMediaError(2), 'NETWORK')
	assert.equal(presentation.classifyHtmlMediaError(3), 'DECODE')
	assert.equal(presentation.classifyHtmlMediaError(4), 'UNSUPPORTED')
	assert.equal(presentation.classifyHtmlMediaError(99), 'UNKNOWN')
})

test('allows only bounded Android text and source previews', async () => {
	const presentation = await loadPresentation()
	for (const fileName of ['readme.txt', 'guide.md', 'Main.java', 'data.json', 'app.vue']) {
		assert.equal(presentation.isAndroidPreviewableTextFile({
			fileName,
			sizeBytes: 512 * 1024
		}), true, fileName)
	}
	for (const fileName of ['library.jar', 'bundle.war', 'archive.ear', 'archive.zip', 'report.pdf']) {
		assert.equal(presentation.isAndroidPreviewableTextFile({
			fileName,
			sizeBytes: 1024
		}), false, fileName)
	}
	assert.equal(presentation.isAndroidPreviewableTextFile({
		fileName: 'large.txt',
		sizeBytes: 512 * 1024 + 1
	}), false)
})

test('pure presentation source does not depend on browser-only globals', () => {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-media-presentation.js'),
		'utf8'
	)
	for (const forbidden of ['window', 'document', 'URLSearchParams', 'Intl', 'new URL(']) {
		assert.equal(source.includes(forbidden), false, forbidden)
	}
})

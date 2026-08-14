const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function sourceUrl(source) {
	return `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
}

async function loadControllerModule() {
	const source = fs.readFileSync(
		path.join(__dirname, 'ai-conversation-android-image-source.js'),
		'utf8'
	)
	return import(`${sourceUrl(source)}#${Date.now()}-${Math.random()}`)
}

function createNativeHarness() {
	const materializations = []
	const downloads = []
	const removed = []
	return {
		materializations,
		downloads,
		removed,
		materializeBase64Image(options) {
			const operation = { closed: false, close() { this.closed = true } }
			materializations.push({ options, operation })
			return operation
		},
		fetchHttpsImage(options) {
			const operation = { closed: false, close() { this.closed = true } }
			downloads.push({ options, operation })
			return operation
		},
		removeManagedImage(filePath) {
			removed.push(filePath)
			return true
		}
	}
}

function managedImage(filePath, contentType = 'image/png', sizeBytes = 10) {
	return Object.freeze({
		filePath,
		displayUri: `file://${filePath}`,
		contentType,
		sizeBytes
	})
}

function previewAttachment(overrides = {}) {
	return Object.freeze({
		attachmentId: 'preview-0',
		outputIndex: 0,
		url: 'data:image/png;base64,iVBORw0KGgo=',
		contentType: 'image/png',
		previewKind: 'FULL',
		requiresUpgrade: false,
		...overrides
	})
}

function persistedAttachment(overrides = {}) {
	return Object.freeze({
		attachmentId: 'persisted-0',
		outputIndex: 0,
		url: 'data:image/png;base64,iVBORw0KGgo=',
		persistedUrl: 'https://media.example.test/final-image',
		persistedContentType: 'image/png',
		previewKind: 'FULL',
		requiresUpgrade: false,
		...overrides
	})
}

function historyImageAttachment(overrides = {}) {
	return Object.freeze({
		schemaVersion: 1,
		attachmentId: 'AZ_history_image_1',
		fileName: 'generated-1.webp',
		contentType: 'image/webp',
		sizeBytes: '1700000',
		category: 'IMAGE',
		url: 'https://media.example.test/history-image',
		state: 'AVAILABLE',
		failureCode: null,
		...overrides
	})
}

test('owner keys prefer the live local id and isolate history ids', async () => {
	const module = await loadControllerModule()

	assert.equal(
		module.androidGeneratedImageOwnerKey({
			localId: 'same-id',
			messagePublicId: 'persisted-id'
		}),
		'local:same-id'
	)
	assert.equal(
		module.androidGeneratedImageOwnerKey({ messagePublicId: 'same-id' }),
		'history:same-id'
	)
	assert.equal(module.androidGeneratedImageOwnerKey({}), '')
})

test('history image attachments receive stable unique output indexes without mutation', async () => {
	const module = await loadControllerModule()
	const attachments = Object.freeze([
		historyImageAttachment({
			attachmentId: 'filename-slot',
			fileName: 'generated-2.webp'
		}),
		historyImageAttachment({
			attachmentId: 'fallback-slot',
			fileName: 'provider-result.webp'
		}),
		historyImageAttachment({
			attachmentId: 'explicit-priority-slot',
			fileName: 'provider-duplicate.webp',
			outputIndex: 1
		}),
		historyImageAttachment({
			attachmentId: 'explicit-slot',
			fileName: 'provider-last.webp',
			outputIndex: 9
		}),
		historyImageAttachment({
			attachmentId: 'duplicate-explicit-slot',
			fileName: 'provider-conflict.webp',
			outputIndex: 9
		}),
		Object.freeze({
			attachmentId: 'not-an-image',
			fileName: 'notes.txt',
			contentType: 'text/plain',
			category: 'FILE',
			url: 'https://media.example.test/notes'
		})
	])
	const snapshots = attachments.map(attachment => ({ ...attachment }))

	const normalized = module.normalizeAndroidGeneratedImageAttachments(attachments)

	assert.deepEqual(normalized.map(attachment => attachment.outputIndex), [0, 2, 1, 9, 3])
	assert.deepEqual(
		normalized.map(attachment => attachment.attachmentId),
		[
			'filename-slot',
			'fallback-slot',
			'explicit-priority-slot',
			'explicit-slot',
			'duplicate-explicit-slot'
		]
	)
	assert.equal(normalized.every(attachment => attachment.imageSlot === true), true)
	assert.equal(normalized[0].url, attachments[0].url)
	assert.deepEqual(attachments.map(attachment => ({ ...attachment })), snapshots)
})

test('history image normalization accepts at most ten supported images', async () => {
	const module = await loadControllerModule()
	const attachments = Array.from({ length: 12 }, (_, index) =>
		historyImageAttachment({
			attachmentId: `history-${index}`,
			fileName: `provider-${index}.webp`
		}))

	const normalized = module.normalizeAndroidGeneratedImageAttachments(attachments)

	assert.equal(normalized.length, 10)
	assert.deepEqual(
		normalized.map(attachment => attachment.outputIndex),
		[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
	)
})

test('a FULL preview at or below 384000 bytes stays local after persistence', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const controller = module.createAndroidGeneratedImageSourceController(native)
	const attachment = previewAttachment({ sizeBytes: 384000 })
	const snapshot = { ...attachment }

	controller.acceptPreview('message-1', attachment)
	assert.equal(native.materializations.length, 1)
	assert.equal(native.materializations[0].options.maximumBytes, 384000)
	assert.equal(controller.sourceFor('message-1', 0), '')
	native.materializations[0].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/preview.png',
		'image/png',
		384000
	))

	assert.equal(controller.statusFor('message-1', 0), 'PREVIEW_READY')
	assert.equal(
		controller.sourceFor('message-1', 0),
		'file:///data/user/0/app/cache/ait-conversation-images/preview.png'
	)
	controller.acceptPersisted('message-1', persistedAttachment())
	assert.equal(native.downloads.length, 0)
	assert.equal(controller.statusFor('message-1', 0), 'PREVIEW_READY')
	assert.deepEqual(attachment, snapshot)
})

test('a THUMBNAIL preview upgrades exactly once and deletes the old thumbnail', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const controller = module.createAndroidGeneratedImageSourceController(native)
	const preview = previewAttachment({ previewKind: 'THUMBNAIL', requiresUpgrade: true })
	const persisted = persistedAttachment({ previewKind: 'THUMBNAIL', requiresUpgrade: true })

	controller.acceptPreview('message-2', preview)
	native.materializations[0].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/thumb.webp',
		'image/webp',
		120000
	))
	controller.acceptPersisted('message-2', persisted)
	controller.acceptPersisted('message-2', persisted)

	assert.equal(native.downloads.length, 1)
	assert.equal(controller.statusFor('message-2', 0), 'DOWNLOADING_FINAL')
	assert.equal(
		controller.sourceFor('message-2', 0),
		'file:///data/user/0/app/cache/ait-conversation-images/thumb.webp'
	)
	native.downloads[0].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/final.jpg',
		'image/jpeg',
		1700000
	))

	assert.equal(controller.statusFor('message-2', 0), 'FINAL_READY')
	assert.equal(
		controller.sourceFor('message-2', 0),
		'file:///data/user/0/app/cache/ait-conversation-images/final.jpg'
	)
	assert.deepEqual(native.removed, [
		'/data/user/0/app/cache/ait-conversation-images/thumb.webp'
	])
	controller.acceptPersisted('message-2', persisted)
	assert.equal(native.downloads.length, 1)
})

test('a failed final download preserves the visible thumbnail', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const controller = module.createAndroidGeneratedImageSourceController(native)

	controller.acceptPreview('message-3', previewAttachment({
		previewKind: 'THUMBNAIL',
		requiresUpgrade: true
	}))
	native.materializations[0].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/thumb.png',
		'image/png',
		100000
	))
	controller.acceptPersisted('message-3', persistedAttachment({
		previewKind: 'THUMBNAIL',
		requiresUpgrade: true
	}))
	native.downloads[0].options.onError({ code: 'NETWORK', message: 'failed' })

	assert.equal(controller.statusFor('message-3', 0), 'PREVIEW_READY')
	assert.equal(
		controller.sourceFor('message-3', 0),
		'file:///data/user/0/app/cache/ait-conversation-images/thumb.png'
	)
	controller.acceptPersisted('message-3', persistedAttachment({
		previewKind: 'THUMBNAIL',
		requiresUpgrade: true
	}))
	assert.equal(native.downloads.length, 1)
})

test('preview failure waits for persistence and then displays the downloaded final image', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const controller = module.createAndroidGeneratedImageSourceController(native)

	controller.acceptPreview('message-4', previewAttachment())
	native.materializations[0].options.onError({ code: 'DECODE_FAILED', message: 'failed' })
	assert.equal(controller.statusFor('message-4', 0), 'WAITING_REMOTE')

	controller.acceptPersisted('message-4', persistedAttachment())
	assert.equal(native.downloads.length, 1)
	native.downloads[0].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/final.webp',
		'image/webp',
		800000
	))
	assert.equal(controller.statusFor('message-4', 0), 'FINAL_READY')
})

test('a persisted HTTPS image without a preview materializes directly', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const controller = module.createAndroidGeneratedImageSourceController(native)

	controller.acceptPersisted('history-message', persistedAttachment({
		url: 'https://media.example.test/final-image',
		persistedUrl: ''
	}))
	assert.equal(native.downloads.length, 1)
	assert.equal(controller.statusFor('history-message', 0), 'DOWNLOADING_FINAL')
})

test('an API history image without localId or outputIndex downloads and becomes ready', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const controller = module.createAndroidGeneratedImageSourceController(native)
	const message = Object.freeze({
		messagePublicId: 'AZ_history_message',
		responseAttachments: Object.freeze([historyImageAttachment()])
	})
	const ownerKey = module.androidGeneratedImageOwnerKey(message)
	const [attachment] = module.normalizeAndroidGeneratedImageAttachments(
		message.responseAttachments
	)

	controller.acceptPersisted(ownerKey, attachment)
	assert.equal(native.downloads.length, 1)
	assert.equal(controller.statusFor(ownerKey, 0), 'DOWNLOADING_FINAL')
	native.downloads[0].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/history.webp',
		'image/webp',
		1700000
	))

	assert.equal(controller.statusFor(ownerKey, 0), 'FINAL_READY')
	assert.equal(
		controller.sourceFor(ownerKey, 0),
		'file:///data/user/0/app/cache/ait-conversation-images/history.webp'
	)
})

test('a failed history download enters ERROR and can be retried', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const controller = module.createAndroidGeneratedImageSourceController(native)
	const ownerKey = module.androidGeneratedImageOwnerKey({
		messagePublicId: 'AZ_failed_history_message'
	})
	const [attachment] = module.normalizeAndroidGeneratedImageAttachments([
		historyImageAttachment()
	])

	controller.acceptPersisted(ownerKey, attachment)
	native.downloads[0].options.onError({ code: 'NETWORK', message: 'failed' })
	assert.equal(controller.statusFor(ownerKey, 0), 'ERROR')
	const diagnosticRunId = native.downloads[0].options.diagnosticRunId
	assert.equal(native.downloads[0].options.downloadAttempt, 1)
	assert.equal(controller.retryFinal(ownerKey, 0), true)
	assert.equal(native.downloads.length, 2)
	assert.equal(native.downloads[1].options.diagnosticRunId, diagnosticRunId)
	assert.equal(native.downloads[1].options.downloadAttempt, 2)
})

test('a mismatched display URI is rejected and only the managed file path is removed', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const controller = module.createAndroidGeneratedImageSourceController(native)

	controller.acceptPersisted('history:unsafe-display', persistedAttachment({
		url: 'https://media.example.test/unsafe-display',
		persistedUrl: ''
	}))
	native.downloads[0].options.onSuccess({
		filePath: '/data/user/0/app/cache/ait-conversation-images/managed.png',
		displayUri: 'file:///data/user/0/app/cache/shared/not-managed.png',
		contentType: 'image/png',
		sizeBytes: 100
	})

	assert.equal(controller.statusFor('history:unsafe-display', 0), 'ERROR')
	assert.equal(controller.sourceFor('history:unsafe-display', 0), '')
	assert.deepEqual(native.removed, [
		'/data/user/0/app/cache/ait-conversation-images/managed.png'
	])
})

test('late operations cannot overwrite a newer preview and their files are removed', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const controller = module.createAndroidGeneratedImageSourceController(native)

	controller.acceptPreview('message-5', previewAttachment({ attachmentId: 'old' }))
	controller.acceptPreview('message-5', previewAttachment({
		attachmentId: 'new',
		url: 'data:image/png;base64,iVBORw0KGgoAAA=='
	}))
	assert.equal(native.materializations[0].operation.closed, true)
	native.materializations[1].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/new.png'
	))
	native.materializations[0].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/old.png'
	))

	assert.equal(
		controller.sourceFor('message-5', 0),
		'file:///data/user/0/app/cache/ait-conversation-images/new.png'
	)
	assert.deepEqual(native.removed, [
		'/data/user/0/app/cache/ait-conversation-images/old.png'
	])
})

test('a newer preview keeps the previous image visible until replacement succeeds', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const controller = module.createAndroidGeneratedImageSourceController(native)

	controller.acceptPreview('message-visible', previewAttachment({ attachmentId: 'first' }))
	native.materializations[0].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/first.png'
	))
	controller.acceptPreview('message-visible', previewAttachment({
		attachmentId: 'second',
		url: 'data:image/png;base64,iVBORw0KGgoAAA=='
	}))

	assert.equal(controller.statusFor('message-visible', 0), 'PREPARING_PREVIEW')
	assert.equal(
		controller.sourceFor('message-visible', 0),
		'file:///data/user/0/app/cache/ait-conversation-images/first.png'
	)
	native.materializations[1].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/second.png',
		'image/png',
		11
	))

	assert.equal(
		controller.sourceFor('message-visible', 0),
		'file:///data/user/0/app/cache/ait-conversation-images/second.png'
	)
	assert.deepEqual(native.removed, [
		'/data/user/0/app/cache/ait-conversation-images/first.png'
	])
})

test('a late final download cannot replace a newer persisted URL', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const controller = module.createAndroidGeneratedImageSourceController(native)

	controller.acceptPersisted('message-final-race', persistedAttachment({
		url: 'https://media.example.test/old',
		persistedUrl: ''
	}))
	controller.acceptPersisted('message-final-race', persistedAttachment({
		url: 'https://media.example.test/new',
		persistedUrl: ''
	}))
	assert.equal(native.downloads[0].operation.closed, true)
	native.downloads[1].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/new-final.webp',
		'image/webp',
		30
	))
	native.downloads[0].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/old-final.webp',
		'image/webp',
		30
	))

	assert.equal(
		controller.sourceFor('message-final-race', 0),
		'file:///data/user/0/app/cache/ait-conversation-images/new-final.webp'
	)
	assert.deepEqual(native.removed, [
		'/data/user/0/app/cache/ait-conversation-images/old-final.webp'
	])
	controller.acceptPreview('message-final-race', previewAttachment({
		url: 'data:image/png;base64,iVBORw0KGgoAAA=='
	}))
	assert.equal(native.materializations.length, 0)
})

test('release cancels work and removes only controller-owned paths', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const controller = module.createAndroidGeneratedImageSourceController(native)

	controller.acceptPreview('message-6', previewAttachment())
	native.materializations[0].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/preview.jpg',
		'image/jpeg'
	))
	controller.acceptPreview('message-6', previewAttachment({
		attachmentId: 'preview-1',
		outputIndex: 1
	}))
	const operation = native.materializations[1].operation
	controller.releaseMessage('message-6')

	assert.equal(operation.closed, true)
	assert.deepEqual(native.removed, [
		'/data/user/0/app/cache/ait-conversation-images/preview.jpg'
	])
	assert.equal(controller.sourceFor('message-6', 0), '')
})

test('exposes only the controller-owned local file path for explicit album saving', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const controller = module.createAndroidGeneratedImageSourceController(native)

	controller.acceptPreview('history:preview-only', previewAttachment())
	native.materializations[0].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/preview-only.webp',
		'image/webp',
		20
	))
	assert.equal(controller.filePathFor('history:preview-only', 0), '')

	controller.acceptPersisted('history:save-image', persistedAttachment())
	assert.equal(controller.filePathFor('history:save-image', 0), '')
	native.downloads[0].options.onSuccess(managedImage(
		'/data/user/0/app/cache/ait-conversation-images/save.webp',
		'image/webp',
		30
	))

	assert.equal(
		controller.filePathFor('history:save-image', 0),
		'/data/user/0/app/cache/ait-conversation-images/save.webp'
	)
})

test('diagnostics correlate one slot across native calls and preserve structured failures', async () => {
	const module = await loadControllerModule()
	const native = createNativeHarness()
	const diagnostics = []
	const controller = module.createAndroidGeneratedImageSourceController({
		...native,
		diagnosticsEnabled: true,
		onDiagnostic: diagnostic => diagnostics.push(diagnostic)
	})
	const ownerKey = 'history:diagnostic-message'

	controller.acceptPersisted(ownerKey, persistedAttachment({
		url: 'https://media.example.test/diagnostic-image',
		persistedUrl: ''
	}))

	const diagnosticRunId = controller.diagnosticRunIdFor(ownerKey, 0)
	assert.match(diagnosticRunId, /^img-[a-z0-9]+-\d+-0$/)
	assert.equal(native.downloads[0].options.diagnosticsEnabled, true)
	assert.equal(native.downloads[0].options.diagnosticRunId, diagnosticRunId)
	assert.equal(native.downloads[0].options.outputIndex, 0)
	assert.equal(native.downloads[0].options.downloadAttempt, 1)

	native.downloads[0].options.onError({
		code: 'AI_ANDROID_IMAGE_DECODE_FAILED',
		stage: 'BITMAP_DECODE',
		exceptionType: 'IllegalStateException',
		statusCode: 200,
		message: 'must not be logged'
	})

	const failure = diagnostics.find(item => item.phase === 'FINAL_ERROR_CALLBACK')
	assert.equal(failure.diagnosticRunId, diagnosticRunId)
	assert.equal(failure.failureCode, 'AI_ANDROID_IMAGE_DECODE_FAILED')
	assert.equal(failure.failureStage, 'BITMAP_DECODE')
	assert.equal(failure.exceptionType, 'IllegalStateException')
	assert.equal(failure.statusCode, 200)
	assert.equal(JSON.stringify(diagnostics).includes('must not be logged'), false)
})

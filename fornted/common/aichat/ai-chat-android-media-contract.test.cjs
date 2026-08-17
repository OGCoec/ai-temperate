const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const componentRoot = path.resolve(__dirname, '../../components/user/workspace')

function readComponent(name) {
	return fs.readFileSync(path.join(componentRoot, name), 'utf8')
}

function nativeVideosAreInsideNonAppBranches(source) {
	let offset = 0
	let count = 0
	while (true) {
		const videoIndex = source.indexOf('<video', offset)
		if (videoIndex < 0) break
		const branchStart = source.lastIndexOf('<!-- #ifndef APP-PLUS -->', videoIndex)
		const branchEnd = source.indexOf('<!-- #endif -->', videoIndex)
		if (branchStart < 0 || branchEnd < videoIndex) return false
		count += 1
		offset = videoIndex + 6
	}
	return count === 2
}

test('Android and H5 use separate media presentation branches', () => {
	const panel = readComponent('user-chat-panel.vue')

	assert.equal(panel.includes('<user-android-chat-video'), true)
	assert.equal(panel.includes('<user-android-chat-image'), true)
	assert.equal(panel.includes('<user-android-file-card'), true)
	assert.equal(nativeVideosAreInsideNonAppBranches(panel), true)
	assert.equal((panel.match(/<video\b/g) || []).length, 2)
	assert.equal(panel.includes('@loadedmetadata="handleGeneratedVideoMetadata(attachment, $event)"'), true)
})

test('Android image gallery exposes aspect-ratio calculation through component methods', () => {
	const panel = readComponent('user-chat-panel.vue')
	const gallery = readComponent('user-generated-image-gallery.vue')

	assert.equal(
		panel.includes(':aspect-ratio="generatedImageGalleryAspectRatio(message)"'),
		true
	)
	assert.match(
		panel,
		/generatedImageGalleryAspectRatio\(message\)\s*\{\s*return imageGalleryAspectRatio\(\s*message\?\.requestedImageAspect \|\| this\.selectedImageAspect\s*\)\s*\}/
	)
	assert.match(gallery, /galleryStyle\(\)\s*\{[\s\S]*?'--image-gallery-aspect'/)
})

test('Android video is a renderjs HTML5 player without native video or a child WebView', () => {
	const video = readComponent('user-android-chat-video.vue')

	assert.equal(video.includes('lang="renderjs"'), true)
	assert.equal(video.includes("document.createElement('video')"), true)
	assert.equal(video.includes('IntersectionObserver'), true)
	assert.equal(video.includes("rootMargin: '320px 0px'"), true)
	assert.equal(video.includes("video.preload = 'metadata'"), true)
	assert.equal(video.includes('video.autoplay = false'), true)
	assert.equal(video.includes('plus.video'), false)
	assert.equal(video.includes('plus.webview'), false)
	assert.equal(video.includes('<video'), false)
	assert.equal(
		video.indexOf("listen('loadedmetadata'") < video.indexOf('video.src = this.config.src'),
		true
	)
	assert.equal(video.indexOf('setTimeout(() => {') < video.indexOf('video.src = this.config.src'), true)
})

test('Android video state is revision guarded and cleanup releases every browser resource', () => {
	const video = readComponent('user-android-chat-video.vue')

	assert.equal(video.includes('payload.revision !== this.revision'), true)
	assert.equal(video.includes('payload.key !== this.descriptor.key'), true)
	assert.equal(video.includes('this.revision += 1'), true)
	assert.equal(video.includes('observer?.disconnect?.()'), true)
	assert.equal(video.includes('clearTimeout(this.loadTimer)'), true)
	assert.equal(video.includes('video.removeEventListener'), true)
	assert.equal(video.includes("video.removeAttribute('src')"), true)
	assert.equal(video.includes('video.remove()'), true)
	assert.equal(video.includes("this.emitState(MEDIA_PHASES.ERROR, 'TIMEOUT')"), true)
})

test('Android media never returns signed addresses through state events or diagnostics', () => {
	const video = readComponent('user-android-chat-video.vue')
	const statePayload = video.slice(
		video.indexOf("this.$emit('state-change'"),
		video.indexOf('const diagnosticStage')
	)
	const diagnostic = video.slice(
		video.indexOf('safeDiagnostic(stage'),
		video.indexOf('</script>')
	)

	assert.equal(statePayload.includes('src:'), false)
	assert.equal(statePayload.includes('url:'), false)
	assert.equal(diagnostic.includes('attachment'), false)
	assert.equal(diagnostic.includes('descriptor'), false)
})

test('Android scroll scheduling is instance scoped, cancellable, and DOM independent', () => {
	const panel = readComponent('user-chat-panel.vue')
	const scheduler = panel.slice(
		panel.indexOf('requestAndroidScrollBottom(reason'),
		panel.indexOf('generatedResponseImageKey(attachment)')
	)

	assert.equal(panel.includes('androidScrollEpoch'), true)
	assert.equal(panel.includes('androidScrollScheduled'), true)
	assert.equal(panel.includes('androidScrollTimer'), true)
	assert.equal(panel.includes('requestAndroidScrollBottom(reason'), true)
	assert.equal(panel.includes('invalidateAndroidScroll()'), true)
	assert.equal(panel.includes('const delay = immediate ? 0 : 50'), true)
	assert.equal(panel.includes('epoch !== this.androidScrollEpoch'), true)
	assert.equal(scheduler.includes('getBoundingClientRect'), false)
})

test('Android image and file components expose bounded visible failure paths', () => {
	const image = readComponent('user-android-chat-image.vue')
	const file = readComponent('user-android-file-card.vue')

	assert.equal(image.includes("variant === 'THUMBNAIL' ? 'aspectFill' : 'widthFix'"), true)
	assert.equal(image.includes('this.autoRetryCount < 1'), true)
	assert.equal(image.includes('图片加载失败'), true)
	assert.equal(image.includes("this.$emit('preview'"), true)
	assert.equal(file.includes('Java Archive'), true)
	assert.equal(file.includes('Java 源文件'), true)
	assert.equal(file.includes('v-html'), false)
})

test('Android secondary generated-image placeholders stay compact and retryable', () => {
	const image = readComponent('user-android-chat-image.vue')
	const gallery = readComponent('user-generated-image-gallery.vue')

	assert.match(image, /compactPlaceholder:\s*\{ type: Boolean, default: false \}/)
	assert.match(image, /'is-compact-placeholder': compactPlaceholder/)
	assert.match(image, /v-if="!compactPlaceholder"\s+class="android-image-placeholder-label"/)
	assert.match(image, /v-if="compactPlaceholder"[\s\S]*?class="android-image-compact-retry"/)
	assert.match(image, /aria-label="图片加载失败，点击重新加载"/)
	assert.match(gallery, /:compact-placeholder="true"/)
})

test('input images keep local previews outside persisted attachment URLs', () => {
	const panel = readComponent('user-chat-panel.vue')
	const image = readComponent('user-android-chat-image.vue')

	assert.equal(
		panel.includes(':local-src="inputAttachmentLocalSrc(message, attachment)"'),
		true
	)
	assert.equal(
		panel.includes(':src="inputAttachmentDisplaySrc(message, attachment)"'),
		true
	)
	assert.equal(panel.includes('createOptimisticInputPresentation'), true)
	assert.equal(panel.includes('previewImage(attachment, message)'), true)
	assert.equal(image.includes("localSrc: { type: String, default: '' }"), true)
	assert.equal(image.includes('localSrc: this.localSrc'), true)
})

test('Android input preview failure waits for the persisted URL instead of reporting upload failure', () => {
	const image = readComponent('user-android-chat-image.vue')

	assert.equal(image.includes("phase === 'WAITING_REMOTE'"), true)
	assert.equal(image.includes('图片正在处理'), true)
	assert.equal(image.includes('this.awaitingRemote'), true)
})

test('Android generated images use the private source controller without changing H5 src', () => {
	const panel = readComponent('user-chat-panel.vue')
	const gallery = readComponent('user-generated-image-gallery.vue')

	assert.equal(
		gallery.includes(':local-src="androidSource(attachment).src"'),
		true
	)
	assert.equal(
		gallery.includes(':source-status="androidSource(attachment).status"'),
		true
	)
	assert.equal(panel.includes(':android-sources="generatedImageAndroidSources(message)"'), true)
	assert.equal(panel.includes('createAndroidGeneratedImageSourceController'), true)
	assert.equal(panel.includes('androidGeneratedImageOwnerKey'), true)
	assert.equal(panel.includes('normalizeAndroidGeneratedImageAttachments'), true)
	assert.equal(panel.includes('syncAndroidGeneratedImageSources'), true)
	assert.equal(panel.includes('this.syncAndroidGeneratedImageSources(message)'), true)
	assert.match(
		gallery,
		/<!-- #ifndef APP-PLUS -->[\s\S]*?:src="attachment\.url"/
	)
})

test('Android generated image preview opens the conversation viewer with the confirmed render phase', () => {
	const panel = readComponent('user-chat-panel.vue')
	const gallery = readComponent('user-generated-image-gallery.vue')
	const image = readComponent('user-android-chat-image.vue')
	const viewer = readComponent('user-generated-image-viewer.vue')

	assert.equal(image.includes("sourceStatus: { type: String, default: '' }"), true)
	assert.match(image,
		/this\.\$emit\('preview',\s*\{[\s\S]*?attachment:\s*this\.attachment,[\s\S]*?src:\s*this\.renderSrc,[\s\S]*?phase:\s*this\.phase/)
	assert.equal(gallery.includes('@preview="emitOpen(attachment, $event)"'), true)
	assert.equal(panel.includes('@open="openGeneratedImageViewer"'), true)
	assert.equal(panel.includes('<user-generated-image-viewer'), true)
	assert.equal(viewer.includes('<swiper'), true)
	assert.equal(viewer.includes('mode="aspectFit"'), true)
	assert.equal(
		image.includes("if (this.sourceStatus && !String(this.localSrc || '').trim()) return null"),
		true
	)
	assert.equal(
		image.includes("['PREVIEW_READY', 'FINAL_READY'].includes(this.sourceStatus)"),
		true
	)
	assert.equal(image.includes("diagnosticRunId: { type: String, default: '' }"), true)
	assert.equal(image.includes('allowManagedFileUri: this.managedLocalSource'), true)
	assert.equal(gallery.includes(':managed-local-source="true"'), true)
	assert.equal(
		gallery.includes(':diagnostic-run-id="androidSource(attachment).diagnosticRunId"'),
		true
	)
})

test('Android generated image diagnostics cover page, controller, and view without logging sources', () => {
	const panel = readComponent('user-chat-panel.vue')
	const image = readComponent('user-android-chat-image.vue')
	const diagnosticSource = image.slice(
		image.indexOf('emitDiagnostic(phase'),
		image.indexOf('resetSource()', image.indexOf('emitDiagnostic(phase'))
	)

	for (const phase of [
		'SYNC_MESSAGE_ENTERED',
		'ATTACHMENT_NORMALIZED',
		'PREVIEW_DISPATCHED',
		'PERSISTED_DISPATCHED',
		'USER_RETRY_REQUESTED'
	]) {
		assert.equal(panel.includes(phase), true, phase)
	}
	for (const phase of [
		'VIEW_SOURCE_RESET',
		'VIEW_LOAD_STARTED',
		'VIEW_LOAD_SUCCEEDED',
		'VIEW_LOAD_FAILED',
		'VIEW_AUTO_RETRY_SCHEDULED',
		'VIEW_ERROR_SHOWN',
		'VIEW_MANUAL_RETRY'
	]) {
		assert.equal(image.includes(phase), true, phase)
	}
	assert.equal(diagnosticSource.includes('this.renderSrc'), false)
	assert.equal(diagnosticSource.includes('this.attachment'), false)
})

test('Android generated image upgrade never writes a local path into attachment.url', () => {
	const panel = readComponent('user-chat-panel.vue')
	const androidUpgrade = panel.slice(
		panel.indexOf("beginImageUpgrade(localId, attachment, reason = 'MESSAGE_VISIBLE')"),
		panel.indexOf('completeImageUpgrade(localId, outputIndex')
	)

	assert.equal(androidUpgrade.includes('attachment.url ='), false)
	assert.equal(androidUpgrade.includes('url: result.displayUrl'), false)
	assert.equal(androidUpgrade.includes('acceptPersisted(ownerKey, attachment)'), true)
})

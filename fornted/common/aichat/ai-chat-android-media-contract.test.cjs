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

	assert.equal(
		panel.includes(':aspect-ratio="generatedImageGalleryAspectRatio(message)"'),
		true
	)
	assert.match(
		panel,
		/generatedImageGalleryAspectRatio\(message\)\s*\{\s*return imageGalleryAspectRatio\(\s*message\?\.requestedImageAspect \|\| this\.selectedImageAspect\s*\)\s*\}/
	)
	assert.match(
		panel,
		/generatedImageGalleryStyle\(message\)\s*\{\s*const aspect = this\.generatedImageGalleryAspectRatio\(message\)/
	)
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

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const panelPath = path.resolve(__dirname, 'user-chat-panel.vue')

function panelSource() {
	return fs.readFileSync(panelPath, 'utf8')
}

test('AI generated video renders one accessible download action below the media frame', () => {
	const source = panelSource()
	assert.equal((source.match(/class="video-download-button"/g) || []).length, 1)
	assert.match(source, /v-if="previewVideo\(attachment\)"[\s\S]{0,180}class="video-download-button"/)
	assert.match(source, /@click="downloadVideo\(attachment\)"/)
	assert.match(source, /:disabled="videoDownloading\(attachment\)"/)
	assert.match(source, /:aria-busy="String\(videoDownloading\(attachment\)\)"/)
	assert.match(source, /videoDownloading\(attachment\) \? '正在下载' : '下载视频'/)
})

test('video download reuses the attachment URL and releases the temporary object URL', () => {
	const source = panelSource()
	assert.match(source, /async downloadVideo\(attachment\)/)
	assert.match(source, /fetch\(attachment\.url, \{ credentials: 'omit' \}\)/)
	assert.match(source, /await response\.blob\(\)/)
	assert.match(source, /URL\.createObjectURL\(blob\)/)
	assert.match(source, /link\.download = this\.videoDownloadFileName\(attachment\)/)
	assert.match(source, /URL\.revokeObjectURL\(objectUrl\)/)
	assert.match(source, /视频下载失败，请重试/)
})

test('video download state is keyed by attachment and cleaned up on unmount', () => {
	const source = panelSource()
	assert.match(source, /videoDownloadBusyById: \{\}/)
	assert.match(source, /videoDownloadObjectUrls: markRaw\(new Set\(\)\)/)
	assert.match(source, /releaseAllVideoDownloadObjectUrls\(\)/)
	assert.match(source, /beforeUnmount\(\)[\s\S]*this\.releaseAllVideoDownloadObjectUrls\(\)/)
	assert.match(source, /\.video-download-button/)
	assert.match(source, /\.video-download-button:focus-visible/)
})

test('video download action sits outside the framed media and is centered below it', () => {
	const source = panelSource()
	const videoCardRule = source.match(/\.attachment-card\.is-video \{[^}]+\}/)?.[0] || ''
	assert.match(videoCardRule, /overflow: visible;/)
	assert.match(videoCardRule, /border: 0;/)
	assert.match(videoCardRule, /border-radius: 0;/)
	assert.match(videoCardRule, /background: transparent;/)
	const videoFrameRule = source.match(/\.attachment-media-frame\.is-video \{[^}]+\}/)?.[0] || ''
	assert.match(videoFrameRule, /overflow: hidden;/)
	assert.match(videoFrameRule, /border: 1px solid #313a35;/)
	assert.match(videoFrameRule, /border-radius: 12px;/)
	assert.match(source, /\.video-download-button \{[^}]*margin: 10px auto 0;/)
})

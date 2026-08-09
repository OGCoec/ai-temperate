# Generated Video Download Button Implementation Plan

> **执行方式：** 在当前桌面工程内使用 `executing-plans` 逐项实施；不创建额外 worktree，不触碰已有后端与 FC 改动。步骤使用复选框（`- [ ]`）跟踪。

**Goal:** Add an H5-only download button below each available AI-generated video that downloads the existing OSS URL through `fetch → Blob` without changing images or backend APIs.

**Architecture:** Keep the UI and short-lived download state inside the existing `user-chat-panel.vue`, keyed by `attachmentId`. A focused CommonJS contract test guards placement, video-only scope, Blob download behavior, duplicate-click prevention, cleanup, error copy, and styling.

**Tech Stack:** Vue Options API, uni-app H5 conditional compilation, browser Fetch/Blob/Object URL APIs, Node.js built-in test runner.

---

## File map

- Create `fornted/components/user/workspace/user-video-download-contract.test.cjs`: generated-video-only UI and download-lifecycle contract.
- Modify `fornted/components/user/workspace/user-chat-panel.vue`: template, reactive busy state, Blob download methods, cleanup, and existing-theme styles.

No Java, YAML, API DTO, OSS upload, image rendering, database, Redis, or FC files change.

### Task 1: Add the failing video download contract

**Files:**
- Create: `fornted/components/user/workspace/user-video-download-contract.test.cjs`
- Read: `fornted/components/user/workspace/user-chat-panel.vue`

- [x] **Step 1: Create the focused contract test**

```js
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
```

- [ ] **Step 2: Run the test and verify RED**

Run from `fornted/`:

```powershell
node --test components/user/workspace/user-video-download-contract.test.cjs
```

Expected: three failures because the button, method, and state do not exist. The test only reads the Vue source and does not contact any service.

### Task 2: Implement the minimal H5 video download behavior

**Files:**
- Modify: `fornted/components/user/workspace/user-chat-panel.vue:179-197`
- Modify: `fornted/components/user/workspace/user-chat-panel.vue:718-830`
- Modify: `fornted/components/user/workspace/user-chat-panel.vue:2934-2945`
- Modify: `fornted/components/user/workspace/user-chat-panel.vue:3131-3185`
- Test: `fornted/components/user/workspace/user-video-download-contract.test.cjs`

- [x] **Step 1: Render the button only after an AI response video frame**

Insert immediately after the closing `attachment-media-frame` view in the AI response attachment loop and before `user-media-upload-progress`:

```vue
<!-- #ifdef H5 -->
<button
	v-if="previewVideo(attachment)"
	class="video-download-button"
	type="button"
	:disabled="videoDownloading(attachment)"
	:aria-busy="String(videoDownloading(attachment))"
	@click="downloadVideo(attachment)"
>
	<uni-icons type="download" size="16" color="#37d39a" aria-hidden="true" />
	<text>{{ videoDownloading(attachment) ? '正在下载' : '下载视频' }}</text>
</button>
<!-- #endif -->
```

This location is inside `message.responseAttachments` only. Do not add the button to `message.contentAttachments` or any image branch.

- [x] **Step 2: Add per-attachment in-memory state**

Add in `data()` next to generated-media state:

```js
videoDownloadBusyById: {},
videoDownloadObjectUrls: markRaw(new Set()),
```

- [x] **Step 3: Add Blob download and cleanup methods**

Add immediately after `previewVideo`:

```js
videoDownloading(attachment) {
	const attachmentId = String(attachment?.attachmentId || '')
	return Boolean(attachmentId && this.videoDownloadBusyById[attachmentId])
},
setVideoDownloading(attachmentId, downloading) {
	const next = { ...this.videoDownloadBusyById }
	if (downloading) next[attachmentId] = true
	else delete next[attachmentId]
	this.videoDownloadBusyById = next
},
videoDownloadFileName(attachment) {
	const fileName = String(attachment?.fileName || '').trim()
	return /\.mp4$/i.test(fileName) ? fileName : 'generated-video.mp4'
},
releaseVideoDownloadObjectUrl(objectUrl) {
	if (!objectUrl) return
	URL.revokeObjectURL(objectUrl)
	this.videoDownloadObjectUrls.delete(objectUrl)
},
releaseAllVideoDownloadObjectUrls() {
	this.videoDownloadObjectUrls.forEach(objectUrl => URL.revokeObjectURL(objectUrl))
	this.videoDownloadObjectUrls.clear()
},
async downloadVideo(attachment) {
	if (!this.previewVideo(attachment)
		|| !/^https:\/\//i.test(String(attachment.url || ''))) return
	const attachmentId = String(attachment.attachmentId || '')
	if (!attachmentId || this.videoDownloading(attachment)) return
	this.setVideoDownloading(attachmentId, true)
	let objectUrl = ''
	try {
		const response = await fetch(attachment.url, { credentials: 'omit' })
		if (!response.ok) throw new Error('VIDEO_DOWNLOAD_HTTP_FAILED')
		const blob = await response.blob()
		if (!blob.size) throw new Error('VIDEO_DOWNLOAD_EMPTY')
		objectUrl = URL.createObjectURL(blob)
		this.videoDownloadObjectUrls.add(objectUrl)
		const link = document.createElement('a')
		link.href = objectUrl
		link.download = this.videoDownloadFileName(attachment)
		link.rel = 'noopener'
		link.style.display = 'none'
		document.body.appendChild(link)
		link.click()
		link.remove()
		setTimeout(() => this.releaseVideoDownloadObjectUrl(objectUrl), 1000)
	} catch (_) {
		this.releaseVideoDownloadObjectUrl(objectUrl)
		uni.showToast({ title: '视频下载失败，请重试', icon: 'none' })
	} finally {
		this.setVideoDownloading(attachmentId, false)
	}
},
```

Do not log the URL, OSS key, response body, or exception.

- [x] **Step 4: Release remaining Blob URLs on unmount**

Add near the existing preview cleanup in `beforeUnmount()`:

```js
this.releaseAllVideoDownloadObjectUrls()
```

- [x] **Step 5: Add existing-theme button styles**

Add `.video-download-button` to the frosted-control selector, then add:

```scss
.video-download-button { min-height: 36px; margin: 8px 10px 10px auto; padding: 0 12px; display: inline-flex; align-items: center; justify-content: center; gap: 7px; border-radius: 10px; color: #a7e6c9; font-size: 12px; font-weight: 700; }
.video-download-button:disabled { cursor: wait; opacity: .62; }
.video-download-button:focus-visible { outline: 2px solid rgba(55, 211, 154, .82); outline-offset: 2px; }
```

Do not change the video's `720px × 1080px` constraints.

### Task 3: Verify and commit after explicit phase-two authorization

**Files:**
- Verify: `fornted/components/user/workspace/user-video-download-contract.test.cjs`
- Verify: existing chat suite from `fornted/package.json`
- Commit: only the two video-download implementation files

- [ ] **Step 1: Run the focused test and verify GREEN**

Run from `fornted/`:

```powershell
node --test components/user/workspace/user-video-download-contract.test.cjs
```

Expected: 3 tests pass, 0 fail.

- [ ] **Step 2: Run the existing chat tests**

Run from `fornted/`:

```powershell
npm run test:chat
```

Expected: all chat tests pass. They are local Node tests and must not connect to the backend, OSS, xAI, Redis, RabbitMQ, or PostgreSQL.

- [ ] **Step 3: Check the scoped diff**

```powershell
git diff --check -- fornted/components/user/workspace/user-chat-panel.vue fornted/components/user/workspace/user-video-download-contract.test.cjs
git diff -- fornted/components/user/workspace/user-chat-panel.vue fornted/components/user/workspace/user-video-download-contract.test.cjs
```

Expected: no whitespace or conflict-marker errors and no image/backend changes.

- [ ] **Step 4: Commit only the implementation files**

```powershell
git add -- fornted/components/user/workspace/user-chat-panel.vue fornted/components/user/workspace/user-video-download-contract.test.cjs
git commit -m "feat: download generated videos from chat"
```

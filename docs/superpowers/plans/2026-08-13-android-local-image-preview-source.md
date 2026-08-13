# Android 本地图片预览来源分离修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` task-by-task。当前项目采用两阶段交付：第一阶段只编写代码和测试源码；本文列出的 Node 测试、H5 检查、HBuilderX 构建和 Android 实机验证均须在用户再次明确授权后执行。

**Goal:** 消除 Android/App-Plus 用户图片在发送后先显示“图片加载失败”、回答完成后又恢复的错误状态，同时保持 H5 本地 Blob 预览和远端 HTTPS 安全边界不变。

**Architecture:** 本次修复只调整图片附件：图片的正式字段 `attachment.url` 只表示服务端返回的永久 HTTPS 地址；客户端本地图片预览通过按“消息本地 ID + 附件 ID”索引的内存表单独保存，禁止再把 Android 图片路径塞进 `url`。Android 图片组件通过显式 `localSrc` 属性接收可信本地路径，回答完成后删除本地预览索引并自然切换到正式 OSS URL。视频和普通文件保持现有乐观展示行为，避免本次图片修复引入跨媒体回归。

**Tech Stack:** uni-app、Vue Options API、App-Plus Android、Node.js `node:test`、阿里云 OSS。

---

## 已确认根因

1. Android 文件选择器返回应用缓存目录的绝对路径。
2. 发送时的乐观消息把该路径写进了 `contentAttachments[].url`。
3. `createMediaDescriptor()` 把 `url` 当成远端资源，只接受 `https://`。
4. Android 图片组件因此直接进入 `ERROR`；这不是上传状态失败。
5. 回答完成事件用服务端正式附件替换乐观附件，新的 HTTPS OSS URL 才能加载成功。

## 范围

### 本计划修改

- Android/App-Plus 用户输入图片的本地乐观预览。
- H5 用户输入图片对同一“本地预览索引”的读取，保证现有 Blob 行为不回归。
- Android 图片组件对“本地预览暂不可用”和“正式远端图片加载失败”的状态区分。
- 媒体来源的安全验证及对应的单元/契约测试源码。

### 本计划不修改

- 预上传接口、OSS PUT、临时对象最终化、数据库字段和 Spring 后端。
- 视频本地播放策略、文件附件下载策略和语音链路。
- 生成图片的 Base64/高清升级协议；如果后续确认其 Android 预览存在同类问题，应沿用本文的来源分离接口另立小任务，不能把 `data:` 全局加入远端 URL 白名单。
- `fornted/unpackage/**` 生成产物。

## 文件结构

- Modify: `fornted/common/aichat/ai-media-presentation.js` — 验证远端 HTTPS 与显式 App 本地预览来源。
- Modify: `fornted/common/aichat/ai-media-presentation.test.cjs` — 覆盖可信本地路径、未标记路径和危险协议。
- Modify: `fornted/common/aichat/ai-conversation-upload-state.js` — 构造乐观附件和按附件 ID 索引的本地预览表。
- Modify: `fornted/common/aichat/ai-conversation-upload-state.test.cjs` — 锁定 `url` 不再承载本地路径。
- Modify: `fornted/components/user/workspace/user-chat-panel.vue` — 保存、读取、释放本地预览索引，并分别传给 H5 与 App-Plus 渲染分支。
- Modify: `fornted/components/user/workspace/user-android-chat-image.vue` — 接收 `localSrc`，实现本地到远端的状态切换和准确错误文案。
- Modify: `fornted/common/aichat/ai-chat-android-media-contract.test.cjs` — 锁定模板接线、安全边界与等待状态。

> 当前工作区中的 `user-chat-panel.vue` 已有用户改动。实施时必须先读取当前 diff，只做精确补丁；禁止覆盖、回退或整文件格式化。未得到用户明确授权前，不提交 Git commit，也不暂存这个重叠文件。

---

### Task 1: 为媒体描述器增加显式的 App 本地来源

**Files:**
- Modify: `fornted/common/aichat/ai-media-presentation.test.cjs`
- Modify: `fornted/common/aichat/ai-media-presentation.js`

**Acceptance criteria:**
- [ ] 未提供本地来源时，远端资源仍必须是 HTTPS。
- [ ] 只有显式传入 `localSrc` 的调用方可以使用 App 本地绝对路径或 `_doc/` 路径。
- [ ] `http:`、`javascript:`、`data:`、`content:`、控制字符和包含 `..` 路径段的值仍被拒绝。

- [ ] **Step 1: 先编写来源策略失败测试**

在 `ai-media-presentation.test.cjs` 增加：

```js
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

	assert.equal(descriptor.src,
		'/data/user/0/com.example/cache/ait-conversation-picks/preview')
	assert.equal(descriptor.sourceKind, 'APP_LOCAL')
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
		'javascript:alert(1)',
		'content://media/external/images/1',
		'/data/user/0/com.example/cache/../shared/secret.png',
		'_doc/../secret.png'
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
```

- [ ] **Step 2: 第二阶段授权后确认测试能够捕获当前 Bug**

Run from `fornted`:

```powershell
node --test common/aichat/ai-media-presentation.test.cjs
```

Expected before implementation: FAIL because the current function ignores the third argument and rejects the empty remote URL.

- [ ] **Step 3: 实现最小来源解析器**

在 `ai-media-presentation.js` 中保持远端校验原样，并加入局部来源解析：

```js
function requireAppLocalSource(value) {
	const source = String(value || '').trim()
	const localShape = source.startsWith('/') || source.startsWith('_doc/')
	const hasParentSegment = source.split('/').includes('..')
	const hasControlCharacter = /[\u0000-\u001f\u007f]/.test(source)
	if (!localShape || hasParentSegment || hasControlCharacter) {
		throw new TypeError('App local media source is invalid.')
	}
	return source
}

function resolveMediaSource(attachment, options) {
	const localSrc = String(options?.localSrc || '').trim()
	if (localSrc) {
		return Object.freeze({ src: requireAppLocalSource(localSrc), kind: 'APP_LOCAL' })
	}
	return Object.freeze({
		src: requireHttpsSource(attachment.url || attachment.src),
		kind: 'REMOTE_HTTPS'
	})
}
```

将签名改为：

```js
export function createMediaDescriptor(attachment, metadata = null, options = {})
```

描述器使用解析结果：

```js
const source = resolveMediaSource(attachment, options)
return Object.freeze({
	key,
	src: source.src,
	sourceKind: source.kind,
	fileName: String(attachment.fileName || '').trim(),
	contentType: String(attachment.contentType || '').trim().toLowerCase(),
	width: positiveFiniteNumber(metadata?.width) ?? positiveFiniteNumber(attachment.width),
	height: positiveFiniteNumber(metadata?.height) ?? positiveFiniteNumber(attachment.height),
	durationMillis: normalizedDurationMillis(
		metadata?.durationMillis ?? attachment.durationMillis)
})
```

- [ ] **Step 4: 第二阶段授权后运行定向测试**

```powershell
node --test common/aichat/ai-media-presentation.test.cjs
```

Expected: all media presentation tests PASS;既有 HTTP 和 `javascript:` 拒绝测试继续通过。

---

### Task 2: 从乐观图片附件的 `url` 中移除本地路径

**Files:**
- Modify: `fornted/common/aichat/ai-conversation-upload-state.test.cjs`
- Modify: `fornted/common/aichat/ai-conversation-upload-state.js`

**Dependencies:** Task 1

**Acceptance criteria:**
- [ ] 可由现有图片组件预览的乐观图片附件只包含可公开的附件元数据，`url` 固定为空；SVG 继续按普通文件附件处理。
- [ ] 所有乐观附件的本地路径仍进入独立、只存在于内存的 `previewSources` 索引，以维持现有 Blob 生命周期回收；只有图片从 `url` 中移除本地路径。
- [ ] 视频和普通文件保持现有乐观 URL 行为，本次不修改其展示与打开策略。
- [ ] 索引使用服务端生成的 `attachmentId`，不依赖数组顺序。

- [ ] **Step 1: 添加乐观附件建模测试**

```js
test('separates optimistic attachment metadata from local preview paths', async () => {
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
	assert.equal(result.previewSources['attachment-1'],
		'/data/user/0/com.example/cache/ait-conversation-picks/local')
	assert.equal(JSON.stringify(result.attachments).includes('/data/user/0'), false)
})
```

- [ ] **Step 2: 第二阶段授权后确认测试先失败**

```powershell
node --test common/aichat/ai-conversation-upload-state.test.cjs
```

Expected before implementation: FAIL because `createOptimisticInputPresentation` does not exist.

- [ ] **Step 3: 实现纯函数构造器**

在 `ai-conversation-upload-state.js` 中加入：

```js
export function createOptimisticInputPresentation(files, options = {}) {
	const previewSources = {}
	const attachments = Array.from(files || []).map(file => {
		const attachmentId = String(file?.uploaded?.attachmentId || '').trim()
		if (!attachmentId) {
			throw stateError('AI_ATTACHMENT_UPLOAD_REFERENCE_INVALID',
				'附件上传引用无效。')
		}
		const category = attachmentCategory(file)
		const contentType = String(file?.contentType || '').trim().toLowerCase()
		const localPath = String(file?.path || '').trim()
		if (localPath) {
			previewSources[attachmentId] = localPath
		}
		const suppressVideoPreview = options.suppressVideoPreview === true
			&& category === 'VIDEO'
		const localImagePreview = category === 'IMAGE'
			&& contentType !== 'image/svg+xml'
		return Object.freeze({
			attachmentId,
			fileName: String(file.fileName || ''),
			contentType,
			sizeBytes: String(file.sizeBytes),
			category,
			url: localImagePreview || suppressVideoPreview ? '' : localPath,
			state: 'AVAILABLE'
		})
	})
	return Object.freeze({
		attachments: Object.freeze(attachments),
		previewSources: Object.freeze(previewSources)
	})
}
```

- [ ] **Step 4: 第二阶段授权后运行上传状态测试**

```powershell
node --test common/aichat/ai-conversation-upload-state.test.cjs
```

Expected: all upload state tests PASS.

---

### Task 3: 将本地预览索引接入 H5 与 Android 消息渲染

**Files:**
- Modify: `fornted/components/user/workspace/user-chat-panel.vue`
- Modify: `fornted/components/user/workspace/user-android-chat-image.vue`
- Modify: `fornted/common/aichat/ai-chat-android-media-contract.test.cjs`

**Dependencies:** Tasks 1-2

**Acceptance criteria:**
- [ ] Android 发送后立即使用 App 缓存路径显示图片，不经过远端 HTTPS 校验。
- [ ] H5 继续使用 Blob 本地预览；回答完成后切换正式 URL 并回收 Blob。
- [ ] 回答完成替换附件后，相同 `attachmentId` 的组件会因 `localSrc`/`attachment` 更新而重置来源。

- [ ] **Step 1: 先扩充 Android 媒体契约测试**

在 `ai-chat-android-media-contract.test.cjs` 增加源码契约：

```js
test('input images keep local previews outside persisted attachment URLs', () => {
	const panel = readComponent('user-chat-panel.vue')
	const image = readComponent('user-android-chat-image.vue')

	assert.equal(panel.includes(':local-src="inputAttachmentLocalSrc(message, attachment)"'), true)
	assert.equal(panel.includes(':src="inputAttachmentDisplaySrc(message, attachment)"'), true)
	assert.equal(panel.includes('createOptimisticInputPresentation'), true)
	assert.equal(image.includes("localSrc: { type: String, default: '' }"), true)
	assert.equal(image.includes('{ localSrc: this.localSrc }'), true)
})
```

- [ ] **Step 2: 第二阶段授权后确认契约测试先失败**

```powershell
node --test common/aichat/ai-chat-android-media-contract.test.cjs
```

Expected before implementation: FAIL because the template and component do not yet expose `localSrc`.

- [ ] **Step 3: 在发送边界建立本地预览索引**

在 `user-chat-panel.vue` 导入 `createOptimisticInputPresentation`。发送时替换现有的内联 `selectedAttachments.map(...)`：

```js
const inputPresentation = createOptimisticInputPresentation(
	selectedAttachments,
	{ suppressVideoPreview: this.videoGenerationAvailable }
)
if (Object.keys(inputPresentation.previewSources).length) {
	this.localPreviewUrls.set(localId, inputPresentation.previewSources)
}
```

乐观消息使用：

```js
contentAttachments: inputPresentation.attachments,
```

新增两个只读解析方法：

```js
inputAttachmentLocalSrc(message, attachment) {
	const localId = String(message?.localId || '')
	const attachmentId = String(attachment?.attachmentId || '')
	return String(this.localPreviewUrls.get(localId)?.[attachmentId] || '')
},
inputAttachmentDisplaySrc(message, attachment) {
	return this.inputAttachmentLocalSrc(message, attachment)
		|| String(attachment?.url || '')
},
```

更新 Blob 回收函数，使对象索引也能安全展开：

```js
previewSourceValues(value) {
	if (Array.isArray(value)) return value
	if (value && typeof value === 'object') return Object.values(value)
	return []
},
releasePreviewUrls(value) {
	// #ifdef H5
	this.previewSourceValues(value).forEach(url => {
		if (String(url || '').startsWith('blob:')) {
			globalThis.URL?.revokeObjectURL?.(url)
		}
	})
	// #endif
},
```

完成事件必须先同时移除本地索引并提交正式附件，再在下一次 Vue 渲染完成后回收旧 Blob，避免 H5 在 DOM 尚未切换时提前撤销当前来源：

```js
const previewSources = this.localPreviewUrls.get(localId)
this.localPreviewUrls.delete(localId)
this.applyStore(patchLocalMessage(localId, {
	messagePublicId: event.data.messagePublicId,
	contentAttachments: event.data.inputAttachments || [],
	responseAttachments: event.data.responseAttachments || [],
	streaming: false,
	saving: false,
	modelActivity: null,
	warnings: event.data.warnings || []
}))
this.$nextTick(() => this.releasePreviewUrls(previewSources))
```

启用异步 generation observer 时，终态事件不携带 `inputAttachments`。该分支必须使用 `messagePublicId` 从历史接口只读对账同一消息的正式 `contentAttachments`；对账成功后再删除本地索引并于 `$nextTick` 回收 Blob。对账失败时保留本地预览，避免把网络失败再次伪装成图片加载失败。
视频生成成功的 `video_ready` 同样只携带 `messagePublicId` 而不携带正式输入附件，因此必须复用上述历史对账；`video_failed` 没有正式消息证据时继续保留本地预览。

- [ ] **Step 4: 分别接线 H5 与 App-Plus 模板**

App-Plus 输入图片：

```vue
<user-android-chat-image
	v-if="previewImage(attachment, message)"
	:attachment="attachment"
	:local-src="inputAttachmentLocalSrc(message, attachment)"
	variant="FULL"
	@layout-change="handleAndroidMediaLayoutChange"
	@preview="previewAndroidImage"
/>
```

H5 输入图片：

```vue
<image
	v-if="previewImage(attachment, message)"
	class="attachment-image"
	:src="inputAttachmentDisplaySrc(message, attachment)"
	mode="aspectFill"
/>
```

输入图片分支必须把 `message` 传给 `previewImage`，使 `attachment.url` 为空时仍可根据本地预览索引判断图片可展示；响应图片分支继续只依赖正式 URL。

- [ ] **Step 5: 让 Android 图片组件监听两类来源**

新增属性并传给描述器：

```js
props: {
	attachment: { type: Object, required: true },
	localSrc: { type: String, default: '' },
	variant: {
		type: String,
		default: 'FULL',
		validator: value => ['FULL', 'THUMBNAIL'].includes(value)
	},
	aspectRatio: { type: Number, default: null }
},
```

```js
descriptor() {
	try {
		return createMediaDescriptor(
			this.attachment,
			null,
			{ localSrc: this.localSrc }
		)
	} catch (_) {
		return null
	}
},
```

监听两者，确保终态替换会重新加载：

```js
watch: {
	attachment() { this.resetSource() },
	localSrc() { this.resetSource() }
},
```

- [ ] **Step 6: 第二阶段授权后运行定向测试**

```powershell
node --test common/aichat/ai-media-presentation.test.cjs common/aichat/ai-conversation-upload-state.test.cjs common/aichat/ai-chat-android-media-contract.test.cjs
```

Expected: all targeted tests PASS.

---

### Task 4: 区分“本地预览不可用”和“正式图片失败”

**Files:**
- Modify: `fornted/components/user/workspace/user-android-chat-image.vue`
- Modify: `fornted/common/aichat/ai-chat-android-media-contract.test.cjs`

**Dependencies:** Task 3

**Acceptance criteria:**
- [ ] 本地预览首次失败后仍允许一次有限重试。
- [ ] 本地预览重试仍失败但正式 URL 尚未到达时，显示“图片已上传，正在处理”，不能显示“图片加载失败”。
- [ ] 正式 HTTPS 图片加载失败时保留现有“图片加载失败/重新加载”。

- [ ] **Step 1: 添加等待状态契约**

```js
test('Android input preview failure waits for the persisted URL instead of reporting upload failure', () => {
	const image = readComponent('user-android-chat-image.vue')
	assert.equal(image.includes("phase === 'WAITING_REMOTE'"), true)
	assert.equal(image.includes('图片已上传，正在处理'), true)
	assert.equal(image.includes('this.awaitingRemote'), true)
})
```

- [ ] **Step 2: 实现等待远端的派生状态**

模板在错误块之前增加：

```vue
<view v-else-if="phase === 'WAITING_REMOTE'" class="android-image-placeholder" role="status">
	<uni-icons type="image" size="24" color="#8fdcbe" aria-hidden="true" />
	<text>图片已上传，正在处理</text>
</view>
```

本地来源最终失败时：

```js
finishLoadFailure() {
	this.renderSrc = ''
	this.phase = this.awaitingRemote ? 'WAITING_REMOTE' : 'ERROR'
	this.emitState()
},
```

`handleError()` 的一次自动重试耗尽后调用 `finishLoadFailure()`；`resetSource()` 在描述器无效时也使用相同判断。正式 URL 到达后，`attachment` 与 `localSrc` watcher 会重新进入 `LOADING`。

- [ ] **Step 3: 第二阶段授权后运行 Android 展示测试**

```powershell
npm run test:android-chat-presentation
```

Expected: PASS；错误文案只用于正式远端加载失败。

---

### Task 5: 全量回归与真实端到端验收

**Files:**
- No source changes unless a preceding test exposes an in-scope regression.

**Dependencies:** Tasks 1-4

#### 第二阶段：Node 回归测试（需用户明确授权）

- [ ] Run targeted suite:

```powershell
node --test common/aichat/ai-media-presentation.test.cjs common/aichat/ai-conversation-upload-state.test.cjs common/aichat/ai-chat-android-media-contract.test.cjs
```

Expected: PASS.

- [ ] Run chat regression suite:

```powershell
npm run test:chat
```

Expected: PASS；H5 SSE、会话 Store、图片生成、上传与 Android 契约无回归。

- [ ] Run focused Android presentation suite:

```powershell
npm run test:android-chat-presentation
```

Expected: PASS.

#### 第三阶段：HBuilderX/Android 实机（需单独授权）

- [ ] 使用非生产账号与测试会话安装调试 APK。
- [ ] 依次选择 PNG、JPEG、WebP，等待上传进度到 100%，然后发送。
- [ ] 从发送到回答完成期间，用户消息中的图片始终可见；不得出现“图片加载失败”。
- [ ] 回答完成后图片仍可见，点击预览正常，抓包最终正式 OSS GET 为 200。
- [ ] 人为删除或破坏本地缓存预览时，只显示“图片已上传，正在处理”；正式 URL 到达后自动恢复。
- [ ] 关闭页面、取消发送、新建会话后，不得继续引用已经释放的本地预览。

#### 第三阶段：H5 手工回归（需单独授权）

- [ ] 选择图片后确认 Blob 预览正常。
- [ ] 发送后回答生成期间图片持续可见。
- [ ] completed 事件后切换正式 HTTPS URL，并回收旧 Blob URL。
- [ ] 浏览器控制台无 Blob 过早释放或图片加载错误。

#### 网络与隐私验收

- [ ] `preuploads` 返回 201、OSS PUT 返回 200、回答请求返回 200。
- [ ] 本地 `/data/user/...`、`_doc/...` 和 `blob:` 地址不进入请求体、SSE、日志、Redis 或数据库。
- [ ] 服务端返回的 `http:`、`javascript:`、`data:` 或本地路径不能进入正式附件渲染。
- [ ] 正式附件仍只接受 HTTPS OSS URL。

---

## 依赖顺序

```text
Task 1 媒体来源解析
  -> Task 2 乐观附件与本地路径分离
    -> Task 3 H5/Android 接线
      -> Task 4 准确的等待/失败状态
        -> Task 5 经授权验证
```

任务必须顺序执行。Task 3 同时修改共享的 `user-chat-panel.vue`，不适合与其他前端任务并行。

## 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| `user-chat-panel.vue` 已有未提交改动 | 误覆盖用户工作 | 实施前查看限定 diff，只用精确补丁，不整文件格式化或回退 |
| Blob URL 在 Vue 完成重新渲染前被回收 | H5 短暂闪烁 | 同一同步边界先删除本地索引并 patch 正式附件，再在 `$nextTick` 回收旧 Blob |
| 本地路径校验过严 | 部分 Android 版本无法预览 | 覆盖实际 `/data/user/...` 与 `_doc/...` 两类路径，不允许通过放宽远端 HTTPS 规则解决 |
| 本地预览失败被误认为上传失败 | 用户误解状态 | 独立 `WAITING_REMOTE`，上传状态仍由上传状态机决定 |
| 把本地图片路径意外发给后端 | 隐私泄漏 | 乐观图片附件 `url` 固定为空，网络/隐私验收检查请求体和日志 |

## 完成标准

- Android 上传成功后不再出现“先失败、回答完成后成功”的错误闪烁。
- 图片本地预览与正式 HTTPS URL 的职责在数据结构中完全分离。
- H5 Blob 预览行为不变。
- 本地预览失败与正式远端失败使用不同 UI 状态。
- 第一阶段只交付代码和测试源码，并明确所有未执行验证。
- 第二、三阶段只有在用户明确批准相应命令和基础设施后才能执行。

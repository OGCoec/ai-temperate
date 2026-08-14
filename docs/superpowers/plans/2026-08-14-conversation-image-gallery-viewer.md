# ChatGPT 风格双主图与会话级图片查看器实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 H5 与 Android 的生成图片体验改为“单次最多 10 张、消息内按数量自适应排版、5～10 张时固定两张主图加副图轨道、点击任意图片进入会话级连续查看器”，并补齐 H5 下载、Android 保存、键盘/手势切换与历史图片懒加载。

**Architecture:** 把“单条消息怎么排版”和“整个会话有哪些可预览图片”拆成两层纯数据模型。消息层只处理本次生成的 1～10 张图片，并使用稳定的 `outputIndex` 排序；查看器层以 `messagePublicId/localId + outputIndex` 为稳定身份，将当前已加载消息中的图片按会话时间合并，并在靠近边界时复用现有消息分页 API 拉取更早页面。H5 与 Android 共用身份、排序、选中项和分页状态，但分别实现符合平台习惯的全屏表面与下载/保存动作。

**Tech Stack:** uni-app、Vue Options API、H5 DOM/CSS、App-Plus Android、现有 Android UTS 图片缓存、Node.js `node:test`、现有 `GET /api/ai/conversations/{conversationPublicId}/messages` 游标分页接口。

---

## 一、已确认的产品边界

- [ ] 单次图片生成数量继续限制为 1～10 张，不扩大生成接口上限。
- [ ] 1 张：一张主图。
- [ ] 2 张：两张等权主图。
- [ ] 3 张：一张主图 + 两张副图。
- [ ] 4 张：一张主图 + 三张副图。
- [ ] 5～10 张：两张等权主图 + 一条紧凑副图轨道；副图可滚动、展开，也可直接进入查看器。
- [ ] `+N` 必须是可点击、可聚焦、有中文无障碍名称的真实按钮，不再是 `aria-hidden` 遮罩。
- [ ] 消息卡片只代表“一次生成”；全屏查看器代表“整个会话”。例如同一会话有两批各 10 张，查看器显示 `1/20`～`20/20`。
- [ ] 会话级查看不设 10 张上限；采用现有消息游标分页逐页加载，禁止一次性递归拉完整个超长会话。
- [ ] 点击任意主图、副图或 `+N` 都能打开详细查看；打开时必须定位到实际被点击图片，而不是永远从第一张开始。
- [ ] H5 提供关闭、上一张、下一张、缩略图选择、下载；Android 提供关闭、左右滑动、缩略图/圆点选择、保存到相册。分享仅在平台能力可靠时显示。
- [ ] 图片编辑、评论、移除和宽高比调整不属于本次范围；不能用无功能按钮假装与 ChatGPT 完全一致。

## 二、再次检查 ChatGPT.com 得到的可复用规律

本次检查只连接了用户外部 Chrome 扩展实例，没有使用 Codex In-app Browser，也没有回退到隐藏 WebView。

- [ ] ChatGPT 消息内图片展示是“一个有边界的主展示区 + 独立缩略图轨道”，不是把所有原图按自然高度直接铺进消息流。
- [ ] 实测主区域约为 `480 × 480`，整个媒体组约为 `768 × 480`；缩略图约为 `56 × 56`，轨道滚动范围被主图高度约束。
- [ ] 同一会话存在两批各 10 张图片时，点击主图打开的媒体查看器包含 20 张，按钮的无障碍名称从“第 1 张图片，共 20 张”持续到“第 20 张图片，共 20 张”。这说明详细查看器按会话聚合，而不是只看当前消息的十张。
- [ ] 桌面查看器使用黑色全屏遮罩、居中 `contain` 主图、垂直缩略图轨道和顶部操作区；移动端使用黑色全屏表面、底部圆点/横向缩略图和顶部关闭/保存/分享。
- [ ] 当前项目不需要照搬 ChatGPT 的“一张主图”，而是在 5～10 张时把主区改成“两张主图”；其余导航、选中态、连续查看和下载行为按同一交互原则实现。

## 三、当前实现为什么会过大、杂乱、点了没反应

| 问题 | 当前代码证据 | 直接后果 | 计划中的修复 |
| --- | --- | --- | --- |
| 拼图 class 名不一致 | `user-chat-panel.vue` 生成 `is-hero_two` / `is-hero_three`，CSS 只声明 `is-hero-two` / `is-hero-three` | 三、四张图的桌面拼图规则完全没有命中，图片按普通块纵向放大 | 用显式 class 映射，禁止对枚举只做 `toLowerCase()` |
| 视觉顺序按网络到达先后锁定 | `recordImagePresentationOrder()` 保存 PARTIAL 第一次到达顺序，现有测试还锁定 `[7, 2]` | 并发生成时第 7 张可能突然成为主图，刷新后顺序与流式阶段不一致 | 展示顺序固定为 `outputIndex` 升序；到达顺序只用于进度诊断，不再决定版位 |
| 五张以上只渲染前四张 | `MAX_VISIBLE_IMAGE_OUTPUTS = 4`，`visibleIndexes.slice(0, 4)` | 后六张根本没有可交互节点，只剩一个覆盖层数字 | 返回全部副图模型；折叠只改变可见副图数量，不丢失项目 |
| `+6` 是遮罩，不是按钮 | 模板使用普通 `<view aria-hidden="true">`，没有 click | 点击没有业务事件，覆盖层还可能截获底图点击 | 改为 `<button @click.stop>`，打开/展开真实副图 |
| H5 图片没有 click | H5 `<image>` 只有 `@load` | 主图和副图均不能打开详细查看 | 统一发出 `open-image(message, attachment)` |
| Android 预览只传一张 | `uni.previewImage({ current: source, urls: [source] })` | 只能看当前图，无法在同会话连续切换，也没有定制保存栏 | 删除单图系统预览路径，改为项目自有会话级查看器 |
| Android 未就绪点击静默无效 | `user-android-chat-image.vue` 仅在 `phase === READY` 时 emit | 用户看到图片或占位却点不动，也没有反馈 | 始终发图片身份；查看器显示加载/失败状态并允许重试 |
| 高清升级只覆盖 `visibleItems` | `beginVisibleImageUpgrades()` 和 H5 `visibleOutputIndexes` 门禁 | 隐藏副图即使进入查看器也可能一直停在预览质量 | 升级集合改为“消息可见项 + 查看器当前项及相邻项” |

## 四、目标数据流

```text
一条消息的 responseAttachments（最多 10 张）
  -> 按 outputIndex 稳定排序
  -> 单次生成展示模型
       1: SINGLE
       2: PAIR
       3: HERO_TWO
       4: HERO_THREE
       5..10: DUAL_WITH_RAIL
  -> 主图/副图/折叠副图均保留同一个 imageIdentity

当前会话已加载 messages + 当前流式本地消息
  -> 过滤成功、可查看、非 SVG 的生成图片
  -> 按消息时间正序，再按 outputIndex 正序
  -> 按 imageIdentity 去重预览版/最终版
  -> 会话级 viewerItems
  -> 点击身份定位 activeIdentity
  -> 靠近第一张且 hasMoreBefore 时读取更早消息页
  -> 前插旧图片后仍以 activeIdentity 保持当前图不跳动
```

稳定身份规则：

```js
const ownerId = String(message.messagePublicId || message.localId || '').trim()
const outputIndex = Number(attachment.outputIndex)
const imageIdentity = `${ownerId}:${outputIndex}`
```

不能把数组下标当身份。加载更早历史后数组会前插，只有 `activeIdentity` 能保证用户正在看的图片不被切换。

---

### Task 1：重写单次生成图片展示模型，先修复顺序与布局语义

**Files:**

- Modify: `fornted/common/aichat/ai-conversation-image-gallery.test.cjs`
- Modify: `fornted/common/aichat/ai-conversation-image-gallery.js`

**Dependencies:** 无。

**Acceptance criteria:**

- [ ] 展示顺序只由合法 `outputIndex` 0～9 决定，不再由 PARTIAL/FINAL 到达先后决定。
- [ ] 预览版本被最终版本替换时，身份和版位不变。
- [ ] 5～10 张均返回两张 `primaryItems`，剩余返回 `secondaryItems`。
- [ ] 默认副图轨道展示前三张副图；超出的数量通过 `hiddenSecondaryCount` 表达，但 `allItems` 仍完整保留。
- [ ] 失败项不进入视觉集合；进度仍只统计 FINAL 证据。

- [ ] **Step 1：先把现有“到达先后决定主图”测试改为目标契约**

```js
test('orders generated images by outputIndex regardless of streaming arrival', async () => {
	const module = await loadModule()
	const view = module.createImageGalleryPresentation({
		attachments: [image(7, 'FINAL'), image(2, 'PARTIAL'), image(0, 'FINAL')],
		presentationOrder: [7, 2, 0],
		requestedCount: 3
	})

	assert.deepEqual(view.allItems.map(item => item.outputIndex), [0, 2, 7])
	assert.equal(view.layout, 'HERO_TWO')
	assert.deepEqual(view.primaryItems.map(item => item.outputIndex), [0])
	assert.deepEqual(view.secondaryItems.map(item => item.outputIndex), [2, 7])
})
```

- [ ] **Step 2：用表驱动测试锁定 1～10 张布局**

```js
const cases = [
	[1, 'SINGLE', 1, 0],
	[2, 'PAIR', 2, 0],
	[3, 'HERO_TWO', 1, 2],
	[4, 'HERO_THREE', 1, 3],
	[5, 'DUAL_WITH_RAIL', 2, 3],
	[6, 'DUAL_WITH_RAIL', 2, 4],
	[10, 'DUAL_WITH_RAIL', 2, 8]
]

for (const [count, layout, primaryCount, secondaryCount] of cases) {
	const attachments = Array.from({ length: count }, (_, index) => image(index))
	const view = module.createImageGalleryPresentation({ attachments, requestedCount: count })
	assert.equal(view.layout, layout)
	assert.equal(view.primaryItems.length, primaryCount)
	assert.equal(view.secondaryItems.length, secondaryCount)
	assert.equal(view.allItems.length, count)
}
```

- [ ] **Step 3：将展示函数改为明确分区，保留短期兼容字段**

目标返回结构：

```js
return Object.freeze({
	layout,
	allItems: Object.freeze(allItems),
	primaryItems: Object.freeze(primaryItems),
	secondaryItems: Object.freeze(secondaryItems),
	visibleSecondaryItems: Object.freeze(secondaryItems.slice(0, 3)),
	orderedOutputIndexes: Object.freeze(allItems.map(item => item.outputIndex)),
	visibleOutputIndexes: Object.freeze([
		...primaryItems,
		...secondaryItems.slice(0, 3)
	].map(item => item.outputIndex)),
	hiddenSecondaryCount: Math.max(0, secondaryItems.length - 3),
	completedCount,
	pendingCount,
	requestedCount: normalizedRequestedCount,
	progressLabel
})
```

实现时保留一轮 `visibleItems` 兼容别名，等 Task 5 完成所有调用迁移后再删除；不要让大文件重构和数据模型重构在同一个未编译状态中停留。

- [ ] **Step 4：第二阶段授权后运行定向测试**

```powershell
Set-Location C:\Users\damn\Desktop\ai-temperate-main\fornted
node --test common/aichat/ai-conversation-image-gallery.test.cjs
```

Expected: 1～10 张布局、稳定排序、失败项过滤和进度测试全部通过。

---

### Task 2：建立会话级图片清单与稳定选中状态

**Files:**

- Create: `fornted/common/aichat/ai-conversation-image-viewer.js`
- Create: `fornted/common/aichat/ai-conversation-image-viewer.test.cjs`

**Dependencies:** Task 1。

**Acceptance criteria:**

- [ ] 同一会话两条消息各 10 张图片时，返回 20 个查看项。
- [ ] 消息按时间正序；每条消息内部按 `outputIndex` 正序。
- [ ] 同一身份先出现 PARTIAL、后出现 FINAL 时只保留一个项目，并优先 FINAL/持久化 URL。
- [ ] 失败、空 URL、SVG、非生成图片不会混入查看器。
- [ ] 前插更早消息页后，通过身份重新计算索引，当前图片不跳动。

- [ ] **Step 1：先定义纯函数契约**

```js
export function generatedImageIdentity(message, attachment)
export function conversationGeneratedImages(messages)
export function mergeConversationGeneratedImages(currentItems, olderMessages)
export function activeGeneratedImageIndex(items, activeIdentity)
export function adjacentGeneratedImageItems(items, activeIdentity, radius = 1)
```

- [ ] **Step 2：规范化每个查看项**

```js
function viewerItem(message, attachment) {
	const identity = generatedImageIdentity(message, attachment)
	if (!identity || !viewableGeneratedImage(attachment)) return null
	return Object.freeze({
		identity,
		ownerId: String(message.messagePublicId || message.localId),
		messagePublicId: String(message.messagePublicId || ''),
		localId: String(message.localId || ''),
		outputIndex: Number(attachment.outputIndex),
		attachment,
		createdAt: String(message.createdAt || message.completedAt || '')
	})
}
```

`viewableGeneratedImage()` 必须同时检查：`imageSlot === true`、`state === AVAILABLE`、`status !== FAILED`、`contentType` 是非 SVG 图片、存在安全的预览或持久化来源。

- [ ] **Step 3：覆盖跨批次、去重与前插保持选中项**

```js
test('aggregates every generated image in the loaded conversation', async () => {
	const module = await loadModule()
	const items = module.conversationGeneratedImages([
		message('message-1', 0, 10),
		message('message-2', 10, 10)
	])
	assert.equal(items.length, 20)
	assert.equal(items[0].identity, 'message-1:0')
	assert.equal(items[19].identity, 'message-2:9')
})

test('keeps the active image after older messages are prepended', async () => {
	const module = await loadModule()
	const current = module.conversationGeneratedImages([message('newer', 10, 2)])
	const activeIdentity = current[1].identity
	const merged = module.mergeConversationGeneratedImages(
		current,
		[message('older', 0, 10)]
	)
	assert.equal(merged[module.activeGeneratedImageIndex(merged, activeIdentity)].identity,
		activeIdentity)
})
```

- [ ] **Step 4：第二阶段授权后运行定向测试**

```powershell
Set-Location C:\Users\damn\Desktop\ai-temperate-main\fornted
node --test common/aichat/ai-conversation-image-viewer.test.cjs
```

---

### Task 3：抽取 H5 下载与平台保存策略，避免查看器直接拼接危险 URL

**Files:**

- Create: `fornted/common/aichat/ai-conversation-image-download.js`
- Create: `fornted/common/aichat/ai-conversation-image-download.test.cjs`
- Modify: `fornted/common/aichat/ai-chat-android-media-contract.test.cjs`

**Dependencies:** Task 2。

**Acceptance criteria:**

- [ ] H5 只下载 `https:`、已受控的 Blob/Object URL 或当前页面生成的可信 `data:image`；拒绝 `http:`、`javascript:`、控制字符和路径穿越。
- [ ] H5 远程下载继续使用 `fetch(..., { credentials: 'omit' })`，避免把会话 Cookie 发给 OSS。
- [ ] H5 下载结束后释放 Object URL，不泄漏长会话内存。
- [ ] Android 只保存现有控制器返回的受控本地文件；没有本地文件时才通过现有控制器下载最终 HTTPS 图片。
- [ ] Android 相册权限只在用户点击“保存”后申请；拒绝权限、下载失败、保存失败都有明确 Toast。

- [ ] **Step 1：实现可单测的文件名和 H5 下载描述器**

```js
export function generatedImageFileName(item) {
	const original = String(item?.attachment?.fileName || '').trim()
	if (/\.(png|jpe?g|webp)$/i.test(original)) return original
	return `generated-${Number(item?.outputIndex) + 1 || 1}.png`
}

export function h5GeneratedImageSource(item) {
	const source = String(item?.displaySrc || item?.attachment?.url || '').trim()
	if (/^https:\/\/[^\s]+$/i.test(source)) return { source, kind: 'HTTPS' }
	if (/^blob:/i.test(source)) return { source, kind: 'BLOB' }
	if (/^data:image\/(?:png|jpe?g|webp);base64,/i.test(source)) {
		return { source, kind: 'DATA_IMAGE' }
	}
	throw new TypeError('Generated image source is not downloadable.')
}
```

- [ ] **Step 2：H5 下载复用现有视频下载的 Blob 生命周期模式**

```js
const response = await fetch(source, { credentials: 'omit' })
if (!response.ok) throw new Error('IMAGE_DOWNLOAD_HTTP_FAILED')
const blob = await response.blob()
if (!blob.size) throw new Error('IMAGE_DOWNLOAD_EMPTY')
const objectUrl = URL.createObjectURL(blob)
try {
	const link = document.createElement('a')
	link.href = objectUrl
	link.download = fileName
	link.rel = 'noopener'
	document.body.appendChild(link)
	link.click()
	link.remove()
} finally {
	setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
}
```

- [ ] **Step 3：Android 保存动作只接收受控本地来源**

查看器向父组件发出 `save`；父组件使用：

```js
uni.saveImageToPhotosAlbum({
	filePath: controlledLocalFilePath,
	success: () => uni.showToast({ title: '已保存到相册', icon: 'success' }),
	fail: error => showAndroidImageSaveFailure(error)
})
```

不得重新把 `data:` URL 或任意远程 URL 直接交给系统相册 API；应复用 `androidGeneratedImageSourceController` 已建立的下载、大小限制、内容类型验证和托管文件清理边界。

- [ ] **Step 4：第二阶段授权后运行安全边界测试**

```powershell
Set-Location C:\Users\damn\Desktop\ai-temperate-main\fornted
node --test common/aichat/ai-conversation-image-download.test.cjs common/aichat/ai-chat-android-media-contract.test.cjs
```

---

### Task 4：将消息内拼图抽成组件，落实“两张主图 + 副图轨道”

**Files:**

- Create: `fornted/components/user/workspace/user-generated-image-gallery.vue`
- Create: `fornted/components/user/workspace/user-generated-image-gallery-contract.test.cjs`
- Modify: `fornted/components/user/workspace/user-chat-panel.vue`
- Modify: `fornted/components/user/workspace/user-android-chat-image.vue`

**Dependencies:** Tasks 1～2。

**Acceptance criteria:**

- [ ] `user-chat-panel.vue` 不再在消息循环中重复调用 `generatedImageGallery(message)` 五次以上；先得到 presentation，再交给组件。
- [ ] class 使用显式映射：`HERO_TWO -> is-hero-two`、`HERO_THREE -> is-hero-three`、`DUAL_WITH_RAIL -> is-dual-with-rail`。
- [ ] 5～10 张在 H5 和 Android 都只有一组有限高度主区，不能再出现四张 720px 宽图片连续纵向铺满屏幕。
- [ ] 两张主图等宽、等高；副图 56～72px，并有明显选中/按下反馈。
- [ ] 每一张图片和 `+N` 都发出包含 `message`、`attachment`、`identity` 的 `open` 事件。
- [ ] Android 源仍由现有 `androidGeneratedImageSourceController` 提供，禁止退回直连 Base64/OSS 的旧路径。

- [ ] **Step 1：定义组件输入输出**

```js
props: {
	message: { type: Object, required: true },
	presentation: { type: Object, required: true },
	aspectRatio: { type: Number, default: 1 },
	androidClient: { type: Boolean, default: false },
	androidSources: { type: Object, default: () => ({}) }
},
emits: ['open', 'expand', 'image-load', 'android-layout-change', 'android-retry']
```

`androidSources` 由父组件按图片身份构建，值只包含 `{ src, status, diagnosticRunId }`；组件不得持有或释放控制器。

- [ ] **Step 2：使用两个明确区域而不是一个模糊的 `visibleItems` 网格**

```html
<view class="generated-image-primary" :class="layoutClass">
	<button
		v-for="item in presentation.primaryItems"
		:key="item.attachmentId"
		class="generated-image-tile is-primary"
		type="button"
		@click="open(item)"
	>
		<!-- H5 image / Android controlled image -->
	</button>
</view>

<scroll-view
	v-if="presentation.secondaryItems.length"
	class="generated-image-secondary"
	scroll-x
>
	<button
		v-for="item in visibleSecondaryItems"
		:key="item.attachmentId"
		class="generated-image-tile is-secondary"
		type="button"
		@click="open(item)"
	/>
	<button
		v-if="presentation.hiddenSecondaryCount"
		class="generated-image-more"
		type="button"
		:aria-label="`查看其余 ${presentation.hiddenSecondaryCount} 张图片`"
		@click.stop="$emit('expand')"
	>
		+{{ presentation.hiddenSecondaryCount }}
	</button>
</scroll-view>
```

对于 3、4 张，副图不是底部轨道，而是桌面右侧 2/3 格、窄屏底部 2/3 格；对于 5～10 张才使用 `DUAL_WITH_RAIL`。

- [ ] **Step 3：把尺寸变成有上限的响应式规则**

建议基础变量：

```scss
.generated-image-gallery {
	--gallery-max-width: 720px;
	--gallery-gap: 8px;
	--gallery-thumb-size: clamp(56px, 12vw, 72px);
	width: min(100%, var(--gallery-max-width));
}

.generated-image-primary.is-dual-with-rail {
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: var(--gallery-gap);
}

.generated-image-primary.is-dual-with-rail .is-primary {
	min-height: 0;
	aspect-ratio: var(--image-gallery-aspect, 1);
}

.generated-image-secondary {
	max-width: 100%;
	margin-top: 8px;
	white-space: nowrap;
}

.generated-image-secondary .is-secondary,
.generated-image-more {
	width: var(--gallery-thumb-size);
	height: var(--gallery-thumb-size);
	margin-right: 8px;
}
```

Android 仍使用同一主图数量和副图轨道；仅把圆角、间距、safe-area 和触控最小尺寸调整为移动端值。禁止使用四张 `width:100%` 卡片充当 5～10 张布局。

- [ ] **Step 4：修复 Android 点击静默无效**

`user-android-chat-image.vue` 的 `handlePreview()` 改为始终上报身份与当前状态：

```js
handlePreview() {
	this.$emit('preview', {
		attachment: this.attachment,
		src: this.renderSrc,
		phase: this.phase
	})
}
```

父级打开查看器后，如果 `phase` 未 READY，则显示加载/错误态，并调用现有 retry；不能静默吞掉点击。

- [ ] **Step 5：第二阶段授权后运行组件契约测试**

```powershell
Set-Location C:\Users\damn\Desktop\ai-temperate-main\fornted
node --test components/user/workspace/user-generated-image-gallery-contract.test.cjs common/aichat/ai-chat-android-media-contract.test.cjs
```

---

### Task 5：实现 H5 全屏会话级图片查看器

**Files:**

- Create: `fornted/components/user/workspace/user-generated-image-viewer.vue`
- Create: `fornted/components/user/workspace/user-generated-image-viewer-contract.test.cjs`
- Modify: `fornted/components/user/workspace/user-chat-panel.vue`

**Dependencies:** Tasks 2～4。

**Acceptance criteria:**

- [ ] `position: fixed; inset: 0` 黑色查看器覆盖整个工作区，z-index 高于侧栏、composer 和设置弹层。
- [ ] 主图使用 `object-fit: contain`，不裁切，不因原图比例撑高页面。
- [ ] 桌面为左侧纵向缩略图，移动 H5 为底部横向缩略图/圆点。
- [ ] 支持 `Escape` 关闭、左右方向键切换、缩略图点击选择；首尾按键禁用而不是越界。
- [ ] 打开时聚焦查看器，关闭时恢复到原点击按钮；背景滚动锁定并在所有退出路径恢复。
- [ ] 显示 `当前序号 / 已加载总数`；仍有更早页面时显示“正在加载更早图片”或“继续加载”。
- [ ] 下载只针对当前图片，按钮有 busy/disabled 状态和失败反馈。

- [ ] **Step 1：建立受控组件接口**

```js
props: {
	open: { type: Boolean, default: false },
	items: { type: Array, default: () => [] },
	activeIdentity: { type: String, default: '' },
	hasMoreBefore: { type: Boolean, default: false },
	loadingBefore: { type: Boolean, default: false },
	downloadBusy: { type: Boolean, default: false }
},
emits: ['close', 'select', 'request-older', 'download', 'retry']
```

组件只渲染和发事件，不直接调用历史 API，也不拥有会话状态。

- [ ] **Step 2：H5 模板结构**

```html
<!-- #ifdef H5 -->
<view
	v-if="open"
	ref="dialog"
	class="generated-image-viewer"
	role="dialog"
	aria-modal="true"
	aria-label="图片查看器"
	tabindex="-1"
	@keydown.esc.prevent="$emit('close')"
	@keydown.left.prevent="selectPrevious"
	@keydown.right.prevent="selectNext"
>
	<header class="viewer-toolbar">
		<button type="button" aria-label="关闭图片查看器" @click="$emit('close')">×</button>
		<text>{{ activeIndex + 1 }} / {{ items.length }}</text>
		<button type="button" :disabled="downloadBusy" @click="$emit('download', activeItem)">下载</button>
	</header>
	<aside class="viewer-thumbnails" aria-label="会话图片">
		<button v-for="(item, index) in items" :key="item.identity" @click="$emit('select', item.identity)">
			<image :src="displaySource(item)" mode="aspectFill" />
		</button>
	</aside>
	<main class="viewer-stage">
		<image :src="displaySource(activeItem)" mode="aspectFit" />
	</main>
</view>
<!-- #endif -->
```

- [ ] **Step 3：只预加载当前项相邻一张**

打开或切换后调用 `adjacentGeneratedImageItems(items, activeIdentity, 1)`，让父组件升级当前图与前后各一张。不要一次性下载会话中几百张高清图。

- [ ] **Step 4：滚动接近最早缩略图时请求一页**

当 `activeIndex <= 2 && hasMoreBefore && !loadingBefore` 时只发一次 `request-older`。请求完成后父级前插项目，组件仍依赖 `activeIdentity` 重新计算索引。

- [ ] **Step 5：第二阶段授权后运行 H5 查看器契约测试**

```powershell
Set-Location C:\Users\damn\Desktop\ai-temperate-main\fornted
node --test components/user/workspace/user-generated-image-viewer-contract.test.cjs
```

---

### Task 6：在同一查看器组件中实现 Android 原生习惯的滑动与保存

**Files:**

- Modify: `fornted/components/user/workspace/user-generated-image-viewer.vue`
- Modify: `fornted/components/user/workspace/user-generated-image-viewer-contract.test.cjs`
- Modify: `fornted/common/aichat/ai-chat-android-media-contract.test.cjs`
- Modify: `fornted/components/user/workspace/user-chat-panel.vue`

**Dependencies:** Tasks 3、5。

**Acceptance criteria:**

- [ ] Android 使用 `<swiper>`，左右滑动改变 `activeIdentity`，不是 `uni.previewImage({ urls: [source] })`。
- [ ] 主图使用 `aspectFit`，黑色背景，顶部和底部考虑 safe-area。
- [ ] 底部缩略图最多只渲染当前附近窗口；大量图片时不能一次创建全部高清 `<image>` 节点。
- [ ] 当前图片仍在下载最终文件时显示可见加载状态；失败可重试。
- [ ] 点击保存调用受控本地路径的 `uni.saveImageToPhotosAlbum`。

- [ ] **Step 1：App-Plus 分支使用受控 swiper**

```html
<!-- #ifdef APP-PLUS -->
<view v-if="open" class="generated-image-viewer is-android">
	<view class="viewer-toolbar is-android-safe">
		<button type="button" @click="$emit('close')">关闭</button>
		<text>{{ activeIndex + 1 }} / {{ items.length }}</text>
		<button type="button" :disabled="downloadBusy" @click="$emit('download', activeItem)">保存</button>
	</view>
	<swiper class="viewer-swiper" :current="activeIndex" @change="handleSwiperChange">
		<swiper-item v-for="item in renderedWindow" :key="item.identity">
			<image :src="displaySource(item)" mode="aspectFit" />
		</swiper-item>
	</swiper>
	<scroll-view class="viewer-mobile-thumbnails" scroll-x>
		<!-- 当前项附近缩略图 -->
	</scroll-view>
</view>
<!-- #endif -->
```

如果使用窗口化 swiper，必须保留“局部 swiper index ↔ 全局 item index”转换测试，禁止切到窗口边界时跳图。

- [ ] **Step 2：父级为每个查看项解析 Android 本地源**

继续复用：

```js
const ownerKey = androidGeneratedImageOwnerKey({
	messagePublicId: item.messagePublicId,
	localId: item.localId
})
const src = controller.sourceFor(ownerKey, item.outputIndex)
const status = controller.statusFor(ownerKey, item.outputIndex)
```

查看器打开前对已加载 `messages` 调用现有 `syncAllAndroidGeneratedImageSources()`；历史页合并后只同步新增页，不要释放当前会话已有 owner。

- [ ] **Step 3：删除单图预览入口**

`user-chat-panel.vue` 中的：

```js
uni.previewImage({ current: source, urls: [source] })
```

必须被新的 `openGeneratedImageViewer(message, attachment)` 替代。契约测试应明确断言源码不再出现 `urls: [source]`。

- [ ] **Step 4：第二阶段授权后运行 Android 契约测试**

```powershell
Set-Location C:\Users\damn\Desktop\ai-temperate-main\fornted
node --test common/aichat/ai-chat-android-media-contract.test.cjs components/user/workspace/user-generated-image-viewer-contract.test.cjs
```

---

### Task 7：把查看器状态、历史分页和高清升级接入聊天面板

**Files:**

- Modify: `fornted/components/user/workspace/user-chat-panel.vue`
- Modify: `fornted/common/aichat/ai-conversation-api.test.cjs`
- Modify: `fornted/common/aichat/ai-conversation-store.test.cjs`（仅在决定把查看器历史页写入主 store 时）

**Dependencies:** Tasks 1～6。

**Acceptance criteria:**

- [ ] 打开查看器立即使用当前 `messages` 构建项目，不产生额外请求。
- [ ] 查看器拥有独立的 `nextBefore/hasMore/loading/error`，不会调用现有 `loadOlderMessages()` 改变聊天滚动锚点。
- [ ] 每次只请求一页，`pageSize` 使用现有最大 100；请求进行中去重。
- [ ] 切换会话、页面隐藏或组件卸载时关闭查看器并清理 H5 Object URL/焦点锁；Android 受控缓存仍按 owner 生命周期释放。
- [ ] 当前项及相邻项可触发高清升级；隐藏副图不再被 `visibleOutputIndexes` 永久挡住。

- [ ] **Step 1：在 `data()` 中加入明确状态**

```js
imageViewerOpen: false,
imageViewerItems: Object.freeze([]),
imageViewerActiveIdentity: '',
imageViewerNextBefore: null,
imageViewerHasMoreBefore: false,
imageViewerLoadingBefore: false,
imageViewerError: '',
imageViewerDownloadBusyIdentity: '',
expandedGeneratedImageOwners: Object.freeze({})
```

- [ ] **Step 2：打开时按被点击身份定位**

```js
openGeneratedImageViewer(message, attachment) {
	const identity = generatedImageIdentity(message, attachment)
	const items = conversationGeneratedImages(this.messages)
	if (!identity || !items.some(item => item.identity === identity)) return
	this.imageViewerItems = items
	this.imageViewerActiveIdentity = identity
	this.imageViewerNextBefore = this.nextBefore
	this.imageViewerHasMoreBefore = this.hasMoreMessages
	this.imageViewerOpen = true
	this.beginViewerImageUpgrades(identity)
}
```

当前正在生成但还没有 `messagePublicId` 的消息使用 `localId`；终态保存替换后，如果身份从 localId 变为 messagePublicId，需要用 `outputIndex + 原 owner` 映射一次，不能突然关闭查看器。

- [ ] **Step 3：查看器独立分页**

```js
async loadOlderViewerImages() {
	if (!this.currentConversationPublicId || !this.imageViewerNextBefore
		|| this.imageViewerLoadingBefore) return
	const conversationPublicId = this.currentConversationPublicId
	this.imageViewerLoadingBefore = true
	try {
		const page = await aiConversationApi.messages(conversationPublicId, {
			before: this.imageViewerNextBefore,
			pageSize: 100
		})
		if (conversationPublicId !== this.currentConversationPublicId) return
		this.imageViewerItems = mergeConversationGeneratedImages(
			this.imageViewerItems,
			page.messages
		)
		this.imageViewerNextBefore = page.nextBefore
		this.imageViewerHasMoreBefore = page.hasMore
	} finally {
		this.imageViewerLoadingBefore = false
	}
}
```

第一版不修改后端：现有 `AiConversationQueryController.messages()` 已做用户身份授权，现有 `AiConversationHistoryService` 已返回每条消息的响应附件，前端 `aiConversationApi.messages()` 已支持 `before` 和最大 100 条。只有未来发现一页消息体因为大量非图片内容过重，才另开“会话媒体清单 API”ADR；不能在没有性能证据时提前增加第二条查询链路。

- [ ] **Step 4：把高清升级门禁改为显示意图集合**

移除：

```js
if (!this.generatedImageGallery(message).visibleOutputIndexes.includes(
	Number(attachment.outputIndex))) return
```

替换为由调用方明确传入升级原因：

```js
beginImageUpgrade(localId, attachment, reason = 'MESSAGE_VISIBLE')
```

合法原因只允许：`MESSAGE_VISIBLE`、`VIEWER_ACTIVE`、`VIEWER_ADJACENT`。消息卡片仅升级主图与当前可见副图；查看器每次选择变化只升级当前及前后一张。

- [ ] **Step 5：切换会话时完整复位**

在 `openConversation()`、登出/页面卸载路径中先调用：

```js
this.closeGeneratedImageViewer({ restoreFocus: false })
this.resetGeneratedImageViewerPagination()
```

异步历史响应必须比较发起请求时的 `conversationPublicId`，防止旧会话图片注入新会话查看器。

- [ ] **Step 6：第二阶段授权后运行 API 和状态测试**

```powershell
Set-Location C:\Users\damn\Desktop\ai-temperate-main\fornted
node --test common/aichat/ai-conversation-api.test.cjs common/aichat/ai-conversation-image-viewer.test.cjs
```

---

### Task 8：补齐响应式、无障碍、并发与失败态契约

**Files:**

- Modify: `fornted/pages/user/h5-responsive-layout-contract.test.cjs`
- Modify: `fornted/pages/ai-chat/ai-chat-page-contract.test.cjs`
- Modify: `fornted/common/aichat/ai-conversation-async-generation-contract.test.cjs`
- Modify: `fornted/common/aichat/ai-conversation-generation-manager.test.cjs`（仅在身份迁移接入该模块时）
- Modify: `fornted/components/user/workspace/user-generated-image-gallery-contract.test.cjs`
- Modify: `fornted/components/user/workspace/user-generated-image-viewer-contract.test.cjs`

**Dependencies:** Tasks 4～7。

**Acceptance criteria:**

- [ ] H5 320、375、768、1024、1440px 下都不产生横向页面滚动。
- [ ] Android 竖屏和矮横屏下查看器工具栏不会压住主图或系统 safe-area。
- [ ] `prefers-reduced-motion` 下关闭缩略图选择、查看器进出和图片切换的非必要动画。
- [ ] 生成中 10 张 PARTIAL 乱序到达时，版位仍按 outputIndex 固定；FINAL 替换不闪位。
- [ ] 某一张失败后，其余图片紧凑补位；正在查看的图片失败时保留身份并显示错误/重试，不直接跳到下一张。
- [ ] 连续快速点击不同缩略图不会并发触发同一高清 URL 多次下载。
- [ ] 历史分页失败只在查看器内显示“重试加载更早图片”，不污染 composer error，也不关闭当前图。

- [ ] **Step 1：增加静态契约，锁定关键 class 与事件**

至少断言：

```js
assert.match(gallerySource, /is-dual-with-rail/)
assert.match(gallerySource, /查看其余/)
assert.match(gallerySource, /\$emit\('open'/)
assert.match(viewerSource, /role="dialog"/)
assert.match(viewerSource, /aria-modal="true"/)
assert.doesNotMatch(panelSource, /urls:\s*\[source\]/)
```

- [ ] **Step 2：增加状态级用例**

覆盖以下组合：

```text
1 / 2 / 3 / 4 / 5 / 10 张
两批 10 + 10 = 20 张会话查看
流式 localId -> 持久化 messagePublicId 身份迁移
历史页前插后 activeIdentity 不变
当前图下载失败 / Android 权限拒绝 / 历史页 500
图片 FINAL URL 更新时缓存与查看器同时刷新
```

- [ ] **Step 3：第二阶段授权后运行前端完整聊天测试**

```powershell
Set-Location C:\Users\damn\Desktop\ai-temperate-main\fornted
npm run test:chat
npm run test:android-chat-presentation
npm run test:user-ui
```

这些命令可能覆盖较多现有测试，只能在用户确认进入第二阶段后运行。

---

### Task 9：第二阶段浏览器与 Android 实机验收

**Files:** 不修改业务代码；记录验收证据时可新建 `docs/research/` 下的说明文档。

**Dependencies:** Tasks 1～8 全部完成且定向测试通过。

**执行前必须再次取得用户对以下范围的明确授权。**

- [ ] **H5 外部 Chrome 验收**

只连接 `agent.browsers.get("extension")`，先确认浏览器类型为 `extension`。如果扩展断开，立即停止；禁止回退 Codex IAB。

验收矩阵：

```text
生成 1 张：单主图，点击打开 1/N
生成 2 张：双主图等权
生成 3 张：1 主 + 2 副
生成 4 张：1 主 + 3 副
生成 5 张：2 主 + 3 副，无 +N
生成 10 张：2 主 + 3 可见副图 + “查看其余 5 张”
同一会话再生成 10 张：从任意一张打开，查看器总数为 20
键盘 Escape / 左右键 / Tab 焦点闭环
下载当前图，确认文件名、内容和 Object URL 已释放
```

- [ ] **Android 实机验收**

```text
竖屏 1/2/3/4/5/10 张布局
横屏与刘海/safe-area
任意主图、副图、+N 打开查看器
左右快速滑动 20 张，不黑屏、不跳序
当前图未下载完成时显示 loading，不静默无反应
保存到相册成功
首次权限拒绝、永久拒绝和重试提示
切换会话后旧图缓存按 owner 生命周期释放
```

- [ ] **性能阈值**

```text
消息卡片：一批最多 10 个缩略资源，不加载会话全部高清图
查看器：只主动升级当前 ±1 张
历史：每次最多 100 条消息，只允许一个在途分页请求
Android：查看器渲染窗口建议当前 ±2 张，缩略图使用本地预览源
H5：关闭查看器后恢复 body overflow 与原焦点
```

---

## 五、实施顺序与停点

1. Task 1～2：先完成纯数据模型和测试源码，确保“排序、两张主图、会话 20 张”语义固定。
2. Task 3：完成安全下载/保存边界。
3. Task 4：替换消息卡片排版并让 `+N` 真正可点。
4. Task 5～6：完成 H5/Android 查看器。
5. Task 7：接入历史分页、高清升级和生命周期。
6. Task 8：补齐静态契约与边界测试源码。
7. 第一阶段在这里停止并交付代码；不得自动编译、打包、运行测试或连接外部服务。
8. 用户明确批准第二阶段后，按 Task 1～8 的定向命令逐步测试；全部通过后再执行 Task 9 的 Chrome 与 Android 实机验收。

## 六、明确不做的事情

- 不修改单次生成最多 10 张的后端/模型协议。
- 第一版不新增“会话媒体列表”后端接口，先复用已经有授权和分页的消息历史接口。
- 不把所有历史图片一次性预加载或一次性下载成高清。
- 不继续使用 `uni.previewImage` 冒充 ChatGPT 风格详细查看器。
- 不让 Android 绕过现有 UTS 缓存控制器直连不受控 URL。
- 不实现尚无后端能力的编辑、评论、删除、宽高比调整按钮。
- 不修改 `fornted/unpackage/**` 构建产物。
- 不覆盖当前工作区中已有的用户改动；特别是 `user-chat-panel.vue`、`user-android-chat-image.vue`、`ai-chat-android-media-contract.test.cjs` 已有重叠改动，实施每个 Task 前必须先读取当前 diff 并做精确补丁。

## 七、最终完成定义

只有同时满足以下条件，才能说功能完成：

- [ ] 1～10 张消息布局符合本计划，5～10 张始终为两张主图。
- [ ] 图片顺序在流式、刷新和历史加载后三者一致。
- [ ] `+N`、主图、副图在 H5 和 Android 都能打开查看器。
- [ ] 查看器按会话聚合；两批 10 张可连续查看 20 张。
- [ ] H5 下载和 Android 保存均有成功与失败反馈。
- [ ] 历史分页、切换会话、快速切图、图片失败和权限拒绝不会破坏当前查看状态。
- [ ] 用户明确授权后的定向测试、聊天测试、外部 Chrome 验收与 Android 实机验收均有新证据。
- [ ] 未经第二阶段授权时，只能表述为“代码已实现/计划已交付，测试尚未执行”，不能表述为“已验证通过”。

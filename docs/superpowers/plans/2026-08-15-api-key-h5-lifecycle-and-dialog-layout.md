# API Key H5 生命周期与弹窗排版修正 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 取消 H5 切换浏览器标签页或应用前后台时的 API Key 自动刷新与界面重置，并统一创建、管理弹窗的关闭按钮、日期按钮和滚动布局。

**Architecture:** 保留页面 `onShow/onHide` 供聊天等需要暂停资源的功能使用，但 API Key 面板不再把 H5 后台化当作销毁。API Key 状态只在真正卸载、退出登录或用户显式关闭时清理；列表更新只由首次加载、用户点击刷新和成功写操作触发。创建弹窗与管理侧栏采用固定头部、单一滚动正文和固定操作区，继续复用现有过期日期与模型选择组件。

**Tech Stack:** UniApp H5、Vue Options API、SCSS、Node.js `node:test` 契约测试。

---

## 已确认的根因

当前链路是：

```text
浏览器标签页进入后台
→ @dcloudio/uni-h5 监听 document.visibilitychange
→ 当前页面 onHide
→ user-workspace.handlePageHide()
→ user-api-key-panel.handlePageHide()
→ releasePageState()
→ items/listLoaded/createOpen/editorId/createdSecret 全部被清空

浏览器标签页恢复前台
→ 当前页面 onShow
→ user-workspace.handlePageShow()
→ user-api-key-panel.onAuthenticatedPageReady()
→ 因 listLoaded=false 再次调用 refreshKeys()
```

该行为把“暂时不可见”错误地当成“页面销毁”。它会产生不必要的列表请求，并造成创建草稿、管理草稿、详情面板及一次性密钥展示状态丢失。

---

### Task 1: 固化“后台化不刷新、卸载才清理”的生命周期契约

**Files:**
- Modify: `fornted/pages/account/api-keys/api-key-page-contract.test.cjs`
- Test: `fornted/pages/account/api-keys/api-key-page-contract.test.cjs`

- [ ] **Step 1: 添加后台化回归测试**

在现有 API Key 页面契约测试中加入：

```js
test('H5 backgrounding preserves API Key state and never refreshes implicitly', () => {
	const workspace = read('components/user/user-workspace.vue')
	const panel = read('components/user/workspace/user-api-key-panel.vue')

	assert.doesNotMatch(
		workspace,
		/handlePageHide\(\)[\s\S]{0,220}apiKeyPanel\?\.handlePageHide/
	)
	assert.doesNotMatch(panel, /handlePageHide\(\)[\s\S]{0,120}releasePageState\(\)/)
	assert.match(panel, /handlePageUnload\(\)[\s\S]{0,120}releasePageState\(\)/)
	assert.match(panel, /authenticated\(value\)[\s\S]{0,180}else this\.releasePageState\(\)/)
	assert.match(panel, /handlePageShow\(\)[\s\S]{0,100}onAuthenticatedPageReady\(\)/)
	assert.match(panel, /!this\.listLoaded/)
})
```

- [ ] **Step 2: 添加敏感状态边界测试**

明确一次性完整 Key 仍然只保存在组件内存中，同时不会因为后台化丢失：

```js
assert.match(panel, /createdSecret\s*=\s*created\.value\.apiKey/)
assert.doesNotMatch(panel, /localStorage|sessionStorage|setStorage/)
assert.match(panel, /handlePageUnload\(\)[\s\S]{0,120}releasePageState\(\)/)
assert.match(panel, /clearCreatedSecret\(\)/)
```

- [ ] **Step 3: 第一阶段不运行测试**

仅交付测试代码；等待用户批准第二阶段后运行：

```powershell
cd C:\Users\damn\Desktop\ai-temperate-main\fornted
npm run test:api-keys
```

预期修改实现前新增用例失败，失败点为 `handlePageHide()` 仍调用 API Key 状态释放。

---

### Task 2: 取消 API Key 的前后台自动刷新

**Files:**
- Modify: `fornted/components/user/user-workspace.vue`
- Modify: `fornted/components/user/workspace/user-api-key-panel.vue`
- Test: `fornted/pages/account/api-keys/api-key-page-contract.test.cjs`

- [ ] **Step 1: 从工作区后台处理删除 API Key 清理调用**

将工作区的后台处理限定为真正需要暂停流式资源的聊天面板：

```js
handlePageHide() {
	this.$refs.chatPanel?.handlePageHide()
},
```

不得再调用：

```js
this.$refs.apiKeyPanel?.handlePageHide()
```

- [ ] **Step 2: 删除 API Key 面板的错误后台清理入口**

从 `user-api-key-panel.vue` 删除：

```js
handlePageHide() {
	this.releasePageState()
},
```

保留以下边界：

```js
handlePageShow() {
	this.onAuthenticatedPageReady()
},
handlePageUnload() {
	this.releasePageState()
},
```

这样恢复前台时，已加载页面因为 `listLoaded === true` 不会重新请求；第一次进入页面仍会正常加载。

- [ ] **Step 3: 保留显式清理与并发保护**

不得修改以下行为：

- `authenticated` 变为 `false` 时调用 `releasePageState()`；
- `handlePageUnload()` 清理列表、详情、表单和一次性 Key；
- 用户明确关闭创建、详情或一次性 Key 弹窗时清理对应状态；
- `requestGeneration` 继续防止卸载后的旧请求回写；
- 手动“刷新”按钮继续调用 `refreshKeys()`；
- ETag/If-Match 冲突继续以 `412/VERSION_CONFLICT` 提醒用户重新加载。

- [ ] **Step 4: 验证不引入隐式定时刷新**

API Key组件中不得新增：

```text
visibilitychange
focus / blur
pageshow / pagehide
setInterval
自动 TTL 刷新
```

数据更新来源固定为：首次加载、用户手动刷新、创建成功、管理保存成功和撤销成功。

---

### Task 3: 先补齐关闭按钮、日期按钮与弹窗骨架测试

**Files:**
- Modify: `fornted/pages/account/api-keys/api-key-page-contract.test.cjs`
- Modify: `fornted/pages/user/h5-responsive-layout-contract.test.cjs`

- [ ] **Step 1: 添加图标一致性测试**

```js
test('API Key dialogs use the shared icon language instead of text glyphs', () => {
	const createDialog = read('components/user/workspace/user-api-key-create-dialog.vue')
	const editor = read('components/user/workspace/user-api-key-editor-sheet.vue')
	const expiry = read('components/user/workspace/user-api-key-expiry-picker.vue')

	for (const source of [createDialog, editor]) {
		assert.match(source, /<uni-icons[^>]*type="closeempty"/)
		assert.doesNotMatch(source, />\s*×\s*</)
	}
	assert.match(expiry, /<uni-icons[^>]*type="calendar"/)
	assert.doesNotMatch(expiry, />\s*▦\s*</)
})
```

- [ ] **Step 2: 添加固定头部/正文/操作区结构测试**

创建弹窗必须出现：

```text
api-key-dialog-heading
api-key-dialog-body
api-key-dialog-actions
grid-template-rows: auto minmax(0, 1fr) auto
```

管理侧栏必须出现：

```text
api-key-editor-heading
api-key-editor-body
grid-template-rows: auto minmax(0, 1fr)
```

正文是唯一外层纵向滚动容器，头部和主要操作按钮不随正文滚走。

- [ ] **Step 3: 保留可访问性契约**

继续断言：

- 关闭按钮保留明确的中文 `aria-label`；
- `Escape` 可以关闭非忙碌弹窗；
- 焦点仍被限制在当前弹窗；
- 触摸目标不小于 `44px × 44px`；
- 窄屏继续使用全屏弹窗/侧栏。

---

### Task 4: 重排创建 API Key 弹窗

**Files:**
- Modify: `fornted/components/user/workspace/user-api-key-create-dialog.vue`
- Modify: `fornted/components/user/workspace/user-api-key-model-picker.vue` only if a named layout class is required

- [ ] **Step 1: 将弹窗改成三段式骨架**

目标结构：

```vue
<view class="api-key-dialog">
	<view class="api-key-dialog-heading">...</view>
	<view class="api-key-dialog-body">
		<!-- 有效期、模型授权、错误提示 -->
	</view>
	<view class="api-key-dialog-actions">
		<!-- 取消、创建 -->
	</view>
</view>
```

核心样式：

```scss
.api-key-dialog {
	display: grid;
	grid-template-rows: auto minmax(0, 1fr) auto;
	overflow: hidden;
	padding: 0;
}
.api-key-dialog-heading,
.api-key-dialog-actions { padding: 20px 24px; }
.api-key-dialog-body {
	min-height: 0;
	overflow-y: auto;
	padding: 0 24px 24px;
}
```

桌面端弹窗保持居中；移动端仍占满视口。底部操作区使用清晰的上边界与实色表面，不使用大面积阴影。

- [ ] **Step 2: 统一关闭按钮**

将文本 `×` 替换为项目已使用的：

```vue
<uni-icons type="closeempty" size="20" color="#dce5e0" aria-hidden="true" />
```

关闭按钮保持 44px 点击区域，视觉尺寸控制在 20px；默认、hover、focus、active、disabled 状态与其他工作区侧栏一致。

- [ ] **Step 3: 保持表单草稿**

切换浏览器标签页或应用前后台时必须保留：

- 已选有效期和自定义日期；
- 已选模型；
- 搜索关键词及模型分页结果；
- 表单错误；
- 当前滚动位置。

只在弹窗从关闭变为打开时执行现有初始化；不得把页面恢复事件绑定到 `open` 状态。

- [ ] **Step 4: 控制决策密度**

有效期的 8 个选项保持 4×2（窄屏2×4），不新增向导步骤。模型列表继续作为有边界的滚动区域，避免最多500个模型把整个弹窗无限拉长；弹窗正文与模型列表均需保留 `overscroll-behavior: contain`，并确保底部操作区始终可见。

---

### Task 5: 重排管理 API Key 侧栏与日期控件

**Files:**
- Modify: `fornted/components/user/workspace/user-api-key-editor-sheet.vue`
- Modify: `fornted/components/user/workspace/user-api-key-expiry-picker.vue`
- Test: `fornted/pages/account/api-keys/api-key-page-contract.test.cjs`
- Test: `fornted/pages/user/h5-responsive-layout-contract.test.cjs`

- [ ] **Step 1: 固定管理侧栏头部**

将侧栏改成：

```vue
<view class="api-key-editor">
	<view class="api-key-editor-heading">...</view>
	<view class="api-key-editor-body">
		<!-- 加载、冲突、设置、模型、使用信息、危险操作 -->
	</view>
</view>
```

核心样式：

```scss
.api-key-editor {
	display: grid;
	grid-template-rows: auto minmax(0, 1fr);
	overflow: hidden;
	padding: 0;
}
.api-key-editor-heading { padding: 20px 24px; }
.api-key-editor-body {
	min-height: 0;
	overflow-y: auto;
	padding: 0 24px calc(24px + env(safe-area-inset-bottom));
}
```

这样用户滚动到授权模型或危险操作时，标题、Key掩码和关闭按钮仍然可见。

- [ ] **Step 2: 保留分区保存语义**

“保存设置”和“保存授权”继续分开，因为它们对应不同后端端点和同一 ETag 乐观锁版本。不要合并成一个含糊的全局保存按钮；保存成功后只更新对应区块，并保留现有 Toast 与冲突提示。

- [ ] **Step 3: 统一日期按钮**

把 `▦` 替换为现有 `uni-icons type="calendar"`。按钮规则：

```text
44×44px 点击区域
20px 图标
关闭状态使用中性表面
展开状态使用绿色选中态
保留 aria-expanded 与 aria-controls
```

日历继续内联展开，不改成容易被滚动容器裁切的绝对定位浮层。自定义日期输入、日历按钮和日期摘要保持一个明确的上下阅读顺序。

- [ ] **Step 4: 降低侧栏纵向噪声**

保持四个业务分区，但统一间距层级：

```text
头部→首个分区：20px
分区内部控件：12–14px
分区之间：24px
元数据行：48–52px
危险操作与普通设置使用明显但克制的语义色区分
```

不引入额外标签页、步骤向导或自动折叠，避免为解决排版再次过度设计。

---

### Task 6: 第二阶段验证与验收

**Files:**
- Verify only; no additional source changes unless a test exposes a concrete defect

- [ ] **Step 1: 运行 API Key 契约测试**

获得用户明确批准后运行：

```powershell
cd C:\Users\damn\Desktop\ai-temperate-main\fornted
npm run test:api-keys
```

预期：全部通过。

- [ ] **Step 2: 运行 H5 用户界面契约测试**

```powershell
cd C:\Users\damn\Desktop\ai-temperate-main\fornted
npm run test:user-ui
```

预期：全部通过，并确认窄屏全屏布局、桌面侧栏和响应式网格未回归。

- [ ] **Step 3: Chrome手工生命周期验收**

使用隔离账号执行：

1. 打开 API Key列表，记录 Network请求数量；切到另一个 Chrome标签页再返回，不得新增列表或详情请求。
2. 打开创建弹窗，选择自定义日期、模型并输入搜索词；切换标签页后返回，弹窗、选择、搜索词和滚动位置必须保留。
3. 创建成功显示一次性完整 Key 后切换标签页再返回，Key仍应显示，直到用户点击“我已保存，关闭”；禁止写入任何持久化存储。
4. 打开管理侧栏，修改状态、日期和模型但不保存；切换标签页后返回，草稿必须保留且不得重新请求详情。
5. 点击页面“刷新”按钮，必须且只应产生一次列表请求。
6. 在另一个标签页修改同一 Key，再回到旧草稿保存，必须显示现有 `412/VERSION_CONFLICT` 提示，禁止静默覆盖。
7. 退出登录或真正卸载页面后重新进入，旧草稿、详情和一次性 Key必须不存在，并执行一次正常首次加载。

- [ ] **Step 4: 视觉验收**

在 360px、768px、1440px 三个视口确认：

- 关闭按钮始终可见、图形居中、点击区域至少44px；
- 日期按钮与项目其他图标同一视觉语言；
- 创建弹窗底部操作区始终可见；
- 管理侧栏滚动到底部时头部仍可见；
- 不出现正文与页面同时滚动造成的滚动穿透；
- 键盘 Tab、Shift+Tab与Escape行为保持正确。

---

## Non-goals

- 不修改 API Key后端接口、ETag、数据库或Cloudflare Worker。
- 不增加自动轮询、焦点刷新、可见性刷新或时间阈值刷新。
- 不把完整 API Key、创建草稿或管理草稿写入 LocalStorage、SessionStorage、IndexedDB或URL。
- 不合并生命周期保存与模型授权保存接口。
- 不重做整个 API Key页面视觉品牌，只修正生命周期、弹窗骨架和高优先级控件一致性。

# 普通用户前端本地认证预览能力移除 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 彻底删除普通用户前端的本地认证预览入口、模拟身份数据和认证绕过分支，同时保留真实登录后的个人中心。

**Architecture:** 删除集中式 `ui-preview-session.js` 模块，并从登录页、启动会话页、HTTP 客户端和当前用户 API 中移除全部调用。真实认证数据流保持不变：登录和会话恢复依赖后端，个人中心通过 `/api/users/me` 读取资料。

**Tech Stack:** UniApp、Vue、JavaScript、Node.js `node:test`

---

## 文件结构

- 删除：`fornted/common/auth/ui-preview-session.js`，不再保留预览状态、URL 参数解析或模拟用户资料。
- 修改：`fornted/pages/auth/login.vue`，删除预览按钮、状态、方法和导入。
- 修改：`fornted/pages/launch/session-gate.vue`，删除从路由激活预览的逻辑。
- 修改：`fornted/common/user/current-user-api.js`，当前用户资料始终通过真实 API 获取。
- 修改：`fornted/common/auth/http-client.js`，会话恢复和退出始终走真实认证流程。
- 修改：`fornted/common/auth/cookie-scope-migration.js`，删除对预览会话清理函数的依赖。
- 修改：`fornted/pages/account/profile/navigation-contract.test.cjs`，改为断言登录成功跳转中不存在预览入口。
- 修改：`fornted/common/auth/page-guard-contract.test.cjs`，改为断言受保护页面不存在预览绕过。
- 修改：`fornted/README.md`，删除本地预览说明。

### Task 1: 先更新前端契约测试

**Files:**
- Modify: `fornted/pages/account/profile/navigation-contract.test.cjs:14-26`
- Modify: `fornted/common/auth/page-guard-contract.test.cjs:25-33`

- [x] **Step 1: 收紧登录页导航契约**

将原来的“登录成功和本地预览进入个人中心”测试替换为：

```javascript
test('login success enters the account profile page without a local preview bypass', () => {
	const login = read('pages/auth/login.vue')
	const config = read('common/auth/config.js')

	assert.match(login, /completeLogin\(\)/)
	assert.equal(login.includes(`uni.${legacyTabNavigationMethod}({`), false)
	assert.match(login, /uni\.reLaunch\(\{/)
	assert.match(login, /url:\s*AUTH_ROUTES\.home/)
	assert.doesNotMatch(login, /previewAuthenticatedPages|authUiPreview|ui-preview-session/)
	assert.match(config, /home:\s*'\/pages\/account\/profile'/)
	assert.match(config, /profile:\s*'\/pages\/account\/profile'/)
})
```

- [x] **Step 2: 将页面守卫预览正向契约改为删除契约**

将 `auth UI preview restores and exits without backend requests` 测试替换为：

```javascript
test('protected authentication flows contain no local preview bypass', () => {
	const httpClient = read('common/auth/http-client.js')
	const sessionGate = read('pages/launch/session-gate.vue')
	const currentUserApi = read('common/user/current-user-api.js')
	const cookieMigration = read('common/auth/cookie-scope-migration.js')
	const combined = [httpClient, sessionGate, currentUserApi, cookieMigration].join('\n')
	const previewModule = path.resolve(
		__dirname,
		'..',
		'..',
		'common/auth/ui-preview-session.js'
	)

	assert.doesNotMatch(
		combined,
		/AuthUiPreview|authUiPreview|ui-preview-session|preview:\s*true/
	)
	assert.equal(fs.existsSync(previewModule), false)
})
```

- [x] **Step 3: 记录第二阶段候选测试，不在本阶段运行**

第二阶段获得用户明确授权后，可在 `fornted` 目录运行：

```powershell
node --test common/auth/page-guard-contract.test.cjs pages/account/profile/navigation-contract.test.cjs
```

预期：两个文件中的测试全部通过。本次第一阶段不得执行该命令。

### Task 2: 删除登录页入口和路由激活

**Files:**
- Modify: `fornted/pages/auth/login.vue:161-172,195-198,240,516-527`
- Modify: `fornted/pages/launch/session-gate.vue:15-20,41-44`
- Modify: `fornted/README.md:13`

- [x] **Step 1: 删除登录页预览按钮**

登录页链接区域保留：

```vue
<view class="auth-links">
	<button class="auth-link" type="button" :disabled="busy" @click="goRegister">创建账号</button>
	<button class="auth-link" type="button" :disabled="busy" @click="goReset">忘记密码</button>
</view>
```

- [x] **Step 2: 删除登录页预览状态和逻辑**

删除以下导入：

```javascript
import {
	enableAuthUiPreviewSession,
	isAuthUiPreviewAvailable
} from '@/common/auth/ui-preview-session.js'
```

从 `data()` 返回值中删除：

```javascript
authUiPreviewAvailable: isAuthUiPreviewAvailable()
```

从 `methods` 中删除：

```javascript
previewAuthenticatedPages() {
	if (this.busy || !enableAuthUiPreviewSession()) return
	this.successMessage = '已进入本地预览'
	uni.reLaunch({
		url: AUTH_ROUTES.profile,
		success: () => {
			uni.showToast({ title: '本地预览已开启', icon: 'none' })
		},
		fail: () => { this.error = '页面跳转失败，请重试。' }
	})
}
```

- [x] **Step 3: 删除启动会话页的预览 URL 激活**

删除 `activateAuthUiPreviewFromRoute` 导入，并将生命周期改为：

```javascript
onLoad() {
	this.restoreSession()
},
```

这样 `authUiPreview`、`uiPreview` 和 `mockAuth` 查询参数不再产生任何认证效果。

- [x] **Step 4: 删除 README 中的本地预览说明**

删除以下整行：

```markdown
本地预览登录后页面会进入个人中心，不依赖后端接口、不使用原生多项底部栏。
```

### Task 3: 删除认证和用户资料中的预览绕过

**Files:**
- Modify: `fornted/common/user/current-user-api.js:1-10`
- Modify: `fornted/common/auth/http-client.js:22,272-278,343-350,378-385`
- Modify: `fornted/common/auth/cookie-scope-migration.js:1,45-51`

- [x] **Step 1: 当前用户资料始终调用后端**

删除预览模块导入，并将 `me()` 保持为：

```javascript
me() {
	return authorizedRequest('/api/users/me', { method: 'GET' })
},
```

- [x] **Step 2: 会话恢复不再承认预览标记**

删除预览模块导入，并将 `restorePersistedSession()` 保持为：

```javascript
export function restorePersistedSession() {
	if (clientPlatform() === 'H5') return restoreBrowserSession()
	const credentials = currentSession()
	return Promise.resolve(hasCompleteSessionCredentials(credentials)
		? { restored: true }
		: null)
}
```

- [x] **Step 3: 退出操作始终调用真实会话流程**

从 `logoutSession()` 和 `logoutAllSessions()` 开头分别删除：

```javascript
if (isAuthUiPreviewEnabled()) {
	clearAuthUiPreviewSession()
	clearSession()
	invalidatePreAuth()
	invalidateWebRtcVerification()
	return
}
```

保留两个函数其余真实后端请求和本地清理逻辑不变。

- [x] **Step 4: Cookie 作用域迁移只清理真实会话**

删除：

```javascript
import { clearAuthUiPreviewSession } from './ui-preview-session.js'
```

将重置分支保持为：

```javascript
if (reset) {
	// 父域 Cookie 清理代表旧浏览器会话整体失效，本地会话也必须同步清空。
	clearSession()
}
```

### Task 4: 删除预览模块并静态复核

**Files:**
- Delete: `fornted/common/auth/ui-preview-session.js`

- [x] **Step 1: 物理删除预览模块**

删除 `fornted/common/auth/ui-preview-session.js`。不新增旧存储键迁移代码，因为新版本不再读取 `ait.auth.ui-preview.enabled.v1`，残留值不能产生认证效果。

- [x] **Step 2: 检查普通用户前端非测试源码是否仍有预览引用**

运行只读搜索：

```powershell
rg -n --glob '!**/*.test.cjs' "本地预览登录后页面|AuthUiPreview|authUiPreview|uiPreview|mockAuth|ui-preview-session|previewAuthenticatedPages|preview\.user@example\.test" fornted
```

预期：无匹配。

- [x] **Step 3: 检查没有修改后端和管理员前端**

运行只读差异检查：

```powershell
git diff -- fornted docs/superpowers/plans/2026-07-27-remove-auth-ui-preview.md
```

人工确认本任务新增差异只涉及本计划列出的普通用户前端文件、测试、README 和计划文档。由于这些文件已有用户改动，禁止整体暂存或提交实现文件。

- [x] **Step 4: 第一阶段交付说明**

交付时明确说明：

- 本地认证预览能力已从代码中移除。
- 真实个人中心和真实认证流程仍保留。
- Java 后端及管理员前端未修改。
- 未运行测试、编译和打包。
- 若用户同意进入第二阶段，再说明测试命令和无外部写入范围并请求确认。

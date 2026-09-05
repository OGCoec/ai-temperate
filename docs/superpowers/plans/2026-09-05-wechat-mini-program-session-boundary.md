# 微信小程序基础连通性与独立会话边界 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户端微信小程序生成稳定的安全安装 ID、被前后端识别为独立平台，并在不改变 H5/Android 行为的前提下完成基础 HTTP 与邮箱/密码会话运输。

**Architecture:** uni-app 编译标识继续使用 `MP-WEIXIN`，业务平台值统一使用 `WECHAT_MINI_PROGRAM`。微信端使用独立随机数适配器和独立会话仓库；服务端将微信和 Android 都归入“显式 Token 运输”，但保留不同的平台枚举、能力开关和审计标签。第一版微信凭据只驻留当前小程序进程内，冷启动重新登录，避免把长期 Refresh Token 明文写入普通 Storage；持久登录和 `wx.login()` 交换协议另立后续计划。

**Tech Stack:** uni-app Vue 3、微信小程序 `wx.getRandomValues`/Storage、Spring Boot、Spring Security、Node test、JUnit 5/MockMvc。

## Codex 严格变更控制与自动回退规范

本计划的执行代理必须把每次代码修改视为受控变更，并严格遵守以下流程。该规范用于保证 Codex 能够查看 diff、识别本次变更、保护用户已有修改，并在获得验证授权后于验证失败时安全回退。

### 1. 变更前置检查

1. 先读取并遵守仓库中的 `AGENTS.md`、`README.md` 和本计划中的工程约束。
2. 修改前必须检查并记录 `git status --short`、`git diff --stat` 和 `git diff`。
3. 任务开始时的工作区状态定义为“基线状态”。基线中已经存在的修改全部属于用户代码，禁止覆盖、删除、回退、重新格式化或纳入本次变更。
4. 如果无法明确区分基线修改与本次修改，必须停止操作并说明原因，不得继续编辑。

### 2. 修改范围和禁止操作

1. 只修改完成本计划任务所必需的文件，禁止顺手重构、格式化、升级依赖或修改无关代码。
2. 未经用户明确授权，禁止执行 `git reset --hard`、`git checkout -- .`、`git restore .`、`git clean -fd`，也不得提交代码、推送远程仓库或创建 Pull Request。
3. 优先使用独立 Git worktree 或独立分支执行任务；未通过验证前，不得将变更合并回用户当前工作区。
4. 修改前必须说明准备修改的文件、修改目的和预期验证方式。

### 3. Diff 检查和验证

1. 修改完成后必须先执行 `git diff --check` 并展示当前 diff，再判断任务是否完成。
2. Diff 审查必须确认：没有无关文件变更、没有覆盖基线修改、没有敏感信息、没有违反项目架构和本计划不变量，且修改与任务目标一致。
3. 本计划第一阶段只交付代码和测试文件，禁止自动执行测试、编译、打包、依赖分析、安全扫描、集成测试或外部服务连接测试；交付时必须明确列出未执行的验证。
4. 只有用户明确确认进入第二阶段后，才可以执行本计划列出的测试和构建命令。不得把“代码看起来正确”视为验证通过。

### 4. 失败回退规则

1. 在第二阶段验证中发生测试失败、编译失败、静态检查失败、无关 diff、基线被影响、需求无法证明满足或无法安全区分变更时，必须将本次变更标记为失败。
2. 失败后必须停止继续修改，只撤销本次任务产生的修改，不得影响基线状态。
3. 回退完成后必须重新检查 `git status --short`、`git diff --stat` 和 `git diff`，确认工作区恢复到任务开始时的基线状态。
4. 如果无法安全回退，禁止强行执行破坏性 Git 命令，必须停止并请求人工处理。
5. 回退完成后必须报告失败原因、触发回退的验证命令、受影响文件以及基线恢复结果。

### 5. 完成条件和报告格式

只有同时满足“需求已实现、diff 已审查、授权范围内的验证全部通过、基线修改未受影响、没有无关变更”时，才能报告任务完成。

最终报告必须包含：

- 修改文件；
- 修改内容；
- 当前 diff 摘要；
- 已执行的验证命令及结果；
- 未执行的验证及原因；
- 是否发生回退；
- 当前 Git 工作区状态；
- 风险或需要用户确认的事项。

---

## 范围和不变量

- H5 继续使用 HttpOnly Cookie、浏览器 CSRF 和现有 H5 WebRTC。
- Android 继续使用 AndroidKeyStore、Bearer/Header/请求体 Token 和现有原生能力。
- 微信小程序不得读取 `document.cookie`、DOM、浏览器 WebRTC、AndroidKeyStore 或 Android WebView。
- `X-Client-Platform` 只选择运输协议，不构成认证凭据。
- 微信安装 ID 是本地随机 UUIDv4，不是 AppID、OpenID、UnionID 或硬件 ID。
- 第一版不实现微信一键登录、Google/GitHub OAuth、语音 WebSocket、支付和 Turnstile。
- 第一阶段只交付代码和测试文件，不自动执行测试或构建；第二阶段必须由用户明确确认后执行。

## 文件职责

### 新增文件

- `fornted/common/auth/device-installation-mp-weixin.js`：异步调用微信安全随机 API 并生成 UUIDv4。
- `fornted/common/auth/mp-weixin-session-vault.js`：保存当前小程序进程内的 AT、RT、CSRF 和 PreAuth。
- `fornted/common/auth/mp-weixin-platform.test.cjs`：锁定平台、随机数、会话仓库和禁用能力边界。
- `ai-temperate-web/src/test/java/com/example/temperate/web/auth/session/transport/AuthClientPlatformTest.java`：锁定三平台解析和显式运输分类。

### 修改文件

- `fornted/common/auth/config.js`：增加微信平台常量和平台能力函数。
- `fornted/common/auth/device-installation.js`：提供异步初始化入口，保留已有同步读取契约。
- `fornted/pages/launch/session-gate.vue`：请求前等待安装 ID；微信冷启动无会话时进入登录页。
- `fornted/common/auth/session-vault.js`：H5、Android、微信三路存取。
- `fornted/common/auth/pre-auth.js`：微信使用显式 PreAuth Token，不进入 H5 Cookie 分支。
- `fornted/common/auth/http-client.js`：微信使用 Bearer、Refresh、CSRF 和 PreAuth Header。
- `fornted/common/auth/auth-api.js`：登录、注册和找回密码成功时提交微信内存会话。
- `fornted/package.json`：把微信平台测试加入独立脚本。
- `fornted/common/release/mp-weixin-source-boundary.test.cjs`：禁止微信构建引用 H5/Android 私有实现。
- `ai-temperate-web/src/main/java/com/example/temperate/web/auth/session/transport/AuthClientPlatform.java`：增加微信枚举和运输能力。
- `ai-temperate-web/src/main/java/com/example/temperate/web/auth/interceptor/UserSessionAuthenticationInterceptor.java`：微信读取显式 Header Token。
- `ai-temperate-web/src/main/java/com/example/temperate/web/auth/login/controller/LoginController.java`：微信登录响应返回显式凭据。
- `ai-temperate-web/src/main/java/com/example/temperate/web/auth/registration/controller/RegistrationController.java`：微信注册流程返回显式凭据。
- `ai-temperate-web/src/main/java/com/example/temperate/web/auth/passwordreset/controller/PasswordResetController.java`：微信找回密码流程使用显式凭据。
- `ai-temperate-web/src/main/java/com/example/temperate/web/auth/session/controller/SessionController.java`：微信退出登录读取请求体 RT，不写/清 H5 Cookie。
- `ai-temperate-web/src/main/java/com/example/temperate/web/risk/PreAuthTransport.java`：微信通过 Header/JSON 运输 PreAuth。
- `ai-temperate-web/src/main/java/com/example/temperate/web/risk/NetworkRiskEdgeController.java`：微信预认证响应返回 PreAuth Token。
- `ai-temperate-web/src/main/java/com/example/temperate/web/auth/config/SecurityConfiguration.java`：微信显式 Token 请求不进入浏览器 Cookie CSRF 链。
- 对应现有 Token transport、NetworkRisk 和 SecurityConfiguration 测试：补充微信用例，确保 H5/Android 原断言不变。

---

### Task 1: 锁定三平台前端契约

**Files:**
- Create: `fornted/common/auth/mp-weixin-platform.test.cjs`
- Modify: `fornted/common/release/mp-weixin-source-boundary.test.cjs`
- Modify: `fornted/package.json`

- [ ] **Step 1: 写平台解析契约测试**

测试必须验证源码包含三条互斥编译分支：

```js
assert.match(configSource, /#ifdef MP-WEIXIN[\s\S]*return 'WECHAT_MINI_PROGRAM'/)
assert.match(configSource, /#ifdef APP-PLUS[\s\S]*resolveClientPlatform/)
assert.match(configSource, /#ifdef H5[\s\S]*return 'H5'/)
assert.doesNotMatch(configSource, /#ifndef APP-PLUS[\s\S]*return 'H5'/)
```

- [ ] **Step 2: 写能力矩阵测试**

锁定以下结果：

```text
H5                      browserCookie=true,  explicitToken=false
ANDROID                 browserCookie=false, explicitToken=true
WECHAT_MINI_PROGRAM     browserCookie=false, explicitToken=true
```

- [ ] **Step 3: 写构建边界测试**

检查微信专用文件不得出现 `document`、`window`、`plus.android`、`AndroidKeyStore`、`RTCPeerConnection` 或 `@shikijs`。

- [ ] **Step 4: 增加测试脚本但不执行**

```json
"test:mp-weixin-auth": "node --test common/auth/mp-weixin-platform.test.cjs common/release/mp-weixin-source-boundary.test.cjs"
```

---

### Task 2: 增加明确的平台值和运输能力

**Files:**
- Modify: `fornted/common/auth/config.js`
- Test: `fornted/common/auth/mp-weixin-platform.test.cjs`

- [ ] **Step 1: 定义稳定平台常量**

```js
export const ClientPlatform = Object.freeze({
	H5: 'H5',
	ANDROID: 'ANDROID',
	WECHAT_MINI_PROGRAM: 'WECHAT_MINI_PROGRAM'
})
```

- [ ] **Step 2: 将条件编译改为三条明确分支**

```js
export function clientPlatform() {
	// #ifdef MP-WEIXIN
	return ClientPlatform.WECHAT_MINI_PROGRAM
	// #endif
	// #ifdef APP-PLUS
	return resolveClientPlatform(uni.getSystemInfoSync()?.platform)
	// #endif
	// #ifdef H5
	return ClientPlatform.H5
	// #endif
}
```

- [ ] **Step 3: 增加能力函数，后续禁止散落“非 Android 等于 H5”的判断**

```js
export function usesBrowserCookieTransport(platform = clientPlatform()) {
	return platform === ClientPlatform.H5
}

export function usesExplicitTokenTransport(platform = clientPlatform()) {
	return platform === ClientPlatform.ANDROID
		|| platform === ClientPlatform.WECHAT_MINI_PROGRAM
}
```

---

### Task 3: 实现微信安全安装 ID

**Files:**
- Create: `fornted/common/auth/device-installation-mp-weixin.js`
- Modify: `fornted/common/auth/device-installation.js`
- Test: `fornted/common/auth/mp-weixin-platform.test.cjs`

- [ ] **Step 1: 测试 16 字节、UUIDv4 位和错误路径**

使用假的 `wx.getRandomValues` 返回固定 16 字节，断言生成值满足：

```regex
^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$
```

同时验证 API 缺失或回调失败时返回受控错误码 `DEVICE_INSTALLATION_ID_UNAVAILABLE`，不得回退到 `Math.random()`。

- [ ] **Step 2: 新增微信随机字节适配器**

```js
export function createMpWeixinInstallationId(wxApi = wx) {
	return new Promise((resolve, reject) => {
		if (typeof wxApi?.getRandomValues !== 'function') {
			reject(deviceIdError())
			return
		}
		wxApi.getRandomValues({
			length: 16,
			success: ({ randomValues }) => resolve(uuidV4(new Uint8Array(randomValues))),
			fail: () => reject(deviceIdError())
		})
	})
}
```

`uuidV4` 必须把 `bytes[6]` 设为版本 4，把 `bytes[8]` 设为 RFC 4122 variant，再格式化为小写 UUID。

- [ ] **Step 3: 增加一次性异步初始化**

```js
export async function ensureDeviceInstallationId() {
	const stored = storedInstallationId()
	if (stored) return stored
	let generated = ''
	// #ifdef MP-WEIXIN
	generated = await createMpWeixinInstallationId()
	// #endif
	// #ifndef MP-WEIXIN
	generated = androidUuid() || browserUuid()
	// #endif
	return persistValidInstallationId(generated)
}
```

保留 `getDeviceInstallationId()` 作为同步读取：已有有效值直接返回；微信尚未初始化时抛出 `DEVICE_INSTALLATION_ID_NOT_READY`，不得悄悄生成弱随机值。

---

### Task 4: 让启动页进入微信正确路径

**Files:**
- Modify: `fornted/pages/launch/session-gate.vue`
- Modify: `fornted/common/auth/http-client.js`
- Modify: `fornted/common/auth/pre-auth.js`
- Test: `fornted/common/auth/mp-weixin-platform.test.cjs`

- [ ] **Step 1: 在恢复会话前初始化安装 ID**

```js
await ensureDeviceInstallationId()
const restored = await restorePersistedSession(null, sessionGeneration)
```

- [ ] **Step 2: 微信没有进程内会话时返回 `null`**

`restorePersistedSession` 只允许 H5 调用 Cookie bootstrap；Android 和微信分别检查自己的凭据仓库。微信冷启动为空时，沿用现有 `SESSION_NOT_FOUND` 终止分支进入登录页，而不是显示网络错误。

- [ ] **Step 3: 所有请求入口再次确保安装 ID 已就绪**

`publicRequest` 和 `bootstrapPreAuth` 在构造 Header 前调用 `await ensureDeviceInstallationId()`，避免页面绕过 session gate 时出现同步异常。

---

### Task 5: 建立微信进程内凭据仓库

**Files:**
- Create: `fornted/common/auth/mp-weixin-session-vault.js`
- Modify: `fornted/common/auth/session-vault.js`
- Modify: `fornted/common/auth/pre-auth.js`
- Test: `fornted/common/auth/mp-weixin-platform.test.cjs`

- [ ] **Step 1: 实现不落盘的凭据仓库**

```js
let credentials = emptySessionCredentials()

export function loadMpWeixinSessionCredentials() {
	return { ...credentials }
}

export function saveMpWeixinSessionCredentials(update) {
	credentials = mergeSessionCredentials(credentials, update)
	return loadMpWeixinSessionCredentials()
}

export function clearMpWeixinSessionCredentials() {
	credentials = emptySessionCredentials()
}
```

- [ ] **Step 2: session-vault 三路分发**

H5 只使用 Cookie，Android 只使用 AndroidKeyStore，微信只使用上述内存仓库。任何平台都不得读取另一个平台的存储实现。

- [ ] **Step 3: PreAuth 使用同一微信仓库**

微信从响应 JSON 保存 `preAuthToken`，后续请求通过 `X-AIT-PreAuth` 发送；H5 仍使用 HttpOnly Cookie，Android 仍使用 AndroidKeyStore。

---

### Task 6: 服务端增加独立微信枚举但复用显式 Token 运输

**Files:**
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/session/transport/AuthClientPlatform.java`
- Create: `ai-temperate-web/src/test/java/com/example/temperate/web/auth/session/transport/AuthClientPlatformTest.java`

- [ ] **Step 1: 测试解析和运输能力**

验证 `H5`、`ANDROID`、`WECHAT_MINI_PROGRAM` 精确解析；微信与 Android 的 `usesExplicitTokenTransport()` 为 `true`，H5 为 `false`。未知非空值必须受控拒绝，不能继续默认为 H5。

- [ ] **Step 2: 扩展枚举**

```java
public enum AuthClientPlatform {
    H5(false),
    ANDROID(true),
    WECHAT_MINI_PROGRAM(true);

    private final boolean explicitTokenTransport;

    AuthClientPlatform(boolean explicitTokenTransport) {
        this.explicitTokenTransport = explicitTokenTransport;
    }

    public boolean usesExplicitTokenTransport() {
        return explicitTokenTransport;
    }
}
```

保留缺失 Header 按 H5 兼容；未知非空 Header 抛出受控 400。JavaDoc 必须说明平台值只决定凭据位置，不承担认证作用。

---

### Task 7: 扩展服务端登录和会话运输

**Files:**
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/interceptor/UserSessionAuthenticationInterceptor.java`
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/login/controller/LoginController.java`
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/registration/controller/RegistrationController.java`
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/passwordreset/controller/PasswordResetController.java`
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/session/controller/SessionController.java`
- Test: existing `*TokenTransportTest.java` files

- [ ] **Step 1: 把“Android 判断”替换为“显式运输判断”**

```java
boolean explicit = platform.usesExplicitTokenTransport();
String access = explicit ? bearerToken(request) : cookieToken(request, ACCESS_COOKIE);
String refresh = explicit ? request.getHeader(REFRESH_HEADER) : cookieToken(request, REFRESH_COOKIE);
```

- [ ] **Step 2: 登录类成功响应**

H5 继续写 Cookie 且 JSON 不返回 Token；Android 和微信都在 JSON 返回 AT、RT、CSRF、PreAuth，但审计平台值保持各自枚举。

- [ ] **Step 3: 退出登录**

Android和微信从请求体读取 RT；只有 H5 清 Cookie。微信退出成功后前端清空进程内仓库。

- [ ] **Step 4: 锁定互斥测试**

每个 Token transport 测试必须覆盖：微信不接受 Cookie-only、H5 不接受请求体 RT、微信带完整显式凭据成功、微信缺少设备 ID 或 CSRF 失败。

---

### Task 8: 扩展 PreAuth 和 Spring Security 边界

**Files:**
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/risk/PreAuthTransport.java`
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/risk/NetworkRiskEdgeController.java`
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/auth/config/SecurityConfiguration.java`
- Test: corresponding NetworkRisk/Security tests

- [ ] **Step 1: 微信 PreAuth 显式运输**

微信和 Android从 `X-AIT-PreAuth` 读取，bootstrap 响应 JSON 返回原始 PreAuth；H5 仍只用 HttpOnly Cookie。

- [ ] **Step 2: CSRF 分类**

H5 保留浏览器 Cookie CSRF；微信和 Android 使用显式会话 CSRF Header。平台 Header 不能单独绕过认证、设备绑定或 PreAuth 校验。

- [ ] **Step 3: 风险能力降级**

微信第一版不执行 WebRTC、Turnstile 或顶层浏览器 Challenge。服务端对微信使用明确的 `UNSUPPORTED`/`SKIPPED_BY_PLATFORM` 风险能力状态，不得把“没有候选 IP”伪装成 H5 探测成功。

---

### Task 9: 前端显式 Header 与能力隔离

**Files:**
- Modify: `fornted/common/auth/http-client.js`
- Modify: `fornted/common/auth/auth-api.js`
- Modify: `fornted/common/auth/webrtc-verification.js`
- Modify: `fornted/common/auth/turnstile-prewarm.js`
- Test: `fornted/common/auth/mp-weixin-platform.test.cjs`

- [ ] **Step 1: 微信请求 Header**

```text
X-Client-Platform: WECHAT_MINI_PROGRAM
X-Device-Installation-Id: <UUIDv4>
Authorization: Bearer <AT>             仅登录后
X-Refresh-Token: <RT>                  受保护请求
X-CSRF-Token: <CSRF>                   受保护请求
X-AIT-PreAuth: <PreAuth>               风险模式要求时
```

- [ ] **Step 2: 响应续签**

微信和 Android 都读取 `X-New-Access-Token` 并更新各自仓库；H5 继续由服务端 Set-Cookie 更新。

- [ ] **Step 3: 禁用浏览器/Android 私有能力**

微信不得调用 H5 WebRTC、Turnstile 预热、浏览器 OAuth 或 Android challenge。禁用必须是明确的平台能力分支，不得通过运行时异常降级。

---

### Task 10: 域名、验证和验收

**Manual configuration:**
- 微信公众平台 → 开发管理 → 开发设置 → 服务器域名。
- `request` 合法域名加入 `https://niko000o.site`。
- 不在仓库中长期依赖 `urlCheck: false`；真机和发布验收必须开启域名校验。

**第二阶段命令，仅在用户明确确认后执行：**

```powershell
cd C:\Users\damn\Desktop\ai-temperate-main\fornted
npm run test:mp-weixin-auth
npm run test:auth-session
npm run test:auth-network-risk

cd C:\Users\damn\Desktop\ai-temperate-main
mvn -pl ai-temperate-web -am `
  -Dtest=AuthClientPlatformTest,LoginControllerTokenTransportTest,SessionControllerTokenTransportTest,RegistrationControllerTokenTransportTest,PasswordResetControllerTokenTransportTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

**HBuilderX/微信开发者工具验收：**

- 清空小程序 Storage 后首次启动生成合法 UUIDv4。
- 同一份 Storage 再次启动保持相同安装 ID；清除数据后生成新 ID。
- Network 出现发往 `https://niko000o.site/api/_edge/pre-auth` 的请求，而不是只有内部调试 WebSocket。
- 请求 Header 的平台值为 `WECHAT_MINI_PROGRAM`，设备 ID 非空且不包含账号信息。
- 未登录冷启动进入登录页，不再误显示“暂时无法连接”。
- 邮箱/密码登录后可访问个人资料；退出后显式凭据清空。
- 微信产物不包含 H5 Cookie、DOM、WebRTC、AndroidKeyStore 或 Android WebView 实现。
- H5 生产构建仍使用 Cookie；Android 本地打包仍使用 AndroidKeyStore，二者行为不变。

## 后续独立计划

如果需要“关闭小程序再打开仍保持登录”，必须另行设计 `wx.login()` code 与服务端会话交换/轮换协议。该计划需要定义 AppID/Secret 的服务端保管、OpenID/UnionID 绑定、短期 code 防重放、账号合并规则和 Refresh Session 生命周期；不得简单把长期 RT 明文写入微信普通 Storage。

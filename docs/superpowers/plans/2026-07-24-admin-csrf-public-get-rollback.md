# 管理员公开 GET 恢复签发 CSRF Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. 本项目只能由根代理实施，禁止分派子代理。

**Goal:** 精确撤销管理员延迟 CSRF 签发改动，使刷新管理员 H5 时由公开管理员 GET 重新签发或保持 `ADMIN-XSRF-TOKEN`，同时保留 Flow/Session 清理隔离和跨子域 Cookie Domain 修复。

**Architecture:** 管理员安全链恢复使用项目原有的 `SpaCsrfTokenRequestHandler`，由 `CookieCsrfTokenRepository` 在管理员公开请求中签发 `ADMIN-XSRF-TOKEN`。注册和登录仍使用各自的 `admin_register_csrf`、`admin_login_csrf`，普通用户 `XSRF-TOKEN` 不参与管理员链路。回退采用定点文件修改，禁止对脏工作树执行 Git 整体回退。

**Tech Stack:** Java 21、Spring Boot、Spring Security、JUnit 5、AssertJ、uni-app/Vue、Node.js contract tests。

---

## 回退边界

只回退以下改动：

- 删除 `AdminDeferredCsrfTokenRequestHandler`。
- `AdminSecurityConfiguration` 恢复使用 `SpaCsrfTokenRequestHandler`。
- 将“管理员公开 GET 不签发会话 CSRF”的测试和文档改回“公开 GET 会签发或刷新管理员 CSRF”。

必须保留以下改动：

- `ADMIN_CSRF_COOKIE_DOMAIN` 与 `ADMIN_COOKIE_DOMAIN` 分离。
- `requireFlowReadable()` 与 `requireSessionReadable()` 两种校验上下文。
- Flow 配置错误只设置 `clearFlow=true`，不得清理管理员 Session。
- Session 配置错误才设置 `clearSession=true`。
- 全新注册页面没有 `admin_register_csrf` 时跳过 `/register/status`。
- 注册、登录 Flow 继续使用各自的 CSRF Cookie；登录后写请求继续使用 `ADMIN-XSRF-TOKEN`。
- 普通用户 `XSRF-TOKEN` 行为保持不变，管理员页面不得依赖它。

## 文件职责

- 删除：`ai-temperate-web/src/main/java/com/example/temperate/web/admin/security/AdminDeferredCsrfTokenRequestHandler.java`
- 修改：`ai-temperate-web/src/main/java/com/example/temperate/web/admin/security/AdminSecurityConfiguration.java`
- 修改：`ai-temperate-web/src/test/java/com/example/temperate/web/admin/security/AdminSecurityConfigurationCsrfTest.java`
- 保持并核对：`ai-temperate-web/src/test/java/com/example/temperate/web/admin/security/AdminH5CsrfCookieScopeValidatorTest.java`
- 保持并核对：`ai-temperate-web/src/test/java/com/example/temperate/web/admin/api/AdminWebExceptionHandlerTest.java`
- 修改：`README.md`
- 修改：`docs/authentication-api.md`
- 修改：`scripts/cloudflare/README.md`

### Task 1: 先修改测试源码，锁定恢复后的 Cookie 生命周期

**Files:**
- Modify: `ai-temperate-web/src/test/java/com/example/temperate/web/admin/security/AdminSecurityConfigurationCsrfTest.java`
- Inspect without changing behavior: `ai-temperate-web/src/test/java/com/example/temperate/web/admin/api/AdminWebExceptionHandlerTest.java`

- [ ] **Step 1: 把延迟解析断言改为公开 GET 必须解析 Token**

测试使用 `SpaCsrfTokenRequestHandler`，分别构造以下 H5 GET：

```text
/api/admin/auth/state
/api/admin/auth/phone-country
/api/admin/auth/hcaptcha/config
```

每个请求调用 `handle()` 后都必须断言供应器只解析一次：

```java
assertThat(resolutions).hasValue(1);
```

- [ ] **Step 2: 保留原始管理员 Header 解析契约**

继续断言：

```text
Cookie 名：ADMIN-XSRF-TOKEN
请求头名：X-Admin-CSRF-Token
Header 提交原始双提交值
```

- [ ] **Step 3: 保留 Flow 错误不得清理 Session 的测试**

`AdminWebExceptionHandlerTest` 必须继续验证：

```java
assertThat(setCookies)
        .noneMatch(value -> value.startsWith("ADMIN-XSRF-TOKEN="));
```

这里的含义是配置错误发生在注册或登录 Flow 时，响应不能发送删除管理员会话 CSRF 的 `Set-Cookie`。

- [ ] **Step 4: 第一阶段停止执行测试**

本任务只编写测试源码，不运行 Maven、Node、Spring 上下文或外部联调。

### Task 2: 精确恢复管理员公开请求签发 CSRF

**Files:**
- Modify: `ai-temperate-web/src/main/java/com/example/temperate/web/admin/security/AdminSecurityConfiguration.java`
- Delete: `ai-temperate-web/src/main/java/com/example/temperate/web/admin/security/AdminDeferredCsrfTokenRequestHandler.java`

- [ ] **Step 1: 恢复原有处理器导入**

在 `AdminSecurityConfiguration` 中恢复：

```java
import com.example.temperate.web.auth.config.SpaCsrfTokenRequestHandler;
```

- [ ] **Step 2: 替换管理员安全链处理器**

将：

```java
AdminDeferredCsrfTokenRequestHandler csrfTokenRequestHandler =
        new AdminDeferredCsrfTokenRequestHandler();
```

替换为：

```java
SpaCsrfTokenRequestHandler csrfTokenRequestHandler =
        new SpaCsrfTokenRequestHandler();
```

保留：

```java
.csrfTokenRepository(adminCsrfTokenRepository)
.csrfTokenRequestHandler(csrfTokenRequestHandler)
```

因为仓库仍是管理员专用 `CookieCsrfTokenRepository`，恢复处理器只会签发 `ADMIN-XSRF-TOKEN`，不会签发普通用户的 `XSRF-TOKEN`。

- [ ] **Step 3: 更新紧邻中文注释**

注释必须说明：管理员公开 GET 主动解析延迟 Token，是为了在注册或登录页面刷新后稳定恢复管理员双提交 CSRF；真正的管理员 Session Token 仍只能由登录成功或有效会话恢复产生。

- [ ] **Step 4: 删除不再使用的延迟处理器文件**

删除 `AdminDeferredCsrfTokenRequestHandler.java`，禁止同时保留两个语义相反的实现，避免后续再次接错。

### Task 3: 核对不会重新引入 Cookie 闪现删除

**Files:**
- Inspect: `ai-temperate-web/src/main/java/com/example/temperate/web/admin/security/AdminH5CsrfCookieScopeValidator.java`
- Inspect: `ai-temperate-web/src/main/java/com/example/temperate/web/admin/api/AdminWebExceptionHandler.java`
- Inspect: `myuniappadmin/common/admin/admin-api.js`
- Inspect: `myuniappadmin/pages/index/index.vue`

- [ ] **Step 1: 保持 Flow/Session 清理上下文不变**

必须继续满足：

```text
requireFlowReadable → clearFlow=true, clearSession=false
requireSessionReadable → clearFlow=false, clearSession=true
```

因此 `/register/start`、`/register/status` 或 `/login/start` 的配置错误不能再删除 `ADMIN-XSRF-TOKEN`。

- [ ] **Step 2: 保持全新注册页面跳过无效恢复请求**

继续保留：

```javascript
if (adminApi.hasRegistrationFlow()) {
    await adminApi.registerStatus()
}
```

恢复公开 GET 签发 `ADMIN-XSRF-TOKEN` 不代表存在注册 Flow；是否恢复 `/register/status` 仍只能由 `admin_register_csrf` 判断。

- [ ] **Step 3: 保持三套管理员 CSRF 路由**

```text
/api/admin/auth/register/** → admin_register_csrf
/api/admin/auth/login/**    → admin_login_csrf
其他管理员写请求           → ADMIN-XSRF-TOKEN
```

普通用户 `XSRF-TOKEN` 不加入上述任何路径。

### Task 4: 更新文档，纠正上一轮生命周期描述

**Files:**
- Modify: `README.md`
- Modify: `docs/authentication-api.md`
- Modify: `scripts/cloudflare/README.md`

- [ ] **Step 1: 删除“公开管理员接口不会生成 ADMIN-XSRF-TOKEN”的描述**

- [ ] **Step 2: 写明恢复后的生命周期**

```text
刷新管理员 H5
→ GET /api/admin/auth/state
→ 写入或刷新 Domain=niko000o.site 的 ADMIN-XSRF-TOKEN
→ 无 admin_register_csrf 时跳过 /register/status
→ /register/start 成功后另外生成 admin_register_csrf
→ hCaptcha 成功后使用注册 Flow CSRF 提交
```

- [ ] **Step 3: 明确管理员公开 GET 不生成普通用户 XSRF-TOKEN**

避免以后再次把两个 Cookie 当作一对：管理员链只生成 `ADMIN-XSRF-TOKEN`，普通用户链只生成 `XSRF-TOKEN`。

### Task 5: 第二阶段定向验证

只有用户单独授权后才能执行。

**Commands:**

```powershell
Set-Location C:\Users\damn\Desktop\ai-temperate
mvn -pl ai-temperate-web -am `
  -Dtest=AdminSecurityConfigurationCsrfTest,AdminH5CsrfCookieScopeValidatorTest,AdminWebExceptionHandlerTest,AdminCookieWriterTest `
  -Dsurefire.failIfNoSpecifiedTests=false test

Set-Location C:\Users\damn\Desktop\ai-temperate\myuniappadmin
npm run test:auth-hcaptcha
```

测试只读源码并写入本地测试产物，不应连接 PostgreSQL、Redis、RabbitMQ、hCaptcha 或生产环境。

随后再单独授权非生产 H5 联调：

1. 清理旧的管理员测试 Cookie，打开全新无痕窗口。
2. 请求 `/api/admin/auth/state`，确认返回 `200`。
3. 确认响应写入 `ADMIN-XSRF-TOKEN`，包含 `Domain=niko000o.site`，且没有 `HttpOnly`。
4. 刷新页面五次，确认 Token 始终存在，不出现 `Max-Age=0` 删除响应。
5. 确认初始页面仍然没有 `admin_register_csrf`，也不请求 `/register/status`。
6. 点击注册，确认 `/register/start` 返回 `201` 并生成 `admin_register_csrf`。
7. 确认 hCaptcha 可以打开，且后续请求使用 `admin_register_csrf`，不是 `ADMIN-XSRF-TOKEN` 或普通 `XSRF-TOKEN`。
8. 人为制造 Flow 配置错误，确认只清理 Flow Cookie，不删除 `ADMIN-XSRF-TOKEN`。

## 完成标准

- 管理员 H5 每次刷新后都存在可读的 `ADMIN-XSRF-TOKEN`。
- 管理员公开 GET 不生成普通用户 `XSRF-TOKEN`。
- `/register/start` 成功后独立生成 `admin_register_csrf`。
- Flow 错误不得删除管理员 Session 或 `ADMIN-XSRF-TOKEN`。
- 不恢复旧的 `/register/status` 无条件调用。
- 不修改 CORS、hCaptcha Siteverify、Redis Key、请求体或普通用户认证链。

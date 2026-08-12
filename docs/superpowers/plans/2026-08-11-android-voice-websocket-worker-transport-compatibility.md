# Android Voice WebSocket Worker Transport Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Android App-Plus 的正常 `/ws/voice` 握手不再被 Worker 的通用浏览器运输检测误拒绝。

**Architecture:** 在 `classifyClientTransport()` 中增加精确 Android Voice WebSocket 运输判断。只要根域名、路径、GET、Upgrade 和 v2 Ticket 子协议均有效，就按 Android Native 运输继续处理；所有浏览器元数据仍在回源前删除，其他路由继续执行通用拒绝规则。

**Tech Stack:** Cloudflare Workers、JavaScript、Node Test Runner、uni-app App-Plus WebSocket。

---

## Task 1：固定 App-Plus 握手回归合同

**Files:**

- Modify: `cloudflare/api-gateway/test/index.test.js`

- [x] 在现有 Android Voice WebSocket 成功测试中加入同源 Origin 和 WebSocket Fetch Metadata。
- [x] 断言请求仍返回原上游 101 WebSocket 对象。
- [x] 断言回源请求保留 `X-Client-Platform: ANDROID` 和两个子协议。
- [x] 断言回源 Cookie、Authorization、Origin、Referer 和全部 `Sec-Fetch-*` 均不存在。
- [x] 保留普通 Android API 带 Origin/Fetch Metadata 返回 403 的现有测试。

## Task 2：增加精确 Voice 运输例外

**Files:**

- Modify: `cloudflare/api-gateway/src/index.js`

- [x] 新增 `validAndroidVoiceWebSocketTransport(request, route, url)`。
- [x] 要求 `route.webSocket`、root surface、根域名、精确 `/ws/voice`、GET、Upgrade 和有效 v2 Ticket 子协议同时成立。
- [x] 在通用 Android Origin/Fetch Metadata 拒绝之前返回 `ANDROID_TRANSPORT`。
- [x] 不读取或信任 Cookie、Authorization、Origin 或 Fetch Metadata 内容。
- [x] 不修改 `signedUpstreamRequest()` 的凭据与浏览器头删除逻辑。

## Task 3：分阶段验证

第一阶段只写代码与测试源码，并运行目标 `git diff --check`；不执行 Node 测试、部署或外部连接。

第二阶段需再次授权，在 `cloudflare/api-gateway` 目录运行：

```powershell
node --test --test-name-pattern "Android voice WebSocket" test/index.test.js
npm test
```

第三阶段需单独授权部署 Worker，并用 Android 实机确认 Worker 不再返回 `EDGE_CLIENT_TRANSPORT_INVALID`，随后出现 Spring 101、`CONNECTION_ESTABLISHED` 和 Whisper 上游生命周期日志。

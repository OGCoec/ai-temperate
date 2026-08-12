# Android Voice WebSocket URL Runtime Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Android App-Plus 在取得语音 Ticket 后因缺少浏览器全局 `URL` 而无法调用 `uni.connectSocket()` 的问题。

**Architecture:** 保持 `voiceWebSocketUrl(apiBaseUrl = AUTH_API_BASE_URL)` 的公开接口不变，在函数内部将受信任的纯 HTTPS Origin 严格校验后转换为固定 `/ws/voice` WSS 地址。H5 同源回退、Ticket、子协议、Worker、Spring 和 Whisper 链路均保持不变。

**Tech Stack:** uni-app、App-Plus、JavaScript、Node Test Runner。

---

## 不可改变的边界

- 不引入 URL Polyfill、新依赖或 Android 专用硬编码域名。
- 不修改 `voice-websocket-session.js`、Ticket JSON、WebSocket 子协议或音频协议。
- 不修改 H5 同源回退、Spring Security、Cloudflare Worker 或 Whisper 7896。
- 不把真实 Token、Cookie、Ticket、设备标识或其他凭据写入代码和测试。
- 保留当前脏工作区，不 reset、checkout、批量格式化、暂存或提交。

## Task 1：建立 Android 运行时回归测试

**Files:**

- Create: `fornted/common/voice/voice-ticket-api.test.cjs`

- [ ] 通过源码替换解除 `@/` Alias，仅加载真实 `voice-ticket-api.js`。
- [ ] 在 `globalThis.URL = undefined` 时断言生产 HTTPS Origin 转换为 `wss://niko000o.site/ws/voice`。
- [ ] 覆盖带端口 localhost、H5 安全同源回退和全局对象恢复。
- [ ] 拒绝 HTTP、凭据、路径、Query、Fragment、空 Host、端口 0、非法端口和反斜杠。
- [ ] 第一阶段不执行测试，RED 证据留到第二阶段授权。

## Task 2：移除 App-Plus 对浏览器 URL 类的依赖

**Files:**

- Modify: `fornted/common/voice/voice-ticket-api.js`

- [ ] 保持 `voiceWebSocketUrl(apiBaseUrl = AUTH_API_BASE_URL)` 签名不变。
- [ ] 只接受纯 HTTPS Origin，可带单个尾斜杠和 1 至 65535 的端口。
- [ ] 从校验结果构造 authority，返回 `wss://${authority}/ws/voice`。
- [ ] 不引用 `URL`、`window.URL` 或 `globalThis.URL`，不降级到 `ws://`。
- [ ] 保留空 API Base 时的 H5 `window.location` 安全回退。
- [ ] 使用中文原因注释解释 App-Plus JS Service 的运行时差异。

## Task 3：更新合同和测试入口

**Files:**

- Modify: `fornted/common/voice/voice-contract.test.cjs`
- Modify: `fornted/package.json`

- [ ] 删除固定 `new URL(base)` 实现的源码断言。
- [ ] 增加不得重新引入 `new URL()` 和不安全 `ws://` 的反向断言。
- [ ] 保留主域名、`X-Client-Platform`、`ait-voice-v2` 和 Ticket 子协议合同。
- [ ] 将 `voice-ticket-api.test.cjs` 加入 `test:voice`，不修改其他脚本或依赖。

## Task 4：分阶段验证

第一阶段只运行：

```powershell
git diff --check
```

第二阶段需要用户再次明确授权，在 `fornted` 目录运行：

```powershell
node --test common/voice/voice-ticket-api.test.cjs
npm run test:voice
```

第三阶段需要单独授权重新构建并安装 Android App，再确认 Ticket 200 后出现 `/ws/voice` 请求，页面不再显示 `URL is not defined`，Worker 为 `EDGE_WEBSOCKET_UPGRADED`，Spring 为 `status=101 setCookiePresent=false`，并继续出现 Whisper 的 `CONNECT_STARTED`、`WEBSOCKET_OPEN` 和 `SESSION_START_SENT`。

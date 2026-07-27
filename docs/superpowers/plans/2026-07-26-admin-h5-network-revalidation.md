# Admin H5 Network Revalidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 MyUniAppAdmin H5 中发现网络可能变化后原地重新确认 HTTP IP 风险与 WebRTC 一致性，同时避免页面刷新、重复探测和无界重试。

**Architecture:** 新增一个 H5 全局网络重检调度器，统一接收浏览器网络事件、页面恢复事件和低频兜底定时器。调度器复用现有 PreAuth、`/api/admin/_edge/webrtc/start` 与 `/report`，后端继续作为 HTTP IP 和风险结论的唯一可信来源；管理员业务请求在重检完成前由同一个 Promise 门禁暂停。

**Tech Stack:** uni-app H5、JavaScript、Node.js `node:test` 契约测试、Spring Boot 网络风险与 WebRTC 现有接口。

---

### Task 1: 锁定前端网络重检契约

**Files:**
- Modify: `myuniappadmin/common/admin/admin-network-risk-contract.test.cjs`

- [x] 增加源代码契约，要求全局调度器监听 `online`、`offline`、`visibilitychange`、`focus` 与 `navigator.connection.change`。
- [x] 固定两秒防抖、六十秒后台恢复阈值、五分钟兜底周期、三十秒成功冷却和 `2/5/10/30` 秒有限退避。
- [x] 要求管理员 HTTP 请求等待统一网络可信门禁，失败页手动重试也经过同一调度器。
- [x] 禁止调度器使用 `WebSocket`、`location.reload()`、`localStorage`、`sessionStorage` 或主动 `fetch`。

### Task 2: 实现全局重检状态机

**Files:**
- Create: `myuniappadmin/common/admin/admin-network-revalidation.js`

- [x] 实现 `TRUSTED`、`SUSPECTED`、`CHECKING`、`WAITING_NETWORK`、`VERIFICATION_FAILED` 与 `RISK_ACTION` 状态。
- [x] 实现幂等监听安装、清理、事件防抖、单飞 Promise、可见页面五分钟兜底和离线暂停。
- [x] 普通事件只失效 WebRTC 内存可信状态；仅 `PREAUTH_REQUIRED` 或 `ADMIN_PREAUTH_REQUIRED` 才重建 PreAuth。
- [x] 重检始终先调用现有 `start`，仅后端要求时才由现有验证模块创建 `RTCPeerConnection` 并上报。
- [x] 网络失败最多按照 `2/5/10/30` 秒退避四次，之后保持只读等待下一次明确事件。

### Task 3: 接入应用生命周期和业务请求门禁

**Files:**
- Modify: `myuniappadmin/App.vue`
- Modify: `myuniappadmin/common/admin/admin-http.js`
- Modify: `myuniappadmin/pages/risk/webrtc-failed.vue`

- [x] H5 `onLaunch` 立即安装全局监听并与 Cookie Scope、PreAuth 初始化复用同一单飞请求；非 H5 行为保持不变。
- [x] H5 管理员业务请求在发送前调用 `ensureAdminNetworkTrusted()`，复用正在执行的重检 Promise。
- [x] WebRTC 失败页的人工重试通过调度器执行，使全局状态和后端状态同步恢复。

### Task 4: 保留风险导航字段

**Files:**
- Modify: `myuniappadmin/common/admin/admin-webrtc-verification.js`

- [x] WebRTC `start/report` 非 2xx 错误保留 `challengeRef`、`challengePath`、`expiresAt` 与 `reauthenticationRequired`。
- [x] 成功路径继续不保存和不回显 HTTP IP；失败详情继续只存在内存。

### Task 5: 第二阶段定向验证

**Commands（本轮不执行）:**

```powershell
Push-Location myuniappadmin
npm run test:auth-network-risk
Pop-Location
```

预期所有管理员网络风险契约通过，无构建和外部服务连接。

在用户再次确认第二阶段浏览器验收范围后，使用生产式 H5 而不是 Vite HMR 页面验证 Wi-Fi、VPN、IPv4/IPv6、离线恢复、UDP 阻断、WebRTC 空结果、不匹配、Challenge 与 Block。

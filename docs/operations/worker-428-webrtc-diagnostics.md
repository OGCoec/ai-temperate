# Worker 428 与 WebRTC 端到端诊断手册

## 1. 适用范围

本手册用于判断公网 H5 在 Google OAuth 返回后出现的
`EDGE_COOKIE_SCOPE_RESET_REQUIRED`、WebRTC 探测未启动或探测被拒绝问题。
Worker 诊断能力不改变边缘 428 条件或 Cookie 属性。H5 OAuth 的 WebRTC 业务 428
则必须作为终态消费：report 未 VERIFIED 时不得调用 OAuth complete，也不得创建下一代探测。

## 2. 三层关联标识

一次业务网络请求使用唯一的 `X-AIT-Client-Request-Id`，同一页面生命周期使用
`X-AIT-Page-Instance-Id`，同一次 WebRTC 探测使用
`X-AIT-WebRTC-Probe-Run-Id`。

收到 428 的原请求记为 A。随后 Cookie 迁移请求 B 和唯一一次业务重试 C 都创建新的
Client Request ID，并携带 `X-AIT-Trigger-Request-Id: A`。排查时不得把 B 或 C 的
Client Request ID 当成 A，也不得预期三次网络请求复用同一个 ID。

## 3. 部署身份与安全响应头

Worker 对认证、Cookie 迁移和 WebRTC HTTP 路由补充以下响应头：

| 响应头 | 判定用途 |
| --- | --- |
| `X-AIT-Edge-Outcome` | 边缘分支结果，例如 marker 缺失、迁移签发、上游响应或上游连接失败 |
| `X-AIT-Edge-Upstream-Attempted` | `0` 表示未访问后端，`1` 表示至少尝试访问后端 |
| `X-AIT-Cookie-Scope-State` | `MISSING`、`RESET_ISSUED`、`CURRENT` 或 `NOT_APPLICABLE` |
| `X-AIT-Cookie-Scope-Reset` | Cookie 迁移是否实际签发重置响应 |
| `X-AIT-Worker-Version` | Wrangler `version_metadata` 提供的 Worker 版本 ID |
| `X-AIT-Pages-Deployment` | `H5_PAGES_ORIGIN` 中提取的不可变 Pages 部署标识 |
| `X-AIT-Backend-Release` | 后端返回的 `APP_RELEASE_ID`；没有到达后端时为 `unknown` |

响应头和日志禁止出现 Cookie 值、Token、OAuth code/state、完整 IP、完整 URL 查询、
完整 User-Agent 或 Worker 签名。

### 3.1 后端发布标识注入

通过 `start-local-https-dev.bat` 启动本地开发环境时，启动器会把发布标识写入
Antigravity 子进程的 `APP_RELEASE_ID`，随后由该进程启动的 Spring Boot 自动继承。
合法的显式 `APP_RELEASE_ID` 优先；未设置或值不合法时按以下规则生成：

```text
干净 Git 工作区：local-g<12位Git SHA>
脏 Git 工作区：local-g<12位Git SHA>-dirty-<UTC启动时间>
Git 信息不可用：local-unknown-<UTC启动时间>
```

自动注入只作用于本次进程树，不写入永久用户或系统环境变量。同一个后端进程从启动到
退出必须保持同一个标识；源码、构建产物或部署发生变化后必须重启并生成新标识。

正式打包或镜像环境应该显式注入不可变的 `git-<SHA>`、镜像标签或 CI 构建号。发布标识
必须匹配 `^[A-Za-z0-9._-]{1,64}$`，禁止包含 Token、Secret、用户名、完整路径或其他
敏感信息。缺失或非法值会安全显示为 `unknown`，仅降低跨版本排障能力，不改变认证结果。

## 4. 日志入口

### 4.1 前端手工导出

只有在页面地址显式加入 `?aitAuthDiagnostics=1` 时才开启 Console 镜像和手工导出桥。
复现后在外部 Chrome DevTools Console 执行：

```javascript
window.__AIT_AUTH_DIAGNOSTICS__.exportJson()
```

导出最多保留当前 `sessionStorage` 中的 500 条脱敏事件。运行环境只包含浏览器家族与
主版本、操作系统家族、安全上下文、Cookie API 是否启用及当前 Origin Host。

### 4.2 Worker 日志

检索事件名 `auth_edge_request_completed`，优先使用以下任一字段：

- `cfRay`
- `clientRequestId`
- `triggerRequestId`
- `probeRunId`

所有 4xx/5xx、异常、428 与 Cookie 迁移事件全量记录；普通成功认证/WebRTC 上游响应
按 1% 采样。每个进入诊断边界的请求最多写一条该事件。

### 4.3 后端日志

检索事件名 `auth_request_completed`。关键字段为：

- `clientRequestId`、`triggerClientRequestId`、`probeRunId`、`traceId`
- `workerRay`：只可能来自通过 HMAC 验签的 `X-AIT-Edge-Ray`
- `originCfRay`：后端源站收到的 `CF-Ray`，不等同于 Worker Ray
- `edgeProxyOutcome`：`VERIFIED`、`MISSING_REQUIRED`、`INVALID`、
  `UNSIGNED_OPTIONAL` 或 `DISABLED`
- `backendRelease`

客户端直接伪造的 `X-AIT-Edge-Ray` 不会进入 `workerRay`。

## 5. 428 判定表

| 观察结果 | 确定结论或下一检查点 |
| --- | --- |
| 前端没有 `COOKIE_SCOPE_MIGRATION_*` 事件 | Cookie 迁移门禁或前端初始化没有执行；先检查 `COOKIE_SCOPE_MIGRATION_GATE` 的 disposition |
| 迁移为 `RESET_ISSUED`，下一请求仍为 `MISSING` | 浏览器没有保存或没有发送 marker；检查 Cookie 存储、父域清理、浏览器策略以及 OAuth 前后是否切换上下文 |
| 迁移为 `ALREADY_CURRENT`，下一请求却为 `MISSING` | 两个请求使用了隔离的 Cookie 上下文；检查标签页、隐私模式、WebView/Chrome 切换或站点分区 |
| Worker 428 且 `upstreamAttempted=false`，后端无同 ID 日志 | 这是预期且确定的 Worker 边缘拦截，不能据此归咎后端未记录 |
| Worker `upstreamAttempted=true`，后端无对应日志 | 检查 Worker 到源站的连接、路由和签名边界；若 outcome 为 `UPSTREAM_FETCH_FAILED`，优先检查连接失败 |
| 后端收到请求且 WebRTC 日志拒绝 | 按 `traceId`、`clientRequestId`、`probeRunId` 检查验证状态、generation、报告结果与稳定失败码 |
| `/api/_edge/webrtc/report` 返回 428 且 `upstreamAttempted=true` | 这是后端 WebRTC 业务失败，不是 Worker marker 428；H5 OAuth 必须立即终止 |
| report 428 后仍出现 `/api/auth/oauth2/complete` 200 | 严格闸门发生回归；检查前端是否等待 report，以及后端 H5 complete 是否只接受 VERIFIED |
| report 428 后出现第二次 start 或 generation+1 | 严格轮换发生回归；OAuth complete 不得把 FAILED/PENDING 降级轮换为新的 REQUIRED |
| 前端 ICE candidate 为 0 且没有 report 请求 | 请求尚未到 Worker/后端 report 阶段；定位浏览器 RTCPeerConnection、STUN 或页面生命周期 |
| 历史 Pages 版本全部失败，Worker/后端版本相同且均为 marker missing | 优先判断当天浏览器或边缘 Cookie 状态，不先归因于某个前端提交 |
| Pages 部署相同，但 Worker 或后端版本在故障点变化 | 优先核查对应 Worker 发布、后端镜像及环境变量差异 |

## 6. 一次受控复现的读取顺序

1. 使用外部 Chrome 打开带 `?aitAuthDiagnostics=1` 的公网 H5；禁止使用 Codex 内置浏览器。
2. 清空 DevTools Network 记录后执行一次 Google 登录并等待 OAuth 返回。
3. 记录第一个 428 的 Client Request ID、`CF-Ray`、Worker/Pages/Backend 版本和三个边缘状态头。
4. 找到紧随其后的 `/api/_edge/cookie-scope`，确认其 Client Request ID 不同且 Trigger ID 指向第一个 428。
5. 找到唯一一次业务重试，确认它再次使用新的 Client Request ID，Trigger ID 仍指向第一个 428。
6. 导出前端 JSON，并按 A 的 Client Request ID 或 Ray 检索 Worker 日志。
7. 只有 Worker 显示 `upstreamAttempted=true` 时才要求后端存在对应完成日志。
8. 到达后端后，对齐 `workerRay`、`originCfRay`、发布版本、`probeRunId` 和 WebRTC 专用日志。

## 7. 发布和验收顺序

1. 先部署后端，并把非敏感镜像、Git SHA 或 CI 构建号注入 `APP_RELEASE_ID`；本地开发通过项目 HTTPS 启动器自动注入。
2. 再部署只含诊断增强的 Pages，记录不可变 Pages URL。
3. 对不可变 URL 执行 `npm --prefix fornted run verify:h5-pages-deployment -- --origin <URL>`；任一资源非 200 时停止发布。
4. 最后将已验收 URL 写入 Worker 的 `H5_PAGES_ORIGIN`，与 `version_metadata`、日志配置一起发布 Worker。
5. 使用空 marker 的受控请求确认 428 响应为 `MISSING`、`upstreamAttempted=0`，Worker 仅有一条日志且后端无对应日志。
6. 完成迁移后确认 B 与 C 均指向 A，正常到达后端时三层部署身份和关联字段一致。
7. 检查导出与日志中不存在 Cookie、Token、OAuth code/state、完整 IP、查询串或完整 User-Agent。

## 8. 第二阶段验证命令

以下命令只在明确进入第二阶段并获得执行授权后运行：

```text
npm --prefix cloudflare/api-gateway test
npm --prefix fornted run test:auth-session
npm --prefix fornted run test:auth-https
npm --prefix fornted run test:auth-network-risk
```

后端只执行 EdgeProxy、AuthRequestTrace、CORS 与 Spring 上下文相关的定向测试。

# WebRTC Android UTS 探针与 H5 请求顺序交接文档

更新时间：2026-08-11  
工作区：`C:\Users\damn\Desktop\ai-temperate-main`  
当前分支：`codex/backup-20260811-fullstack`  
当前 HEAD：`bb00241 backup: save uncommitted frontend and backend risk challenge work`

## 1. 交接目标

这份文档用于把当前较长的排查上下文压缩成可以独立阅读的工程上下文，重点覆盖：

1. Android uni-app WebRTC 后台探针为什么最初完全无法启动。
2. 已实现的 UTS `SecureRandom + AES-GCM` 修复及其安全边界。
3. CSP `unsafe-eval` / `load__plus` 问题及现有修复。
4. Android 目前为何仍然向后端报告空 `webRtcIps`。
5. 为什么有时只看到 `/start` 失败而看不到 `/report`。
6. H5 的 `phone-country` 为什么被排在 WebRTC `/report` 之后。
7. Chrome WebRTC Network Limiter 插件对 H5 检测结果的影响，以及它为什么与 Android 无关。
8. 已执行的验证、未完成的运行时验收和建议的下一步。

本文不包含用户 Cookie、PreAuth Token、Cloudflare Clearance、密钥、密文或完整设备标识。

## 2. 一句话现状

当前不是一个单一故障，而是三件彼此独立的事情：

| 范围 | 当前结论 | 状态 |
| --- | --- | --- |
| H5 WebRTC 偶发空候选 | Chrome WebRTC Network Limiter 自动切换到第四项后，可能让 STUN/UDP 无法产生 `srflx` 候选 | 已找到外部原因，切换第三项后 H5 恢复过 |
| H5 `phone-country` 请求顺序 | 页面加载时已经调用，但通用 `publicRequest()` 在 H5 中强制等待 WebRTC 完成，所以网络请求实际排在 `/report` 后 | 已定位，尚未修改 |
| Android WebRTC 空候选 | Android 已能进入探针并发送 `/report`，但报告内容仍为 `webRtcIps: []`；所有内部失败目前都被折叠为空数组 | 核心未解决问题 |

## 3. 已确认的产品规则

### 3.1 后端 WebRTC 一致性规则

当前规则目标为：

- HTTP IPv4 只与 WebRTC IPv4 比较。
- HTTP IPv6 只与 WebRTC IPv6 比较。
- IPv4 比较 `/24`，前三个字节相同即可，最后一个字节允许不同。
- IPv6 比较 `/64`，前 64 位相同即可。
- WebRTC 最多允许一个不同的公网 IPv4 和一个不同的公网 IPv6。
- 同类型出现两个或更多不同公网地址时，返回 `WEBRTC_IP_MISMATCH`。
- 空候选返回 `WEBRTC_VERIFICATION_FAILED`。
- `REQUIRED`、`PENDING` 阶段属于异步开放状态；到达 `FAILED` 终态后，ENFORCE 模式停止访问。

后端 `report(...)` 已使用前缀匹配：

- `ai-temperate-service/src/main/java/com/example/temperate/service/risk/webrtc/service/impl/WebRtcVerificationServiceImpl.java:137`
- 调用 `normalizer.matchesTrustedPrefix(canonicalHttpIp, candidate)`。

### 3.2 一个需要下一位接手者注意的后端不一致

同一个实现类的 `inspectVerified(...)` 仍使用完整字符串相等：

```java
return ips.contains(canonicalHttpIp)
```

位置：

- `ai-temperate-service/src/main/java/com/example/temperate/service/risk/webrtc/service/impl/WebRtcVerificationServiceImpl.java:220`
- 精确比较位于约第 229 行。

这与 `/24`、`/64` 的新规则不一致。可能出现：

```text
report 阶段：66.90.98.35 与 66.90.98.38 按 /24 验证成功
后续 inspect：因为完整 IP 不相等而返回 STATE_INVALID
```

下一步需要为 `inspectVerified(...)` 复用与 `report(...)` 相同的同类型、候选数量和前缀匹配逻辑，并补回归测试。此项不是本轮 Android 空数组的直接原因，但属于真实的潜在回归点。

## 4. WebRTC 四态与请求链路

### 4.1 新会话的正常链路

```text
PreAuth 创建/更新
  -> GET /api/_edge/webrtc/start
  -> verificationState=PENDING
  -> probeRequired=true
  -> 客户端收集 WebRTC 公网候选
  -> POST /api/_edge/webrtc/report
  -> VERIFIED 或 FAILED
```

`timeoutMillis=12000`，`reportGraceMillis=3000`，服务端总 pending 窗口通常为 15 秒。

### 4.2 为什么有时 `/start` 直接返回 FAILED，且没有新的 `/report`

前端 `verify(...)` 的顺序是：

1. 先调用 `/start`。
2. 如果服务端返回 `verificationState=FAILED`，前端立即抛出失败并进入失败页。
3. 只有 `verificationState=PENDING` 且 generation 合法时，才启动本地探针并发送 `/report`。

对应代码：

- `fornted/common/auth/webrtc-verification.js:160`
- `fornted/common/auth/webrtc-verification.js:173`：遇到 `FAILED` 直接抛错。
- `fornted/common/auth/webrtc-verification.js:192`：只在 PENDING 后收集候选。
- `fornted/common/auth/webrtc-verification.js:198`：收集完成后才提交 report。

因此，抓包中只有 `/start -> FAILED` 而没有 `/report`，通常表示当前 PreAuth/generation 在 Redis 中已经是失败终态，客户端只是读取既有状态；不是该次 `/start` 请求现场执行了 WebRTC 探测。

### 4.3 已确认的 Android 当前真实链路

较新的抓包已经出现：

```text
POST /api/_edge/pre-auth                    200
GET  /api/_edge/webrtc/start                200 PENDING
GET  /api/auth/phone-country                200
POST /api/_edge/webrtc/report               428
GET  /api/_edge/webrtc/start                200 FAILED
```

`/report` 的服务端响应包含：

```json
{
  "code": "WEBRTC_VERIFICATION_FAILED",
  "webRtcStatus": false,
  "webRtcIps": []
}
```

这证明：

- Android 后台任务已经被触发。
- `/report` 确实发送到了服务器。
- 428 不是“请求没有发出”，而是服务端按 fail-closed 规则拒绝了空候选。
- 当前问题发生在 HTTP/WebRTC 前缀比较之前，因为根本没有候选可比较。

## 5. Android 最初的运行环境问题

在清理客户端 PreAuth/会话状态、强制产生新 generation 后，HBuilderX 曾打印：

```text
[WebRTC capability] {
  plus: true,
  webview: true,
  crypto: false,
  subtle: false,
  randomValues: false,
  rtcPeerConnection: "undefined"
}
```

该日志说明：

- uni-app Android 逻辑层存在 `plus` 和 `plus.webview`。
- 逻辑层不提供 `globalThis.crypto`、`crypto.subtle`、`crypto.getRandomValues`。
- 逻辑层也不提供 `RTCPeerConnection`。
- 这不是“缺少服务端密钥”，也不是后端加密算法错误，而是 JavaScript 执行上下文能力不同。

因此不能在 uni-app 逻辑层直接使用 Web Crypto；同时也不应把 WebRTC 移到逻辑层。实际 WebRTC 继续由隐藏本地 WebView 执行。

## 6. 已实施的 UTS 原生加密方案

### 6.1 最终结构

```text
uni-app 逻辑层
  -> UTS SecureRandom 生成 channelId、nonce、AES-256 key
  -> 创建隐藏本地 WebView
  -> 隐藏 WebView 使用 RTCPeerConnection + 后端四个 STUN
  -> 隐藏 WebView使用 Web Crypto AES-GCM 加密结果
  -> aitwebrtc://result 只回传 channel、IV、密文
  -> UTS 使用 Android AES-GCM 解密
  -> 共享 JS 校验 channel、nonce、数量与长度
  -> POST /webrtc/report
```

### 6.2 普通端与管理员端 UTS 插件

普通端：

```text
fornted/uni_modules/ait-webrtc-crypto/
  package.json
  utssdk/interface.uts
  utssdk/app-android/config.json
  utssdk/app-android/index.uts
```

管理员端：

```text
myuniappadmin/uni_modules/ait-webrtc-crypto/
  package.json
  utssdk/interface.uts
  utssdk/app-android/config.json
  utssdk/app-android/index.uts
```

两端插件保持相同 ID、接口和 Android 实现。

### 6.3 UTS 原生实现

实现使用：

- `java.security.SecureRandom`
- `AES/GCM/NoPadding`
- 32 字节 AES-256 key
- 12 字节 GCM IV
- 128 位认证 Tag
- AAD：`channelId + "|" + nonce`
- 无填充 Base64URL 严格规范化验证

内部错误码：

```text
INVALID_INPUT
AUTHENTICATION_FAILED
CRYPTO_FAILURE
```

UTS 插件不：

- 持久化 key。
- 使用 AndroidKeyStore。
- 发起网络请求。
- 输出密钥、密文、nonce、IP 或 Java 异常到日志。

### 6.4 普通端接入

文件：

- `fornted/common/auth/webrtc-verification-android.js`

变化：

- 删除逻辑层 `globalThis.crypto` 前置要求。
- 只要求 `plus.webview`。
- 注入 `createWebRtcProbeChannel` 和 `decryptWebRtcProbePayload`。

### 6.5 管理员端接入

文件：

- `myuniappadmin/common/admin/admin-webrtc-verification.js`
- `myuniappadmin/common/admin/admin-webrtc-verification-android.js`

变化：

- Android 使用管理员工程本地 UTS 插件。
- H5 继续使用浏览器探针。
- generation、PreAuth、后台去重和 report API 保持原逻辑。

### 6.6 共享 Android 探针

文件：

- `shared-frontend/auth/android-webrtc-background-probe.js`
- `shared-frontend/auth/android-webrtc-background-probe.test.cjs`

变化：

- 不再直接依赖逻辑层 Web Crypto。
- 由调用方注入 UTS crypto bridge。
- 严格校验 channel、nonce、key、IV、payload。
- 解密后再次校验 channel 与 nonce。
- 最多接收 8 个、每个最多 64 字符的候选。
- 重复 Scheme 回调只处理一次。
- 完成后关闭 WebView，并清空 JS 对 key、nonce、channelId 的引用。
- 任一错误统一返回空数组，维持 fail-closed。

## 7. CSP `load__plus` 错误与修复

曾出现：

```text
Uncaught EvalError: Evaluating a string as JavaScript violates CSP
script-src 'self'
load__plus function error
at hybrid/html/webrtc-probe.html:2
```

原因是隐藏 WebView 默认注入 Plus 运行时，而 Plus 注入过程使用了动态求值；探针页面 CSP 明确禁止 `unsafe-eval`。

当前修复是在创建隐藏 WebView 时加入：

```js
plusrequire: 'none'
```

位置：

- `shared-frontend/auth/android-webrtc-background-probe.js:68`

探针页本身不需要 `plus`，所以该设置可以阻止 `load__plus` 注入，同时保持：

```text
script-src 'self'
```

禁止通过添加 `unsafe-eval` 放宽 CSP。

## 8. Android 当前仍未解决的问题

### 8.1 当前表现

隐藏探针最终返回：

```js
[]
```

随后客户端正常提交：

```json
{
  "probeGeneration": "...",
  "webRtcIps": []
}
```

后端将其写为 `NO_PUBLIC_CANDIDATE`，对外返回 `WEBRTC_VERIFICATION_FAILED`。

### 8.2 为什么现有截图还不能确定具体根因

当前共享探针会把以下全部情况折叠成同一个空数组：

1. UTS `createChannel()` 失败或返回格式错误。
2. 隐藏 WebView 创建失败、加载失败、关闭或整体超时。
3. `evalJS` 没有真正调用 `window.startWebRtcProbe(...)`。
4. 隐藏 WebView 中没有 `RTCPeerConnection`。
5. 隐藏 WebView 中没有 Web Crypto 或 `TextEncoder`。
6. 四个 STUN 均未生成 `srflx`，例如 UDP/STUN 被网络或节点限制。
7. 页面通过 Scheme 返回了 `error=crypto_unavailable` 或 `error=probe_error`。
8. Scheme 格式、channel、IV、payload 校验失败。
9. UTS AES-GCM Tag 验证或解密失败。
10. 解密后的 channel/nonce 不匹配。

尤其需要注意：

- `shared-frontend/auth/android-webrtc-background-probe.js:122` 对任何 `error` 参数直接返回 `[]`。
- WebView error、close、超时和解密失败也全部返回 `[]`。
- 所以“能看到 `/report`”只证明后台流程完成了，不证明 WebView 已成功获得候选，也不证明 AES-GCM 已完成一次成功往返。

### 8.3 推荐的下一步：安全的阶段诊断

下一次修改应先增加不含敏感数据的阶段事件，而不是继续猜测 Clash、DNS 或后端匹配规则。

建议阶段：

```text
CHANNEL_CREATED
WEBVIEW_CREATED
WEBVIEW_LOADED
EVAL_DISPATCHED
SCHEME_RECEIVED
WEBVIEW_CRYPTO_UNAVAILABLE
WEBVIEW_RTC_UNAVAILABLE
ICE_GATHERING_EMPTY
PROBE_ERROR
DECRYPT_SUCCEEDED
DECRYPT_FAILED
PLAINTEXT_VALIDATED
PROBE_TIMEOUT
```

允许记录：

- 阶段名。
- generation 的非敏感本地关联标识或 attemptId。
- elapsedMillis。
- candidateCount。
- 枚举型内部错误码。

禁止记录：

- AES key。
- nonce/channel 原值。
- IV、payload、明文。
- 完整 IP。
- Cookie、PreAuth、设备安装 ID。

建议将诊断放在开发模式或显式 debug flag 下，并确保生产版本默认关闭。

### 8.4 使用时间差快速判断

比较 `/start` 和 `/report` 的时间：

- 接近 12 秒后才 report：优先怀疑 ICE/STUN 无候选或整个探针超时。
- 很快就 report：优先怀疑 API 缺失、页面主动 `postError`、Scheme 校验或 AES-GCM 解密失败。

这只能用于缩小范围，不能替代阶段诊断。

## 9. Chrome 插件与 H5 的结论

桌面 Chrome 安装了 WebRTC Network Limiter。插件有四个选项，用户环境中插件曾自动回到第四项；切换到第三项后 H5 曾恢复获取候选。

### 9.1 为什么 IPPure 全部显示“未检测到泄露”

当第四项强制 WebRTC 尝试通过代理，而代理链路不能承载所需 UDP/STUN 时，浏览器可能完全不产生 `srflx` 公网候选。

IPPure 显示：

```text
未检测到 WebRTC IP 泄露
```

不一定代表“WebRTC 正常并且出口一致”，也可能只代表“没有任何可见候选”。

本项目的安全门禁要求必须获得公网候选进行一致性比较，所以同样情况会得到：

```text
webRtcIps=[]
WEBRTC_VERIFICATION_FAILED
```

两者并不矛盾。

### 9.2 插件不能解释 Android

桌面 Chrome 扩展只影响桌面 Chrome 页面。HBuilderX Android 应用使用 Android System WebView/隐藏 WebView，不会加载桌面 Chrome 的扩展。

因此：

- 插件可以解释 H5 临时无候选。
- 插件不能解释 Android 隐藏 WebView的空候选。

## 10. H5 `phone-country` 请求顺序问题

### 10.1 当前页面其实已经在加载时触发

登录页 `onLoad()` 已调用：

```js
this.initializePhoneCountry()
```

位置：

- `fornted/pages/auth/login.vue:251`
- 注册和找回密码页面也有同类调用。

因此问题不是页面忘记启动国家解析，而是请求层把真正的网络请求阻塞了。

### 10.2 具体阻塞点

`authApi.phoneCountry()` 使用通用：

```js
publicRequest('/api/auth/phone-country', ...)
```

位置：

- `fornted/common/auth/auth-api.js:109`

而 `publicRequest()` 在 H5 条件编译块中无条件执行：

```js
await ensureH5WebRtcVerified()
```

位置：

- `fornted/common/auth/http-client.js:219`
- 关键等待约在第 223 行。

所以即使页面加载时已经调用 `phoneCountry()`，请求也会停在通用 H5 WebRTC 门禁上，直到 `/report` 完成后才真正发出。

### 10.3 当前与期望顺序

当前 H5：

```text
/start
  -> WebRTC 探测
  -> /report
  -> /phone-country
```

期望 H5：

```text
                 -> WebRTC 探测 -> /report
/start / PreAuth
                 -> /phone-country
```

### 10.4 建议修复边界

只为低风险、只读的 `/api/auth/phone-country` 增加明确的 H5 WebRTC 前置豁免，不要全局取消 `publicRequest()` 的门禁。

可选实现：

1. 给 `publicRequest()` 增加内部选项，例如 `skipH5WebRtcGate: true`，并只由 `phoneCountry()` 使用。
2. 或新增一个用途单一的 PreAuth-only 公共请求函数，仅允许白名单路径。

必须保持：

- 仍先执行 Cookie Scope migration 和 PreAuth。
- 不影响登录、注册、验证码、CSRF 或其他敏感请求的 WebRTC 门禁。
- 后端到达 FAILED 终态后仍可按 ENFORCE 规则阻止后续访问。
- 添加契约测试，证明只有 `phone-country` 可以在 PENDING 阶段并行请求。

### 10.5 Android 顺序已经符合异步目标

Android 不执行 H5 的 `await ensureH5WebRtcVerified()`。较新的抓包已经证明：

```text
/start PENDING
  -> /phone-country 200
  -> /report 428（空候选）
```

所以 Android 的 `phone-country` 顺序本身没有上述 H5 串行问题。早期抓包中的 `phone-country 428` 是因为会话当时已经进入 FAILED 终态。

## 11. Clash、DNS 与 STUN 的边界

### 11.1 当前空数组不能直接归因于 DNS

DNS 只负责把 STUN 域名解析成地址；是否生成 `srflx` 还取决于：

- WebView 是否真正提供 RTCPeerConnection。
- 是否成功创建 Offer 并设置 Local Description。
- UDP/STUN 是否能通过 TUN/代理/节点。
- STUN 服务本身是否可达。
- 隐藏页面是否把结果正确加密并通过 Scheme 返回。

因此 DNS 正常不等于 WebRTC 一定有候选；空候选也不能仅凭截图证明是 DNS 错误。

### 11.2 Clash YAML 的作用范围

Clash 规则、TUN、UDP 支持和节点上游会影响 STUN 数据包的实际出口与可达性，但它们不能修复：

- WebView 缺少 RTCPeerConnection。
- WebView Web Crypto 不可用。
- `evalJS` 未执行。
- Scheme/AES-GCM 协议失败。

所以在没有阶段诊断之前，不应继续盲目修改节点 YAML 或 DNS 覆写。

## 12. 已执行的验证证据

以下是本轮此前已执行并记录的结果；创建本文档时没有重新运行：

### 12.1 Node 契约测试

普通端：

```powershell
npm --prefix C:\Users\damn\Desktop\ai-temperate-main\fornted run test:auth-network-risk
```

结果：27/27 通过。

管理员端：

```powershell
npm --prefix C:\Users\damn\Desktop\ai-temperate-main\myuniappadmin run test:auth-network-risk
```

结果：26/26 通过。

这些测试覆盖共享探针调用协议、UTS 文件一致性、敏感 API 禁止项、重复 Scheme 回调、错误 channel、认证失败、nonce 不匹配、超时与候选数量边界。

注意：Node 契约测试不会真实执行 Android `SecureRandom`、`Cipher`、Android WebView 或真实 STUN 网络。

### 12.2 HBuilderX 构建

- 普通端 `fornted` Android 调试编译曾成功。
- 管理员端完整编译被一个与本次 WebRTC 无关的既有 UTS 错误阻塞：

```text
myuniappadmin/uni_modules/ait-sse/utssdk/app-android/index.uts:125
Kotlin ?: 相关错误
```

不要为了验证 WebRTC 顺手修改该无关插件，除非用户单独授权。

### 12.3 运行时验收

仍未通过：Android 实际 `/report` 仍然提交空 `webRtcIps`。

因此不能宣称 Android WebRTC 探针已经功能完成；目前只能宣称：

- 代码结构和契约测试已落地。
- 逻辑层缺少 Web Crypto 的原始兼容问题已通过 UTS 设计绕开。
- CSP `load__plus` 注入问题已按 `plusrequire:'none'` 修复。
- 真实 Android 候选收集/加密回传仍需阶段诊断。

## 13. 当前相关工作区变更

相关已修改文件：

```text
fornted/common/auth/network-risk-contract.test.cjs
fornted/common/auth/webrtc-verification-android.js
fornted/package.json
myuniappadmin/common/admin/admin-network-risk-contract.test.cjs
myuniappadmin/common/admin/admin-webrtc-verification.js
myuniappadmin/package.json
shared-frontend/auth/android-webrtc-background-probe.js
```

相关新增文件/目录：

```text
fornted/uni_modules/ait-webrtc-crypto/
myuniappadmin/common/admin/admin-webrtc-verification-android.js
myuniappadmin/uni_modules/ait-webrtc-crypto/
shared-frontend/auth/android-webrtc-background-probe.test.cjs
```

工作区还有多项 `tmp/` 下的抓包和 WireGuard 诊断文件，它们属于用户已有或其他任务内容，禁止删除、覆盖或纳入本次 WebRTC 修改。

## 14. 推荐接手顺序

### 第一步：不要先改 Clash/DNS

先在 Android 探针加入不含敏感数据的阶段诊断，确定失败发生在哪一层。

### 第二步：真实 Android 验收

1. 清理或失效当前 PreAuth，使服务端产生新的 generation。
2. 确认第一次 `/start` 为 `PENDING`，不是既有 `FAILED`。
3. 记录 `/start` 到 `/report` 的时间差。
4. 观察阶段诊断。
5. 查看 `/report` 请求体中的 `webRtcIps` 是否非空。
6. 只有候选非空后，才继续验证 `/24`、`/64` 和多候选策略。

### 第三步：按诊断结果分支

- `WEBVIEW_RTC_UNAVAILABLE`：研究 Android System WebView/本地 `file://` 页面是否允许 RTCPeerConnection，必要时调整探针承载方式，但不得明文回传。
- `WEBVIEW_CRYPTO_UNAVAILABLE`：评估让 UTS 同时负责加密，或提供受控的原生桥；不得降低为明文 Scheme。
- `ICE_GATHERING_EMPTY`/超时：再排查 TUN、UDP、STUN 可达性和节点能力。
- `DECRYPT_FAILED`：逐项对齐 Base64URL、IV、AAD、Tag、UTF-8 与 Web Crypto/Android Cipher 参数。
- `EVAL_DISPATCHED` 后无 Scheme：检查隐藏 WebView 页面、`window.startWebRtcProbe` 是否就绪以及 `overrideUrlLoading` 行为。

### 第四步：修复 H5 `phone-country` 串行问题

只对白名单低风险请求绕过 H5 WebRTC 前置等待，并添加契约测试。

### 第五步：修复后端 `inspectVerified(...)`

让已验证状态的后续检查继续使用 `/24`、`/64` 前缀逻辑，避免 report 成功后又因完整 IP 不相等转成状态异常。

## 15. 完成标准

只有同时满足以下条件，才能宣称本任务完成：

1. 普通 Android 端在新 generation 中产生至少一个有效公网候选并成功 report。
2. 管理员 Android 端完成同样验收。
3. Scheme 中不出现明文 IP 或 AES key。
4. 生产日志不包含 key、nonce、payload、完整 IP、Cookie、PreAuth 或设备标识。
5. 空候选、多个同类型公网 IP、前缀不一致继续 fail-closed。
6. `/24`、`/64` 匹配在 report 与后续 inspect 中保持一致。
7. H5 Chrome 插件第三项下能产生候选；第四项导致空候选时，系统仍按空候选失败，不误判为通过。
8. H5 `phone-country` 可以在 WebRTC PENDING 期间并行加载，但其他敏感请求的门禁不被放宽。
9. 普通端与管理员端网络风险测试重新通过。
10. 普通端与管理员端 Android 构建及真实运行分别验证。

## 16. 可直接交给下一条 Codex 任务的简短上下文

```text
请阅读：
C:\Users\damn\Desktop\ai-temperate-main\docs\handoffs\2026-08-11-webrtc-android-uts-and-h5-ordering.md

当前首要任务不是继续修改 Clash/DNS，而是为
shared-frontend/auth/android-webrtc-background-probe.js
和隐藏页面 hybrid/html/webrtc-probe.js
增加不泄露 key/nonce/payload/IP 的阶段诊断，查明 Android 为什么最终 report webRtcIps=[]。

必须保持 UTS SecureRandom + AES-GCM、密文 Scheme、plusrequire:'none' 和严格 CSP。
不要降级到明文回传，不要修改 H5/后端公开错误码，不要碰 tmp 下的用户抓包文件。

其次处理两个独立问题：
1. H5 phone-country 被 publicRequest 的 ensureH5WebRtcVerified 串行阻塞；只对白名单 phone-country 做窄豁免。
2. WebRtcVerificationServiceImpl.report 已按 /24、/64 匹配，但 inspectVerified 仍是 ips.contains 完整相等，需要统一逻辑和测试。
```

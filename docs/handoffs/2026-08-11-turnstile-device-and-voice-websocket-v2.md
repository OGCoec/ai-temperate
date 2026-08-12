# 2026-08-11 全栈网络风险、Turnstile与Voice WebSocket v2交接文档

更新时间：2026-08-11
工作区：`C:\Users\damn\Desktop\ai-temperate-main`
当前分支：`codex/backup-20260811-fullstack`
当前HEAD：`bb00241`
文档性质：工程交接、问题复盘、部署前检查和故障排查手册
目标读者：下一位继续修改普通端、管理员端、Spring后端或Cloudflare Worker的工程人员
---
## 0. 阅读说明

### 0.1 文档目的

这份文档用于压缩本轮很长的排查与实现上下文。
接手者不需要重新猜测已经确认的根因。
接手者应该先阅读“一句话现状”“安全不变量”和“发布顺序”。
只有需要继续调试某个子系统时，才进入对应的详细章节。
### 0.2 文档覆盖范围

本文覆盖普通用户H5。
本文覆盖普通用户Android App-Plus。
本文覆盖管理员H5的WebRTC适配遗漏。
本文覆盖管理员Android共享WebRTC探针。
本文覆盖Spring网络风险和会话状态。
本文覆盖Cloudflare Worker运输分类。
本文覆盖Turnstile受控WebView页面。
本文覆盖Voice Ticket和Voice WebSocket v2握手。
本文覆盖已执行的Node、Worker和Maven定向测试。
本文不覆盖生产账号数据。
本文不覆盖数据库结构迁移。
本文不覆盖语音模型本身的识别质量。
### 0.3 当前交付状态

生产代码已经写入当前工作树。
测试代码已经写入当前工作树。
计划内Node测试已经执行。
计划内Worker测试已经执行。
计划内Maven定向测试已经执行。
上述测试最终全部通过。
隔离Redis Lua集成测试尚未执行。
`mvn clean verify`尚未执行。
Spring后端尚未因本轮Voice v2改动重新部署。
Cloudflare Worker尚未因本轮Voice v2改动重新部署。
H5资源尚未因Voice v2严格切换重新发布。
Android客户端尚未因Voice v2严格切换重新构建。
模拟器端到端Voice验收尚未执行。
### 0.4 工作树注意事项

当前工作树不是干净工作树。
其中包含本轮修改。
其中也包含此前WebRTC诊断修改。
其中还包含管理员H5修复。
其中存在用户自己的临时文件和抓包辅助文件。
禁止执行`git reset --hard`。
禁止使用覆盖式`git checkout --`清理文件。
禁止删除不属于当前任务的未跟踪文件。
提交前必须人工区分本轮文件和既有用户文件。
### 0.5 浏览器与自动化限制

没有用户明确要求时，不得操作Chrome。
不得使用Codex内置浏览器。
不得创建隐藏的Browser Use会话。
如用户明确要求浏览器操作，只允许连接外部Chrome扩展会话。
如外部Chrome未连接，应立即停止浏览器操作。
本轮第二阶段没有操作浏览器。
本轮第二阶段没有操作Computer Use。
---
## 1. 一句话现状

Android WebRTC公网IP采集和回传问题已经定位到App-Plus父层URL解析兼容性，并已修正。
管理员H5黑屏已经定位到旧WebRTC导入，并已通过独立H5适配器修正。
Android Turnstile入口428先后暴露了首次导航Header缺失、Worker运输误分类和子页面二次`/config`缺少设备上下文三个问题。
Turnstile最终采用“父层安全取配置、首次导航带Header、Site Key走Fragment、子页面不再取配置”的方案。
Voice WebSocket已经从“返回101后首帧认证”严格升级到“返回101前完成完整授权”的v2协议。
H5与Android均使用同一套Voice子协议Ticket。
旧Voice客户端不兼容v2服务端。
发布必须协调Spring、Worker、H5和Android。
---
## 2. 核心安全不变量

### 2.1 WebRTC不变量

不得用HTTP IP伪造WebRTC IP。
不得在没有STUN候选时填充HTTP IP。
只接受公网`srflx`候选。
不得接受私网`host`候选作为公网证明。
不得把实际WebRTC IP写入新增诊断日志。
外部STUN确实全部失败时必须Fail Closed。
父层不得在解密完成前关闭隐藏WebView。
同一探针只允许完成一次。
### 2.2 PreAuth与设备不变量

Android受保护请求必须显式携带PreAuth。
Android受保护请求必须显式携带设备安装ID。
设备安装ID必须是规范的小写UUID v4。
PreAuth必须与设备摘要绑定。
PreAuth不得进入URL Query。
设备ID不得进入URL Query。
PreAuth不得写入日志。
设备ID不得完整写入日志。
### 2.3 Turnstile不变量

Turnstile Site Key是公开配置，但仍不得写入日志。
Site Key不得与PreAuth混合进入Query。
首次受保护HTML导航必须带PreAuth和设备Header。
CSS和JavaScript子资源不依赖原生附加Header。
受保护HTML入口不得加入全局排除列表。
子页面不得在缺少安全上下文时降级请求`/config`。
Fragment读取后必须立即清除。
### 2.4 Voice不变量

Voice Ticket只能使用一次。
Voice Ticket不得进入URL。
Voice Ticket不得进入Cookie。
Voice Ticket不得进入Authorization。
Voice Ticket不得进入第一帧。
Voice Ticket不得进入日志。
握手返回101前必须完成授权。
非法Origin必须在Ticket消费前拒绝。
Ticket一旦消费，后续校验失败不得恢复。
成功响应不得反射`ait-ticket.*`。
成功响应只能选择`ait-voice-v2`。
Worker回源WebSocket时继续删除Cookie和Authorization。
### 2.5 日志不变量

不得记录完整PreAuth。
不得记录完整设备ID。
不得记录完整Voice Ticket。
不得记录完整`Sec-WebSocket-Protocol`。
不得记录AES key、IV、密文或明文。
不得记录完整ICE candidate。
不得记录真实用户Cookie。
诊断只允许记录阶段、数量、类型和受控错误码。
---
## 3. 系统边界总览

### 3.1 普通H5请求链

```text
浏览器
→ Cloudflare Worker
→ Edge HMAC签名
→ Spring Security Filter
→ NetworkRiskInterceptor
→ WebRtcVerificationInterceptor
→ 业务或认证拦截器
→ Controller
```
H5主要依赖Cookie Scope。
H5由浏览器自动发送Origin。
H5的Worker运输类型是`H5`。
### 3.2 Android普通HTTP请求链

```text
UniApp Android
→ 通用http-client
→ X-Client-Platform: ANDROID
→ X-Device-Installation-Id
→ X-AIT-PreAuth
→ Cloudflare Worker
→ 删除浏览器Cookie语义
→ Edge HMAC签名
→ Spring
```
Android不依赖浏览器Cookie完成普通API认证。
Android Worker运输类型通常是`ANDROID_NATIVE`。
### 3.3 Android受控WebView导航链

```text
父层UniApp
→ 创建空白WebView
→ 注册URL拦截和生命周期事件
→ loadURL(url, additionalHttpHeaders)
→ Worker分类为ANDROID_WEBVIEW_DOCUMENT
→ Spring受保护HTML入口
→ 页面静态资源
```
受控WebView顶层导航可能带Fetch Metadata。
因此它不能简单归入Android原生运输。
它也不能被当作普通H5处理。
### 3.4 Voice连接链

```text
已登录客户端
→ POST签发Voice Ticket
→ Ticket绑定安全状态
→ WebSocket HTTP Upgrade
→ Sec-WebSocket-Protocol携带Ticket
→ Worker严格校验并签名
→ Spring Origin拦截器
→ Spring安全握手拦截器
→ 原子消费Ticket并重新校验状态
→ 全部成功后返回101
→ 第一帧session.start
→ 音频流
```
---
## 4. 故障时间线摘要

### 4.1 H5请求顺序

最初观察到H5的`phone-country`出现在WebRTC`/report`之后。
原因不是后端排序。
原因是前端通用请求链等待WebRTC验证完成。
用户明确要求H5不要等待`/report`完成后才发`phone-country`。
这属于前端请求编排问题。
该背景与后续Android空候选是两个独立问题。
### 4.2 Android WebRTC空数组

最初抓包看到`/start`为200。
随后`/report`请求体为`webRtcIps: []`。
后端按规则返回428。
外部IP检测页面却能获得WebRTC公网IP。
因此怀疑从STUN转向前端回传链路。
### 4.3 Android诊断日志

隐藏页日志证明RTCPeerConnection可用。
隐藏页日志证明出现多个`srflx`候选。
隐藏页日志证明去重后接受一个公网候选。
隐藏页日志证明AES-GCM加密成功。
隐藏页日志证明自定义协议已经分发。
因此STUN不是最终根因。
### 4.4 父层日志格式

HBuilderX最初只显示多个`[ait-webrtc]`前缀。
结构化对象正文没有显示。
日志输出随后改成单一字符串。
这使父层阶段链可被完整读取。
### 4.5 父层URL解析

日志最终定位到父层回调已触发。
但App-Plus运行环境没有可靠的浏览器`new URL()`。
自定义协议无法按浏览器方式解析。
有效结果被折叠为空数组。
严格手动解析器解决了该兼容问题。
### 4.6 Android Turnstile

WebRTC恢复后，Turnstile页面首次导航仍返回428。
第一根因是原生WebView导航不经过通用HTTP客户端。
增加`loadURL` Header后，Worker返回403运输错误。
第二根因是Worker不认识带Fetch Metadata的Android受控WebView。
增加独立运输分类后，页面入口恢复200。
页面随后显示Turnstile错误，并出现子页面`/config` 428。
第三根因是子页面Fetch不会继承首次导航附加Header。
最终改为父层交付Site Key。
### 4.7 Voice WebSocket

审查发现旧Voice Ticket在连接后的第一帧消费。
这意味着未认证连接已经获得101。
用户要求H5和Android都必须在101之前完成完整校验。
因此实施严格v2切换。
---
## 5. 术语表

`PreAuth`：登录前及网络风险流程的短期安全状态。
`WebRTC generation`：当前PreAuth WebRTC验证轮次。
`srflx`：STUN产生的Server Reflexive ICE候选。
`App-Plus`：DCloud/UniApp Android原生运行环境。
`父层`：创建隐藏或可见WebView的UniApp代码。
`隐藏页`：在WebView内运行RTCPeerConnection的HTML/JavaScript。
`H5`：普通浏览器端构建。
`ANDROID_NATIVE`：没有浏览器运输元数据的Android请求。
`ANDROID_WEBVIEW_DOCUMENT`：严格受控的Android WebView顶层文档导航。
`Voice Ticket`：三十秒左右有效、单次消费的WebSocket握手凭据。
`安全信封`：只保存HMAC绑定和generation的Ticket快照。
`101`：WebSocket HTTP Upgrade成功状态。
`Fail Closed`：安全状态缺失或不可用时拒绝访问。
---
## 6. H5与管理员端前置背景

### 6.1 普通H5请求顺序问题

早期普通H5把公开请求也串到了WebRTC验证Promise之后。
这会让`phone-country`等无须等待WebRTC结果的请求被延后。
用户明确要求的顺序原则是：
```text
可以独立发起的country请求
→ 立即发起

WebRTC report
→ 独立异步完成
```
不得写成：
```text
await WebRTC report
→ 再请求country
```
这个问题属于前端编排顺序，不是后端强制依赖。
后续若再次改动`http-client`，必须检查公开请求是否被错误地加入统一等待链。
本轮Voice与Turnstile工作不应重新引入这类同步等待。
### 6.2 管理员H5黑屏问题

管理员H5曾从共享核心导入已经删除的`collectBrowserWebRtcIps`。
浏览器模块加载阶段因此抛出缺失导出错误。
Vue尚未挂载，页面表现为整屏黑色。
修复方式是新增管理员H5适配器：
`myuniappadmin/common/admin/admin-webrtc-verification-h5.js`
该适配器只调用共享的：
`collectH5WebRtcIps`
管理员平台分发文件改为：
```text
H5
→ collectAdminH5VerificationIps
→ collectH5WebRtcIps

APP-PLUS Android
→ collectAdminAndroidVerificationIps
→ collectAndroidWebRtcIpsInBackground
```
H5适配器禁止导入隐藏WebView、UTS或Android父层代码。
Android适配器禁止复制浏览器RTCPeerConnection实现。
该隔离避免H5构建再次被Android运行时依赖污染。
### 6.3 管理员与普通端共享边界

普通端和管理员端共享Android WebRTC父层模块。
因此父层时间预算、URL拦截、严格解析和解密修复会同时生效。
普通端与管理员端仍保留各自的编排层和API路径。
不得因为共享父层而混用用户PreAuth和管理员会话。
管理员H5修复不改变管理员Android路径。
普通端H5修复也不应改变管理员端请求协议。
---
## 7. Android WebRTC完整复盘

### 7.1 最初现象

后端`/api/_edge/webrtc/report`持续返回428。
响应中的`webRtcIps`为空数组。
HTTP出口IP能够正常取得。
同一模拟器中的Chrome访问IPPure能够通过部分STUN服务器取得公网候选。
因此不能简单归因于模拟器、UDP或STUN全部不可用。
### 7.2 隐藏页证据

开发诊断日志曾明确出现：
```text
hostCount=2
srflxCount=3
acceptedCount=1
ipv4Count=1
encryption_succeeded
result_dispatched
```
这组证据证明：
```text
RTCPeerConnection可用
→ ICE候选出现
→ 至少一个srflx通过公网过滤
→ AES-GCM加密成功
→ 自定义协议结果已经发出
```
因此“没有获取到STUN”并不是最终根因。
准确表述应是：隐藏页采集成功，但父层交付链曾丢失结果。
### 7.3 第一处竞态修复

原实现把完整总预算同时交给ICE内部计时器和父层计时器。
ICE在接近截止时间完成时，父层可能先关闭WebView。
修复后以固定截止时间为准：
```js
const deadlineAt = Date.now() + timeoutMillis
```
页面加载后计算实际剩余时间。
从剩余时间中预留1000毫秒用于结果交付。
预留阶段包含：

- AES-GCM加密；
- `aitwebrtc://result`分发；
- 原生URL拦截回调；
- UTS解密；
- Promise调度与WebView关闭。
外层总超时不再使用不稳定的`timeoutMillis + 250`补偿。
收到第一条结果回调后必须立即清除父层计时器。
解密完成前禁止关闭WebView。
### 7.4 URL拦截规则

原始匹配写法过于宽泛且不符合DCloud实际匹配语义。
父层改为精确拦截结果协议：
```js
{
  mode: 'reject',
  effect: 'instant',
  match: '^aitwebrtc://result.*$'
}
```
`reject`表示阻止WebView真的导航到自定义scheme。
`instant`要求尽快进入父层回调。
如果拦截失败，Android可能尝试寻找处理该scheme的Activity。
典型副作用是`ActivityNotFoundException`或父层最终超时。
### 7.5 真正的父层解析根因

精确父层日志最终显示结果回调已经进入。
失败发生在URL解析阶段。
App-Plus父层运行环境并不可靠提供浏览器全局`URL`和`URLSearchParams`。
旧代码执行：
```js
new URL(rawUrl)
```
在该运行时失败后返回安全空数组。
因此`/report`拿到`webRtcIps: []`。
隐藏页、STUN、AES-GCM、UTS和Spring后端在这条根因上都没有错误。
### 7.6 严格手动解析器

生产修复位于：
`shared-frontend/auth/android-webrtc-background-probe.js`
内部解析器只接受固定前缀：
```text
aitwebrtc://result?
```
URL最大长度为4608字符。
允许参数只有：
```text
channel
iv
payload
error
```
成功形态必须恰好是：
```text
channel + iv + payload
```
错误形态必须恰好是：
```text
channel + error
```
允许的子页错误只有：
```text
probe_error
crypto_unavailable
```
解析器拒绝：

- 错误scheme或大小写；
- 空query；
- 控制字符、空格、反斜线或fragment；
- 缺少等号；
- 空键或空值；
- 未知参数；
- 重复参数；
- 非法百分号编码；
- 同时包含error和密文；
- 超长URL。
参数值只执行一次`decodeURIComponent`。
结构解析后仍继续使用现有正则验证channel、IV和payload。
任何结构错误都不得调用UTS解密。
### 7.7 修复后的证据

修复后抓包显示`/report`请求体包含一个真实采集候选。
示例结构为：
```json
{
  "probeGeneration": "1",
  "webRtcIps": ["203.0.113.10"]
}
```
这里使用文档示例地址，不记录用户真实IP。
后端在HTTP IP前缀匹配时返回200。
这证明父层URL解析丢失问题已经闭环。
### 7.8 不得回退的安全规则

不得用HTTP IP替代WebRTC IP。
不得接受`host`、`relay`或私网`srflx`作为公网WebRTC证据。
不得为了减少428而默认填充候选。
不得把完整ICE candidate写入日志。
不得把AES key、nonce、IV、payload或完整自定义URL写入日志。
全部STUN真实失败时仍必须上报空数组并Fail Closed。
---
## 8. Android WebRTC诊断设计

### 8.1 统一日志模块

共享诊断模块为：
`shared-frontend/auth/webrtc-diagnostics.js`
日志器由编译入口显式传入`enabled`。
父层不在App-Plus运行时直接读取`process.env`。
开发构建输出单一字符串：
```text
[ait-webrtc] {JSON}
```
改成单字符串是因为HBuilderX会把多参数console调用折叠成只剩前缀。
生产构建传入`false`并完全停止诊断输出。
### 8.2 安全字段白名单

允许记录阶段、耗时和计数。
允许记录的典型字段包括：
```text
scope
stage
probeRunId
elapsedMs
reason
state
stunCount
timeoutMillis
candidateCount
acceptedCount
rejectedCount
ipv4Count
ipv6Count
sourceIndexes
urlLength
ivLength
payloadLength
plaintextLength
timerActive
```
禁止记录：
```text
实际IP
完整URL
Cookie
PreAuth
设备UUID
channel
nonce
AES key
IV正文
payload正文
明文
ICE candidate
异常message或stack
```
异常只能映射为固定安全错误码。
### 8.3 父层关键阶段

一次成功链应依次出现：
```text
probe_requested
crypto_channel_ready
webview_created
interceptor_registered
webview_loaded
ice_probe_started
result_callback_entered
parent_timer_cleared
result_url_parsed
result_channel_validated
encrypted_payload_validated
native_decrypt_started
native_decrypt_completed
plaintext_parsed
result_identity_validated
decrypt_success
parent_result_ready
finish_started
webview_close_completed
probe_finished
promise_resolving
```
`parent_result_ready`、`probe_finished`和`promise_resolving`的候选数量必须一致。
重复结果只记录`DUPLICATE`或`SETTLED`，不得重复解密。
### 8.4 编排层关键阶段

父层Promise返回后应继续看到：
```text
platform_probe_completed
report_payload_prepared
report_started
report_completed
```
前三个阶段的`candidateCount`必须一致。
如果`start`直接返回`FAILED`，链路会短路：
```text
start_response_received state=FAILED
verification_short_circuited reason=failed
```
这种情况下探针没有启动是正确行为。
必须创建新PreAuth或新generation才能重新验证。
### 8.5 快速判定矩阵

隐藏页有`result_dispatched`但没有`result_callback_entered`：检查原生URL拦截。
出现`result_url_invalid`：检查严格协议格式或父层运行时解析。
出现`result_channel_mismatch`：检查旧结果、重复探针或WebView生命周期。
出现`encrypted_payload_invalid`：检查URL截断和Base64URL编码。
出现`native_decrypt_failed`：检查UTS密钥轮次和AES-GCM认证。
出现`result_identity_mismatch`：检查channel和nonce所属轮次。
`parent_result_ready=1`但`probe_finished=0`：检查`finish()`内部。
`probe_finished=1`但`platform_probe_completed=0`：检查Android适配器返回链。
`platform_probe_completed=1`但`report_payload_prepared=0`：检查编排层数组重建。
抓包请求体已有候选但后端仍为空：才进入Worker和Spring排查。
---
## 9. WebRTC相关文件索引

共享父层：
`shared-frontend/auth/android-webrtc-background-probe.js`
共享诊断：
`shared-frontend/auth/webrtc-diagnostics.js`
父层测试：
`shared-frontend/auth/android-webrtc-background-probe.test.cjs`
诊断测试：
`shared-frontend/auth/webrtc-diagnostics.test.cjs`
普通端隐藏页：
`fornted/hybrid/html/webrtc-probe.js`
管理员隐藏页：
`myuniappadmin/hybrid/html/webrtc-probe.js`
普通端编排：
`fornted/common/auth/webrtc-verification.js`
普通端Android适配器：
`fornted/common/auth/webrtc-verification-android.js`
管理员编排：
`myuniappadmin/common/admin/admin-webrtc-verification.js`
管理员H5适配器：
`myuniappadmin/common/admin/admin-webrtc-verification-h5.js`
管理员Android适配器：
`myuniappadmin/common/admin/admin-webrtc-verification-android.js`
普通端契约测试：
`fornted/common/auth/network-risk-contract.test.cjs`
管理员契约测试：
`myuniappadmin/common/admin/admin-network-risk-contract.test.cjs`
---
## 10. Android Turnstile完整复盘

### 10.1 H5为何正常而Android最初失败

H5页面内的API请求通过统一HTTP客户端或浏览器同源Cookie完成。
Android原生API也通过统一`http-client`注入PreAuth、设备UUID和平台Header。
但Turnstile是一个独立WebView顶层文档导航。
最初使用`plus.webview.create(url, ...)`直接打开受保护页面。
该首次导航绕过了统一HTTP客户端。
因此最初缺少：
```text
X-AIT-PreAuth
X-Device-Installation-Id
X-Client-Platform: ANDROID
```
Spring `NetworkRiskInterceptor`据此返回428 `PREAUTH_REQUIRED`。
这不是Cloudflare Turnstile SDK本身失败。
也不是已经取得的PreAuth状态凭空消失。
问题是顶层导航没有携带原生安全上下文。
### 10.2 Android安全导航模块

新增文件：
`fornted/common/auth/android-turnstile-navigation.js`
正确流程为：
```text
ensurePreAuth
→ getDeviceInstallationId
→ 创建空白WebView
→ 注册URL拦截、close和error监听
→ loadURL(页面URL, 安全Headers)
→ show
```
创建空白WebView是为了避免第一条网络请求在监听器注册前发生。
PreAuth和UUID只进入`loadURL`附加Header。
它们禁止进入Query、Fragment、WebView ID、日志或错误正文。
Cookie由Android CookieManager按同源规则自动处理，代码不主动设置Cookie Header。
### 10.3 Worker运输分类冲突

前端Header修复生效后，页面响应从428变成过403。
抓包证明安全Header已经存在。
但Android WebView顶层导航同时携带浏览器Fetch Metadata，例如：
```text
Sec-Fetch-Mode: navigate
Sec-Fetch-Dest: document
Sec-Fetch-Site: none
```
Worker原规则把“ANDROID + 浏览器元数据”统一视为伪装请求。
因此返回`EDGE_CLIENT_TRANSPORT_INVALID`。
这属于请求运输分类兼容问题。
它与`CF-Capabilities`响应能力无关。
也不是Spring后端业务逻辑错误。
### 10.4 ANDROID_WEBVIEW_DOCUMENT

Worker新增独立运输类型：
```text
ANDROID_WEBVIEW_DOCUMENT
```
它不把WebView混入`ANDROID_NATIVE`。
只允许严格匹配：

- Host为主域；
- 方法为GET；
- 路径精确为`/api/auth/turnstile/page`；
- Query恰好包含一次`challenge`和一次`action`；
- challenge和action格式合法；
- PreAuth为43位Base64URL；
- UUID为规范小写UUID v4；
- Origin缺失或为主域；
- Fetch Metadata缺失，或严格为受控顶层文档导航。
其他Android浏览器形态仍然403。
不得全局删除`Sec-Fetch-*`反伪造保护。
合法WebView回源前仍由Worker：
```text
删除Cookie、Origin、Referer和Sec-Fetch-*
→ 规范化平台为ANDROID
→ 保留PreAuth与设备UUID
→ 添加Worker HMAC签名
```
上游Android响应仍禁止`Set-Cookie`。
### 10.5 页面200后的第二次428

部署兼容Worker后，顶层`/turnstile/page`已经返回200。
CSS和JavaScript静态资源也返回200。
随后页面脚本自己发起：
```text
GET /api/auth/turnstile/config
```
这个子请求由WebView页面JavaScript发出。
它只有浏览器Cookie和Fetch Metadata。
它不会自动继承首次`loadURL`附加的PreAuth与UUID Header。
因此第二次`/config`被Spring拦截为428。
抓包中看到`__Host-ait-preauth` Cookie并不能推翻这个结论。
Android安全链要求的是Worker认可并回源的显式设备绑定上下文。
Cookie PreAuth不能替代`X-Device-Installation-Id`绑定。
### 10.6 最终Site Key Fragment方案

父层在打开WebView前已经能通过安全HTTP客户端调用`getTurnstileConfig()`。
因此没有必要让子页面再次请求配置。
最终导航接口增加公开`siteKey`输入。
网络Query继续只有：
```text
challenge
action
```
公开Site Key放入Fragment：
```text
#siteKey=<encodedSiteKey>
```
Fragment不会发送到Worker或Spring。
页面脚本严格读取唯一`siteKey`参数。
读取后立即调用`history.replaceState`清除Fragment。
然后才加载Cloudflare Turnstile SDK并执行`turnstile.render()`。
Fragment缺失、重复、未知或非法时直接失败。
禁止回退到`fetch('/api/auth/turnstile/config')`。
### 10.7 CSP日志的正确解释

控制台曾出现Cloudflare Insights beacon被CSP阻止。
该beacon不在允许的`script-src`中，但它不是Turnstile核心SDK。
还出现HBuilderX `load__plus`触发`unsafe-eval`被CSP阻止。
这是DCloud运行时注入与严格CSP之间的噪声或兼容表现。
它不是`/config` 428的直接根因。
禁止为了消除该日志向CSP加入`unsafe-eval`。
Turnstile核心允许源仍应保持最小化。
### 10.8 Turnstile文件索引

Android安全导航：
`fornted/common/auth/android-turnstile-navigation.js`
Turnstile组件：
`fornted/components/auth/auth-turnstile.vue`
受控页面脚本：
`ai-temperate-web/src/main/resources/verification-pages/turnstile-page.js`
页面Controller与测试位于`ai-temperate-web`对应Turnstile包。
Worker分类实现：
`cloudflare/api-gateway/src/index.js`
Worker测试：
`cloudflare/api-gateway/test/index.test.js`
前端导航测试：
`fornted/common/auth/android-turnstile-navigation.test.cjs`
生命周期契约测试：
`fornted/pages/auth/turnstile-lifecycle.test.cjs`
---
## 11. Voice WebSocket v2设计与实现

### 11.1 旧链路的问题

旧版先建立WebSocket连接并返回101。
客户端随后在第一帧发送一次性Voice Ticket。
服务端在连接后启动约5秒认证计时器并消费Ticket。
虽然未认证前不处理音频，但连接资源已经被占用。
这不满足“未完成安全校验就不能建立连接”的要求。
Spring MVC `HandlerInterceptor`不会处理WebSocket Handler。
但WebSocket在升级前一定经过HTTP Upgrade握手。
因此完整校验应放在Filter和`HandshakeInterceptor`阶段。
### 11.2 用户确认的强制范围

H5和Android都必须在返回101之前完成完整校验。
采用严格v2切换，不保留首帧Ticket兼容模式。
旧客户端在升级前将无法连接Voice。
不允许H5继续旧版、Android单独使用新版。
### 11.3 v2子协议格式

客户端发送：
```http
Sec-WebSocket-Protocol: ait-voice-v2, ait-ticket.<43位Base64URL>
```
解析规则：

- Header最大128字符；
- 恰好两个不同Token；
- Token顺序可以变化；
- 必须有固定`ait-voice-v2`；
- 必须有一个合法`ait-ticket.*`；
- 禁止未知Token、重复Ticket或多个业务协议。
成功响应只能选择：
```http
Sec-WebSocket-Protocol: ait-voice-v2
```
绝不能把`ait-ticket.*`反射到响应。
Ticket禁止进入URL、Cookie、Authorization、第一帧或日志。
### 11.4 前端会话变化

前端连接调用统一传入：
```js
protocols: [
  'ait-voice-v2',
  `ait-ticket.${ticket}`
]
```
Android额外声明：
```text
X-Client-Platform: ANDROID
```
H5依赖浏览器自动发送可信Origin。
连接建立后的第一帧变为：
```json
{
  "type": "session.start",
  "protocolVersion": 2,
  "language": "auto",
  "format": "pcm_s16le",
  "sampleRate": 16000,
  "channels": 1
}
```
第一帧继续携带`ticket`必须作为未知字段拒绝。
缺少Ticket时前端不得调用`uni.connectSocket()`。
### 11.5 Ticket安全信封Schema v2

Ticket快照不保存原始UUID、PreAuth、Refresh Token或实际IP。
绑定模型包含：
```text
userId
platform
preAuthTokenDigest
preAuthDeviceDigest
sessionReferenceDigest
refreshSessionDigest
sessionDeviceDigest
globalDeviceBlockDigest
webRtcGeneration
```
所有Digest使用43位Base64URL HMAC。
`refreshSessionDigest`是实施中必须增加的独立字段。
原因是PreAuth session reference与Refresh Session Token属于不同HMAC域。
不能用一个摘要冒充另一类标识。
Schema v1 Ticket不迁移并安全拒绝。
旧Ticket TTL约30秒，部署后会自然失效。
### 11.6 Ticket签发链

HTTP签发路径：
`/api/users/me/voice/session-tickets`
最终拦截顺序：
```text
NetworkRiskInterceptor
→ WebRtcVerificationInterceptor
→ GlobalDeviceBlockInterceptor
→ UserSessionAuthenticationInterceptor
→ VoiceWebSocketAuthorizationService.issueTicket
```
签发服务要求：

- 用户Principal有效；
- 全局设备未封禁；
- PreAuth Scope为USER；
- PreAuth设备与请求UUID一致；
- PreAuth已绑定USER_REFRESH Session；
- WebRTC phase严格为VERIFIED；
- 当前generation可读取；
- Session、设备和用户绑定一致。
`PENDING`、`REQUIRED`和`FAILED`都不得签发Voice Ticket。
### 11.7 握手授权顺序

Origin拦截器必须先运行。
非法Origin不得消耗合法一次性Ticket。
安全握手拦截器随后执行：
```text
严格解析子协议
→ 原子消费Ticket
→ 校验Ticket平台与握手平台
→ 检查全局设备封禁Digest
→ 按Digest重新解析当前PreAuth
→ 校验设备、Session类型和Session引用
→ 用当前HTTP IP重新inspect WebRTC
→ 要求当前phase=VERIFIED
→ 要求当前generation等于Ticket generation
→ 校验Refresh Session仍存在且设备匹配
→ 校验账号仍为ACTIVE
→ 校验Session用户等于Ticket用户
→ 创建最小VoiceHandshakePrincipal
```
Ticket一旦消费，即使后续检查失败也不恢复。
客户端必须重新申请Ticket。
### 11.8 Digest级查询能力

`PreAuthService.resolveBound`只接受受保护Digest。
它同时匹配scope、设备、Session类型和Session引用。
`AccessSessionService.validateActiveBinding`重新验证当前Refresh Session。
Redis只读Lua一次完成Key存在、TTL、设备摘要与快照读取。
Lua文件为：
`lua/auth-session/validate_session_binding.lua`
该校验不续签Token、不轮换CSRF、不延长Session。
`GlobalDeviceBlockService.remainingBlockTtlByDigest`供握手使用。
握手授权不需要恢复原始UUID。
### 11.9 Spring握手边界

协议解析器：
`VoiceWebSocketProtocolParser`
Origin拦截器：
`VoiceWebSocketOriginInterceptor`
安全拦截器：
`VoiceWebSocketSecurityHandshakeInterceptor`
注册顺序必须为Origin在前、安全授权在后。
成功attributes只保存`VoiceHandshakePrincipal`。
禁止保存Ticket、PreAuth、UUID、IP、Cookie或任何Token。
`VoiceWebSocketHandler`实现`SubProtocolCapable`。
它只声明`ait-voice-v2`。
Handler删除连接后Ticket计时器和首帧Ticket消费。
没有Principal的连接必须立即关闭。
### 11.10 Worker WebSocket边界

Worker在回源前严格校验v2子协议结构。
合法Header原样回源给Spring解析。
Worker继续删除Cookie和Authorization。
Worker继续添加HMAC签名、可信客户端IP和地理信息。
上游成功必须同时满足：
```text
HTTP 101
response.webSocket存在
选中协议恰好为ait-voice-v2
无Set-Cookie
```
反射Ticket、选择错误协议或设置Cookie统一转为502。
Spring的400、401、403、428和503可由Worker安全映射为统一边缘错误体。
Worker日志禁止输出完整`Sec-WebSocket-Protocol`。
### 11.11 状态码语义

协议结构非法：400。
Ticket缺失、过期、非法或重放：401。
登录Session失效或账号不可用：401。
Origin或平台非法：403。
全局设备已封禁的握手：403。
PreAuth或设备绑定失效：428。
WebRTC未验证、IP变化或generation变化：428。
安全状态基础设施不可用：503。
Ticket签发HTTP接口被全局设备限流时沿用现有429与`Retry-After`语义。
不要把签发接口的429误改成握手403。
---
## 12. Voice与Turnstile主要文件索引

### 12.1 普通前端

- `fornted/common/auth/android-turnstile-navigation.js`
- `fornted/components/auth/auth-turnstile.vue`
- `fornted/common/voice/voice-websocket-session.js`
- `fornted/common/voice/voice-ticket-api.js`
- `fornted/common/auth/android-turnstile-navigation.test.cjs`
- `fornted/pages/auth/turnstile-lifecycle.test.cjs`
- `fornted/common/voice/voice-websocket-session.test.cjs`
- `fornted/common/voice/voice-contract.test.cjs`
### 12.2 Spring页面资源

- `ai-temperate-web/src/main/resources/verification-pages/turnstile-page.js`
- 同目录Turnstile HTML与CSS资源
- Turnstile Controller只返回独立资源，不拼接JavaScript
### 12.3 Voice Service

- `VoiceTicketSecurityBinding.java`
- `VoiceSessionTicketSnapshot.java`
- `VoiceSessionTicketService.java`
- `VoiceSessionTicketServiceImpl.java`
- `VoiceSessionTicketStore.java`
- `RedisVoiceSessionTicketStore.java`
- `VoiceWebSocketAuthorizationService.java`
- `VoiceWebSocketAuthorizationServiceImpl.java`
- `VoiceTicketIssueCommand.java`
- `VoiceHandshakeCommand.java`
- `VoiceHandshakePrincipal.java`
- `PreAuthService.java`
- `PreAuthServiceImpl.java`
- `AccessSessionService.java`
- `AccessSessionServiceImpl.java`
- `RefreshSessionStore.java`
- `RedisRefreshSessionStore.java`
- `GlobalDeviceBlockService.java`
- `RedisGlobalDeviceBlockService.java`
- `create_voice_session_ticket.lua`
- `consume_voice_session_ticket.lua`
- `validate_session_binding.lua`
具体包路径应通过`rg --files`定位，禁止凭文件名创建重复类型。
### 12.4 Voice Web

- `VoiceSessionTicketController.java`
- `GlobalDeviceBlockInterceptorConfiguration.java`
- `GlobalDeviceBlockInterceptor.java`
- `VoiceWebSocketProtocolParser.java`
- `VoiceWebSocketOriginInterceptor.java`
- `VoiceWebSocketSecurityHandshakeInterceptor.java`
- `VoiceWebSocketConfiguration.java`
- `VoiceWebSocketHandler.java`
- `VoiceWebSocketStartMessage.java`
### 12.5 Cloudflare Worker

- `cloudflare/api-gateway/src/index.js`
- `cloudflare/api-gateway/test/index.test.js`
- `cloudflare/api-gateway/README.md`
---
## 13. 第二阶段测试证据

### 13.1 Node测试

本轮第二阶段已经执行下列无外部基础设施测试。
`node --test fornted/common/auth/android-turnstile-navigation.test.cjs`
结果：6项通过，0项失败。
`node --test fornted/pages/auth/turnstile-lifecycle.test.cjs`
结果：10项通过，0项失败。
`npm --prefix fornted run test:auth-turnstile`
结果：27项通过，0项失败。
`npm --prefix fornted run test:voice`
结果：19项通过，0项失败。
`npm --prefix cloudflare/api-gateway test`
结果：55项通过，0项失败。
### 13.2 Maven定向测试

最终定向Reactor覆盖六个模块并成功结束。
Service侧结果：11项通过。
Web侧结果：26项通过。
最终Reactor状态：SUCCESS。
验证范围包含：

- Ticket Schema v2；
- 单次消费；
- 授权Service；
- Origin与安全握手拦截；
- Voice Handler v2；
- 第一帧契约；
- 全局设备封禁路径；
- Turnstile受控页面。
### 13.3 测试过程中发现并修正的问题

首次Maven运行出现5个测试错误。
原因是测试便捷构造器使用裸`ObjectMapper`，没有注册Java Time模块。
这是测试夹具问题，不是生产序列化路径失败。
同轮还有一个握手测试失败。
原因是在响应提交前从底层Mock对象读取`Cache-Control`。
测试改为从正确的`ServerHttpResponse`边界断言。
下一轮有一项全局设备封禁状态断言不符。
测试原本预期403。
现有HTTP签发接口的正确语义是429并携带`Retry-After`。
测试按既有业务契约修正为429。
最终完整定向测试全部通过。
### 13.4 差异检查

已执行文档和代码差异空白检查。
`git diff --check`退出码为0。
只出现工作区既有LF/CRLF提示，不是语法错误。
### 13.5 明确未执行

未执行隔离Redis Lua集成测试。
未执行`mvn clean verify`全量测试。
未连接生产PostgreSQL、Redis或RabbitMQ。
未进行Spring生产部署。
未部署本轮Voice v2 Worker变更。
未发布H5资源或Android安装包。
未由代理操作Chrome、Computer Use或隐藏浏览器。
未完成Voice v2模拟器端到端101验收。
这些项目不得在后续说明中误报为已验证。
---
## 14. 部署顺序

### 14.1 为什么必须同一维护窗口

Voice v2是严格切换。
旧客户端只会首帧提交Ticket。
新后端只接受握手子协议Ticket。
新客户端连接旧后端同样无法完成v2协商。
因此Spring、Worker、H5和Android版本不能长时间混跑。
### 14.2 推荐发布步骤

1. 确认当前分支、提交和未提交改动归属。
2. 备份当前Spring与Worker可回滚制品。
3. 校验Worker HMAC、Redis HMAC和Origin白名单配置。
4. 部署Spring后端及Turnstile静态页面资源。
5. 部署Cloudflare Worker。
6. 发布H5前端资源。
7. 构建并分发Android客户端。
8. 在隔离测试账号上完成Turnstile和Voice验收。
9. 观察401、403、428、429、502和503指标。
10. 确认旧客户端淘汰策略后结束维护窗口。
### 14.3 Worker部署说明

早期`ANDROID_WEBVIEW_DOCUMENT`兼容修改曾单独部署并使Turnstile页面返回200。
本轮Voice v2新增Worker子协议边界仍需要重新部署。
仅部署Spring不能让边缘接受v2握手。
仅部署Worker也不能替代Spring握手授权。
部署命令由用户手动执行：
```powershell
Set-Location C:\Users\damn\Desktop\ai-temperate-main\cloudflare\api-gateway
npx wrangler deploy
```
执行前应再次确认Cloudflare账号、环境和路由。
代理不得未经用户明确指示代替部署。
---
## 15. 手工验收清单

### 15.1 Android WebRTC

- `/api/_edge/pre-auth`返回200。
- `/api/_edge/webrtc/start`返回PENDING或允许探测状态。
- 隐藏页出现至少一个有效`srflx`时`acceptedCount`大于0。
- `result_callback_entered`出现在父层。
- `native_decrypt_completed state=SUCCESS`。
- `platform_probe_completed candidateCount=1`。
- `/report`请求体包含真实采集候选。
- 日志不包含真实IP和加密材料。
- 全部STUN失败时仍安全返回空数组并由后端拒绝。
### 15.2 Android Turnstile

- 父层`/turnstile/config`通过安全HTTP客户端返回200。
- `/turnstile/page`顶层导航返回200。
- 顶层导航Header存在PreAuth、UUID和ANDROID平台。
- URL Query只有challenge和action。
- Fragment只有公开Site Key且随后被页面清除。
- 页面内部不再发起第二次`/turnstile/config`。
- Cloudflare挑战脚本成功加载。
- 完成验证后Token只交付父层一次。
- 控制台不输出Site Key、PreAuth、UUID或Token。
### 15.3 Voice正常链

- Ticket签发HTTP接口返回200。
- 签发请求已经过NetworkRisk、WebRTC、设备封禁和登录拦截。
- WebSocket Upgrade携带两个v2子协议Token。
- Spring在101之前完成安全授权。
- 响应子协议恰好为`ait-voice-v2`。
- 第一帧不包含Ticket。
- 第一帧协议版本为2。
- 音频上游只在安全Principal存在后启动。
### 15.4 Voice失败链

- 非法Origin在消费Ticket前返回403。
- Ticket重放返回401。
- Session撤销后旧Ticket返回401。
- 设备封禁握手返回403。
- PreAuth失效返回428。
- WebRTC generation变化返回428。
- 当前HTTP IP导致WebRTC重新检查失败时返回428。
- Redis或数据库安全状态不可用时返回503。
- 上游反射Ticket时Worker返回502。
- 上游设置Cookie时Worker返回502。
---
## 16. 排障矩阵

### 16.1 Turnstile页面403

先看响应码是否为`EDGE_CLIENT_TRANSPORT_INVALID`。
若是，检查Worker是否已部署`ANDROID_WEBVIEW_DOCUMENT`。
确认方法、Host、路径、Query、Origin和Fetch Metadata严格匹配。
不要在Spring中放开页面路径规避Worker拒绝。
### 16.2 Turnstile页面428

检查顶层导航是否使用空白WebView后`loadURL(headers)`。
确认Header同时存在PreAuth、UUID和ANDROID平台。
检查Worker是否保留这三个受控Header回源。
后端日志若为`preauth_missing`，重点检查运输边界而非Cookie显示。
### 16.3 页面200但验证失败200500

检查是否仍出现子页面`/turnstile/config` 428。
若出现，说明静态页面脚本仍是旧版本或缓存未更新。
确认页面从Fragment读取Site Key并立即清除。
确认没有重新加入网络配置降级。
### 16.4 Voice签发接口429

这是全局设备封禁或限流语义。
检查`Retry-After`和设备封禁TTL。
不要把它误判为WebSocket握手Origin失败。
### 16.5 Voice握手400

检查子协议数量、长度、重复Token和固定v2协议。
确认客户端没有把Ticket放在URL或第一帧。
### 16.6 Voice握手401

检查Ticket是否过期、已经消费或Schema仍为v1。
检查Refresh Session是否撤销、过期或账号已禁用。
失败Ticket不得恢复，必须重新签发。
### 16.7 Voice握手403

先区分Origin、平台和全局设备封禁。
H5必须有白名单Origin。
Android必须无Origin且平台为ANDROID。
非法Origin应在Ticket消费前失败。
### 16.8 Voice握手428

检查PreAuth Digest绑定、设备Digest、Session引用和WebRTC generation。
确认握手使用当前可信HTTP IP重新inspect。
不得降级为只相信Ticket签发时快照。
### 16.9 Voice握手502

检查Worker是否看到真正101与`response.webSocket`。
检查上游选择协议是否恰好为`ait-voice-v2`。
检查是否错误反射`ait-ticket.*`或返回`Set-Cookie`。
---
## 17. 回滚原则

Voice v2必须成组回滚。
不能只把客户端回滚到v1而保留v2 Spring。
不能只回滚Worker而保留v2子协议客户端。
若必须回滚，应恢复同一组：
```text
Spring v1
Worker v1规则
H5 v1资源
Android v1客户端
```
等待约30秒让已签发v2 Ticket自然过期。
不得为了临时兼容快速加入双协议宽松解析。
若确需双栈，必须单独设计版本隔离、Ticket Schema隔离和退役期限。
Turnstile回滚时不得移除Spring对页面入口的安全保护。
不得把`/turnstile/page`加入拦截器排除列表。
不得恢复子页面无Header调用受保护`/config`。
---
## 18. 下一位接手者行动清单

1. 先阅读本文件第1至5章，理解全局边界。
2. 阅读第6至9章，确认WebRTC历史问题已经闭环。
3. 阅读第10章，区分Turnstile的两次428和一次Worker 403。
4. 阅读第11章，确认Voice v2是严格切换。
5. 用`git status --short`识别用户已有改动。
6. 不清理、不reset、不覆盖无关工作树。
7. 按第13章复核现有测试证据。
8. 若代码再改动，重新申请第二阶段测试授权。
9. 部署前按第14章准备同窗发布。
10. 由用户手动部署Worker和客户端。
11. 按第15章执行隔离账号验收。
12. 失败时按第16章从运输边界向内排查。
13. 完成后记录实际部署版本和时间。
14. 补充Redis Lua隔离集成测试证据。
15. 在有授权时再运行全量`mvn clean verify`。
---
## 19. 明确禁止事项

- 禁止把HTTP IP伪装成WebRTC IP。
- 禁止全局放开Android的Fetch Metadata规则。
- 禁止在URL中携带PreAuth、UUID或Voice Ticket。
- 禁止在日志中输出安全凭据或真实IP。
- 禁止为HBuilderX注入加入CSP `unsafe-eval`。
- 禁止绕过Turnstile页面入口的NetworkRisk保护。
- 禁止让子页面重新请求缺少UUID的受保护配置。
- 禁止在返回101后才做主要身份认证。
- 禁止恢复首帧Ticket兼容而没有明确版本设计。
- 禁止让Worker反射Ticket子协议。
- 禁止在WebSocket attributes保存Ticket或PreAuth。
- 禁止把一次性Ticket消费失败后重新放回Redis。
- 禁止把一次测试授权扩展为生产部署授权。
- 禁止代理擅自操作Chrome、Computer Use或内置浏览器。
- 禁止在脏工作树执行`git reset --hard`或清理用户改动。
---
## 20. 交接结论

Android WebRTC公网候选空数组的根因已经定位并修复为App-Plus父层URL解析兼容问题。
Turnstile经历了三个独立边界问题：首次导航Header、Worker运输分类、子页面二次配置请求。
最终方案保留严格安全Header，并通过受控Fragment传递公开Site Key。
Voice WebSocket已经从连接后首帧认证升级为返回101前的v2安全信封认证。
H5与Android使用同一子协议模型，但保持各自Origin与平台规则。
Node、Worker和Maven定向测试已经通过。
隔离Redis集成、生产部署和端到端模拟器验收仍未执行。
下一步不是继续猜测代码，而是按同窗部署计划完成受控发布与手工验收。
---

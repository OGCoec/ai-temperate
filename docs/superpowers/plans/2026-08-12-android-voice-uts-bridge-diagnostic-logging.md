# Android Voice UTS Bridge 结构化诊断日志实施计划

## 目标

通过一次 Android 实机录音，判断不足一秒主动 `session.stop` 的最后成功边界位于 UTS 回调投递、返回控制对象、租约调用、JS 状态守卫、页面生命周期，还是 `SocketTask.send()`。

本计划只增加诊断证据，不修改录音参数、租约时序、WebSocket 协议、页面业务判断或服务器行为。H5 AudioWorklet、Cloudflare Worker、Spring、Ticket、Cookie、Whisper 和 PCM 协议均不在修改范围内。

## 诊断架构

每次 Android 录音由 JS 生成非敏感 `diagnosticRunId`，UTS 为原生帧生成从 1 开始递增的 `frameSequence`。日志覆盖以下路径：

```text
页面启动
→ JS startRecording 调用与返回形状
→ UTS AudioRecord 启动
→ UTS onStarted 投递
→ JS 租约调用
→ UTS PCM 读取与 Base64 编码
→ UTS onFrame 投递
→ JS 回调入口、状态 Guard 与解码
→ VoiceWebSocketSession.sendAudio
→ SocketTask.send 成功或失败
→ 页面、运行时或租约触发的停止
→ UTS 幂等释放
```

首帧记录完整边界，正常高频过程每 50 帧汇总一次，租约每 10 次汇总一次；失败立即记录。任何日志均不得包含 Base64、PCM、Ticket、Token、Cookie、设备标识、用户标识、识别正文、异常消息、异常对象或堆栈。

## 修改文件

- `fornted/common/voice/voice-recorder.js`
- `fornted/common/voice/voice-websocket-session.js`
- `fornted/components/user/workspace/user-chat-panel.vue`
- `fornted/uni_modules/ait-voice-recorder/utssdk/interface.uts`
- `fornted/uni_modules/ait-voice-recorder/utssdk/app-android/index.uts`
- `fornted/common/voice/voice-recorder.test.cjs`
- `fornted/common/voice/voice-websocket-session.test.cjs`
- `fornted/common/voice/voice-contract.test.cjs`

禁止编辑 `fornted/unpackage/**` 生成文件。所有补丁基于当前未提交修改，不执行 reset、checkout、格式化、暂存或提交。

## 内部关联合同

Android UTS 内部选项增加：

```uts
diagnosticRunId: string
onFrame: (
  payloadBase64: string,
  declaredByteLength: number,
  frameSequence: number
) => void
```

JS 每次 `start()` 使用当前录音 Epoch 生成：

```js
`v${Date.now().toString(36)}-${recordingEpoch}`
```

这些字段只用于 UTS 与 JS 日志关联，不参与鉴权、状态机、错误映射或 WebSocket 数据。`createVoiceRecorder()`、`requestPermission()`、`start()`、`stop()` 和 `destroy()` 的公开接口保持不变。

## 固定事件合同

固定事件名：

```text
voice_android_native
voice_android_pcm_bridge
voice_android_bridge
voice_android_socket_audio
voice_android_ui_lifecycle
```

主要阶段：

```text
START_CALL
START_RETURNED
START_ENTERED
AUDIO_RECORD_INITIALIZED
WORKER_STARTED
START_CALLBACK_DISPATCH_ATTEMPT
START_CALLBACK_DISPATCH_RETURNED
START_CALLBACK_DISPATCH_FAILED
START_CALLBACK_ENTERED
LEASE_TIMER_STARTED
LEASE_RENEW_ATTEMPT
LEASE_RENEW_SUCCEEDED
LEASE_RENEW_FAILED
LEASE_RENEWED
LEASE_EXPIRED
NATIVE_FRAME_READ
NATIVE_FRAME_ENCODED
NATIVE_FRAME_DISPATCH_ATTEMPT
NATIVE_FRAME_DISPATCH_RETURNED
NATIVE_FRAME_DISPATCH_FAILED
NATIVE_FRAME_SUMMARY
FRAME_CALLBACK_ENTERED
FRAME_CALLBACK_IGNORED
JS_FRAME_DECODED
JS_FRAME_SUMMARY
FIRST_SEND_ENTERED
FIRST_BINARY_SENT
BINARY_SEND_FAILED
FAILURE_REPORTED
STOP_REQUESTED
STOP_INVOKE_ATTEMPT
STOP_INVOKE_RETURNED
STOP_INVOKE_FAILED
RELEASED
```

缺失值使用 `ABSENT` 或 `-1`，未知枚举使用 `UNKNOWN`。JS 和 UTS 异常仅记录白名单化后的异常类名。

## UTS 原生边界

`startRecording()` 记录入口、录音编号、AudioRecord 初始化状态、系统最小缓冲区和实际缓冲区。录音源、16 kHz、单声道、PCM16、1600 个采样/帧及线程行为保持不变。

`onStarted` 和首帧 `onFrame` 都记录投递尝试、正常返回或失败。UTS 维护 `framesRead`、`framesDispatchReturned` 和 `renewalCount`；首帧之外每 50 帧仅输出一次汇总。原生异常通过 `getClass().getSimpleName()` 提取类型，不调用 `toString()`。

停止原因限定为：

```text
CLIENT_CONTROL
REPLACED_BY_NEW_SESSION
LEASE_EXPIRED
INITIALIZATION_FAILED
START_CALLBACK_FAILED
FRAME_CALLBACK_FAILED
READ_FAILED
WORKER_EXCEPTION
WORKER_FINALLY
```

`AudioRecord.stop()`、`release()` 和停止回调继续使用原有幂等入口，`RELEASED` 只输出一次。

## App-Plus JS 桥边界

调用 `startRecording()` 前记录 `START_CALL`，返回后只通过空值和 `typeof` 记录：

```text
sessionPresent
sessionType
renewLeaseType
stopType
```

不得枚举或序列化原生代理对象。`onStarted` 与 `onFrame` 的入口日志位于全部状态 Guard 之前。迟到帧固定归类为 `RUNTIME_FAILURE` 或 `EPOCH_MISMATCH`，且不进入 Base64 解码和业务回调。

租约和停止代理调用分别记录尝试、返回或失败；诊断代码不伪造缺失方法、不增加回退实现、不改变 Promise resolve/reject 和一次性失败通知语义。

## 页面与 WebSocket 边界

页面启动流程记录：

```text
TICKET_ISSUED
WEBSOCKET_READY
RECORDER_START_REQUESTED
RECORDER_START_RESOLVED
```

停止来源限定为：

```text
USER_TAP
RUNTIME_FAILURE
PAGE_HIDE
PAGE_UNLOAD
COMPONENT_UNMOUNT
MAX_DURATION
SERVER_LIMIT
TRANSCRIPT_FINAL
STALE_ASYNC_BRANCH
```

`VoiceWebSocketSession` 在首个二进制帧进入、`SocketTask.send()` 成功以及发送失败时记录长度、状态和帧序号。`stop(source)` 的来源只进入客户端日志，服务器仍只收到原有的 `{"type":"session.stop"}`。

## 测试源码

Node 测试覆盖：

- 健康桥接的启动、租约、帧入口和解码日志。
- 返回对象缺少 `renewLease`、返回 `null`、代理调用抛 `TypeError`。
- 租约失败后的迟到帧与旧 Epoch 帧。
- 3200 字节首帧的长度日志及正文反向断言。
- 51 帧采样策略，只记录首帧和第 50 帧汇总。
- 首个二进制帧发送成功及 `SocketTask.send()` 失败。
- 停止来源不进入服务器 JSON。
- H5 源码不出现 Android UTS、租约或诊断字段。

## 分阶段验证

第一阶段只交付源码和测试源码，并运行目标 `git diff --check`。不运行 Node 测试、HBuilderX、APK 构建、实机连接或外部服务。

第二阶段需再次授权后，在 `fornted` 执行：

```powershell
node --test common/voice/voice-recorder.test.cjs common/voice/voice-websocket-session.test.cjs common/voice/voice-contract.test.cjs
npm run test:voice
```

第三阶段需单独授权：重新编译 UTS、安装 Android 构建，只录音一次并保持至少 5 秒，收集同一 `diagnosticRunId` 从 `START_CALL` 到 `STOP_REQUESTED/RELEASED` 的日志。本阶段只判定根因，不顺手修改架构。

## 根因判定

| 最后证据 | 结论 |
| --- | --- |
| `START_RETURNED renewLeaseType!=function` | UTS 返回控制对象不能作为 JS 可调用对象使用 |
| `START_CALLBACK_DISPATCH_FAILED` | 原生启动回调投递失败 |
| 原生 `NATIVE_FRAME_DISPATCH_RETURNED` 后无 JS `FRAME_CALLBACK_ENTERED` | UTS→JS 任务投递或 JS 实例生命周期失败 |
| JS 帧入口后 `reason=RUNTIME_FAILURE` | 更早的控制错误导致帧被状态 Guard 丢弃 |
| `LEASE_RENEW_ATTEMPT` 后失败 | 返回对象或 `renewLease` 代理调用失败 |
| JS 续约成功、原生无 `LEASE_RENEWED` | JS→UTS 控制调用未进入原生闭包 |
| `STOP_REQUESTED source=PAGE_HIDE/PAGE_UNLOAD` | 页面生命周期误触发停止 |
| `FIRST_SEND_ENTERED` 后 `BINARY_SEND_FAILED` | App-Plus `SocketTask.send()` 二进制发送失败 |
| `FIRST_BINARY_SENT` 且服务端无二进制帧 | 才进入 SocketTask、代理或网络传输层排查 |

根因确认后另写单一修复计划。本计划不提前切换到顶层 UTS 控制函数、原生 WebSocket 或其他架构。

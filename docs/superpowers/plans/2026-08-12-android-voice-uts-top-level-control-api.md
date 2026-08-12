# Android Voice UTS 顶层控制 API 修复计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` task-by-task实施。第一阶段只交付代码和测试源码，不自动运行测试、HBuilderX、构建或实机连接。

## 目标

修复 `startRecording()` 返回对象中的 `renewLease` 与 `stop` 函数字段进入 App-Plus JavaScript 后变成 `undefined`，导致首次续约抛出 `TypeError`、客户端立即发送 `session.stop`、正常 PCM 帧被状态守卫丢弃的问题。

## 已确认根因

同一 `diagnosticRunId` 的实机日志已经确认：

```text
START_RETURNED sessionPresent=true renewLeaseType=undefined stopType=undefined
→ LEASE_RENEW_FAILED exceptionType=TypeError
→ FAILURE_REPORTED failureSource=LEASE_RENEW
→ STOP_REQUESTED source=RUNTIME_FAILURE
→ 后续 FRAME_CALLBACK_ENTERED reason=RUNTIME_FAILURE
```

同一链路中的 WebSocket `101`、Ticket、Origin、Spring Handler、Whisper 7896、PCM采集、Base64编码及 UTS `onFrame` 投递均成功，因此本修复只替换 Android UTS 控制通道。

## 内部接口

删除包含函数字段的返回类型，改为：

```uts
export type StartRecordingApi =
  (options: AitVoiceRecorderOptions) => number

export type RenewRecordingLeaseApi =
  (recordingId: number) => boolean

export type StopRecordingApi =
  (recordingId: number) => boolean
```

`startRecording()` 只返回正整数 `recordingId`。续约和停止是独立顶层 UTS API；只有请求ID匹配当前活动录音时才返回 `true` 并执行操作。旧ID、已释放ID或缺失控制入口返回 `false`，不得影响新录音。

JavaScript 对外接口保持不变：

```js
createVoiceRecorder()
requestPermission()
start(onFrame, onRuntimeError)
stop()
destroy()
```

## 实现任务

### 1. 测试先行

- 将假原生桥改成 `startRecording`、`renewRecordingLease`、`stopRecording` 三个独立函数。
- 覆盖有效ID、无效ID、同步 `onStarted`、续约返回 `false`、续约抛异常、停止返回 `false`、停止抛异常和旧ID隔离。
- 保留全部 PCM/Base64、迟到帧、一次性失败和日志脱敏测试。
- 静态合同禁止重新引入 `AitVoiceRecorderSession`、返回函数对象或 `nativeSession` 调用。

### 2. UTS 顶层控制

- UTS 内部保存当前活动ID、续约闭包、替换停止闭包和客户端停止闭包。
- `startRecording()` 在线程启动前注册控制闭包，所有分支只返回当前ID。
- `renewRecordingLease(recordingId)` 和 `stopRecording(recordingId)` 先验证ID再调用内部闭包。
- `releaseRecorder()` 只有在ID仍匹配时才清空控制槽，避免旧线程清除新会话。
- 保留500ms续约、2500ms超时、CAS幂等释放和原有停止原因。

### 3. JavaScript 适配

- `AndroidVoiceRecorder` 只保存 `nativeRecordingId`。
- 启动Promise必须同时等待 `onStarted` 和有效ID返回，覆盖同步回调竞态。
- 首次续约立即调用顶层API；返回 `false` 或抛异常时只报告一次 `VOICE_AUDIO_BRIDGE_INVALID`。
- 停止通过顶层API执行；返回 `false` 按幂等已释放处理，抛异常只记录异常类型。
- 保留Epoch隔离、250ms停止等待、Base64解码和二进制 WebSocket 发送行为。

## 不修改范围

- H5 AudioWorklet和H5页面行为。
- `VoiceWebSocketSession`协议和发送队列。
- Cloudflare Worker、Spring、Ticket、Cookie、CSRF与Whisper 7896。
- `pcm_s16le / 16000 Hz / mono`及服务端偶数字节校验。
- `fornted/unpackage/**`生成文件。

## 分阶段验证

第一阶段只编写代码与测试源码，并执行定向 `git diff --check`；不运行Node测试、HBuilderX、APK构建或实机连接。

第二阶段经明确授权后，在 `fornted` 运行：

```powershell
node --test common/voice/voice-recorder.test.cjs common/voice/voice-contract.test.cjs
npm run test:voice
```

第三阶段单独授权后重新编译UTS、构建并安装Android应用，录音至少5秒。成功链路必须出现：

```text
START_RETURNED recordingIdValid=true
LEASE_RENEW_ATTEMPT
LEASE_RENEWED
LEASE_RENEW_SUCCEEDED
JS_FRAME_DECODED
FIRST_BINARY_SENT
```

并且不得再出现：

```text
renewLeaseType=undefined
stopType=undefined
LEASE_RENEW_FAILED
LEASE_RENEW_REJECTED
FRAME_CALLBACK_IGNORED reason=RUNTIME_FAILURE
LEASE_EXPIRED
```

最终以 Android 输入框产生真实转写文字作为功能验收标准。

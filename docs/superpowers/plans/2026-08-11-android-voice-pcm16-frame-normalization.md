# Android PCM16 音频帧标准化修复计划

## 目标与根因

Android 已经完成 Voice WebSocket `101`、`session.start` 和 `session.ready`，但第一批二进制音频被 Spring 以 `VOICE_AUDIO_FORMAT_INVALID` 拒绝。H5 使用 AudioWorklet 产生标准 JavaScript `ArrayBuffer`，Android 则把复用的原生 `ByteArray/ByteBuffer` 跨 UTS 边界交给 `SocketTask`，存在缓冲区长度和内存所有权不一致的问题。

本修复只调整以下链路：

```text
Android AudioRecord
→ UTS 原生缓冲区
→ JavaScript ArrayBuffer
→ uni.connectSocket
```

不修改 Vue 页面、H5、WebSocket 协议、Spring 校验、Cloudflare Worker 或 Whisper。

## 实现任务

### 1. Android 原生层按完整采样读取

修改 `fornted/uni_modules/ait-voice-recorder/utssdk/app-android/index.uts`：

- 保持 `VOICE_RECOGNITION / 16000 Hz / mono / PCM 16-bit` 配置不变。
- 使用 `ShortArray(1600)` 调用 `AudioRecord.read(short[], ...)`，把返回值解释为采样数量。
- 每次读取创建新的 `ByteBuffer.allocate(sampleCount * 2)`。
- 明确设置 `ByteOrder.LITTLE_ENDIAN`，逐个写入完整 `short` 采样后 `flip()`。
- 禁止继续使用 `ByteBuffer.wrap(...).slice()` 包装复用缓冲区。

### 2. JavaScript 层隔离内存所有权

修改 `fornted/common/voice/voice-recorder.js`：

- 通过 `new Uint8Array(frame)` 读取 UTS 返回值，不依赖 `instanceof ArrayBuffer`。
- 允许能够由 `Uint8Array` 正确读取、但不暴露浏览器式 `byteLength` 的 App-Plus 桥接对象；以转换后视图长度作为有效长度。
- 拒绝无法转换、非对象、零字节、奇数字节或超过 3200 字节的帧，稳定错误码为 `VOICE_AUDIO_FORMAT_INVALID`。
- 拒绝时仅记录受控原因、声明长度和转换长度，不记录音频正文。
- 创建等长 `Uint8Array` 并执行完整复制，只把 `copy.buffer` 交给业务回调。
- 启动完成前的格式错误拒绝 `start()`，运行期格式错误调用既有 `onRuntimeError()`。
- 同一次终止性错误只通知一次，损坏帧不得进入 WebSocket。

### 3. 回归测试与静态合同

新增 `fornted/common/voice/voice-recorder.test.cjs`，覆盖：

- 3200 字节帧的长度、内容和独立内存所有权。
- 原始 UTS 缓冲区被覆盖后，已交付帧保持不变。
- 最小 2 字节 PCM16 帧。
- 零字节、奇数字节和不可转换输入。
- 启动前失败、运行期只通知一次、停止与销毁生命周期。

扩展 `fornted/common/voice/voice-contract.test.cjs`，固定 `ShortArray`、小端序、独立分配、JavaScript 复制、`pcm_s16le / 16000 / mono` 和服务端偶数字节校验合同。更新 `fornted/package.json` 的 `test:voice` 入口。

### 4. 原生插件交付说明

更新 `fornted/uni_modules/ait-voice-recorder/readme.md`，明确两层复制的职责以及 UTS 变更后必须重新制作自定义基座和 APK，仅刷新页面无效。

## 分阶段验证

### 第一阶段

- 交付业务代码、测试源码和说明文档。
- 只运行目标 `git diff --check`。
- 不运行 Node 测试、HBuilderX 编译、APK 构建、部署或外部连接。

### 第二阶段（需再次授权）

在 `fornted` 目录运行：

```powershell
node --test common/voice/voice-recorder.test.cjs
npm run test:voice
```

要求所有 Voice 测试 `0 failures / 0 errors`，且不包含真实音频、Token、Cookie 或设备标识。

### 第三阶段（需单独授权）

重新编译 UTS 插件、制作自定义基座、构建并安装 APK，再进行一次 Android 实机语音输入。二进制帧必须满足：

```text
0 < frameBytes <= 3200
frameBytes % 2 == 0
```

最终验收必须依次出现 `101`、`session.ready`、二进制帧、`input.commit` 和转写结果，后端不再返回 `VOICE_AUDIO_FORMAT_INVALID`。

## 边界

- 不引入 MP3、AAC、Base64、文件录音、重采样或新依赖。
- 不修改 `voice-recorder-h5.js`、`voice-websocket-session.js`、UTS 公共接口、Spring、Worker、Ticket、Cookie、CSRF 或 7896。
- 服务端继续拒绝零字节和奇数字节 PCM16 帧，不通过补字节或放宽校验掩盖客户端损坏。
- 不自动暂存、提交、部署或安装 APK。

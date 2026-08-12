# AI Temperate Android PCM Recorder

## Android Base64 内部桥接

Android `AudioRecord` 使用 `ShortArray` 按完整采样读取 `16 kHz / mono / PCM S16LE`。每一帧都会创建精确长度、`ByteOrder.LITTLE_ENDIAN` 的独立 `ByteArray`，再使用 `Base64.NO_WRAP` 编码后跨 UTS → App-Plus JS Service 边界传递。

Base64 只用于应用内部桥接，不进入 WebSocket。JavaScript 适配层使用 `uni.base64ToArrayBuffer()` 解码并严格核对声明长度、Base64 字符数、实际字节数和 PCM16 偶数字节约束；校验通过后，`SocketTask` 收到的仍是标准二进制 `ArrayBuffer`。插件不记录 Base64 或 PCM 内容，也不会补齐或删除字节。

`startRecording()` 使用 `@UTSJS.keepAlive` 保持持续录音回调有效，并且只向 JavaScript 返回正整数 `recordingId`。续约和停止分别通过顶层 `renewRecordingLease(recordingId)`、`stopRecording(recordingId)` 调用，禁止再把函数字段放进跨桥返回对象；UTS 与 JavaScript 之间的控制数据因此只包含数字和布尔值。JavaScript 每 500 ms 续约一次当前原生会话；原生线程超过 2500 ms 未收到续约时会停止录音。旧 `recordingId` 不能续约或停止新会话，启动新会话、主动停止、异常退出和线程结束仍共用幂等释放逻辑。

修改 UTS 源码后必须重新编译并重新构建、安装包含新代码的 Android APK，仅刷新 Vue 页面不会更新原生实现。本次修改没有新增原生依赖、Manifest 权限或资源，因此不因该修复本身要求重新制作自定义基座；如果当前项目本来使用自定义基座，仍需用包含最新插件代码的构建产物进行实机验证。

插件不编码 MP3、AAC，不保存录音文件，也不持有网络连接。H5 的 AudioWorklet 录音链不经过本插件，行为保持不变。

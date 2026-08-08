# H5 / Android 本地 GPU Whisper 实时语音转写实施说明

## 当前架构

```text
H5 / Android 麦克风
  -> wss://localhost:6655/ws/voice（生产为 wss://api.niko000o.site/ws/voice）
  -> Java 一次性票据认证与有界双向转发
  -> wss://127.0.0.1:7896/ws/transcribe
  -> faster-whisper Medium（RTX 4070，cuda + int8_float16）
```

- Python 只监听回环地址 `127.0.0.1:7896`，并使用 WSS。
- 公网客户端只连接 Java 现有的 `6655` HTTPS/TLS 端口。
- Java 与 Python 使用指定 PEM 证书建立受信任 TLS 连接，不提供 trust-all 降级。
- 音频固定为 `16 kHz`、单声道、PCM signed 16-bit little-endian 二进制帧。
- 临时转写约每 `1.5 秒`更新；Python 使用最近 `20 秒`滚动窗口和 `1 秒`稳定重叠。
- 单次录音硬上限为 `300 秒`，到达上限后自动提交并执行完整音频的权威最终识别。
- 最终文字只追加到聊天输入框，不自动调用 ChatClient 或发送 AI 消息。

## 安全边界

- 客户端先通过受现有会话与 H5 CSRF 保护的 HTTP 接口申请三十秒一次性票据。
- 原始票据只出现在 HTTP 响应和 WebSocket 首个 JSON 帧中，不进入 URL、Redis Key 或日志。
- Redis 使用用途隔离 HMAC Key，并通过 Lua 原子完成用户/设备限流、创建和单次消费。
- H5 必须通过精确 Origin 白名单；无 Origin 的 Android 连接必须使用 Android 平台票据。
- Java 不缓存完整录音；客户端、Java 和 Python 均设置帧、累计字节和待发送队列边界。
- 音频、转写正文、票据和完整设备标识均不写日志。

## 客户端交互

1. 请求麦克风权限。
2. 申请一次性票据并连接 Java WSS。
3. 收到 `session.ready` 后开始发送约 `100 ms` 的 PCM 二进制帧。
4. `transcript.partial` 只更新临时预览，不修改输入框。
5. 用户停止或达到五分钟时提交 `input.commit`。
6. `transcript.final` 追加到现有 draft，由用户确认后使用原有发送与 SSE 对话流程。

## 分阶段验证

当前交付处于第一阶段：代码与测试代码已写入，但本轮未运行测试、编译、打包、服务启动或外部连接验证。

只有用户明确批准第二阶段测试范围后，才依次运行 Python、Java、前端契约测试以及 H5/Android 真机端到端验证。

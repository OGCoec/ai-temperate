# 本地 Whisper Medium WSS 服务

该服务把已安装的 `faster-whisper-medium` 暴露为仅监听本机回环地址的加密 WebSocket：

```text
wss://127.0.0.1:7896/ws/transcribe
```

服务复用 `C:\Users\damn\.ai-temperate\certs\local-https.p12`。启动脚本通过当前 Windows 用户的 DPAPI 密码文件解密证书密码，只传给当前 Python 子进程；PKCS#12 私钥只为创建 TLS 上下文临时转换，加载后立即删除临时 PEM 文件。

正式客户端不直接访问 7896，而是通过 Java 6655 的 `/ws/voice` 网关完成现有用户会话认证、一次性票据消费、Origin 校验和背压控制。Python 仍只监听 `127.0.0.1`，并固定使用 `cuda + int8_float16` 加载 Whisper Medium。

## 启动

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\whisper-wss-server\start-whisper-wss.ps1
```

正常启动后会输出：

```text
READY wss://127.0.0.1:7896/ws/transcribe
```

按 `Ctrl+C` 停止。

## 测试

当前测试全部基于 Python 标准库 `unittest`，不需要在 GPU 虚拟环境中额外安装 pytest：

```powershell
& 'C:\Users\damn\AppData\Local\whisper-venv\Scripts\python.exe' `
  -m unittest discover `
  -s tools/whisper-wss-server/tests `
  -p 'test_*.py' `
  -v
```

## 协议

连接后先发送文本控制帧：

```json
{"type":"session.start","language":"zh","format":"pcm_s16le","sampleRate":16000,"channels":1}
```

然后持续发送 16 kHz、单声道、PCM S16LE 二进制帧。结束一轮输入时发送：

```json
{"type":"input.commit"}
```

服务返回 `session.queued`、`session.ready`、`transcript.partial` 和 `transcript.final` JSON 消息。临时转写只推理最近 20 秒并保留 1 秒语义重叠；提交时对完整录音执行一次 `beam_size=5` 的权威最终识别。

单次录音上限固定为五分钟。达到 `9,600,000` 字节后服务先返回 `input.limit_reached`，随后自动执行最终识别并关闭本轮连接。默认允许三个活动 GPU 会话并使用三个 faster-whisper Worker；额外五个连接进入最长九十秒的 FIFO 等待队列，第九个连接返回 `VOICE_QUEUE_FULL`。

## 用现有音频测试

服务运行时，在另一个 PowerShell 中执行：

```powershell
& 'C:\Users\damn\AppData\Local\whisper-venv\Scripts\python.exe' `
  '.\tools\whisper-wss-server\transcribe_file.py' `
  'C:\Users\damn\Downloads\新录音.m4a' `
  --ca-file 'C:\Users\damn\.ai-temperate\certs\local-https.pem' `
  --language zh `
  --realtime
```

`--realtime` 会按照音频时长模拟麦克风逐块发送；去掉它则尽快上传整段音频。
